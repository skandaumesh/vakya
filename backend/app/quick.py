"""Friday's instant path: common phone commands understood without a model call.

"set an alarm for 6", "open Instagram", "play Kesariya on Spotify", "turn on the flashlight",
"thanks"... are matched here and answered at once. Anything else (or anything ambiguous)
returns None and goes to the model.
"""

import re
from datetime import datetime

from .schemas import DeviceTask, JarvisPlan, JarvisTurn

_NUMBERS = {
    "a": 1, "an": 1, "one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7,
    "eight": 8, "nine": 9, "ten": 10, "eleven": 11, "twelve": 12, "fifteen": 15, "twenty": 20,
    "thirty": 30, "forty": 40, "forty five": 45, "fifty": 50, "sixty": 60, "ninety": 90,
}
_UNITS = {"second": 1, "minute": 60, "hour": 3600}
_NUMBER = r"(\d+|" + "|".join(sorted(map(re.escape, _NUMBERS), key=len, reverse=True)) + r")"
_TIME = r"(\d{1,2})(?:[:.](\d{2})|\s(\d{2}))?\s*(am|pm|a m|p m)?"


def _clean(text: str) -> str:
    t = re.sub(r"[^\w\s:'.]", " ", text.lower())
    t = re.sub(r"\s+", " ", t).strip(" .")
    t = re.sub(r"^(?:ok(?:ay)? |hey |please |can you |could you |friday |jarvis )+", "", t)
    return re.sub(r" (?:please|now|for me|friday|jarvis)$", "", t).strip()


def _number(word: str) -> int | None:
    return int(word) if word.isdigit() else _NUMBERS.get(word)


def _now(local_time: str | None) -> datetime | None:
    """The phone's time from 'Tue 14 Oct 2026, 9:40 pm'."""
    if not local_time:
        return None
    try:
        return datetime.strptime(local_time.strip(), "%a %d %b %Y, %I:%M %p")
    except ValueError:
        return None


def _alarm_time(h: int, m: int, ampm: str | None, now: datetime | None) -> tuple[int, int] | None:
    if not (0 <= h <= 23 and 0 <= m <= 59):
        return None
    if ampm:
        if h > 12:
            return None
        pm = ampm.replace(" ", "") == "pm"
        h = (h % 12) + (12 if pm else 0)
    elif h <= 12 and now is not None:
        # "6" means the next 6 o'clock coming: 6 am or 6 pm, whichever is sooner.
        options = [(h % 12) + 12 * k for k in (0, 1)]
        h = min(options, key=lambda x: ((x * 60 + m) - (now.hour * 60 + now.minute)) % (24 * 60))
    return h, m


def _spoken(h: int, m: int) -> str:
    suffix = "AM" if h < 12 else "PM"
    return f"{h % 12 or 12}:{m:02d} {suffix}" if m else f"{h % 12 or 12} {suffix}"


def _plan(heard: str, say: str, **task) -> JarvisPlan:
    return JarvisPlan(heard=heard, action="device", say=say, task=DeviceTask(**task))


