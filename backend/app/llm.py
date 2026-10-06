import re

from . import config

from .errors import LLMError, LLMRefusal, MissingCredentials, ProviderTimeout, ProviderUnavailable, RateLimited
from .prompts import (
    COMPOSE_SYSTEM,
    FRIEND_ONLY_WORDS,
    KANNADA_MIXES,
    NON_FRIEND,
    SLURS,
    STYLE_SYSTEM,
    SUGGEST_SYSTEM,
    chat_mix,
    compose_mix,
    render_compose_input,
    render_style_input,
    render_suggest_input,
    strip_slurs,
)
from .providers import active_provider
from .schemas import (
    COMPOSE_STYLES,
    ComposeIdea,
    ComposeOutput,
    ComposeRequest,
    ComposeResponse,
    ComposeVariant,
    StyleCard,
    StyleTraits,
    SuggestOutput,
    SuggestRequest,
    SuggestResponse,
)
from .style_stats import compute_stats
from .vision import describe_media

__all__ = [
    "suggest_replies", "compose_message", "build_style_card",
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


# Assistant phrases the prompt bans but models still write; the sentence holding one is cut.
_AI_SENTENCE = re.compile(
    r"[^.!?\n]*\b(?:let me know if you need anything else|feel free to|i hope this message finds you|"
    r"i'?d be happy to|i would be happy to|please don'?t hesitate)\b[^.!?\n]*[.!?]?",
    re.IGNORECASE,
)


def strip_ai_phrases(text: str) -> str:
    """'Here you go. Let me know if you need anything else.' -> 'Here you go.'
    Em dashes (a tell-tale of AI text) become commas."""
    text = re.sub(r"\s*[—–]\s*", ", ", text).strip(" ,")
    out = re.sub(r"\s{2,}", " ", _AI_SENTENCE.sub("", text)).strip()
    return out or text


# Indian scripts, by Unicode block. A reply in a script the chat doesn't use (Hindi letters
# in a Kanglish chat, "आराम agi ba") is a model slip, not the user's language.
_SCRIPTS = {
    "Devanagari": (0x0900, 0x097F), "Bengali": (0x0980, 0x09FF), "Gurmukhi": (0x0A00, 0x0A7F),
    "Gujarati": (0x0A80, 0x0AFF), "Tamil": (0x0B80, 0x0BFF), "Telugu": (0x0C00, 0x0C7F),
    "Kannada": (0x0C80, 0x0CFF), "Malayalam": (0x0D00, 0x0D7F),
}


def _scripts(text: str) -> set[str]:
    return {name for ch in text for name, (lo, hi) in _SCRIPTS.items() if lo <= ord(ch) <= hi}


def foreign_scripts(options: list[str], chat: list[str]) -> set[str]:
    """Scripts used in the options but nowhere in the chat (or what I typed)."""
    used = set().union(*map(_scripts, chat)) if chat else set()
    return set().union(*map(_scripts, options)) - used if options else set()


def _wrong_script_note(scripts: set[str]) -> str:
    return (
        f"\n\n<note>Your previous options used {', '.join(sorted(scripts))} letters, which this chat doesn't use. "
        "Write them again using only the scripts in the chat: Kannada or Hindi words in English letters "
        "(or Kannada script only if they write in it).</note>"
    )


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
    # Kannada (in English letters or Kannada script) is understood far better by Gemini
    # than by Groq's models, so ask it first.
    prefer = config.KANGLISH_PROVIDER if chat_mix(req) in KANNADA_MIXES else None
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
    elif wrong := foreign_scripts([s.text for s in out.suggestions], [m.text for m in req.messages] + [req.draft]):
        out, retry_ms = await _parse(SUGGEST_SYSTEM, user_content + _wrong_script_note(wrong), SuggestOutput, max_tokens=2000, prefer=prefer)
        elapsed_ms += retry_ms

    # Friend-only address words never go to a client, professor, family member...,
    # whether I set the relationship or the model guessed it, unless I use them here.
    relationship = req.relationship or out.relationship_guess
    banned: set[str] = set()
    if relationship in NON_FRIEND:
        mine_here = req.examples + [m.text for m in req.messages if m.sender == "me"] + [req.draft]
        banned = FRIEND_ONLY_WORDS - {w for t in mine_here for w in re.findall(r"[a-z]+", t.lower())}

    cleaned = [
        s.model_copy(update={"label": _tidy_label(s.label), "text": strip_slurs(strip_ai_phrases(strip_words(s.text, banned)))})
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


def _unquote(text: str) -> str:
    """'"are you coming?"' -> 'are you coming?' (models sometimes quote the message)."""
    text = text.strip()
    for a, b in ('""', "''", "“”"):
        if len(text) > 1 and text[0] == a and text[-1] == b:
            return text[1:-1].strip()
    return text


# Letter openers the model adds to "professional" despite the prompt: a text isn't a letter.
_LETTER_OPENER = re.compile(
    r"^(?:dear\s+[^,\n]{1,30},\s*|(?:please note that|i am writing to (?:let you know|inform you) that|"
    r"this is (?:just )?a (?:quick )?reminder that)\s+)",
    re.IGNORECASE,
)


def _not_a_letter(text: str) -> str:
    """'Dear Sir, please note that the site is late.' -> 'The site is late.'"""
    out = text
    while (m := _LETTER_OPENER.match(out)) and m.end() < len(out):
        out = out[m.end():]
    return out[:1].upper() + out[1:] if out is not text else text


# "ask him if he's coming" in a chat with him means "are you coming?". Models sometimes
# write about him instead ("is he coming?", "avanu bartana?", "can you ask him...").
_ABOUT_CHAT_PERSON = re.compile(r"^\s*(?:pls\s+|please\s+)?(?:ask|tell|remind|inform)\s+(?:him|her|them)\b", re.IGNORECASE)
_THIRD_PERSON = re.compile(
    r"\b(?:ask (?:him|her)|tell (?:him|her)|(?:if|whether|is|will|does|did|has) (?:he|she)|avanu|avalu|avnu|avlu)\b",
    re.IGNORECASE,
)


def talks_about_them(intent: str, texts: list[str]) -> bool:
    """True when I asked to say something TO the person in this chat, but a version talks ABOUT them."""
    return bool(_ABOUT_CHAT_PERSON.match(intent)) and any(_THIRD_PERSON.search(t) for t in texts)


def _texts(out: ComposeOutput) -> list[str]:
    return [v.text for v in out.variants] + [i.text for i in out.ideas]


async def compose_message(req: ComposeRequest) -> ComposeResponse:
    """Write it for me: what I typed in the box -> that message in each style, or, if I asked
    for something to send ("pickup line", "roast him"), 4 different ideas."""
    user_content = render_compose_input(req)
    prefer = config.KANGLISH_PROVIDER if compose_mix(req) in KANNADA_MIXES else None
    out: ComposeOutput
    out, elapsed_ms = await _parse(COMPOSE_SYSTEM, user_content, ComposeOutput, max_tokens=1500, prefer=prefer)

    source = " ".join([*(m.text for m in req.messages), *req.memory, req.intent])
    invented = invented_amounts(_texts(out), source)
    if invented:
        note = (
            f"\n\n<note>Your previous versions stated amounts I never mentioned ({', '.join(invented)}). "
            "Write them again without any price or amount that isn't in what I want to say, the chat or memory.</note>"
        )
        out, retry_ms = await _parse(COMPOSE_SYSTEM, user_content + note, ComposeOutput, max_tokens=1500, prefer=prefer)
        elapsed_ms += retry_ms
    elif out.kind == "message" and talks_about_them(req.intent, [v.text for v in out.variants]):
        note = (
            "\n\n<note>\"him\"/\"her\" in what I want to say is the person this chat is with. Your previous versions "
            "talked about them (\"is he coming?\", \"avanu bartana?\", \"ask him\"). Write every version TO them "
            "directly: \"are you coming?\", \"naale bartiya?\".</note>"
        )
        out, retry_ms = await _parse(COMPOSE_SYSTEM, user_content + note, ComposeOutput, max_tokens=1500, prefer=prefer)
        elapsed_ms += retry_ms
    elif wrong := foreign_scripts(_texts(out), [m.text for m in req.messages] + [req.intent] + req.examples):
        out, retry_ms = await _parse(COMPOSE_SYSTEM, user_content + _wrong_script_note(wrong), ComposeOutput, max_tokens=1500, prefer=prefer)
        elapsed_ms += retry_ms

    banned: set[str] = set()
    if req.relationship in NON_FRIEND:
        mine_here = req.examples + [m.text for m in req.messages if m.sender == "me"] + [req.intent]
        banned = FRIEND_ONLY_WORDS - {w for t in mine_here for w in re.findall(r"[a-z]+", t.lower())}
    by_style: dict[str, str] = {}
    for v in out.variants:
        text = strip_slurs(strip_ai_phrases(strip_words(_unquote(v.text), banned)))
        if v.style == "professional":
            text = _not_a_letter(text)
        if text and v.style not in by_style:
            by_style[v.style] = text
    variants = [ComposeVariant(style=s, text=by_style[s]) for s in COMPOSE_STYLES if s in by_style]

    ideas: list[ComposeIdea] = []
    for i in out.ideas:
        text = strip_slurs(strip_ai_phrases(strip_words(_unquote(i.text), banned)))
        if text and text.lower() not in {x.text.lower() for x in ideas}:
            ideas.append(ComposeIdea(label=_tidy_label(i.label) or "Idea", text=text))
    ideas = ideas[:4]

    # Trust what came back over the label the model gave it.
    kind = "ideas" if ideas and (out.kind == "ideas" or not variants) else "message"
    if kind == "ideas":
        variants = []
    else:
        ideas = []
    if not variants and not ideas:
        raise LLMError("model returned no message")
    return ComposeResponse(
        kind=kind, meaning=out.meaning, language=out.language, variants=variants, ideas=ideas, latency_ms=elapsed_ms,
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
