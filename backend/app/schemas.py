from typing import Literal

from pydantic import BaseModel, Field, model_validator

Intent = Literal[
    "QUESTION",
    "INVITATION",
    "REQUEST",
    "APOLOGY",
    "THANKS",
    "FOLLOW_UP",
    "NEGOTIATION",
    "GREETING",
    "URGENT",
    "CASUAL",
]

Relationship = Literal[
    "friend", "client", "professor", "partner", "family", "colleague", "unknown"
]

ReplyStyle = Literal["mine", "professional", "short", "friendly", "genz"]

# Reply language picked by the user for a chat; "auto" follows the chat.
ReplyLanguage = Literal["auto", "english", "kanglish", "kannada", "hinglish"]

MediaKind = Literal["photo", "sticker", "gif", "video", "voice", "document", "media"]


# ---------- Style card (built once from the user's own messages) ----------


class StyleTraits(BaseModel):
    """Qualitative description of how the user texts. Written by the model."""

    summary: str = Field(description="2-3 sentences: overall vibe, length, tone.")
    language_mix: str = Field(
        description="Languages and script, e.g. 'English with Kannada words in Latin script (~40%)'."
    )
    fillers: list[str] = Field(description="Slang/address words they use: bro, da, macha, sir.")
    signature_phrases: list[str] = Field(description="Short phrases they repeat, verbatim.")
    emoji_habits: str = Field(description="Which emojis, how often, where in the message.")
    punctuation_and_casing: str = Field(description="Casing, full stops, '..', '!!', etc.")
    avoid: list[str] = Field(description="Things this person never does in texts.")


class StyleStats(BaseModel):
    """Measured directly from the messages, no model involved."""

    message_count: int
    avg_words: float
    median_words: float
    emoji_rate: float = Field(description="Fraction of messages containing an emoji.")
    top_emojis: list[str]
    lowercase_start_rate: float
    ends_with_period_rate: float


class StyleCard(BaseModel):
    traits: StyleTraits
    stats: StyleStats


class StyleCardRequest(BaseModel):
    my_messages: list[str] = Field(min_length=5, max_length=2000)


# ---------- Reply suggestions ----------


class ChatMessage(BaseModel):
    sender: Literal["me", "them"]
    text: str = Field(default="", max_length=4000, description="Message text, or a photo's caption.")
    name: str | None = Field(default=None, description="Sender name, for group chats.")
    media: MediaKind | None = Field(default=None, description="Set when this item is a photo, sticker, ...")
    image: str | None = Field(
        default=None,
        max_length=700_000,
        description="Small base64 JPEG of the photo or sticker so the AI can see what it shows.",
    )


class PastReply(BaseModel):
    them: str = Field(max_length=600)
    me: str = Field(max_length=400)


class SuggestRequest(BaseModel):
    app: str = "whatsapp"
    chat_title: str | None = None
    is_group: bool = False
    messages: list[ChatMessage] = Field(min_length=1, max_length=40)
    relationship: Relationship | None = Field(
        default=None, description="Set by the user; None lets the model guess."
    )
    style: ReplyStyle = "mine"
    style_card: StyleCard | None = None
    examples: list[str] = Field(
        default_factory=list,
        max_length=20,
        description="The user's own past messages to this contact.",
    )
    memory: list[str] = Field(default_factory=list, max_length=30)
    draft: str = Field(default="", max_length=2000, description="Text already typed.")
    similar: list[PastReply] = Field(
        default_factory=list,
        max_length=8,
        description="From the phone's reply bank: how I replied to messages like this before.",
    )
    language: ReplyLanguage = Field(default="auto", description="Picked by the user for this chat; auto follows the chat.")


class Suggestion(BaseModel):
    label: str = Field(description="1-2 word action, e.g. Accept, Delay, Clarify.")
    text: str = Field(description="The message exactly as it would be sent.")


