"""Turn photos and stickers in the chat into short descriptions the reply model can use."""

import asyncio
import hashlib
import logging
import re
from collections import OrderedDict

from .providers import active_provider
from .schemas import ChatMessage

log = logging.getLogger("vakya.vision")

# Only the newest image matters for the reply, and each one costs ~1k tokens of a
# free per-minute budget that the reply itself also needs.
MAX_IMAGES = 1

# The same sticker gets sent again and again; describe it once.
_CACHE: "OrderedDict[str, str]" = OrderedDict()
_CACHE_SIZE = 300

PROMPT = (
    "This {kind} was sent in a WhatsApp chat. In under 15 words, say what it shows and its "
    "mood or meaning, so someone can reply to it. No preamble."
)

_THINK = re.compile(r"<think>.*?</think>", re.DOTALL)


async def _describe(msg: ChatMessage) -> str | None:
    key = hashlib.sha256(msg.image.encode()).hexdigest()
    if key in _CACHE:
        _CACHE.move_to_end(key)
        return _CACHE[key]
    try:
        text = await active_provider().describe_image(msg.image, PROMPT.format(kind=msg.media or "image"))
    except Exception as e:  # a missing description must never block the reply
        log.info("vision failed: %s", type(e).__name__)
        return None
    text = _THINK.sub("", text or "").strip().strip('"').rstrip(".")[:150]
    if text:
        _CACHE[key] = text
        while len(_CACHE) > _CACHE_SIZE:
            _CACHE.popitem(last=False)
    return text or None


async def describe_media(messages: list[ChatMessage]) -> dict[int, str]:
    """Index of message -> description, for the newest messages that carry an image."""
    with_image = [i for i, m in enumerate(messages) if m.media and m.image][-MAX_IMAGES:]
    results = await asyncio.gather(*(_describe(messages[i]) for i in with_image))
    return {i: d for i, d in zip(with_image, results) if d}
