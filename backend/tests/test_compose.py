"""Write it for me: one rough sentence in, the message in each style out."""

import pytest
from fastapi.testclient import TestClient

from app import llm, main
from app.prompts import COMPOSE_SYSTEM, render_compose_input
from app.schemas import ComposeOutput, ComposeRequest, ComposeVariant

client = TestClient(main.app)


def output(*pairs, meaning="Ask if he's coming to the party tomorrow") -> ComposeOutput:
    return ComposeOutput(
        meaning=meaning, language="Kanglish",
        variants=[ComposeVariant(style=s, text=t) for s, t in pairs],
    )


@pytest.fixture
def fake_parse(monkeypatch):
    calls = {"n": 0, "outputs": []}

    async def _parse(system, user_content, output_format, max_tokens, **kw):
        calls["n"] += 1
        calls["system"], calls["user_content"], calls["prefer"] = system, user_content, kw.get("prefer")
        outs = calls["outputs"]
        return (outs.pop(0) if len(outs) > 1 else outs[0]), 100

    monkeypatch.setattr(llm, "_parse", _parse)
    return calls


def test_compose_returns_one_message_per_style_in_order(fake_parse):
    fake_parse["outputs"] = [output(
        ("genz", "party ge baartiya naale? fr"),
        ("mine", '"naale party ge barthiya maga?"'),
        ("short", "naale party?"),
        ("professional", "Will you be coming to the party tomorrow?"),
        ("mine", "a second mine version is dropped"),
    )]
    r = client.post("/compose", json={
        "chat_title": "Vinay", "intent": "ask him if he is coming for the party tomorrow",
        "messages": [{"sender": "them", "text": "maga yen plan?"}],
    })
    assert r.status_code == 200, r.text
    data = r.json()
    assert [v["style"] for v in data["variants"]] == ["mine", "professional", "short", "genz"]
    assert data["variants"][0]["text"] == "naale party ge barthiya maga?"  # quotes removed
    assert data["meaning"] and data["latency_ms"] == 100
    assert fake_parse["system"] == COMPOSE_SYSTEM
    assert "ask him if he is coming" in fake_parse["user_content"]
    # A Kanglish chat goes to the provider that's best at Kanglish.
    assert fake_parse["prefer"] is not None or llm.config.KANGLISH_PROVIDER is None


def test_compose_keeps_friend_words_away_from_clients(fake_parse):
    fake_parse["outputs"] = [output(("mine", "sari da, will send by Friday"), ("short", "Friday da"))]
    r = client.post("/compose", json={"relationship": "client", "intent": "tell him I'll send it by Friday"})
    assert [v["text"] for v in r.json()["variants"]] == ["sari, will send by Friday", "Friday"]


def test_compose_retries_once_on_invented_amounts(fake_parse):
    fake_parse["outputs"] = [
        output(("professional", "The total is ₹15k.")),
        output(("professional", "I'll share the total shortly.")),
    ]
    r = client.post("/compose", json={"relationship": "client", "intent": "tell her I'll share the total soon"})
    assert fake_parse["n"] == 2
    assert r.json()["variants"][0]["text"] == "I'll share the total shortly."


def test_compose_with_nothing_usable_is_502(fake_parse):
    fake_parse["outputs"] = [output(("mine", "  "))]
    assert client.post("/compose", json={"intent": "ask him"}).status_code == 502


def test_compose_needs_an_intent():
    assert client.post("/compose", json={"intent": ""}).status_code == 422


def test_compose_input_uses_the_chat_language_even_for_an_english_instruction():
    out = render_compose_input(ComposeRequest(
        intent="ask him if he is coming tomorrow",
        messages=[{"sender": "them", "text": "naale yen plan maga?"}, {"sender": "me", "text": "gottilla"}],
        examples=["sari bartini"],
    ))
    assert "language_guide: They write Kanglish" in out
    assert out.endswith('<what_i_want_to_say>"ask him if he is coming tomorrow"</what_i_want_to_say>')
    assert '- "sari bartini"' in out and "them: \"naale yen plan maga?\"" in out


def test_compose_input_without_a_chat():
    out = render_compose_input(ComposeRequest(intent="tell her the site will be 2 days late", relationship="client"))
    assert "<conversation>" not in out and "<memory>" not in out
    assert "never use in this chat: " in out


@pytest.mark.parametrize("raw, clean", [
    ("Dear Sir, this is a reminder that I submitted my assignment on Monday.", "I submitted my assignment on Monday."),
    ("Please note that the website will be delayed by two days.", "The website will be delayed by two days."),
    ("The website will be delayed by two days.", "The website will be delayed by two days."),
    ("Dear Sir,", "Dear Sir,"),  # nothing would be left: keep it
])
def test_professional_is_a_text_not_a_letter(raw, clean):
    assert llm._not_a_letter(raw) == clean
