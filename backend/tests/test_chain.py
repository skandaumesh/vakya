import asyncio

import pytest

from app import config, providers
from app.errors import LLMError, ProviderUnavailable, RateLimited
from app.providers.chain import ChainProvider


class Fake:
    def __init__(self, name, result=None, error=None):
        self.name, self.result, self.error, self.calls = name, result, error, 0
        self.max_wait_s = 12

    async def parse(self, *a):
        self.calls += 1
        if self.error:
            raise self.error
        return self.result, 1

    async def describe_image(self, *a):
        self.calls += 1
        if self.error:
            raise self.error
        return self.result


def test_uses_next_provider_when_first_is_out_of_quota():
    groq = Fake("groq", error=RateLimited("groq free daily limit reached", daily=True))
    gemini = Fake("gemini", result="ok")
    chain = ChainProvider([groq, gemini])
    assert asyncio.run(chain.parse("s", "u", object, 10)) == ("ok", 1)
    assert groq.max_wait_s == 0 and gemini.max_wait_s == 12  # only the last one waits


def test_preferred_provider_goes_first():
    groq, gemini = Fake("groq", result="g"), Fake("gemini", result="x")
    chain = ChainProvider([groq, gemini])
    assert asyncio.run(chain.parse("s", "u", object, 10, prefer="gemini")) == ("x", 1)
    assert groq.calls == 0
    # Preferred one out of quota: the others still answer.
    gemini.error = RateLimited("gemini limit")
    assert asyncio.run(chain.parse("s", "u", object, 10, prefer="gemini")) == ("g", 1)


def test_first_provider_is_used_when_it_works():
    groq, gemini = Fake("groq", result="g"), Fake("gemini", result="x")
    assert asyncio.run(ChainProvider([groq, gemini]).parse("s", "u", object, 10)) == ("g", 1)
    assert gemini.calls == 0


def test_all_limited_reports_the_first_rate_limit():
    chain = ChainProvider([Fake("a", error=RateLimited("a limit")), Fake("b", error=RateLimited("b limit"))])
    with pytest.raises(RateLimited, match="a limit"):
        asyncio.run(chain.parse("s", "u", object, 10))


def test_refused_backup_is_skipped_and_the_rate_limit_is_reported():
    groq = Fake("groq", error=RateLimited("groq free daily limit reached, try again in 2h"))
    gemini = Fake("gemini", error=ProviderUnavailable("gemini refused access to this key/project"))
    chain = ChainProvider([groq, gemini])
    with pytest.raises(RateLimited, match="groq free daily limit"):
        asyncio.run(chain.parse("s", "u", object, 10))
    # Next request: Gemini isn't even tried for a while.
    with pytest.raises(RateLimited, match="groq"):
        asyncio.run(chain.parse("s", "u", object, 10))
    assert gemini.calls == 1 and groq.calls == 2


def test_refused_api_key_on_backup_is_skipped_too():
    from app.errors import MissingCredentials

    chain = ChainProvider([Fake("groq", result="ok"), Fake("gemini", error=MissingCredentials("bad key"))])
    assert asyncio.run(chain.parse("s", "u", object, 10)) == ("ok", 1)
    chain = ChainProvider([Fake("groq", error=RateLimited("limit")), Fake("gemini", error=MissingCredentials("bad key"))])
    with pytest.raises(RateLimited):
        asyncio.run(chain.parse("s", "u", object, 10))


def test_vision_falls_through_too():
    chain = ChainProvider([Fake("a", error=LLMError("no vision")), Fake("b", result="a sticker")])
    assert asyncio.run(chain.describe_image("img", "p")) == "a sticker"


def test_backup_without_key_is_skipped(monkeypatch):
    monkeypatch.setenv("GROQ_API_KEY", "gsk_test")
    monkeypatch.delenv("GEMINI_API_KEY", raising=False)
    monkeypatch.setattr(config, "PROVIDER", "groq")
    monkeypatch.setattr(config, "FALLBACK_PROVIDERS", "gemini")
    providers.get_provider.cache_clear()
    try:
        p = providers.get_provider()
    finally:
        providers.get_provider.cache_clear()
    assert p.name == "groq"


def test_backup_with_key_makes_a_chain(monkeypatch):
    monkeypatch.setenv("GROQ_API_KEY", "gsk_test")
    monkeypatch.setenv("GEMINI_API_KEY", "gem_test")
    monkeypatch.setattr(config, "PROVIDER", "groq")
    monkeypatch.setattr(config, "FALLBACK_PROVIDERS", "gemini")
    monkeypatch.setattr(config, "MODEL", "openai/gpt-oss-20b")  # override is for the primary only
    providers.get_provider.cache_clear()
    try:
        p = providers.get_provider()
    finally:
        providers.get_provider.cache_clear()
    assert isinstance(p, ChainProvider)
    assert [(x.name, x.model) for x in p.providers] == [("groq", "openai/gpt-oss-20b"), ("gemini", "gemini-flash-lite-latest")]
    assert providers.describe() == "groq/openai/gpt-oss-20b + gemini/gemini-flash-lite-latest"
