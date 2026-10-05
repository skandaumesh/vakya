import pytest
from fastapi.testclient import TestClient

from app import config, llm, main, providers
from app.schemas import Suggestion, SuggestOutput

client = TestClient(main.app)

BODY = {
    "chat_title": "Rahul",
    "relationship": "client",
    "messages": [{"sender": "them", "text": "Can you send the files by tonight?"}],
    "memory": ["Invoice #2 pending", "I promised the homepage by Friday"],
}


def fake_output(**overrides) -> SuggestOutput:
    data = dict(
        intent="REQUEST",
        relationship_guess="friend",
        language="English",
        suggestions=[
            Suggestion(label="Accept", text="yes will send tonight"),
            Suggestion(label="Delay", text="tomorrow morning ok?"),
            Suggestion(label="Clarify", text="which files exactly?"),
            Suggestion(label="Extra", text="should be dropped"),
        ],
        memory_add=["Rahul wants final files tonight", "Invoice #2 pending"],
        memory_resolve=["Invoice #2 pending", "something the client never sent"],
    )
    data.update(overrides)
    return SuggestOutput(**data)


@pytest.fixture
def fake_parse(monkeypatch):
    calls = {}

    async def _parse(system, user_content, output_format, max_tokens, **_kw):
        calls["user_content"] = user_content
        return calls.get("output", fake_output()), 321

    monkeypatch.setattr(llm, "_parse", _parse)
    return calls


def test_health():
    assert client.get("/health").json()["ok"] is True


def test_suggest_post_processing(fake_parse):
    r = client.post("/suggest", json=BODY)
    assert r.status_code == 200, r.text
    data = r.json()
    assert [s["label"] for s in data["suggestions"]] == ["Accept", "Delay", "Clarify"]
    # The user's relationship setting wins over the model's guess.
    assert data["relationship_guess"] == "client"
    # Already-known memory isn't re-added; only known items can be resolved.
    assert data["memory_add"] == ["Rahul wants final files tonight"]
    assert data["memory_resolve"] == ["Invoice #2 pending"]
    assert data["latency_ms"] == 321
    assert "Can you send the files by tonight?" in fake_parse["user_content"]


def test_suggest_empty_suggestions_is_502(fake_parse):
    fake_parse["output"] = fake_output(suggestions=[Suggestion(label="x", text="  ")])
    assert client.post("/suggest", json=BODY).status_code == 502


def test_refusal_is_422(monkeypatch):
    async def _parse(*a, **k):
        raise llm.LLMRefusal("no")

    monkeypatch.setattr(llm, "_parse", _parse)
    assert client.post("/suggest", json=BODY).status_code == 422


@pytest.mark.parametrize(
    "provider, env_var",
    [("groq", "GROQ_API_KEY"), ("gemini", "GEMINI_API_KEY"), ("anthropic", "ANTHROPIC_API_KEY")],
)
def test_missing_credentials_is_503(monkeypatch, provider, env_var):
    for var in ("GROQ_API_KEY", "GEMINI_API_KEY", "ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_PROFILE"):
        monkeypatch.delenv(var, raising=False)
    monkeypatch.setattr(config, "PROVIDER", provider)
    monkeypatch.setattr(config, "API_KEY", None)
    providers.get_provider.cache_clear()
    try:
        r = client.post("/suggest", json=BODY)
    finally:
        providers.get_provider.cache_clear()
    assert r.status_code == 503
    assert env_var in r.json()["detail"]


def test_ollama_needs_no_key(monkeypatch):
    monkeypatch.setattr(config, "PROVIDER", "ollama")
    providers.get_provider.cache_clear()
    try:
        p = providers.get_provider()
    finally:
        providers.get_provider.cache_clear()
    assert p.name == "ollama" and p.json_mode == "object"


@pytest.mark.parametrize(
    "exc, status",
    [(llm.RateLimited("limit"), 429), (llm.ProviderTimeout("slow"), 504), (llm.LLMError("bad"), 502)],
)
def test_provider_errors_map_to_status(monkeypatch, exc, status):
    async def _parse(*a, **k):
        raise exc

    monkeypatch.setattr(llm, "_parse", _parse)
    assert client.post("/suggest", json=BODY).status_code == status


def test_validation_rejects_empty_messages():
    assert client.post("/suggest", json={**BODY, "messages": []}).status_code == 422


def test_app_key_required_when_configured(monkeypatch, fake_parse):
    monkeypatch.setattr(config, "APP_KEY", "secret")
    assert client.post("/suggest", json=BODY).status_code == 401
    assert client.post("/suggest", json=BODY, headers={"X-Vakya-Key": "nope"}).status_code == 401
    assert client.post("/suggest", json=BODY, headers={"X-Vakya-Key": "secret"}).status_code == 200


def test_style_card_combines_model_traits_and_measured_stats(monkeypatch):
    from app.schemas import StyleTraits

    traits = StyleTraits(
        summary="short", language_mix="English", fillers=["bro"], signature_phrases=["ok da"],
        emoji_habits="😂 at end", punctuation_and_casing="lowercase", avoid=[],
    )

    async def _parse(system, user_content, output_format, max_tokens, **_kw):
        assert output_format is StyleTraits
        return traits, 100

    monkeypatch.setattr(llm, "_parse", _parse)
    r = client.post("/style-card", json={"my_messages": ["ok da", "haha 😂", "done", "sent", "yes bro"]})
    assert r.status_code == 200, r.text
    card = r.json()
    assert card["traits"]["fillers"] == ["bro"]
    assert card["stats"]["message_count"] == 5
    assert card["stats"]["top_emojis"] == ["😂"]
