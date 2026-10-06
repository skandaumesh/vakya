import hashlib
import logging
import os
from contextvars import ContextVar
from functools import lru_cache

from .. import config
from ..errors import MissingCredentials

# Defaults per provider; VAKYA_MODEL / VAKYA_BASE_URL / VAKYA_JSON_MODE override them.
PRESETS = {
    "groq": {
        "base_url": "https://api.groq.com/openai/v1",
        "key_env": "GROQ_API_KEY",
        "model": "openai/gpt-oss-120b",
        # Each Groq model has its own free daily allowance, so these add capacity.
        # Qwen is weaker at Kanglish, so it is the last resort.
        "fallback_models": ["openai/gpt-oss-20b", "qwen/qwen3.8-27b"],
        "model_extra": {"qwen/qwen3.8-27b": {"reasoning_effort": "none"}},
        # Looks at photos and stickers; also free, with its own allowance.
        "vision_model": "qwen/qwen3.8-27b",
        "json_mode": "schema",
        "max_tokens_param": "max_completion_tokens",
        # gpt-oss thinks before answering; keep it short so replies stay fast.
        "extra": {"reasoning_effort": "low"},
    },
    "gemini": {
        "base_url": "https://generativelanguage.googleapis.com/v1beta/openai/",
        "key_env": "GEMINI_API_KEY",
        # Flash-Lite: ~1.5 s and good Kanglish. On a free key the bigger Flash models are
        # rate-limited from the first request and gemini-2.5-* is closed to new users, so the
        # backups are other Lite models: each has its own capacity when one answers
        # "503 high demand" (checked 2026-10-06).
        "model": "gemini-flash-lite-latest",
        "fallback_models": ["gemini-3.5-flash-lite", "gemini-3.1-flash-lite-preview", "gemini-3.1-flash-lite"],
        "vision_model": "gemini-flash-lite-latest",
        "json_mode": "schema",
        "max_tokens_param": "max_tokens",
        "extra": {"reasoning_effort": "low"},
    },
    "ollama": {
        "base_url": "http://localhost:11434/v1",
        "key_env": None,
        "model": "qwen2.5:7b",
        "json_mode": "object",
        "max_tokens_param": "max_tokens",
        "extra": {},
    },
    "openai": {
        "base_url": None,  # VAKYA_BASE_URL is required
        "key_env": "VAKYA_API_KEY",
        "model": None,  # VAKYA_MODEL is required
        "json_mode": "object",
        "max_tokens_param": "max_tokens",
        "extra": {},
    },
}

ANTHROPIC_MODEL = "claude-haiku-4-5"

log = logging.getLogger("vakya.providers")


def _build(name: str, primary: bool):
    """One provider. VAKYA_MODEL and the other overrides apply to the primary only."""
    timeout = config.LLM_TIMEOUT
    override = (lambda value: value) if primary else (lambda value: None)

    if name == "anthropic":
        from .anthropic_provider import AnthropicProvider

        return AnthropicProvider(override(config.MODEL) or ANTHROPIC_MODEL, timeout=timeout)

    if name not in PRESETS:
        choices = ", ".join([*PRESETS, "anthropic"])
        raise MissingCredentials(f"unknown provider {name!r}; use one of: {choices}")

    from .openai_compat import OpenAICompatProvider

    preset = PRESETS[name]
    base_url = override(config.BASE_URL) or preset["base_url"]
    model = override(config.MODEL) or preset["model"]
    if not base_url or not model:
        raise MissingCredentials(f"provider {name!r} needs VAKYA_BASE_URL and VAKYA_MODEL")

    key_env = preset["key_env"]
    api_key = override(config.API_KEY) or (os.environ.get(key_env) if key_env else None)
    if key_env and not api_key:
        raise MissingCredentials(f"no API key for {name}: set {key_env}")

    extra = dict(preset["extra"])
    if config.REASONING_EFFORT and "reasoning_effort" in extra:
        extra["reasoning_effort"] = config.REASONING_EFFORT

    if primary and config.FALLBACK_MODELS is not None:
        fallbacks = [m.strip() for m in config.FALLBACK_MODELS.split(",") if m.strip()]
    else:
        fallbacks = preset.get("fallback_models", [])
    fallbacks = [m for m in dict.fromkeys(fallbacks) if m != model]

    return OpenAICompatProvider(
        name=name,
        base_url=base_url,
        api_key=api_key or "not-needed",  # local servers like Ollama ignore it
        model=model,
        json_mode=override(config.JSON_MODE) or preset["json_mode"],
        max_tokens_param=preset["max_tokens_param"],
        extra=extra,
        timeout=timeout,
        fallback_models=fallbacks,
        model_extra=preset.get("model_extra", {}),
        vision_model=override(config.VISION_MODEL) or preset.get("vision_model"),
    )


