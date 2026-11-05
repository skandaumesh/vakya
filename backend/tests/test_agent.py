"""Friday 0.9: several steps per request, asking first, looking at the screen, memory, schedule, briefings."""

import pytest
from fastapi.testclient import TestClient

from app import llm, main
from app.prompts import briefing_kind
from app.quick import quick_plan
from app.recall import recall
from app.schemas import DeviceTask, JarvisPlan

client = TestClient(main.app)
NIGHT = "Wed 4 Nov 2026, 9:10 pm"
MORNING = "Thu 5 Nov 2026, 7:05 am"


@pytest.fixture
def fake(monkeypatch):
    seen = {"inputs": [], "looks": []}

    async def _parse(system, user_content, output_format, max_tokens, **kw):
        seen["inputs"].append(user_content)
        return seen["plan"], 5

    async def look(image_b64, heard):
        seen["looks"].append((image_b64, heard))
        return seen.get("view", "Settings app, Battery page. A switch says Power saving, off.")

    monkeypatch.setattr(llm, "_parse", _parse)
    monkeypatch.setattr(llm, "look_at_screen", look)
    return seen


def _post(**body):
    r = client.post("/jarvis", json={"local_time": NIGHT, **body})
    assert r.status_code == 200, r.text
    return r.json()


def test_several_things_in_one_request_become_steps_in_order(fake):
    fake["plan"] = JarvisPlan(
        heard="message Amma I'll be late and remind me at 8", action="device",
        steps=[DeviceTask(kind="message", contact="Amma", text="I'll be late", app="whatsapp"),
               DeviceTask(kind="alarm", time="20:00", label="remind me"), DeviceTask(kind="none")],
        say="Your message to Amma is ready and I'll remind you at eight. Shall I send it?",
    )
    data = _post(text="message Amma I'll be late and remind me at 8")
    assert data["action"] == "device"
    assert [s["kind"] for s in data["steps"]] == ["message", "alarm"]  # "none" steps are dropped
    assert data["task"]["kind"] == "message"  # phones before 0.9 still get the first step
    assert data["confirm"] is False


def test_at_most_six_steps(fake):
    fake["plan"] = JarvisPlan(heard="x", action="device", say="ok", steps=[DeviceTask(kind="battery")] * 9)
    assert len(_post(text="do everything")["steps"]) == 6


def test_a_routine_asks_first(fake):
    fake["plan"] = JarvisPlan(
        heard="I'm going to sleep", action="device", confirm=True,
        steps=[DeviceTask(kind="toggle", target="dnd", on=True), DeviceTask(kind="brightness", value=5),
               DeviceTask(kind="alarm", time="06:30")],
        say="Do not disturb on, brightness down and an alarm for six thirty. Shall I?",
    )
    data = _post(text="I'm going to sleep", routines=[{"name": "going to sleep", "steps": "dnd on, dim, alarm 6:30"}])
    assert data["confirm"] is True and len(data["steps"]) == 3
    assert '"going to sleep": "dnd on, dim, alarm 6:30"' in fake["inputs"][-1]


def test_yes_runs_the_pending_steps_without_the_model(fake):
    data = _post(text="yes go ahead", pending="Do not disturb on, alarm 6:30")
    assert data["action"] == "confirm" and not fake["inputs"]
    assert _post(text="no", pending="Do not disturb on")["action"] == "cancel"


def test_confirm_without_anything_pending_is_just_an_answer(fake):
    fake["plan"] = JarvisPlan(heard="do it", action="confirm", say="On it, boss.")
    assert _post(text="do it then")["action"] == "answer"


def test_a_question_about_the_screen_asks_to_look_first(fake):
    data = _post(text="what does this error mean")
    assert data["action"] == "look" and data["steps"] == [] and not fake["inputs"]


def test_after_looking_the_screen_goes_to_the_model(fake):
    fake["plan"] = JarvisPlan(heard="how do I turn this on", action="device",
                              steps=[DeviceTask(kind="tap", text="Power saving")], say="Tapping Power saving, boss.")
    data = _post(text="how do I turn this on", looked=True, screen_image="aGVsbG8=",
                 screen_text=["Battery", "Power saving", "Off"])
    assert data["steps"][0] == {**data["steps"][0], "kind": "tap", "text": "Power saving"}
    assert fake["looks"] == [("aGVsbG8=", "how do I turn this on")]
    prompt = fake["inputs"][-1]
    assert "<screen_view>" in prompt and "Power saving, off" in prompt
    assert '"Power saving"' in prompt.split("<screen_text>")[1]


def test_friday_never_asks_to_look_twice(fake):
    fake["plan"] = JarvisPlan(heard="what's this", action="look", say="Let me look, boss.")
    data = _post(text="what's this", looked=True, screen_text=[])
    assert data["action"] == "answer" and "can't make that out" in data["say"]


def test_failed_steps_are_shown_to_the_model(fake):
    fake["plan"] = JarvisPlan(heard="tap accept", action="answer", say="I couldn't find Accept, boss.")
    _post(text="tap accept", looked=True, failed=["tap 'accept': not on screen"], screen_text=["Agree", "Cancel"])
    assert "<failed>" in fake["inputs"][-1] and "not on screen" in fake["inputs"][-1]


def test_only_matching_memories_reach_the_model(fake):
    fake["plan"] = JarvisPlan(heard="what does Rahul owe me", action="answer", say="Five hundred rupees, boss.")
    memories = ["Rahul (WhatsApp): owes me ₹500 for the movie", "Priya (WhatsApp): sending the deck on Monday",
                "Tue 3 Nov 9:10 pm · me: what's the weather · Friday: pulled it up"]
    _post(text="what does Rahul owe me", memories=memories)
    prompt = fake["inputs"][-1]
    assert "owes me ₹500" in prompt and "deck" not in prompt and "weather" not in prompt