class SuggestOutput(BaseModel):
    """Structured output the model must return."""

    # First, so the model works out what they mean before writing replies
    # (code-mixed messages are easy to misread).
    meaning: str = Field(
        default="",
        description="What their latest message says and wants from me, in plain English, one short sentence.",
    )
    intent: Intent
    relationship_guess: Relationship
    language: str = Field(description="Language and script of the chat, e.g. 'Hindi-English (Latin script)'.")
    suggestions: list[Suggestion] = Field(description="Exactly 3, each a different action.")
    memory_add: list[str] = Field(description="New facts worth remembering about this contact.")
    memory_resolve: list[str] = Field(description="Existing memory items that are now done or obsolete, copied exactly.")


class SuggestResponse(SuggestOutput):
    latency_ms: int


# ---------- Write it for me: one sentence in, the message in every style out ----------

ComposeStyle = Literal["mine", "professional", "short", "genz"]
COMPOSE_STYLES: tuple[str, ...] = ("mine", "professional", "short", "genz")


class ComposeRequest(BaseModel):
    app: str = "whatsapp"
    chat_title: str | None = None
    is_group: bool = False
    messages: list[ChatMessage] = Field(
        default_factory=list, max_length=40, description="The chat on screen, for context and language."
    )
    relationship: Relationship | None = None
    style_card: StyleCard | None = None
    examples: list[str] = Field(default_factory=list, max_length=20)
    memory: list[str] = Field(default_factory=list, max_length=30)
    intent: str = Field(
        min_length=1,
        max_length=1000,
        description="What I want to say, in my own rough words: 'ask him if he's coming tomorrow'.",
    )
    language: ReplyLanguage = Field(default="auto", description="Picked by the user for this chat; auto follows the chat.")


class ComposeVariant(BaseModel):
    style: ComposeStyle
    text: str = Field(description="The message exactly as it would be sent.")


class ComposeIdea(BaseModel):
    label: str = Field(description="1-2 words naming its flavour: Cheesy, Smooth, Funny, Sweet, Savage...")
    text: str = Field(description="The message exactly as it would be sent.")


class ComposeOutput(BaseModel):
    # First, so the model decides what was typed before writing anything.
    kind: Literal["message", "ideas"] = Field(
        default="message",
        description="message: I said what I want to say. ideas: I asked for something to send (a pickup line, a joke...).",
    )
    meaning: str = Field(default="", description="What I want to tell them, or what I asked for, in plain English, one short sentence.")
    language: str = Field(description="Language and script used, e.g. 'Kannada-English (Latin script)'.")
    variants: list[ComposeVariant] = Field(
        default_factory=list, description="kind=message: exactly 4, one per style: mine, professional, short, genz. Else empty."
    )
    ideas: list[ComposeIdea] = Field(
        default_factory=list, description="kind=ideas: exactly 4 different ideas, each with its own label. Else empty."
    )


class ComposeResponse(ComposeOutput):
    latency_ms: int


# ---------- Friday ("Hey Friday" + a spoken command; Jarvis in the code) ----------

# look: Friday needs to see the screen first (the phone takes a screenshot and asks again).
# confirm / cancel: the user said yes / no to steps Friday asked about ("Shall I?").
JarvisAction = Literal["write", "suggest", "read", "answer", "device", "end", "look", "confirm", "cancel"]
# "ignore": the wake word was only a word in a sentence ("see you on Friday"); stay quiet.
JarvisReply = Literal["write", "suggest", "read", "answer", "device", "end", "look", "confirm", "cancel", "ignore"]

DeviceTaskKind = Literal[
    "none", "alarm", "timer", "open_app", "call", "message", "play", "navigate", "search",
    "flashlight", "battery", "phone", "settings", "volume", "note_add", "notes_read", "notes_clear", "send",
    "scroll", "media",
    # 0.9: switches, the screen, tasks, calendar, files, routines.
    "toggle", "brightness", "tap", "type", "task_add", "task_done", "calendar_add", "files", "routine_save",
]

# What a toggle step can switch. The phone flips it with Android's own API where there is one,
# else the Quick Settings tile, and checks it really changed.
TOGGLE_TARGETS = (
    "wifi", "bluetooth", "mobile_data", "dnd", "battery_saver", "rotation", "airplane", "hotspot",
    "location", "dark_mode", "nfc",
)


