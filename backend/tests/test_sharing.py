"""Sharing with friends: per-phone daily limit on the shared key, and phones' own Groq keys."""

import pytest
from fastapi.testclient import TestClient

from app import config, llm, main, providers, usage
from app.schemas import Suggestion, SuggestOutput

client = TestClient(main.app)
BODY = {"messages": [{"sender": "them", "text": "maga bartiya?"}]}

OUT = SuggestOutput(intent="INVITATION", relationship_guess="friend", language="Kanglish",
                    suggestions=[Suggestion(label="Accept", text="bartini maga")],
                    memory_add=[], memory_resolve=[])


@pytest.fixture
def fake_ai(monkeypatch):
    seen = []

    async def _parse(system, user_content, output_format, max_tokens, **_kw):
        seen.append(providers.active_provider())
        return OUT, 10

    monkeypatch.setattr(llm, "_parse", _parse)
    usage.reset()
    yield seen
    usage.reset()


def test_each_phone_gets_its_daily_share(monkeypatch, fake_ai):
    monkeypatch.setattr(config, "DAILY_LIMIT", 2)
    a = {"X-Vakya-Device": "phone-a"}
    assert client.post("/suggest", json=BODY, headers=a).status_code == 200
    assert client.post("/suggest", json=BODY, headers=a).status_code == 200
    r = client.post("/suggest", json=BODY, headers=a)
    assert r.status_code == 429 and "own free Groq key" in r.json()["detail"]
    # Another phone still has its own share.
    assert client.post("/suggest", json=BODY, headers={"X-Vakya-Device": "phone-b"}).status_code == 200


def test_own_key_is_unlimited_and_used_for_that_request(monkeypatch, fake_ai):
    monkeypatch.setattr(config, "DAILY_LIMIT", 1)
    headers = {"X-Vakya-Device": "phone-c", "X-Groq-Key": "gsk_friends_own_key"}
    for _ in range(3):
        assert client.post("/suggest", json=BODY, headers=headers).status_code == 200
    assert all(p.name == "your Groq key" for p in fake_ai)
    assert usage.used_today("phone-c") == 0
    # The override doesn't leak into the next request without a key.
    monkeypatch.setenv("GROQ_API_KEY", "gsk_shared")
    providers.get_provider.cache_clear()
    client.post("/suggest", json=BODY, headers={"X-Vakya-Device": "phone-d"})
    assert fake_ai[-1].name == "groq"


def test_own_key_provider_is_reused_not_rebuilt():
    a = providers.user_groq_provider("gsk_one")
    assert providers.user_groq_provider("gsk_one") is a
    assert providers.user_groq_provider("gsk_two") is not a


def test_no_limit_when_not_configured(monkeypatch, fake_ai):
    monkeypatch.setattr(config, "DAILY_LIMIT", 0)
    for _ in range(5):
        assert client.post("/suggest", json=BODY).status_code == 200
