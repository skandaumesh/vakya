import asyncio

from app import llm, vision
from app.prompts import render_suggest_input
from app.schemas import ChatMessage, Suggestion, SuggestOutput, SuggestRequest


def msg(sender, text="", **kw):
    return ChatMessage(sender=sender, text=text, **kw)


def test_media_rendered_with_and_without_description():
    req = SuggestRequest(messages=[
        msg("them", "look", media="photo", image="abc"),
        msg("them", media="sticker"),
    ])
    out = render_suggest_input(req, {0: "closed Mac laptop on a desk"})
    assert 'them: [photo: closed Mac laptop on a desk] "look"' in out
    assert "them: [sticker]" in out


def test_reply_to_points_at_their_newest_turn():
    req = SuggestRequest(messages=[
        msg("them", "Need help drafting it?"), msg("me", "Sure"),
        msg("them", "I appreciate it"), msg("them", "But its really cool"),
    ])
    out = render_suggest_input(req)
    reply_to = out.split("<reply_to>")[1].split("</reply_to>")[0]
    assert '"I appreciate it"' in reply_to and '"But its really cool"' in reply_to
    assert "drafting" not in reply_to


def test_reply_to_when_my_message_was_last():
    out = render_suggest_input(SuggestRequest(messages=[msg("them", "hi"), msg("me", "hey")]))
    reply_to = out.split("<reply_to>")[1].split("</reply_to>")[0]
    # Follow-up mode: nothing of theirs to answer (and never my own message as if it were theirs).
    assert "my message was last" in reply_to and "Follow-up mode" in reply_to
    assert '"hi"' not in reply_to and '"hey"' not in reply_to


def test_examples_already_on_screen_are_not_repeated():
    req = SuggestRequest(
        messages=[msg("me", "Need help drafting it?"), msg("them", "ok")],
        examples=["Need help drafting it?", "ok da will do"],
    )
    examples = render_suggest_input(req).split("<my_past_messages_in_this_chat>")[1].split("</my_past")[0]
    assert "drafting" not in examples and "ok da will do" in examples


def test_only_newest_image_is_described_and_repeats_are_free(monkeypatch):
    seen = []

    class FakeProvider:
        async def describe_image(self, image, prompt):
            seen.append(image)
            return f"<think>hmm</think>desc of {image}."

    monkeypatch.setattr(vision, "active_provider", lambda: FakeProvider())
    vision._CACHE.clear()
    messages = [msg("them", media="photo", image=f"img{i}") for i in range(4)]
    assert asyncio.run(vision.describe_media(messages)) == {3: "desc of img3"}
    # Same sticker again (another tap, another chat): served from the cache.
    assert asyncio.run(vision.describe_media([msg("them", media="sticker", image="img3")])) == {0: "desc of img3"}
    assert seen == ["img3"]


def test_kanglish_guide_added_for_kanglish_chats():
    from app.prompts import detect_mix

    assert detect_mix(["maga naale exam ide, nothing studied 😭"]) == "kanglish"
    assert detect_mix(["bhai kal movie chalein?"]) == "hinglish"
    assert detect_mix(["are you free tonight?"]) is None
    out = render_suggest_input(SuggestRequest(messages=[msg("them", "maga saturday party ide, barthiya?")]))
    assert "language_guide: They write Kanglish" in out
    out = render_suggest_input(SuggestRequest(messages=[msg("them", "Can you send the files?")]))
    assert "language_guide" not in out


def test_similar_past_replies_are_shown_for_mine_only():
    from app.schemas import PastReply

    similar = [PastReply(them="maga college ge bartiya?", me="bartini maga 🔥"),
               PastReply(them="wassup", me="yo nigga")]
    out = render_suggest_input(SuggestRequest(messages=[msg("them", "ivattu college ge bartiya")], similar=similar))
    block = out.split("<how_i_replied_before>")[1].split("</how_i_replied_before>")[0]
    assert 'me: "bartini maga 🔥"' in block and "nigga" not in block
    out = render_suggest_input(SuggestRequest(style="professional", messages=[msg("them", "hi")], similar=similar))
    assert "<how_i_replied_before>" not in out


def test_meaning_is_required_from_the_model():
    from app.providers.openai_compat import strict_schema
    from app.schemas import SuggestOutput

    schema = strict_schema(SuggestOutput)
    assert schema["required"][0] == "meaning" and "meaning" in schema["required"]


def test_genz_style_rule_is_sent():
    out = render_suggest_input(SuggestRequest(style="genz", messages=[msg("them", "i failed the exam 😭")]))
    assert "style_rule: Gen Z texting" in out and "no cap" in out


def test_vision_failure_falls_back_to_plain_placeholder(monkeypatch):
    vision._CACHE.clear()
    class Broken:
        async def describe_image(self, image, prompt):
            raise RuntimeError("down")

    monkeypatch.setattr(vision, "active_provider", lambda: Broken())
    assert asyncio.run(vision.describe_media([msg("them", media="sticker", image="x")])) == {}


def test_suggest_uses_descriptions(monkeypatch):
    async def fake_describe(messages):
        return {0: "excited cartoon mouse cheering"}

    seen = {}

    async def _parse(system, user_content, output_format, max_tokens, **_kw):
        seen["content"] = user_content
        return SuggestOutput(intent="CASUAL", relationship_guess="friend", language="English",
                             suggestions=[Suggestion(label="React", text="haha 😂")],
                             memory_add=[], memory_resolve=[]), 5

    monkeypatch.setattr(llm, "describe_media", fake_describe)
    monkeypatch.setattr(llm, "_parse", _parse)
    asyncio.run(llm.suggest_replies(SuggestRequest(messages=[msg("them", media="sticker", image="x")])))
    assert "[sticker: excited cartoon mouse cheering]" in seen["content"]