class DeviceTask(BaseModel):
    """Something to do on the phone itself; only the fields the kind needs are filled."""

    kind: DeviceTaskKind = "none"
    time: str = Field(default="", description="alarm: 24-hour HH:MM. task_add (optional) and calendar_add: 'YYYY-MM-DD HH:MM'.")
    seconds: int = Field(default=0, description="timer: length in seconds. calendar_add: how long (default 3600).")
    label: str = Field(default="", description="alarm/timer: what it's for, e.g. 'call Amma'. routine_save: the routine's name.")
    app: str = Field(default="", description="open_app: the app's name; message: whatsapp, telegram or sms; play: youtube, spotify or empty.")
    contact: str = Field(default="", description="call/message: the name or number as I said it.")
    text: str = Field(
        default="",
        description="message text, song/video to play, place, search query, note, task, event title, "
        "tap: the button's words as on screen, type: what to type, routine_save: what the routine does.",
    )
    on: bool = Field(default=True, description="flashlight/toggle on or off; volume/brightness up (true) or down (false).")
    target: str = Field(
        default="",
        description="phone: home, back, recents, last_app, notifications, quick_settings, lock, screenshot. "
        "settings: wifi, bluetooth, internet, volume, display, battery, sound, apps, location, storage. "
        "toggle: wifi, bluetooth, mobile_data, dnd, battery_saver, rotation, airplane, hotspot, location, dark_mode, nfc. "
        "volume: mute, unmute or empty. media: play, pause, next or previous. files: downloads, photos, documents or music.",
    )
    value: int = Field(default=-1, description="brightness or volume: the level to set, 0-100. Else -1.")


class JarvisTurn(BaseModel):
    """One earlier exchange in this conversation."""

    heard: str = Field(max_length=500)
    say: str = Field(max_length=500)


class PhoneNotification(BaseModel):
    """A notification the phone showed. Kept on the phone; never stored here."""

    app: str = Field(max_length=40)
    who: str = Field(default="", max_length=100, description="Sender or title, e.g. 'Rahul' or 'College gang: Amit'.")
    text: str = Field(default="", max_length=400)
    when: str = Field(default="", max_length=30, description="e.g. '5 min ago', '2:14 pm', 'yesterday 9:30 pm'.")


class Routine(BaseModel):
    """A routine I set up: say its name ("I'm going to sleep") and Friday does what it says."""

    name: str = Field(max_length=60)
    steps: str = Field(max_length=500, description="What to do, in plain words: 'do not disturb on, brightness low, alarm 6:30'.")


