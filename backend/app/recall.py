"""Friday's memory search: which of the things the phone remembers match what was just asked.

The phone sends everything it keeps (people and promises from chats, notes, past conversations
with Friday); only the best matches go on to the AI, so a request about Rahul doesn't hand the AI
everything else. BM25 over words, with names and numbers weighted by how rare they are, so it
works without an embedding model or a database, and nothing is stored here.
"""

import math
import re
from collections import Counter

_WORD = re.compile(r"[a-z0-9ಀ-೿ऀ-ॿ]+")

# Words that say nothing about which memory is meant.
_STOP = set("""
a an the and or but if so of to in on at by for from with about into over after before is are was were be been am
i me my mine we our you your he him his she her they them their it its this that these those there here
what whats which who whom whose when where why how do does did done doing have has had can could will would
shall should may might must just also too very really please tell say said ask asked know remember recall
friday jarvis hey ok okay boss any anything something some all no not yes yeah up out get got
""".split())

# A few spoken forms of the same thing, so "bday" finds "birthday".
_SAME = {
    "bday": "birthday", "b'day": "birthday", "birthdays": "birthday", "anniv": "anniversary",
    "mom": "amma", "mum": "amma", "mother": "amma", "dad": "appa", "father": "appa",
    "rs": "rupees", "inr": "rupees", "money": "rupees", "owe": "owes", "owed": "owes", "lent": "owes",
    "meet": "meeting", "meetings": "meeting", "call": "call", "calls": "call", "called": "call",
    "pwd": "password", "pin": "password",
}


def _tokens(text: str) -> list[str]:
    out = []
    for w in _WORD.findall(text.lower()):
        w = _SAME.get(w, w)
        if len(w) > 3 and w.endswith("s") and not w.endswith("ss"):
            w = w[:-1]  # "deadlines" -> "deadline"
        if w not in _STOP and (len(w) > 1 or w.isdigit()):
            out.append(w)
    return out


def recall(query: str, memories: list[str], k: int = 8, min_score: float = 0.5) -> list[str]:
    """The [k] memories that best match [query], best first; none when nothing really matches."""
    q = Counter(_tokens(query))
    if not q or not memories:
        return []
    docs = [_tokens(m) for m in memories]
    n = len(docs)
    avg = sum(map(len, docs)) / n or 1.0
    df = Counter(w for d in docs for w in set(d))
    k1, b = 1.4, 0.6
    scored = []
    for i, d in enumerate(docs):
        if not d:
            continue
        tf = Counter(d)
        score = 0.0
        for w in q:
            if w not in tf:
                continue
            idf = math.log(1 + (n - df[w] + 0.5) / (df[w] + 0.5))
            score += idf * tf[w] * (k1 + 1) / (tf[w] + k1 * (1 - b + b * len(d) / avg))
        if score >= min_score:
            scored.append((score, -i, memories[i]))
    scored.sort(reverse=True)
    seen: set[str] = set()
    out = []
    for _, _, m in scored:
        key = m.strip().lower()
        if key not in seen:
            seen.add(key)
            out.append(m)
        if len(out) >= k:
            break
    return out
