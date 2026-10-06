import asyncio
import json
from pathlib import Path

from app import llm
from app.prompts import render_suggest_input
from app.schemas import ChatMessage, StyleCard, Suggestion, SuggestOutput, SuggestRequest

PERSONA = StyleCard.model_validate(
    json.loads((Path(__file__).parent.parent / "eval" / "personas.json").read_text(encoding="utf-8"))["casual_kanglish"]
)


def req(**kw) -> SuggestRequest:
    base = dict(messages=[ChatMessage(sender="them", text="What's your rate for a website?")], style_card=PERSONA)
    return SuggestRequest(**{**base, **kw})


# ---------- invented money ----------

def test_invented_amounts_flags_new_numbers_only():
    source = "the full site will be 45k. can you do it for 30k? also lend me 2k"
    assert llm.invented_amounts(["30k sari", "ok 2k by friday", "45k fixed"], source) == []
    assert llm.invented_amounts(["maybe 35k?"], source) == ["35k"]
    assert llm.invented_amounts(["My rate is $80 per hour", "around ₹15k", "Rs 500 extra"], "") == ["$80 ", "₹15k", "Rs 500"]


def test_invented_amounts_ignores_times_and_counts():
    assert llm.invented_amounts(["call at 5pm?", "send 3 pages by 10:30", "2 mins"], "") == []


def test_invented_price_triggers_one_retry(monkeypatch):
    replies = [
        SuggestOutput(intent="QUESTION", relationship_guess="client", language="English",
                      suggestions=[Suggestion(label="Answer", text="My rate is $80 per hour")],
                      memory_add=[], memory_resolve=[]),
        SuggestOutput(intent="QUESTION", relationship_guess="client", language="English",
                      suggestions=[Suggestion(label="Answer", text="depends on scope, can you share details?")],
                      memory_add=[], memory_resolve=[]),
    ]
    seen = []

    async def _parse(system, user_content, output_format, max_tokens, **_kw):
        seen.append(user_content)
        return replies.pop(0), 100

    monkeypatch.setattr(llm, "_parse", _parse)
    out = asyncio.run(llm.suggest_replies(req(relationship="client")))
    assert out.suggestions[0].text.startswith("depends on scope")
    assert out.latency_ms == 200
    assert "<note>" not in seen[0] and "$80" in seen[1]


def test_labels_are_tidied(monkeypatch):
    async def _parse(*a, **k):
        return SuggestOutput(intent="REQUEST", relationship_guess="client", language="English",
                             suggestions=[Suggestion(label="NeedTime", text="x"), Suggestion(label="Send now", text="y"),
                                          Suggestion(label="OK", text="z")],
                             memory_add=[], memory_resolve=[]), 1

    monkeypatch.setattr(llm, "_parse", _parse)
    labels = [s.label for s in asyncio.run(llm.suggest_replies(req())).suggestions]
    assert labels == ["Need time", "Send now", "OK"]


# ---------- slurs ----------

def test_slurs_are_stripped_but_slang_stays():
    from app.prompts import strip_slurs

    assert strip_slurs("wassup nigga 😂") == "wassup 😂"
    assert strip_slurs("Nigga, you coming?") == "you coming?"
    assert strip_slurs("bro mari le blud 💀 bhai macha") == "bro mari le blud 💀 bhai macha"
    assert strip_slurs("they live in niger") == "they live in niger"  # a country, not a slur


def test_slurs_never_reach_the_model_or_the_user(monkeypatch):
    card = PERSONA.model_copy(update={"traits": PERSONA.traits.model_copy(update={
        "summary": "uses 'blud' and 'nigga' a lot", "fillers": ["blud", "nigga", "mari"],
        "signature_phrases": ["wassup nigga", "ok mari"],
    })})
    out = render_suggest_input(req(relationship="friend", style_card=card))
    assert "nigga" not in out.lower()
    assert "blud" in out and "mari" in out

    async def _parse(*a, **k):
        return SuggestOutput(intent="GREETING", relationship_guess="friend", language="English",
                             suggestions=[Suggestion(label="Greet", text="yo nigga wassup"),
                                          Suggestion(label="Only slur", text="nigga")],
                             memory_add=[], memory_resolve=[]), 1

    monkeypatch.setattr(llm, "_parse", _parse)
    texts = [s.text for s in asyncio.run(llm.suggest_replies(req(relationship="friend"))).suggestions]
    assert texts == ["yo wassup"]


# ---------- placeholder text in the message box ----------

