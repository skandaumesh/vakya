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
    assert "boss" in JARVIS_SYSTEM and "never say you sent anything" in JARVIS_SYSTEM


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
    fake["plan"] = JarvisPlan(heard="open instagram", action="answer", say="Opening.")
    assert client.post("/jarvis", json={"text": "open instagram"}).json()["say"] == "Opening."
    assert len(calls) == 2
