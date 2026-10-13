"""Friday's instant path: common commands without a model call."""

import pytest

from app.quick import quick_plan
from app.schemas import JarvisTurn

NIGHT = "Tue 14 Oct 2026, 9:40 pm"
MORNING = "Wed 15 Oct 2026, 5:10 am"


@pytest.mark.parametrize("said, now, kind, fields", [
    ("Set an alarm for 6.", NIGHT, "alarm", {"time": "06:00"}),          # next 6 after 9:40 pm is 6 am
    ("set alarm for 6", MORNING, "alarm", {"time": "06:00"}),
    ("set an alarm for 10", NIGHT, "alarm", {"time": "22:00"}),          # 10 pm comes first
    ("wake me up at 6:30 am for the gym", NIGHT, "alarm", {"time": "06:30", "label": "the gym"}),
    ("set an alarm for 7 pm", MORNING, "alarm", {"time": "19:00"}),
    ("remind me at 7 to take medicine", NIGHT, "alarm", {"time": "07:00", "label": "take medicine"}),
    ("remind me in 20 minutes to call Amma", NIGHT, "timer", {"seconds": 1200, "label": "call amma"}),
    ("set a timer for ten minutes", NIGHT, "timer", {"seconds": 600}),
    ("set a timer for an hour", NIGHT, "timer", {"seconds": 3600}),
    ("turn on the flashlight", NIGHT, "flashlight", {"on": True}),
    ("torch off", NIGHT, "flashlight", {"on": False}),
    ("how much battery do I have?", NIGHT, "battery", {}),
    ("volume up", NIGHT, "volume", {"on": True}),
    ("lower the volume", NIGHT, "volume", {"on": False}),
    ("mute", NIGHT, "volume", {"target": "mute"}),
    ("take a screenshot", NIGHT, "phone", {"target": "screenshot"}),
    ("go home", NIGHT, "phone", {"target": "home"}),
    ("lock the phone", NIGHT, "phone", {"target": "lock"}),
    ("Open Instagram.", NIGHT, "open_app", {"app": "instagram"}),
    ("open the camera app", NIGHT, "open_app", {"app": "camera"}),
    ("Play Kesariya on Spotify", NIGHT, "play", {"text": "kesariya", "app": "spotify"}),
    ("play lo-fi beats", NIGHT, "play", {"text": "lo fi beats", "app": ""}),
    ("navigate to MG Road", NIGHT, "navigate", {"text": "mg road"}),
    ("call Amma", NIGHT, "call", {"contact": "amma"}),
])
def test_common_commands_need_no_model(said, now, kind, fields):
    plan = quick_plan(said, now)
    assert plan is not None and plan.action == "device" and plan.task.kind == kind, plan
    for k, v in fields.items():
        assert getattr(plan.task, k) == v, (k, plan.task)
    assert plan.say.endswith("boss.")


@pytest.mark.parametrize("said", [
    "message Rahul on WhatsApp I'm on my way",   # needs a well-written message
    "tell him I'll be ten minutes late",
    "what's 15 percent of 2400",
    "make it seven instead",                       # needs the conversation
    "open the chat",
    "play",
    "set an alarm",                                # no time: ask
    "naale 6 ge alarm idu",
])
def test_everything_else_goes_to_the_model(said):
    assert quick_plan(said, NIGHT) is None


def test_thanks_ends_the_conversation():
    plan = quick_plan("Thanks, Friday!", NIGHT)
    assert plan.action == "end" and plan.say == "Anytime, boss."


def test_yes_sends_only_right_after_friday_asked():
    asked = [JarvisTurn(heard="message rahul I'm on my way", say="Your message to Rahul is ready, boss. Shall I send it?")]
    assert quick_plan("yes, send it", NIGHT, asked).task.kind == "send"
    assert quick_plan("yes", NIGHT, []) is None
    other = [JarvisTurn(heard="what time is it", say="It's nine forty, boss.")]
    assert quick_plan("yes", NIGHT, other) is None


@pytest.mark.parametrize("said, who, app", [
    ("open Rahul's chat", "rahul", "whatsapp"),
    ("open chat with Amma on WhatsApp", "amma", "whatsapp"),
    ("open Priya's chat in messages", "priya", "sms"),
])
def test_open_a_persons_chat(said, who, app):
    plan = quick_plan(said, NIGHT)
    assert plan.task.kind == "message" and plan.task.contact == who and plan.task.app == app and plan.task.text == ""
