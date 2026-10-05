import asyncio
import json
from types import SimpleNamespace

import pytest

from app.errors import LLMError, LLMRefusal, RateLimited
from app.providers.openai_compat import OpenAICompatProvider, strict_schema
from app.schemas import StyleTraits, SuggestOutput

GOOD = {
    "intent": "REQUEST",
    "relationship_guess": "client",
    "language": "English",
    "suggestions": [{"label": "Accept", "text": "yes will send tonight"}],
    "memory_add": [],
    "memory_resolve": [],
}


class FakeCompletions:
    def __init__(self, replies):
        self.replies = list(replies)
        self.calls = []

    async def create(self, **kwargs):
        self.calls.append(kwargs)
        reply = self.replies.pop(0)
        if isinstance(reply, Exception):
            raise reply
        content, finish = reply
        return SimpleNamespace(
            choices=[SimpleNamespace(message=SimpleNamespace(content=content), finish_reason=finish)],
            usage=SimpleNamespace(prompt_tokens=10, completion_tokens=5),
        )


def make(replies, **kw):
    completions = FakeCompletions(replies)
    client = SimpleNamespace(chat=SimpleNamespace(completions=completions))
    defaults = dict(name="groq", base_url="x", api_key="k", model="m", client=client)
    return OpenAICompatProvider(**{**defaults, **kw}), completions


def run(provider, fmt=SuggestOutput):
    return asyncio.run(provider.parse("SYSTEM", "USER", fmt, 500))


def test_strict_schema_is_strict_everywhere():
    schema = strict_schema(SuggestOutput)
    assert "$defs" not in json.dumps(schema) and "$ref" not in json.dumps(schema)
    assert schema["additionalProperties"] is False
    assert set(schema["required"]) == set(SuggestOutput.model_fields)
    item = schema["properties"]["suggestions"]["items"]
    assert item["additionalProperties"] is False and item["required"] == ["label", "text"]
    # SuggestOutput has no field called "title", so any "title" key is a leftover schema title.
    assert '"title":' not in json.dumps(schema)


def test_strict_schema_keeps_a_field_named_title():
    from pydantic import BaseModel

    class Note(BaseModel):
        title: str

    assert strict_schema(Note)["properties"] == {"title": {"type": "string"}}


def test_schema_mode_sends_json_schema_and_extras():
    p, calls = make([(json.dumps(GOOD), "stop")], max_tokens_param="max_completion_tokens",
                    extra={"reasoning_effort": "low"})
    out, _ = run(p)
    assert out.intent == "REQUEST"
    sent = calls.calls[0]
    assert sent["response_format"]["type"] == "json_schema"
    assert sent["response_format"]["json_schema"]["strict"] is True
    assert sent["max_completion_tokens"] == 500 and sent["reasoning_effort"] == "low"
    assert sent["messages"][0] == {"role": "system", "content": "SYSTEM"}


def test_object_mode_puts_schema_in_prompt_and_strips_fences():
    p, calls = make([("```json\n" + json.dumps(GOOD) + "\n```", "stop")], json_mode="object")
    out, _ = run(p)
    assert out.suggestions[0].text == "yes will send tonight"
    sent = calls.calls[0]
    assert sent["response_format"] == {"type": "json_object"}
    assert "JSON Schema" in sent["messages"][0]["content"]
    assert sent["max_tokens"] == 500


def test_bad_json_is_retried_once():
    p, calls = make([("{not json", "stop"), (json.dumps(GOOD), "stop")], json_mode="object")
    out, _ = run(p)
    assert out.intent == "REQUEST" and len(calls.calls) == 2


def test_bad_json_twice_is_an_error():
    p, _ = make([("{}", "stop"), ("{}", "stop")])
    with pytest.raises(LLMError, match="schema"):
        run(p)


