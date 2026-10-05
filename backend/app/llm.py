import re

from . import config

from .errors import LLMError, LLMRefusal, MissingCredentials, ProviderTimeout, ProviderUnavailable, RateLimited
from .prompts import FRIEND_ONLY_WORDS, NON_FRIEND, SLURS, STYLE_SYSTEM, SUGGEST_SYSTEM, detect_mix, render_style_input, render_suggest_input, strip_slurs
from .providers import active_provider
from .schemas import (
    StyleCard,
    StyleTraits,
    SuggestOutput,
    SuggestRequest,
    SuggestResponse,
)
from .style_stats import compute_stats
from .vision import describe_media

__all__ = [
    "suggest_replies", "build_style_card",
    "LLMError", "LLMRefusal", "MissingCredentials", "ProviderTimeout", "ProviderUnavailable", "RateLimited",
]

# Most recent messages are the most representative of current style.
STYLE_SAMPLE_SIZE = 300


async def _parse(system: str, user_content: str, output_format, max_tokens: int, prefer: str | None = None):
    """One structured call to whichever provider is configured. Returns (parsed, elapsed_ms).
    [prefer] names a provider to try first when several are configured."""
    provider = active_provider()
    if prefer and hasattr(provider, "providers"):
        return await provider.parse(system, user_content, output_format, max_tokens, prefer=prefer)
    return await provider.parse(system, user_content, output_format, max_tokens)


# Money amounts: ₹15k, $80, Rs 500, 35k, 2 lakh, 500 rupees.
_MONEY = re.compile(
    r"(?:[₹$€£]\s?\d[\d,.]*\s?k?|\b(?:rs\.?|inr|usd)\s?\d[\d,.]*|\b\d[\d,.]*\s?(?:k|lakhs?|rupees|bucks|dollars)\b)",
    re.IGNORECASE,
)
_NUMBER = re.compile(r"\d+(?:[.,]\d+)*")


def invented_amounts(texts: list[str], source: str) -> list[str]:
    """Money amounts in the options whose numbers appear nowhere in the chat, memory or draft."""
    known = set(_NUMBER.findall(source))
    return [m for t in texts for m in _MONEY.findall(t) if not set(_NUMBER.findall(m)) <= known]


def strip_words(text: str, banned: set[str]) -> str:
    """'sari da, will send' -> 'sari, will send'. Last line of defence for friend-only words."""
    if not banned:
        return text
    pattern = r"\s*\b(?:" + "|".join(sorted(map(re.escape, banned))) + r")\b(?=\W|$)"
    out = re.sub(pattern, "", text, flags=re.IGNORECASE)
    out = re.sub(r"\s+([,.!?])", r"\1", out)  # "ok , sure" -> "ok, sure"
    out = re.sub(r"^[\s,]+", "", out)
    return out.strip() or text


def _tidy_label(label: str) -> str:
    """'NeedTime' -> 'Need time'."""
    label = label.strip()
    if " " not in label and re.search(r"[a-z][A-Z]", label):
        words = re.sub(r"(?<=[a-z])(?=[A-Z])", " ", label).split()
        label = " ".join([words[0], *(w.lower() for w in words[1:])])
    return label


async def suggest_replies(req: SuggestRequest) -> SuggestResponse:
    media_notes = await describe_media(req.messages)
    user_content = render_suggest_input(req, media_notes)
    # Kanglish is understood far better by Gemini than by Groq's models, so ask it first.
    mix = detect_mix([m.text for m in req.messages if m.sender == "them"])
    prefer = config.KANGLISH_PROVIDER if mix == "kanglish" else None
    out: SuggestOutput
    out, elapsed_ms = await _parse(SUGGEST_SYSTEM, user_content, SuggestOutput, max_tokens=2000, prefer=prefer)

    # Prices are the costliest thing to get wrong in a client chat, and prompting
    # alone doesn't stop the model inventing them, so check and retry once.
    source = " ".join([*(m.text for m in req.messages), *req.memory, req.draft])
    invented = invented_amounts([s.text for s in out.suggestions], source)
    if invented:
        note = (
            f"\n\n<note>Your previous options stated amounts I never mentioned ({', '.join(invented)}). "
            "Write the options again without any price or amount that isn't in the conversation or memory.</note>"
        )
        out, retry_ms = await _parse(SUGGEST_SYSTEM, user_content + note, SuggestOutput, max_tokens=2000, prefer=prefer)
        elapsed_ms += retry_ms

    # Friend-only address words never go to a client, professor, family member...,
    # whether I set the relationship or the model guessed it, unless I use them here.
    relationship = req.relationship or out.relationship_guess
    banned: set[str] = set()
    if relationship in NON_FRIEND:
        mine_here = req.examples + [m.text for m in req.messages if m.sender == "me"] + [req.draft]
        banned = FRIEND_ONLY_WORDS - {w for t in mine_here for w in re.findall(r"[a-z]+", t.lower())}

    cleaned = [
        s.model_copy(update={"label": _tidy_label(s.label), "text": strip_slurs(strip_words(s.text, banned))})
        for s in out.suggestions
    ]
    suggestions = [s for s in cleaned if s.text.strip()][:3]
    if not suggestions:
        raise LLMError("model returned no suggestions")

    # Only resolve items the client actually sent, so a paraphrase can't delete the wrong one.
    known = set(req.memory)
    return SuggestResponse(
        meaning=out.meaning,
        intent=out.intent,
        relationship_guess=req.relationship or out.relationship_guess,
        language=out.language,
        suggestions=suggestions,
        memory_add=[m for m in out.memory_add if m.strip() and m not in known][:3],
        memory_resolve=[m for m in out.memory_resolve if m in known],
        latency_ms=elapsed_ms,
    )


async def build_style_card(my_messages: list[str]) -> StyleCard:
    sample = [m.strip() for m in my_messages if m.strip()][-STYLE_SAMPLE_SIZE:]
    traits: StyleTraits
    traits, _ = await _parse(
        STYLE_SYSTEM, render_style_input(sample), StyleTraits, max_tokens=3000
    )
    # Never let a slur become part of "how I text" (it would end up in suggestions).
    traits = traits.model_copy(update={
        "summary": strip_slurs(traits.summary),
        "fillers": [f for f in traits.fillers if not SLURS.search(f)],
        "signature_phrases": [x for x in traits.signature_phrases if not SLURS.search(x)],
    })
    return StyleCard(traits=traits, stats=compute_stats(sample))