def test_calendar_and_tasks_only_for_questions_about_plans(fake):
    fake["plan"] = JarvisPlan(heard="x", action="answer", say="ok")
    body = dict(calendar=["Thu 5 Nov 3:00 pm · Design review"], tasks=["Pay rent · due Fri 6 Nov"])
    _post(text="tell me a joke", **body)
    assert "Design review" not in fake["inputs"][-1]
    _post(text="what do I have tomorrow", **body)
    assert "Design review" in fake["inputs"][-1] and "Pay rent" in fake["inputs"][-1]


def test_good_morning_is_a_briefing_with_everything_in_it(fake):
    fake["plan"] = JarvisPlan(heard="good morning", action="answer", say="Morning, boss. Design review at three.")
    _post(text="good morning", local_time=MORNING, notifications_allowed=True,
          notifications=[{"app": "WhatsApp", "who": "Amma", "text": "call me", "when": "7:00 am"}],
          calendar=["Thu 5 Nov 3:00 pm · Design review"], device="battery 14%, not charging")
    prompt = fake["inputs"][-1]
    for part in ("<briefing>morning</briefing>", "Design review", '"call me"', "battery 14%"):
        assert part in prompt


@pytest.mark.parametrize("said, time, kind", [
    ("good morning", MORNING, "morning"), ("brief me", NIGHT, "evening"), ("prepare my day", MORNING, "morning"),
    ("how does my day look", "Thu 5 Nov 2026, 1:00 pm", "day"), ("evening summary", MORNING, "evening"),
    ("what's the weather", MORNING, None), ("good morning, set an alarm", MORNING, None),
])
def test_briefing_kind(said, time, kind):
    assert briefing_kind(said, time) == kind


@pytest.mark.parametrize("said, kind, fields", [
    ("turn on wifi", "toggle", {"target": "wifi", "on": True}),
    ("bluetooth off", "toggle", {"target": "bluetooth", "on": False}),
    ("enable do not disturb", "toggle", {"target": "dnd", "on": True}),
    ("switch off battery saver", "toggle", {"target": "battery_saver", "on": False}),
    ("turn on airplane mode", "toggle", {"target": "airplane", "on": True}),
    ("set brightness to 40 percent", "brightness", {"value": 40}),
    ("dim the screen", "brightness", {"on": False, "value": -1}),
    ("full brightness", "brightness", {"value": 100}),
    ("set volume to 30", "volume", {"value": 30}),
    ("unmute", "volume", {"target": "unmute"}),
    ("switch to the last app", "phone", {"target": "last_app"}),
    ("tap install", "tap", {"text": "install"}),
])
def test_quick_new_commands(said, kind, fields):
    plan = quick_plan(said, NIGHT)
    assert plan is not None and plan.action == "device" and plan.task.kind == kind, plan
    for k, v in fields.items():
        assert getattr(plan.task, k) == v, (k, plan.task)


def test_yes_without_anything_pending_still_goes_to_the_model():
    assert quick_plan("yes", NIGHT, [], pending=None) is None


def test_recall_finds_names_and_ignores_noise():
    memories = ["Amma's birthday: 3 March", "Rahul: best friend from college", "Gym at 6 am on weekdays",
                "Rahul (WhatsApp): owes me ₹500"]
    assert recall("when is amma's bday", memories)[0] == "Amma's birthday: 3 March"
    assert set(recall("what about rahul", memories)) == {"Rahul: best friend from college", "Rahul (WhatsApp): owes me ₹500"}
    assert recall("what's the time", memories) == []
    assert recall("anything", []) == []


def test_only_the_instructions_a_request_needs_are_sent():
    from app.prompts import JARVIS_CORE, jarvis_system
    plain = jarvis_system("<screen>time: x</screen>\n<heard>\"turn on wifi\"</heard>")
    assert plain.startswith(JARVIS_CORE) and "Their screen:" not in plain and "Briefings:" not in plain
    looked = jarvis_system("<screen_view>\"Settings\"</screen_view>\n<failed>\n- x\n</failed>")
    assert "Their screen:" in looked and "<failed>, when present" in looked
    assert "Briefings:" in jarvis_system("<briefing>morning</briefing>")


def test_rubbish_in_a_step_is_cleaned_out():
    step = llm.clean_step(DeviceTask(kind="message", contact="Amma", text="I'll be late",
                                     time="8 pm, 4:01 pm, 7:40 pm, 20:52 AM", value=900))
    assert step.time == "" and step.value == -1 and step.text == "I'll be late"
    assert llm.clean_step(DeviceTask(kind="alarm", time="20:00")).time == "20:00"
    assert llm.clean_step(DeviceTask(kind="alarm", time="8 pm")).time == ""
    assert llm.clean_step(DeviceTask(kind="calendar_add", time="2026-11-05 15:00")).time == "2026-11-05 15:00"


def test_a_plan_labelled_answer_with_steps_is_carried_out(fake):
    fake["plan"] = JarvisPlan(heard="message Amma", action="answer", say="Message ready, boss. Shall I send it?",
                              steps=[DeviceTask(kind="message", contact="Amma", text="I'll be late")])
    data = _post(text="message Amma I'll be late")
    assert data["action"] == "device" and data["steps"][0]["kind"] == "message"