def test_overloaded_model_falls_back_to_next():
    import httpx
    import openai

    request = httpx.Request("POST", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions")
    busy = openai.InternalServerError(
        "high demand", response=httpx.Response(503, request=request),
        body={"error": {"code": 503, "status": "UNAVAILABLE"}},
    )
    p, calls = make([busy, (json.dumps(GOOD), "stop")], name="gemini", fallback_models=["lite"])
    out, _ = run(p)
    assert out.intent == "REQUEST"
    assert [c["model"] for c in calls.calls] == ["m", "lite"]


def test_cut_off_and_filtered_outputs():
    with pytest.raises(LLMError, match="cut off"):
        run(make([("{", "length")])[0])
    with pytest.raises(LLMRefusal):
        run(make([("", "content_filter")])[0])


def _rate_limit_error(message: str):
    import httpx
    import openai

    request = httpx.Request("POST", "https://api.groq.com/openai/v1/chat/completions")
    body = {"message": message, "type": "tokens", "code": "rate_limit_exceeded"}
    return openai.RateLimitError(message, response=httpx.Response(429, request=request), body=body)


def test_per_minute_rate_limit():
    err = _rate_limit_error("Rate limit reached ... on tokens per minute (TPM): Limit 8000 ... Please try again in 14.78s.")
    with pytest.raises(RateLimited, match="per-minute limit reached, try again in 14s") as info:
        run(make([err])[0])
    assert info.value.daily is False


def test_daily_rate_limit():
    err = _rate_limit_error(
        "Rate limit reached for model `openai/gpt-oss-120b` ... on tokens per day (TPD): Limit 200000, "
        "Used 199392, Requested 2189. Please try again in 11m22.992s. Need more tokens?"
    )
    with pytest.raises(RateLimited, match="daily limit reached, try again in 11m22s") as info:
        run(make([err])[0])
    assert info.value.daily is True


def test_falls_back_to_next_model_when_limited():
    daily = _rate_limit_error("... tokens per day (TPD): Limit 200000 ... Please try again in 10m0s.")
    p, calls = make([daily, (json.dumps(GOOD), "stop"), (json.dumps(GOOD), "stop")],
                    fallback_models=["small"])
    out, _ = run(p)
    assert out.intent == "REQUEST"
    assert [c["model"] for c in calls.calls] == ["m", "small"]
    # The limited model is skipped on the next request instead of being asked again.
    run(p)
    assert [c["model"] for c in calls.calls] == ["m", "small", "small"]


def test_per_model_options_override_shared_ones():
    daily = _rate_limit_error("... tokens per day (TPD) ... Please try again in 10m0s.")
    p, calls = make([daily, (json.dumps(GOOD), "stop")], fallback_models=["qwen"],
                    extra={"reasoning_effort": "low"}, model_extra={"qwen": {"reasoning_effort": "none"}})
    run(p)
    assert [(c["model"], c["reasoning_effort"]) for c in calls.calls] == [("m", "low"), ("qwen", "none")]


def test_short_per_minute_limit_is_waited_out(monkeypatch):
    from app.providers import openai_compat

    slept = []

    async def fake_sleep(s):
        slept.append(s)
        p._cooldown_until.clear()  # time "passes": the limit has cleared

    monkeypatch.setattr(openai_compat.asyncio, "sleep", fake_sleep)
    err = _rate_limit_error("... tokens per minute (TPM) ... Please try again in 4s.")
    p, calls = make([err, (json.dumps(GOOD), "stop")])
    out, _ = run(p)
    assert out.intent == "REQUEST" and len(calls.calls) == 2
    assert 4 <= slept[0] <= 5


def test_long_or_daily_limits_are_not_waited_for():
    daily = _rate_limit_error("... tokens per day (TPD) ... Please try again in 10m0s.")
    minute = _rate_limit_error("... tokens per minute (TPM) ... Please try again in 40s.")
    p, _ = make([daily, minute], fallback_models=["small"])
    with pytest.raises(RateLimited, match="per-minute"):
        run(p)
    with pytest.raises(RateLimited, match="all models"):
        run(p)  # both still cooling down, so no call is made at all


def test_seconds_parser():
    from app.providers.openai_compat import _seconds

    assert _seconds("11m22.992s") == pytest.approx(682.992)
    assert _seconds("1h2m") == 3720
    assert _seconds("690ms") == pytest.approx(0.69)  # not 690 minutes


def test_millisecond_wait_is_parsed(monkeypatch):
    from app.providers import openai_compat

    async def no_wait(_):
        return None

    monkeypatch.setattr(openai_compat.asyncio, "sleep", no_wait)
    err = lambda: _rate_limit_error(  # noqa: E731
        "... tokens per minute (TPM): Limit 8000 ... Please try again in 690ms. Need more")
    with pytest.raises(RateLimited) as info:
        run(make([err(), err()])[0])  # waited out once, still limited, then gives up
    assert info.value.retry_after == pytest.approx(0.69)
    assert "try again in 690ms" in str(info.value)


def test_style_traits_schema_works_too():
    traits = {"summary": "s", "language_mix": "English", "fillers": [], "signature_phrases": [],
              "emoji_habits": "none", "punctuation_and_casing": "lower", "avoid": []}
    out, _ = run(make([(json.dumps(traits), "stop")])[0], fmt=StyleTraits)
    assert out.language_mix == "English"
