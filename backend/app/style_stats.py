import re
import statistics
from collections import Counter

from .schemas import StyleStats

_EMOJI = re.compile(
    "[\U0001F300-\U0001F3FA\U0001F400-\U0001FAFF\U0001F000-\U0001F2FF☀-➿⭐⭕]"
)


def emojis_in(text: str) -> list[str]:
    return _EMOJI.findall(text)


def compute_stats(messages: list[str]) -> StyleStats:
    msgs = [m.strip() for m in messages if m.strip()]
    if not msgs:
        return StyleStats(
            message_count=0, avg_words=0, median_words=0, emoji_rate=0,
            top_emojis=[], lowercase_start_rate=0, ends_with_period_rate=0,
        )

    word_counts = [len(m.split()) for m in msgs]
    emoji_counter: Counter[str] = Counter()
    with_emoji = 0
    for m in msgs:
        found = emojis_in(m)
        if found:
            with_emoji += 1
            emoji_counter.update(found)

    letter_starts = [m for m in msgs if m[0].isalpha()]
    lowercase_start = sum(1 for m in letter_starts if m[0].islower())
    # "..." is a trailing-off habit, not a full stop.
    ends_period = sum(1 for m in msgs if m.endswith(".") and not m.endswith(".."))

    n = len(msgs)
    return StyleStats(
        message_count=n,
        avg_words=round(sum(word_counts) / n, 1),
        median_words=statistics.median(word_counts),
        emoji_rate=round(with_emoji / n, 2),
        top_emojis=[e for e, _ in emoji_counter.most_common(5)],
        lowercase_start_rate=round(lowercase_start / len(letter_starts), 2) if letter_starts else 0,
        ends_with_period_rate=round(ends_period / n, 2),
    )
