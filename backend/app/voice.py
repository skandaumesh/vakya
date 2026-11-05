"""Friday's natural voice: what she says, spoken by a neural voice model (Orpheus on Groq).

Optional by design: when the voice can't be had (switched off, rate-limited, the model's terms
not yet accepted on the Groq account), speak() returns None and the phone says it with its own
voice instead. A voice problem never fails a request.
"""

import asyncio
import logging
import re
import time
from collections import OrderedDict

from . import config
from .errors import RateLimited
from .providers import active_provider

log = logging.getLogger("vakya.voice")

# Orpheus English voices on Groq. Women: diana, hannah, autumn. Men: austin, daniel, troy.
VOICES = ("diana", "hannah", "autumn", "austin", "daniel", "troy")

# After a failure, leave the voice alone for a while so each reply isn't slowed by retrying it.
_off_until = 0.0
# Short lines said again and again ("How can I help you, boss?"), kept so they cost nothing.
_cache: "OrderedDict[tuple[str, str], bytes]" = OrderedDict()
_CACHE_SIZE = 64
_CACHE_CHARS = 80

_SENTENCE_END = re.compile(r"(?<=[.!?])\s+")
# What a voice can say: letters, digits, everyday punctuation. Emoji and markup are dropped.
_UNSAYABLE = re.compile(r"[^0-9A-Za-zÀ-ɏ\s.,!?'\"’‘“”:;()%/&+\-–—]")


def clean(text: str) -> str:
    text = text.replace("&", " and ").replace("’", "'").replace("‘", "'")
    text = _UNSAYABLE.sub(" ", text)
    return re.sub(r"\s+", " ", text).strip()


def speakable(text: str) -> bool:
    """English (Latin letters) mostly: the voice can't read Kannada or Hindi script."""
    letters = [c for c in text if c.isalpha()]
    if not letters:
        return False
    latin = sum(1 for c in letters if c.isascii() or "À" <= c <= "ɏ")
    return latin / len(letters) >= 0.9


def pieces(text: str, limit: int) -> list[str]:
    """Whole sentences, joined up to [limit] characters each; a longer sentence is broken at a
    comma or a space. Fewer, fuller pieces: fewer requests, and the pieces are made in parallel."""
    out: list[str] = []
    current = ""
    for sentence in _SENTENCE_END.split(clean(text)):
        while len(sentence) > limit:
            cut = max(sentence.rfind(", ", 0, limit), sentence.rfind(" ", 0, limit))
            cut = cut if cut > limit // 3 else limit
            head, sentence = sentence[:cut].strip(), sentence[cut:].lstrip(", ").strip()
            if current:
                out.append(current)
                current = ""
            out.append(head)
        if not sentence:
            continue
        if current and len(current) + 1 + len(sentence) <= limit:
            current += " " + sentence
        else:
            if current:
                out.append(current)
            current = sentence
    if current:
        out.append(current)
    return out


def _parse_wav(data: bytes) -> tuple[bytes, bytes]:
    """(the 'fmt ' chunk, the audio) of a WAV. Streamed WAVs may give no sizes: the rest is audio."""
    if len(data) < 12 or data[:4] != b"RIFF" or data[8:12] != b"WAVE":
        raise ValueError("not a WAV")
    i, fmt = 12, b""
    while i + 8 <= len(data):
        chunk, size = data[i:i + 4], int.from_bytes(data[i + 4:i + 8], "little")
        start = i + 8
        if chunk == b"data":
            end = len(data) if size in (0, 0xFFFFFFFF) or start + size > len(data) else start + size
            if not fmt:
                raise ValueError("WAV without a format")
            return fmt, data[start:end]
        if chunk == b"fmt ":
            fmt = data[start:start + size]
        i = start + size + (size & 1)
    raise ValueError("WAV without audio")


def join(wavs: list[bytes]) -> bytes:
    """One WAV from several of the same format, played one after another."""
    parts = [_parse_wav(w) for w in wavs]
    fmt = parts[0][0]
    if any(f[:16] != fmt[:16] for f, _ in parts):
        raise ValueError("pieces came back in different formats")
    audio = b"".join(a for _, a in parts)
    if len(audio) % 2:
        audio += b"\0"
    header = b"fmt " + len(fmt).to_bytes(4, "little") + fmt
    if len(fmt) % 2:
        header += b"\0"
    body = b"WAVE" + header + b"data" + len(audio).to_bytes(4, "little") + audio
    return b"RIFF" + len(body).to_bytes(4, "little") + body


async def speak(text: str, voice: str) -> bytes | None:
    """[text] said in [voice] as one WAV, or None if the voice isn't available right now."""
    global _off_until
    if not config.TTS or voice not in VOICES or time.monotonic() < _off_until:
        return None
    if not speakable(text):
        return None
    parts = pieces(text, config.TTS_MAX_CHARS)
    if not parts:
        return None
    key = (voice, " ".join(parts))
    if key in _cache:
        _cache.move_to_end(key)
        return _cache[key]
    provider = active_provider()
    if not hasattr(provider, "speak"):
        return None

    started = time.perf_counter()
    gate = asyncio.Semaphore(4)

    async def one(piece: str) -> bytes:
        async with gate:
            return await provider.speak(piece, voice)

    try:
        wavs = await asyncio.wait_for(asyncio.gather(*(one(p) for p in parts)), timeout=config.TTS_TIMEOUT)
        audio = join(list(wavs))
    except RateLimited as e:
        wait = e.retry_after if e.retry_after else (3600 if e.daily else 60)
        _off_until = time.monotonic() + min(max(wait, 20), 3600)
        log.warning("voice paused for %ds: %s", int(_off_until - time.monotonic()), e)
        return None
    except Exception as e:  # never let the voice fail a reply
        # A refused model (terms not accepted, no access) won't change in seconds; a timeout might.
        pause = 60 if isinstance(e, (asyncio.TimeoutError, TimeoutError)) else 600
        _off_until = time.monotonic() + pause
        log.warning("voice unavailable for %ds: %s %s", pause, type(e).__name__, e)
        return None
    log.info("voice voice=%s pieces=%d chars=%d ms=%d bytes=%d", voice, len(parts), len(text),
             int((time.perf_counter() - started) * 1000), len(audio))
    if len(text) <= _CACHE_CHARS:
        _cache[key] = audio
        while len(_cache) > _CACHE_SIZE:
            _cache.popitem(last=False)
    return audio


def reset() -> None:
    """Forget pauses and saved lines (tests)."""
    global _off_until
    _off_until = 0.0
    _cache.clear()
