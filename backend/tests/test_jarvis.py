"""'Hey Jarvis': a spoken command becomes write / suggest / read / answer."""

import base64

import pytest
from fastapi.testclient import TestClient

from app import llm, main
from app.prompts import JARVIS_SYSTEM, render_jarvis_input
from app.schemas import (
    ComposeOutput,
    ComposeResponse,
    ComposeVariant,
    JarvisPlan,
    JarvisRequest,
    SuggestOutput,
    Suggestion,
)

client = TestClient(main.app)

CHAT = {
    "in_chat": True, "chat_title": "Rahul",
    "messages": [{"sender": "them", "text": "where are you? movie starts at 6"}],
}


@pytest.fixture
def fake(monkeypatch):
    seen = {"calls": []}

    async def _parse(system, user_content, output_format, max_tokens, **kw):
        seen["calls"].append(output_format.__name__)
        if output_format is JarvisPlan:
            seen["jarvis_input"] = user_content
            return seen["plan"], 5
        if output_format is ComposeOutput:
            seen["compose_input"] = user_content
            return ComposeOutput(meaning="Say I'll be late", language="English",
                                 variants=[ComposeVariant(style="mine", text="10 mins late, sorry")]), 5
        return SuggestOutput(intent="QUESTION", relationship_guess="friend", language="English",
                             suggestions=[Suggestion(label="On way", text="on the way!")],
                             memory_add=[], memory_resolve=[]), 5

    async def transcribe(audio):
        seen["audio"] = audio
        return seen.get("transcript", "")

    monkeypatch.setattr(llm, "_parse", _parse)
    monkeypatch.setattr(llm, "transcribe", transcribe)
    return seen


def test_dictation_is_written_into_the_open_chat(fake):
    fake["plan"] = JarvisPlan(heard="tell him I'll be ten minutes late", action="write",
                              intent="tell him I'll be ten minutes late", say="Done, boss. It's in the box.")
    r = client.post("/jarvis", json={**CHAT, "text": "Hey Jarvis, tell him I'll be ten minutes late"})
    assert r.status_code == 200, r.text
    data = r.json()
    assert data["action"] == "write" and data["say"] == "Done, boss. It's in the box."
    assert data["compose"]["variants"][0]["text"] == "10 mins late, sorry"
    # The wake word is dropped before the model sees the command.
    assert '<heard>"tell him I\'ll be ten minutes late"</heard>' in fake["jarvis_input"]
    assert "ten minutes late" in fake["compose_input"]


def test_spoken_audio_is_transcribed_first(fake):
    fake["transcript"] = "Hey Jarvis, what should I reply?"
    fake["plan"] = JarvisPlan(heard="what should I reply?", action="suggest", say="He's asking where you are. Options are up.")
    audio = base64.b64encode(b"RIFF....WAVE").decode()
    data = client.post("/jarvis", json={**CHAT, "audio": audio}).json()
    assert fake["audio"] == b"RIFF....WAVE"
    assert data["action"] == "suggest" and data["suggest"]["suggestions"][0]["text"] == "on the way!"


def test_chat_actions_need_an_open_chat(fake):
    fake["plan"] = JarvisPlan(heard="roast him", action="write", intent="roast him", say="Roasting.")
    data = client.post("/jarvis", json={"text": "roast him", "in_chat": False}).json()
    assert data["action"] == "answer" and "open the chat" in data["say"].lower()
    assert fake["calls"] == ["JarvisPlan"]  # nothing written


def test_questions_are_answered_aloud(fake):
    fake["plan"] = JarvisPlan(heard="what's fifteen percent of 2400", action="answer",
                              say="Three hundred and sixty, boss.")
    data = client.post("/jarvis", json={"text": "what's fifteen percent of 2400", "local_time": "Mon 13 Oct 2026, 8:30 pm"}).json()
    assert data["say"] == "Three hundred and sixty, boss." and data["compose"] is None
    assert "time: Mon 13 Oct 2026, 8:30 pm" in fake["jarvis_input"] and "chat open: no" in fake["jarvis_input"]


def test_nothing_heard_asks_again_without_calling_the_model(fake):
    fake["transcript"] = "Hey Jarvis."
    data = client.post("/jarvis", json={"audio": base64.b64encode(b"x").decode()}).json()
    assert data["action"] == "answer" and "again" in data["say"] and fake["calls"] == []


