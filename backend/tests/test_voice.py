"""Friday's natural voice: replies come back as audio, and a voice problem never fails a reply."""

import base64
import io
import wave

import pytest
from fastapi.testclient import TestClient

from app import llm, main, voice
from app.errors import LLMError, RateLimited
from app.schemas import JarvisPlan

client = TestClient(main.app)
NIGHT = "Wed 4 Nov 2026, 9:10 pm"


def make_wav(frames: bytes, rate: int = 24000) -> bytes:
    out = io.BytesIO()
    with wave.open(out, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(frames)
    return out.getvalue()


class FakeVoice:
    def __init__(self, fail: Exception | None = None):
        self.calls: list[tuple[str, str]] = []
        self.fail = fail

    async def speak(self, text, v):
        self.calls.append((text, v))
        if self.fail:
            raise self.fail
        return make_wav(b"\x01\x00" * len(text))


@pytest.fixture(autouse=True)
def fresh_voice():
    voice.reset()
    yield
    voice.reset()


@pytest.fixture
def fake(monkeypatch):
    seen = {"voice": FakeVoice()}

    async def _parse(system, user_content, output_format, max_tokens, **kw):
        return seen["plan"], 5

    monkeypatch.setattr(llm, "_parse", _parse)
    monkeypatch.setattr(voice, "active_provider", lambda: seen["voice"])
    return seen


def frames_of(wav_bytes: bytes) -> bytes:
    with wave.open(io.BytesIO(wav_bytes)) as w:
        return w.readframes(w.getnframes())


def test_a_short_reply_is_one_piece():
    assert voice.pieces("Done, boss.", 200) == ["Done, boss."]


def test_sentences_are_joined_up_to_the_limit_and_long_ones_split():
    text = "First one here. Second one here. " + "word " * 60
    parts = voice.pieces(text, 60)
    assert parts[0] == "First one here. Second one here."
    assert all(len(p) <= 60 for p in parts)
    assert " ".join(parts).split() == voice.clean(text).split()


def test_emoji_and_markup_are_not_read_out():
    assert voice.clean("Done ✅ **boss** & co") == "Done boss and co"


def test_kannada_script_is_left_to_the_phone():
    assert not voice.speakable("ನಮಸ್ಕಾರ ಬಾಸ್")
    assert voice.speakable("Namaskara, boss. Café's open.")


def test_pieces_join_into_one_wav():
    joined = voice.join([make_wav(b"\x01\x00" * 10), make_wav(b"\x02\x00" * 5)])
    assert frames_of(joined) == b"\x01\x00" * 10 + b"\x02\x00" * 5


def test_a_streamed_wav_without_sizes_still_joins():
    streamed = bytearray(make_wav(b"\x03\x00" * 4))
    streamed[4:8] = b"\xff\xff\xff\xff"
    at = bytes(streamed).index(b"data")
    streamed[at + 4:at + 8] = b"\xff\xff\xff\xff"
    assert frames_of(voice.join([bytes(streamed)])) == b"\x03\x00" * 4


def test_the_reply_comes_back_in_her_voice(fake):
    fake["plan"] = JarvisPlan(heard="tell me a joke", action="answer", say="It's ten past nine, boss.")
    r = client.post("/jarvis", json={"text": "tell me a joke", "local_time": NIGHT, "voice": "diana"})
    assert r.status_code == 200, r.text
    data = r.json()
    assert fake["voice"].calls == [("It's ten past nine, boss.", "diana")]
    assert frames_of(base64.b64decode(data["audio"])) == b"\x01\x00" * len("It's ten past nine, boss.")


def test_no_voice_asked_no_audio(fake):
    fake["plan"] = JarvisPlan(heard="tell me a joke", action="answer", say="It's ten past nine, boss.")
    r = client.post("/jarvis", json={"text": "tell me a joke", "local_time": NIGHT})
    assert r.status_code == 200 and r.json()["audio"] is None
    assert fake["voice"].calls == []


def test_a_voice_problem_still_answers_and_pauses_the_voice(fake):
    fake["voice"] = FakeVoice(fail=LLMError("groq voice error 400 model_terms_required"))
    fake["plan"] = JarvisPlan(heard="hi", action="answer", say="Hello, boss.")
    for _ in range(2):
        r = client.post("/jarvis", json={"text": "hi", "local_time": NIGHT, "voice": "diana"})
        assert r.status_code == 200 and r.json()["audio"] is None and r.json()["say"] == "Hello, boss."
    # Not tried again on the next reply: it would only slow her down.
    assert len(fake["voice"].calls) == 1


def test_a_rate_limit_pauses_the_voice(fake):
    fake["voice"] = FakeVoice(fail=RateLimited("limit", retry_after=30))
    fake["plan"] = JarvisPlan(heard="hi", action="answer", say="Hello, boss.")
    client.post("/jarvis", json={"text": "hi", "local_time": NIGHT, "voice": "diana"})
    client.post("/jarvis", json={"text": "hi", "local_time": NIGHT, "voice": "diana"})
    assert len(fake["voice"].calls) == 1


def test_an_unknown_voice_is_ignored(fake):
    fake["plan"] = JarvisPlan(heard="hi", action="answer", say="Hello, boss.")
    r = client.post("/jarvis", json={"text": "hi", "local_time": NIGHT, "voice": "siri"})
    assert r.status_code == 200 and r.json()["audio"] is None


def test_speak_says_a_line_and_keeps_short_ones(fake):
    for _ in range(2):
        r = client.post("/speak", json={"text": "How can I help you, boss?", "voice": "hannah"})
        assert r.status_code == 200, r.text
        assert frames_of(base64.b64decode(r.json()["audio"]))
    assert fake["voice"].calls == [("How can I help you, boss?", "hannah")]


def test_speak_without_the_voice_is_503(fake):
    fake["voice"] = FakeVoice(fail=LLMError("no"))
    r = client.post("/speak", json={"text": "Reminder, boss: call Amma.", "voice": "diana"})
    assert r.status_code == 503


def test_a_long_reply_is_made_in_pieces(fake, monkeypatch):
    monkeypatch.setattr(voice.config, "TTS_MAX_CHARS", 40)
    say = "Good morning, boss. You have three meetings today. The first is at ten with the design team."
    fake["plan"] = JarvisPlan(heard="good morning", action="answer", say=say)
    r = client.post("/jarvis", json={"text": "good morning", "local_time": NIGHT, "voice": "diana"})
    assert r.status_code == 200
    texts = [t for t, _ in fake["voice"].calls]
    assert len(texts) > 1 and all(len(t) <= 40 for t in texts)
    assert frames_of(base64.b64decode(r.json()["audio"])) == b"\x01\x00" * sum(len(t) for t in texts)
