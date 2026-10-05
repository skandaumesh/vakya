"""Try providers in order (e.g. Groq, then Gemini) when one is out of quota."""

import logging
import time

from ..errors import LLMError, MissingCredentials, ProviderTimeout, ProviderUnavailable, RateLimited

log = logging.getLogger("vakya.providers")

# A provider that refuses the key or project won't change its mind in seconds.
UNAVAILABLE_SKIP_S = 30 * 60

# Errors that mean "try the next provider" rather than "the request itself is bad".
_SKIPPABLE = (RateLimited, ProviderTimeout, ProviderUnavailable, MissingCredentials)


class ChainProvider:
    def __init__(self, providers: list):
        self.providers = providers
        self.name = "+".join(p.name for p in providers)
        self._skip_until: dict[int, float] = {}
        # Waiting out a per-minute limit only makes sense on the last provider;
        # before that, moving on to the next one is faster.
        for p in providers[:-1]:
            if hasattr(p, "max_wait_s"):
                p.max_wait_s = 0

    def _available(self):
        now = time.monotonic()
        return [p for i, p in enumerate(self.providers) if self._skip_until.get(i, 0) <= now]

    def _note_failure(self, p, e: Exception) -> None:
        if isinstance(e, (ProviderUnavailable, MissingCredentials)):
            self._skip_until[self.providers.index(p)] = time.monotonic() + UNAVAILABLE_SKIP_S
            log.warning("provider %s skipped for %d min: %s", p.name, UNAVAILABLE_SKIP_S // 60, e)
        else:
            log.info("provider %s unavailable (%s), trying next", p.name, type(e).__name__)

    @staticmethod
    def _most_useful(errors: list[Exception]) -> Exception:
        # "Groq daily limit reached, try again in 2h" helps the user more than a
        # backup's "access denied", so report a rate limit first.
        return next((e for e in errors if isinstance(e, RateLimited)), errors[-1])

    async def parse(self, system, user_content, output_format, max_tokens, prefer: str | None = None):
        """[prefer]: a provider name to try first for this request (e.g. Gemini for Kanglish)."""
        errors: list[Exception] = []
        order = sorted(self._available(), key=lambda p: p.name != prefer) if prefer else self._available()
        for p in order:
            try:
                return await p.parse(system, user_content, output_format, max_tokens)
            except _SKIPPABLE as e:
                self._note_failure(p, e)
                errors.append(e)
        raise self._most_useful(errors) if errors else RateLimited("all AI services are unavailable right now")

    async def describe_image(self, image_b64: str, prompt: str) -> str:
        errors: list[Exception] = []
        for p in self._available():
            try:
                return await p.describe_image(image_b64, prompt)
            except (LLMError, MissingCredentials) as e:  # rate limits, no vision model, refused...
                if isinstance(e, (ProviderUnavailable, MissingCredentials)):
                    self._note_failure(p, e)
                errors.append(e)
        raise errors[-1] if errors else LLMError("no vision available")
