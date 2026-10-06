from typing import Literal

from pydantic import BaseModel, Field

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


class ComposeVariant(BaseModel):
    style: ComposeStyle
    text: str = Field(description="The message exactly as it would be sent.")


class ComposeOutput(BaseModel):
    meaning: str = Field(default="", description="What I want to tell them, in plain English, one short sentence.")
    language: str = Field(description="Language and script used, e.g. 'Kannada-English (Latin script)'.")
    variants: list[ComposeVariant] = Field(description="Exactly 4, one per style: mine, professional, short, genz.")


class ComposeResponse(ComposeOutput):
    latency_ms: int