def quick_plan(heard: str, local_time: str | None = None, history: list[JarvisTurn] | None = None) -> JarvisPlan | None:
    t = _clean(heard)
    if not t:
        return None
    now = _now(local_time)

    if re.fullmatch(r"(?:ok |okay )?(?:thanks|thank you|thats all|that's all|that is all|stop|bye|goodbye|never ?mind|nothing)", t):
        return JarvisPlan(heard=heard, action="end", say="Anytime, boss.")

    # "yes" / "send it" right after Friday asked "Shall I send it?"
    if re.fullmatch(r"(?:yes|yeah|yep|yup|haan|ha|ok|okay|sure|go ahead|do it|send|send it|yes send it|ok send it|send the message)", t):
        if history and "send it?" in history[-1].say.lower():
            return _plan(heard, "Sent, boss.", kind="send")
        return None

    if m := re.fullmatch(r"(?:set (?:an |the |my )?alarm|wake me(?: up)?)(?: (?:for|at))? " + _TIME
                         + r"(?: tomorrow| today| in the morning| tonight)?(?: (?:for|to) (.+))?", t):
        hm = _alarm_time(int(m[1]), int(m[2] or m[3] or 0), m[4], now)
        if hm:
            label = (m[5] or "").strip()
            return _plan(heard, f"Alarm set for {_spoken(*hm)}, boss.", kind="alarm", time=f"{hm[0]:02d}:{hm[1]:02d}", label=label)

    if m := re.fullmatch(r"remind me at " + _TIME + r" (?:to|about) (.+)", t):
        hm = _alarm_time(int(m[1]), int(m[2] or m[3] or 0), m[4], now)
        if hm:
            return _plan(heard, f"I'll remind you at {_spoken(*hm)}, boss.", kind="alarm",
                         time=f"{hm[0]:02d}:{hm[1]:02d}", label=m[5].strip())

    if m := re.fullmatch(r"(?:set (?:a |the )?timer (?:for )?|start (?:a )?timer (?:for )?|remind me in )"
                         + _NUMBER + r" (second|minute|hour)s?(?: (?:to|for|about) (.+))?", t):
        n = _number(m[1])
        if n:
            seconds = n * _UNITS[m[2]]
            unit = m[2] + ("s" if n != 1 else "")
            label = (m[3] or "").strip()
            say = f"I'll remind you in {n} {unit}, boss." if t.startswith("remind") else f"Timer set for {n} {unit}, boss."
            return _plan(heard, say, kind="timer", seconds=seconds, label=label)

    if m := re.fullmatch(r"(?:turn|switch) (on|off) (?:the )?(?:flash ?light|torch)|(?:turn |switch )?(?:the )?(?:flash ?light|torch) (on|off)", t):
        on = (m[1] or m[2]) == "on"
        return _plan(heard, f"Flashlight {'on' if on else 'off'}, boss.", kind="flashlight", on=on)

    if re.fullmatch(r"(?:how much |what s my |whats my |what is my |check (?:the |my )?)?(?:battery|charge)(?: level| left| percentage| status)?(?: do i have| is left| left)?", t):
        return _plan(heard, "Checking, boss.", kind="battery")

    if m := re.fullmatch(r"(?:turn (?:the )?volume (up|down)|volume (up|down)|(increase|raise|decrease|lower|reduce) (?:the )?volume|(mute)(?: the phone| it)?)", t):
        word = next(g for g in m.groups() if g)
        if word == "mute":
            return _plan(heard, "Muted, boss.", kind="volume", target="mute")
        up = word in ("up", "increase", "raise")
        return _plan(heard, f"Volume {'up' if up else 'down'}, boss.", kind="volume", on=up)

    # The music already playing (works with the phone locked).
    media = {
        r"(?:pause|stop)(?: the| my)? (?:music|song|songs|track|spotify|playback|video)|pause(?: it)?": "pause",
        r"(?:resume|continue|unpause)(?: the| my)?(?: music| song| playback| playing| spotify)?|play (?:the )?music again": "play",
        r"(?:next|skip(?: the| this)?) (?:song|track)|play (?:the )?next (?:song|track)": "next",
        r"(?:previous|last) (?:song|track)|play (?:the )?(?:previous|last) (?:song|track)|go back a (?:song|track)": "previous",
    }
    for pattern, target in media.items():
        if re.fullmatch(pattern, t):
            return _plan(heard, "Done, boss.", kind="media", target=target)

    if re.fullmatch(r"stop (?:scrolling|the scroll(?:ing)?|auto ?scroll(?:ing)?)", t):
        return _plan(heard, "Stopped, boss.", kind="scroll", target="stop")
    if m := re.fullmatch(r"(?:keep scrolling|auto ?scroll|scroll automatically|keep going)(?: every " + _NUMBER + r" seconds?)?", t):
        every = _number(m[1]) if m[1] else 8
        return _plan(heard, f"Scrolling every {every} seconds, boss. Say stop when you're done.", kind="scroll", on=True, seconds=every)
    if re.fullmatch(r"(?:scroll(?: down)?|next(?: reel| video| post| one| story)?|swipe up|skip(?: it| this)?)", t):
        return _plan(heard, "Done, boss.", kind="scroll", on=True)
    if re.fullmatch(r"(?:scroll up|previous(?: reel| video| post| one)?|go back up|swipe down)", t):
        return _plan(heard, "Done, boss.", kind="scroll", on=False)

    phone = {
        r"(?:take (?:a )?)?screenshot": "screenshot",
        r"(?:go )?(?:to (?:the )?)?home(?: screen)?|go home": "home",
        r"(?:go )?back": "back",
        r"(?:show |open )?(?:my )?notifications": "notifications",
        r"(?:show |open )?(?:my )?recent apps|recents": "recents",
        r"lock (?:the |my )?(?:phone|screen)": "lock",
    }
    for pattern, target in phone.items():
        if re.fullmatch(pattern, t):
            return _plan(heard, "Done, boss.", kind="phone", target=target)

    if m := re.fullmatch(r"open (?:my )?(?:chat with ([a-z][a-z ]{0,30}?)|([a-z][a-z ]{0,30}?)'?s chat)(?: (?:on|in) (whatsapp|messages))?", t):
        who = (m[1] or m[2]).strip()
        return _plan(heard, f"Here's your chat with {who.title()}, boss.", kind="message", contact=who,
                     app="sms" if m[3] == "messages" else "whatsapp")

    if m := re.fullmatch(r"(?:open|launch|start) (?:the )?(.+?)(?: app)?", t):
        name = m[1].strip()
        if name and len(name.split()) <= 3 and name not in {"chat", "the chat", "message", "messages"}:
            return _plan(heard, f"Opening {name.title()}, boss.", kind="open_app", app=name)

    if m := re.fullmatch(r"play (.+?)(?: on (spotify|youtube music|youtube))?", t):
        app = m[2] or ""
        where = f" on {'YouTube Music' if app == 'youtube music' else app.title()}" if app else ""
        # "kesariya song" -> "kesariya": the app's search knows it's a song.
        what = " ".join(re.sub(r"\b(?:the )?(?:song|songs|track)\b", " ", m[1]).split()) or m[1].strip()
        return _plan(heard, f"Playing {what}{where}, boss.", kind="play", text=what, app=app)

    if m := re.fullmatch(r"(?:navigate|directions|take me|drive) to (.+)", t):
        return _plan(heard, f"Taking you to {m[1].strip().title()}, boss.", kind="navigate", text=m[1].strip())

    if m := re.fullmatch(r"call ([a-z][a-z ]{0,30}|\+?[\d ]{6,15})", t):
        return _plan(heard, f"Calling {m[1].strip().title()}, boss.", kind="call", contact=m[1].strip())

    return None
