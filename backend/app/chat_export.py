"""Parser for WhatsApp "Export chat" .txt files (Android and iOS formats).

The Android app will carry a Kotlin port of this; keep the two in sync.
"""

import re
from dataclasses import dataclass

# 12/31/23, 9:15 PM - Name: text          (Android, US)
# 31/12/2023, 21:15 - Name: text          (Android, 24h)
# [31/12/23, 9:15:23 PM] Name: text       (iOS)
_LINE = re.compile(
    r"^\[?(?P<date>\d{1,4}[./-]\d{1,2}[./-]\d{1,4}),?\s+"
    r"(?P<time>\d{1,2}[:.]\d{2}(?:[:.]\d{2})?(?:\s?[APap]\.?\s?[Mm]\.?)?)\]?"
    r"\s*(?:-\s*)?(?P<rest>.*)$"
)

_INVISIBLE = dict.fromkeys(map(ord, "‎‏﻿"), None)

# Messages that carry no text the user actually typed.
_SKIP = {
    "<media omitted>",
    "this message was deleted",
    "you deleted this message",
    "null",
    "missed voice call",
    "missed video call",
}
_SKIP_SUFFIX = (" omitted",)  # iOS: "image omitted", "sticker omitted", ...
_EDITED = "<This message was edited>"


@dataclass
class ExportMessage:
    date: str
    time: str
    sender: str
    text: str


def _normalise(line: str) -> str:
    return line.translate(_INVISIBLE).replace(" ", " ").replace(" ", " ").rstrip("\r")


def parse_export(raw: str) -> list[ExportMessage]:
    messages: list[ExportMessage] = []
    current: ExportMessage | None = None

    for line in raw.splitlines():
        line = _normalise(line)
        match = _LINE.match(line)
        if not match:
            # Continuation of a multi-line message.
            if current is not None:
                current.text += "\n" + line
            continue

        rest = match["rest"]
        sender, sep, text = rest.partition(": ")
        if not sep:
            # System line ("Messages and calls are end-to-end encrypted", group events).
            current = None
            continue

        current = ExportMessage(
            date=match["date"],
            time=match["time"],
            sender=sender.lstrip("~ ").strip(),
            text=text,
        )
        messages.append(current)

    cleaned = []
    for m in messages:
        text = m.text.replace(_EDITED, "").strip()
        low = text.lower()
        if not text or low in _SKIP or (low.endswith(_SKIP_SUFFIX) and len(low.split()) <= 2):
            continue
        m.text = text
        cleaned.append(m)
    return cleaned


def senders(messages: list[ExportMessage]) -> list[str]:
    """Senders ordered by message count, most active first."""
    counts: dict[str, int] = {}
    for m in messages:
        counts[m.sender] = counts.get(m.sender, 0) + 1
    return sorted(counts, key=counts.get, reverse=True)


def guess_me(messages: list[ExportMessage], contact_name: str | None) -> str | None:
    """In a 1:1 export, 'me' is the sender who isn't the contact the file is named after."""
    names = senders(messages)
    if len(names) != 2 or not contact_name:
        return None
    others = [n for n in names if n.casefold() != contact_name.casefold()]
    return others[0] if len(others) == 1 else None


def contact_from_filename(filename: str) -> str | None:
    """'WhatsApp Chat with Rahul.txt' -> 'Rahul'."""
    m = re.search(r"WhatsApp Chat (?:with|-)\s*(.+?)(?:\s*\(\d+\))?\.txt$", filename, re.I)
    return m.group(1).strip() if m else None
