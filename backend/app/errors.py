"""Provider-neutral errors. Each provider adapter translates its SDK's errors into these."""


class LLMError(Exception):
    """The model call failed or answered with something we can't use."""


class LLMRefusal(LLMError):
    pass


class RateLimited(LLMError):
    def __init__(self, message: str, daily: bool = False, retry_after: float | None = None):
        super().__init__(message)
        # Daily caps take hours to recover, per-minute ones seconds; callers shouldn't wait on daily.
        self.daily = daily
        self.retry_after = retry_after  # seconds, when the provider says


class ProviderTimeout(LLMError):
    pass


class ProviderUnavailable(LLMError):
    """The provider refuses this key or project (e.g. Gemini "project denied access")."""


class MissingCredentials(Exception):
    pass


class DailyLimitReached(Exception):
    """This phone used its daily share of the shared AI key."""