def test_placeholder_is_not_a_draft():
    from app.prompts import clean_draft

    for placeholder in ("Message", "message", " Message… ", "Type a message", "Text message", "RCS message"):
        assert clean_draft(placeholder) == ""
    assert clean_draft("Message me later") == "Message me later"
    assert clean_draft("yes will send") == "yes will send"
    out = render_suggest_input(req(draft="Message"))
    assert "<draft>" not in out


# ---------- friend-only words and style ----------

def test_strip_words():
    banned = {"da", "bro", "maga"}
    assert llm.strip_words("sari da, will send tonight 👍", banned) == "sari, will send tonight 👍"
    assert llm.strip_words("ok da", banned) == "ok"
    assert llm.strip_words("sorry bro, can't now", banned) == "sorry, can't now"
    assert llm.strip_words("Da, sure", banned) == "sure"
    assert llm.strip_words("send the data today", banned) == "send the data today"
    assert llm.strip_words("madtini maga!", banned) == "madtini!"
    assert llm.strip_words("da", banned) == "da"  # never return an empty reply
    assert llm.strip_words("ok da", set()) == "ok da"


def test_friend_words_stripped_when_model_guesses_professor(monkeypatch):
    async def _parse(*a, **k):
        return SuggestOutput(intent="REQUEST", relationship_guess="professor", language="English",
                             suggestions=[Suggestion(label="Accept", text="ok da, will be there at 11 👍")],
                             memory_add=[], memory_resolve=[]), 1

    monkeypatch.setattr(llm, "_parse", _parse)
    out = asyncio.run(llm.suggest_replies(req(relationship=None)))
    assert out.suggestions[0].text == "ok, will be there at 11 👍"


def test_friend_words_kept_for_friends_and_when_i_use_them(monkeypatch):
    async def _parse(*a, **k):
        return SuggestOutput(intent="REQUEST", relationship_guess="client", language="English",
                             suggestions=[Suggestion(label="Accept", text="ok da 👍")],
                             memory_add=[], memory_resolve=[]), 1

    monkeypatch.setattr(llm, "_parse", _parse)
    assert asyncio.run(llm.suggest_replies(req(relationship="friend"))).suggestions[0].text == "ok da 👍"
    out = asyncio.run(llm.suggest_replies(req(relationship="client", examples=["ok da will send"])))
    assert out.suggestions[0].text == "ok da 👍"

def test_friend_words_removed_for_clients():
    out = render_suggest_input(req(relationship="client", examples=["yes sure, will send by evening"]))
    assert "never use in this chat: bro, da, dude, guru, macha, machan, machi, maga, yaar" in out
    style = out.split("<my_style>")[1].split("</my_style>")[0]
    assert "fillers:" not in style  # all of the persona's fillers are friend-only
    assert '"ok da"' not in style and '"will do"' in style


def test_friend_words_kept_if_i_use_them_with_this_person():
    out = render_suggest_input(req(relationship="client", examples=["ok da will send"]))
    assert "never use in this chat:" in out
    assert "da," not in out.split("never use in this chat:")[1].split("\n")[0]
    assert "fillers: da" in out


def test_friends_keep_everything():
    out = render_suggest_input(req(relationship="friend"))
    assert "never use in this chat" not in out
    assert "fillers: bro, da, maga, macha" in out


def test_style_card_only_sent_for_mine():
    for style in ("professional", "short", "friendly"):
        out = render_suggest_input(req(style=style, examples=["ok da"]))
        assert "<my_style>" not in out and "<my_past_messages_in_this_chat>" not in out
    assert "<my_style>" in render_suggest_input(req(style="mine"))


def test_assistant_phrases_are_cut_from_options():
    from app.llm import strip_ai_phrases
    assert strip_ai_phrases("Will share the logo files by 6. Let me know if you need anything else.") == "Will share the logo files by 6."
    assert strip_ai_phrases("Feel free to call me anytime!") == "Feel free to call me anytime!"  # nothing left: keep
    assert strip_ai_phrases("sari maga, bartini") == "sari maga, bartini"
    assert strip_ai_phrases("Hi Priya, sorry for the delay—I'll share them soon") == "Hi Priya, sorry for the delay, I'll share them soon"


def test_foreign_scripts_in_options_are_caught():
    from app.llm import foreign_scripts
    assert foreign_scripts(["sari macha, आराम agi ba"], ["swalpa late aagutte"]) == {"Devanagari"}
    assert foreign_scripts(["ಆಯ್ತು, ಬರ್ತೀನಿ"], ["ಊಟ ಆಯ್ತಾ?"]) == set()  # they write Kannada script: fine
    assert foreign_scripts(["ಆಯ್ತು"], ["oota aytha?"]) == {"Kannada"}  # they write in English letters
    assert foreign_scripts(["sari, bartini 👍"], ["naale bartiya?"]) == set()