def test_needs_audio_or_text():
    assert client.post("/jarvis", json={"in_chat": True}).status_code == 422
    assert client.post("/jarvis", json={"audio": "not base64!!"}).status_code == 422


def test_jarvis_input_shows_the_screen():
    req = JarvisRequest(**CHAT, local_time="Mon 13 Oct 2026, 8:30 pm")
    out = render_jarvis_input(req, "what did he say?")
    assert "chat open: yes, with Rahul" in out and 'them: "where are you? movie starts at 6"' in out
    assert out.endswith('<heard>"what did he say?"</heard>')
    assert "boss" in JARVIS_SYSTEM and "Never send without that yes" in JARVIS_SYSTEM


def test_ideas_are_announced_not_typed(fake, monkeypatch):
    from app.schemas import ComposeIdea

    async def compose_message(req):
        return ComposeResponse(kind="ideas", meaning="roasts", language="English", latency_ms=1,
                               ideas=[ComposeIdea(label="Savage", text="a"), ComposeIdea(label="Funny", text="b")])

    monkeypatch.setattr(llm, "compose_message", compose_message)
    fake["plan"] = JarvisPlan(heard="roast him", action="write", intent="roast him", say="Done, boss. It's in the box.")
    data = client.post("/jarvis", json={**CHAT, "text": "roast him"}).json()
    assert data["say"] == "2 ideas on screen, boss. Take your pick."


def test_device_tasks_come_back_for_the_phone(fake):
    from app.schemas import DeviceTask
    fake["plan"] = JarvisPlan(heard="set an alarm for 6", action="device", say="Alarm set for six, boss.",
                              task=DeviceTask(kind="alarm", time="06:00"))
    data = client.post("/jarvis", json={"text": "set an alarm for 6"}).json()
    assert data["action"] == "device" and data["task"]["kind"] == "alarm" and data["task"]["time"] == "06:00"


def test_a_device_plan_without_a_task_is_just_an_answer(fake):
    fake["plan"] = JarvisPlan(heard="hmm", action="device", say="Hmm, boss?")
    data = client.post("/jarvis", json={"text": "hmm"}).json()
    assert data["action"] == "answer" and data["task"] is None


def test_follow_ups_see_the_conversation_and_my_list(fake):
    fake["plan"] = JarvisPlan(heard="make it seven", action="answer", say="Done.")
    client.post("/jarvis", json={"text": "make it seven", "notes": ["milk"],
                                 "history": [{"heard": "set an alarm for 6", "say": "Alarm set for six, boss."}]})
    assert '<history>\nme: "set an alarm for 6"\njarvis: "Alarm set for six, boss."\n</history>' in fake["jarvis_input"]
    assert '<notes>\n- "milk"\n</notes>' in fake["jarvis_input"]


def test_malformed_model_output_is_tried_again(fake, monkeypatch):
    from app.errors import LLMError
    calls = []
    real = llm._parse

    async def flaky(system, user_content, output_format, max_tokens, **kw):
        calls.append(kw.get("prefer"))
        if output_format is JarvisPlan and len(calls) == 1:
            raise LLMError("model output did not match schema: json_validate_failed")
        return await real(system, user_content, output_format, max_tokens, **kw)

    monkeypatch.setattr(llm, "_parse", flaky)
    fake["plan"] = JarvisPlan(heard="what's the capital of France", action="answer", say="Paris, boss.")
    assert client.post("/jarvis", json={"text": "what's the capital of France"}).json()["say"] == "Paris, boss."
    assert len(calls) == 2



def test_simple_commands_skip_the_model(fake):
    data = client.post("/jarvis", json={"text": "Hey Friday, set an alarm for 6", "local_time": "Tue 14 Oct 2026, 9:40 pm"}).json()
    assert data["task"]["kind"] == "alarm" and data["task"]["time"] == "06:00"
    assert fake["calls"] == []  # no model call at all


NOTIFS = {
    "notifications_allowed": True,
    "notifications": [
        {"app": "WhatsApp", "who": "Rahul", "text": "bro movie at 6?", "when": "5 min ago"},
        {"app": "Gmail", "who": "HDFC Bank", "text": "Your statement is ready", "when": "1:10 pm"},
    ],
    "habits": "today so far: Instagram 1 h 20 min, WhatsApp 35 min",
    "profile": ["Name: Skanda", "Works at OneZeroLabs"],
}