class JarvisRequest(BaseModel):
    """A voice command (said after "Hey Friday") and what's on the phone's screen."""

    audio: str | None = Field(
        default=None, max_length=1_000_000, description="Base64 WAV of the command, 16 kHz mono, up to ~10 s."
    )
    text: str | None = Field(default=None, max_length=1000, description="The command as text instead of audio.")
    app: str = "whatsapp"
    in_chat: bool = Field(default=False, description="A chat with a message box is open.")
    chat_title: str | None = None
    is_group: bool = False
    messages: list[ChatMessage] = Field(default_factory=list, max_length=40)
    relationship: Relationship | None = None
    style: ReplyStyle = "mine"
    style_card: StyleCard | None = None
    examples: list[str] = Field(default_factory=list, max_length=20)
    memory: list[str] = Field(default_factory=list, max_length=30)
    language: ReplyLanguage = "auto"
    local_time: str | None = Field(default=None, max_length=60, description="The phone's time, e.g. 'Mon 13 Oct 2026, 8:30 pm'.")
    history: list[JarvisTurn] = Field(default_factory=list, max_length=6, description="Earlier in this conversation, oldest first.")
    notes: list[str] = Field(default_factory=list, max_length=30, description="My to-do list, kept on the phone.")
    wake: bool = Field(default=False, description="Started by the wake word: the audio begins just before it.")
    profile: list[str] = Field(default_factory=list, max_length=80, description="What Friday knows about me, kept on the phone.")
    notifications_allowed: bool = False
    notifications: list[PhoneNotification] = Field(default_factory=list, max_length=40, description="Recent ones, newest first.")
    habits: str | None = Field(default=None, max_length=3000, description="How I use the phone, from Android's usage stats.")
    # 0.9
    device: str | None = Field(default=None, max_length=400, description="Phone status: battery, Wi-Fi, Bluetooth, Do Not Disturb...")
    screen_text: list[str] = Field(
        default_factory=list, max_length=150,
        description="Words and buttons on screen, from Android's accessibility tree, top to bottom.",
    )
    screen_image: str | None = Field(default=None, max_length=900_000, description="Base64 JPEG screenshot, sent only when Friday looks.")
    looked: bool = Field(default=False, description="Friday asking again after looking at the screen, or after a step failed.")
    failed: list[str] = Field(default_factory=list, max_length=6, description="Steps that just failed on the phone, and why.")
    calendar: list[str] = Field(default_factory=list, max_length=30, description="My next events: 'Tue 4 Nov 3:00 pm-4:00 pm · Design review'.")
    tasks: list[str] = Field(default_factory=list, max_length=40, description="My open tasks and reminders, with when they're due.")
    memories: list[str] = Field(
        default_factory=list, max_length=300,
        description="Everything else Friday remembers on the phone (people, promises, past conversations). "
        "Searched here; only the matches reach the AI.",
    )
    routines: list[Routine] = Field(default_factory=list, max_length=20)
    pending: str | None = Field(default=None, max_length=500, description="Steps waiting for my yes, if Friday just asked.")
    briefing: Literal["morning", "evening", "day"] | None = Field(default=None, description="Asked for a spoken briefing.")
    voice: str | None = Field(
        default=None, max_length=20,
        description="Say the reply in this natural voice (e.g. 'diana'): `audio` in the response. None: the phone speaks.",
    )


MAX_STEPS = 6


class JarvisPlan(BaseModel):
    """What the model decides to do with a command."""

    heard: str = Field(description="What I said, with obvious speech-to-text mistakes fixed.")
    action: JarvisAction
    intent: str = Field(default="", description="write: what to write, in my words. Else empty.")
    steps: list[DeviceTask] = Field(
        default_factory=list, description="device: what to do on the phone, in order (1 to 6 steps). Else empty.",
    )
    confirm: bool = Field(default=False, description="device: true to ask me first, doing the steps only after I say yes.")
    say: str = Field(description="Friday's spoken answer: 1-2 short sentences.")
    remember: list[str] = Field(
        default_factory=list,
        description="New lasting facts about me to keep, each a short note like 'Name: Skanda'. Usually empty.",
    )
    forget: list[str] = Field(
        default_factory=list, description="Lines from <about_boss> to delete because I asked. Usually empty."
    )

    @model_validator(mode="before")
    @classmethod
    def _one_task(cls, data):
        """JarvisPlan(task=...) is a plan with that one step."""
        if isinstance(data, dict) and "task" in data:
            data = dict(data)
            task = data.pop("task")
            if task is not None and not data.get("steps"):
                data["steps"] = [task]
        return data

    @property
    def task(self) -> DeviceTask:
        """The first step (kind none when there isn't one)."""
        return self.steps[0] if self.steps else DeviceTask()


class JarvisResponse(BaseModel):
    heard: str
    action: JarvisReply
    say: str
    compose: ComposeResponse | None = None
    suggest: SuggestResponse | None = None
    task: DeviceTask | None = Field(default=None, description="The first step, for phones older than 0.9.")
    steps: list[DeviceTask] = Field(default_factory=list)
    confirm: bool = False
    remember: list[str] = Field(default_factory=list)
    forget: list[str] = Field(default_factory=list)
    latency_ms: int
    audio: str | None = Field(default=None, description="Base64 WAV of `say` in the asked-for voice, when it could be made.")


class SpeakRequest(BaseModel):
    """Something Friday says that isn't a reply (the greeting, a reminder), in her natural voice."""

    text: str = Field(min_length=1, max_length=1200)
    voice: str = Field(default="diana", max_length=20)


class SpeakResponse(BaseModel):
    audio: str = Field(description="Base64 WAV.")
