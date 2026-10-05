import os
from pathlib import Path


def _load_dotenv(path: Path) -> None:
    """Read KEY=value lines from backend/.env. Real environment variables win."""
    if not path.exists():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = (part.strip() for part in line.split("=", 1))
        value = value.strip("\"'")
        if key and value and key not in os.environ:
            os.environ[key] = value


_load_dotenv(Path(__file__).resolve().parent.parent / ".env")

# Which AI service writes the replies. Free options first:
#   groq       free tier, fast, no training on your data (default)
#   gemini     free tier, but Google may use free-tier prompts and humans may review them
#   ollama     runs on your own computer, free and private, needs a decent GPU/RAM
#   openai     any other OpenAI-compatible API: set VAKYA_BASE_URL and VAKYA_API_KEY
#   anthropic  Claude (paid)
PROVIDER = os.environ.get("VAKYA_PROVIDER", "groq").strip().lower()

# Backup providers, tried in order when the main one is out of quota, e.g. "gemini".
# One without an API key is skipped.
FALLBACK_PROVIDERS = os.environ.get("VAKYA_FALLBACK_PROVIDERS") or ""

# Provider to try first for Kanglish chats, if it is configured (main or backup):
# Gemini understands Kannada much better than Groq's models. "" turns this off.
KANGLISH_PROVIDER = os.environ.get("VAKYA_KANGLISH_PROVIDER", "gemini").strip().lower() or None

# Optional overrides of the provider preset (see providers/__init__.py).
MODEL = os.environ.get("VAKYA_MODEL") or None
BASE_URL = os.environ.get("VAKYA_BASE_URL") or None
API_KEY = os.environ.get("VAKYA_API_KEY") or None
JSON_MODE = os.environ.get("VAKYA_JSON_MODE") or None  # "schema" or "object"
# Model that describes photos and stickers (defaults per provider; Groq: qwen/qwen3.8-27b).
VISION_MODEL = os.environ.get("VAKYA_VISION_MODEL") or None
# Comma-separated models to try when the main one hits its free limit ("" turns it off).
FALLBACK_MODELS = os.environ.get("VAKYA_FALLBACK_MODELS")
# For thinking models (Groq gpt-oss, Gemini): low = fastest, medium/high = more careful.
REASONING_EFFORT = os.environ.get("VAKYA_REASONING_EFFORT") or None

# Shared secret the Android app sends in the X-Vakya-Key header.
# Leave unset for local development (no auth).
APP_KEY = os.environ.get("VAKYA_APP_KEY") or None

# AI replies each phone may use per day on the shared key (0 = no limit). Phones
# that send their own Groq key are not limited.
DAILY_LIMIT = int(os.environ.get("VAKYA_DAILY_LIMIT", "0"))

# Keep a USB phone linked to this server with adb reverse (local testing). "0" turns it off.
PHONE_LINK = os.environ.get("VAKYA_PHONE_LINK", "1") != "0"
PORT = int(os.environ.get("VAKYA_PORT", "8000"))

# Seconds before a model call is abandoned; a late suggestion is useless.
LLM_TIMEOUT = float(os.environ.get("VAKYA_LLM_TIMEOUT", "15"))