def _input(heard, **extra):
    return render_jarvis_input(JarvisRequest(text=heard, **{**NOTIFS, **extra}), heard)


def test_notifications_go_to_the_model_only_when_asked_about():
    assert "bro movie at 6?" in _input("any new messages?")
    assert "bro movie at 6?" in _input("what did Rahul want")  # a sender's name
    assert "<notifications>" not in _input("what's 15 percent of 2400")
    assert "<notifications>access is off" in _input("read my notifications", notifications_allowed=False)
    assert "none in the last twelve hours" in _input("any messages?", notifications=[])


def test_an_announced_message_can_be_read_with_yes():
    turn = [{"heard": "", "say": "Message from Rahul on WhatsApp. Shall I read it?"}]
    assert "bro movie at 6?" in _input("yes", history=turn)


def test_phone_habits_only_for_questions_about_my_day():
    assert "Instagram 1 h 20 min" in _input("how long was I on Instagram today?")
    assert "<phone_habits>" not in _input("tell me a joke")
    assert "<phone_habits>\naccess is off" in _input("what's my screen time", habits=None)


def test_friday_always_knows_who_i_am():
    assert '"Name: Skanda"' in _input("tell me a joke")
    assert "<about_boss>nothing yet</about_boss>" in _input("what's my name", profile=[])


def test_new_facts_are_remembered_once_and_only_known_ones_forgotten(fake):
    fake["plan"] = JarvisPlan(heard="I go to the gym at 6", action="answer", say="Noted, Skanda.",
                              remember=["Gym at 6 am", "gym at 6 am", "Name: Skanda", ""],
                              forget=["works at onezerolabs", "Something I never said"])
    r = client.post("/jarvis", json={**NOTIFS, "text": "remember I go to the gym at 6 and forget where I work"})
    data = r.json()
    assert data["remember"] == ["Gym at 6 am"]
    assert data["forget"] == ["Works at OneZeroLabs"]


def test_messages_outside_a_chat_are_read_from_notifications(fake):
    fake["plan"] = JarvisPlan(heard="what did Rahul send", action="read", say="Rahul asked if the movie's at six, boss.")
    data = client.post("/jarvis", json={**NOTIFS, "text": "what did Rahul send"}).json()
    assert data["action"] == "answer" and data["say"] == "Rahul asked if the movie's at six, boss."


@pytest.mark.parametrize("said, meant, rest", [
    ("Hey Friday, what's the time?", True, "what's the time?"),
    ("Friday play Kesariya", True, "play Kesariya"),
    ("OK Friday. Set an alarm for 6.", True, "Set an alarm for 6."),
    ("So yeah. Hey Friday, call Amma.", True, "call Amma."),
    ("Um, Friday, open Instagram", True, "open Instagram"),
    ("See you on Friday then.", False, None),
    ("Thank God it's Friday!", False, None),
    ("It's Friday", False, None),
    ("the movie is on friday right", False, None),
    ("Hey Freddie, open Instagram", True, "Hey Freddie, open Instagram"),  # name misheard: the phone heard it
])
def test_the_wake_word_must_be_said_to_friday(said, meant, rest):
    ok, after = llm.addressed(said)
    assert ok is meant
    if meant:
        assert after == rest


def test_a_friday_in_passing_is_ignored_quietly(fake):
    fake["transcript"] = "yeah see you on Friday then"
    audio = base64.b64encode(b"RIFF....WAVE").decode()
    data = client.post("/jarvis", json={"audio": audio, "wake": True}).json()
    assert data["action"] == "ignore" and data["say"] == "" and "jarvis_input" not in fake


def test_a_command_in_one_breath_is_heard_without_the_name(fake):
    fake["transcript"] = "Hey Friday play Kesariya on Spotify"
    audio = base64.b64encode(b"RIFF....WAVE").decode()
    data = client.post("/jarvis", json={"audio": audio, "wake": True}).json()
    assert data["action"] == "device" and data["task"]["kind"] == "play" and data["task"]["text"] == "kesariya"