def _backup_names() -> list[str]:
    names = [n.strip().lower() for n in (config.FALLBACK_PROVIDERS or "").split(",") if n.strip()]
    return [n for n in dict.fromkeys(names) if n != config.PROVIDER]


@lru_cache(maxsize=1)
def get_provider():
    primary = _build(config.PROVIDER, primary=True)
    backups = []
    for name in _backup_names():
        try:
            backups.append(_build(name, primary=False))
        except MissingCredentials as e:
            # A backup without a key is skipped, not fatal: the primary still works.
            log.warning("backup provider %s skipped: %s", name, e)
    if not backups:
        return primary

    from .chain import ChainProvider

    return ChainProvider([primary, *backups])


def describe() -> str:
    """Providers and models for /health, without creating clients."""
    def one(name: str, primary: bool) -> str:
        model = config.MODEL if primary else None
        if name == "anthropic":
            return f"anthropic/{model or ANTHROPIC_MODEL}"
        return f"{name}/{model or PRESETS.get(name, {}).get('model')}"

    def has_key(name: str) -> bool:
        key_env = "ANTHROPIC_API_KEY" if name == "anthropic" else PRESETS.get(name, {}).get("key_env")
        return not key_env or bool(os.environ.get(key_env))

    backups = [one(n, False) for n in _backup_names() if has_key(n)]
    return " + ".join([one(config.PROVIDER, True), *backups])


# ---------- A friend's own Groq key ("bring your own key") ----------

# Set per request when the phone sends its own key; everything below the request
# handler (replies and photo/sticker reading) then uses it instead of the shared one.
_request_provider: ContextVar = ContextVar("vakya_request_provider", default=None)


def active_provider():
    """The provider for the current request: the phone's own key if it sent one, else the shared setup."""
    return _request_provider.get() or get_provider()


def use_for_request(provider):
    """Make [provider] the active one for the current request (returns a token for reset)."""
    return _request_provider.set(provider)


def reset_request(token) -> None:
    _request_provider.reset(token)


_user_providers: dict[str, object] = {}


def user_groq_provider(api_key: str):
    """A Groq provider on the phone owner's own free key. The key is used for their
    requests only; it is never logged or written anywhere."""
    from .openai_compat import OpenAICompatProvider

    fingerprint = hashlib.sha256(api_key.encode()).hexdigest()
    if fingerprint not in _user_providers:
        if len(_user_providers) > 200:  # keep memory bounded
            _user_providers.clear()
        preset = PRESETS["groq"]
        _user_providers[fingerprint] = OpenAICompatProvider(
            name="your Groq key",
            base_url=preset["base_url"],
            api_key=api_key,
            model=preset["model"],
            json_mode=preset["json_mode"],
            max_tokens_param=preset["max_tokens_param"],
            extra=dict(preset["extra"]),
            timeout=config.LLM_TIMEOUT,
            fallback_models=preset["fallback_models"],
            model_extra=preset.get("model_extra", {}),
            vision_model=preset.get("vision_model"),
        )
    return _user_providers[fingerprint]
