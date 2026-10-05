import pytest

from app import config, providers


@pytest.fixture(autouse=True)
def isolate_from_local_env(monkeypatch):
    """backend/.env is loaded on import; keep its backup-provider setting out of tests."""
    monkeypatch.setattr(config, "FALLBACK_PROVIDERS", "")
    providers.get_provider.cache_clear()
    yield
    providers.get_provider.cache_clear()
