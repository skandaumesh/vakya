"""Run the reply-quality eval against the live model.

    python eval/run_eval.py                 # all cases
    python eval/run_eval.py client friend   # only cases whose id contains one of these

Each case gets automatic checks (3 options, distinct actions, right intent,
language, no AI phrasing, ...) and the replies are printed for a human read.
Results are saved to eval/results/<timestamp>.json.
"""

import asyncio
import json
import re
import statistics
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT.parent))

from app import llm  # noqa: E402
from app.prompts import FRIEND_ONLY_WORDS, NON_FRIEND  # noqa: E402
from app.schemas import ChatMessage, StyleCard, SuggestRequest, SuggestResponse  # noqa: E402

# Free tiers allow a few thousand tokens per minute, so stay gentle and wait out limits.
CONCURRENCY = 2
RATE_LIMIT_WAIT_S = 30
RATE_LIMIT_TRIES = 4


async def suggest(req: SuggestRequest) -> SuggestResponse:
    for attempt in range(RATE_LIMIT_TRIES):
        try:
            return await llm.suggest_replies(req)
        except llm.RateLimited as e:
            # A daily cap won't clear in 30 s; stop instead of hanging for many minutes.
            if e.daily or attempt == RATE_LIMIT_TRIES - 1:
                raise
            await asyncio.sleep(RATE_LIMIT_WAIT_S)
    raise AssertionError("unreachable")

AI_PHRASES = [
    "i'd be happy", "i would be happy", "certainly", "absolutely!", "hope this message finds",
    "feel free", "let me know if you need anything else", "—", "#",
]
MARKERS = {
    "kanglish": {"da", "maga", "macha", "guru", "illa", "beda", "gottilla", "aaytu", "madtini",
                 "bartini", "barthini", "bartiya", "barthiya", "swalpa", "yen", "yenu", "houdu", "nange",
                 "ninge", "sari", "ide", "hogona", "banni", "kalsthini", "aytu", "aaitu", "ella", "beku"},
    "hinglish": {"hai", "haan", "nahi", "nhi", "kya", "bhai", "yaar", "kar", "karunga", "karenge",
                 "kal", "acha", "accha", "theek", "thik", "bhej", "dunga", "hoon", "raha", "rahi",
                 "chal", "chalo", "bas", "abhi", "kuch", "pakka", "ho", "jayega", "se", "pehle", "ji"},
}


def words(text: str) -> set[str]:
    return set(re.findall(r"[a-z]+", text.lower()))


# In the app, past messages come from the same chat, so pick the set that matches who
# the case is talking to. For "guess the relationship" cases, use the expected one.
EXAMPLE_GROUP = {"friend": "friend", "client": "work", "professor": "work", "colleague": "work", "family": "family"}


def build_request(case: dict, personas: dict, messages, memory) -> SuggestRequest:
    persona = case.get("persona")
    rel = case.get("relationship") or (case["expect"].get("rel") or [None])[0]
    examples = personas["_examples"].get(persona, {}).get(EXAMPLE_GROUP.get(rel), []) if persona else []
    return SuggestRequest(
        chat_title=case.get("title"),
        is_group=case.get("is_group", False),
        messages=[ChatMessage(sender=m[0], text=m[1], name=m[2] if len(m) > 2 else None) for m in messages],
        relationship=case.get("relationship"),
        style=case.get("style", "mine"),
        style_card=StyleCard.model_validate(personas[persona]) if persona else None,
        examples=examples,
        memory=memory,
        draft=case.get("draft", ""),
    )


def check(case: dict, personas: dict, resp: SuggestResponse) -> list[str]:
    exp = case["expect"]
    texts = [s.text for s in resp.suggestions]
    lows = [t.lower() for t in texts]
    fails = []

    if len(texts) != 3:
        fails.append(f"{len(texts)} options")
    if len({s.label.strip().lower() for s in resp.suggestions}) != len(texts):
        fails.append("duplicate labels")
    if resp.intent not in exp["intents"]:
        fails.append(f"intent {resp.intent} not in {exp['intents']}")
    if "rel" in exp and resp.relationship_guess not in exp["rel"]:
        fails.append(f"relationship {resp.relationship_guess} not in {exp['rel']}")
    for t in lows:
        hit = [p for p in AI_PHRASES if p in t]
        if hit:
            fails.append(f"AI phrasing {hit}")

    lang = exp.get("lang", "any")
    if lang in MARKERS and not any(words(t) & MARKERS[lang] for t in texts):
        fails.append(f"no {lang} in any option")
    if lang == "english":
        mixed = [t for t in texts if words(t) & (MARKERS["kanglish"] - {"ide", "ella", "sari"})]
        if mixed:
            fails.append(f"slang/mixed language where English expected: {mixed}")

    rel = case.get("relationship") or (exp.get("rel") or [None])[0]
    if rel in NON_FRIEND:
        allowed = words(" ".join(build_request(case, personas, case["messages"], []).examples))
        slang = sorted({w for t in texts for w in words(t) & FRIEND_ONLY_WORDS} - allowed)
        if slang:
            fails.append(f"friend slang with a {rel}: {slang}")

    if "max_words" in exp:
        long = [t for t in texts if len(t.split()) > exp["max_words"]]
        if long:
            fails.append(f"over {exp['max_words']} words: {long}")
    if case.get("style") == "mine" and case.get("persona"):
        avg = personas[case["persona"]]["stats"]["avg_words"]
        mean = statistics.mean(len(t.split()) for t in texts)
        if mean > max(12, 3 * avg):
            fails.append(f"too long for persona: mean {mean:.1f} words")
    if "mention" in exp and not any(w in t for w in exp["mention"] for t in lows):
        fails.append(f"no option mentions any of {exp['mention']}")
    for w in exp.get("all_mention", []):
        if not all(w in t for t in lows):
            fails.append(f"not every option contains '{w}'")
    # Old topics answered again, or claims like "here are the files" that aren't true yet.
    stale = [t for t in lows if any(w in t for w in exp.get("none_mention", []))]
    if stale:
        fails.append(f"mentions {exp['none_mention']}: {stale}")
    if exp.get("no_digits") and any(re.search(r"\d", t) for t in texts):
        fails.append("invented numbers")
    for item in exp.get("resolve", []):
        if item not in resp.memory_resolve:
            fails.append(f"didn't resolve memory '{item}'")
    for bad in exp.get("must_not", []):
        if any(bad in t for t in lows):
            fails.append(f"contains '{bad}'")
    return fails


async def run_case(case: dict, personas: dict, sem: asyncio.Semaphore) -> dict:
    async with sem:
        out = {"id": case["id"], "turns": [], "fails": []}
        try:
            req = build_request(case, personas, case["messages"], case.get("memory", []))
            resp = await suggest(req)
            out["turns"].append(resp.model_dump())
            out["fails"] += check(case, personas, resp)

            if case.get("type") == "chain":
                # Days later: only the new message is on screen, so anything the
                # reply knows about the earlier deal must come from memory.
                if not resp.memory_add:
                    out["fails"].append("turn 1 saved nothing to memory")
                req2 = build_request(case, personas, case["then"], resp.memory_add)
                resp2 = await suggest(req2)
                out["turns"].append(resp2.model_dump())
                words_ = case["expect"]["then_mention"]
                if not any(w in s.text.lower() for w in words_ for s in resp2.suggestions):
                    out["fails"].append(f"turn 2 used no memory (none of {words_})")
        except Exception as e:  # record and keep going; one bad case shouldn't stop the run
            out["fails"].append(f"error: {type(e).__name__}: {e}")
        return out


def print_case(r: dict) -> None:
    status = "PASS" if not r["fails"] else "FAIL"
    print(f"\n{status}  {r['id']}")
    for i, t in enumerate(r["turns"]):
        prefix = "  turn 2: " if i else "  "
        print(f"{prefix}[{t['intent']} · {t['relationship_guess']} · {t['language']} · {t['latency_ms']} ms]")
        for s in t["suggestions"]:
            print(f"    {s['label']:<11} {s['text']}")
        for m in t["memory_add"]:
            print(f"    + {m}")
        for m in t["memory_resolve"]:
            print(f"    - {m}")
    for f in r["fails"]:
        print(f"  ✗ {f}")


async def main(filters: list[str]) -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    cases = json.loads((ROOT / "cases.json").read_text(encoding="utf-8"))
    personas = json.loads((ROOT / "personas.json").read_text(encoding="utf-8"))
    if filters:
        cases = [c for c in cases if any(f in c["id"] for f in filters)]

    sem = asyncio.Semaphore(CONCURRENCY)
    results = await asyncio.gather(*(run_case(c, personas, sem) for c in cases))
    for r in results:
        print_case(r)

    latencies = [t["latency_ms"] for r in results for t in r["turns"]]
    passed = sum(1 for r in results if not r["fails"])
    print(f"\n{passed}/{len(results)} cases passed")
    if latencies:
        lat = sorted(latencies)
        p90 = lat[min(len(lat) - 1, int(len(lat) * 0.9))]
        print(f"latency p50 {statistics.median(lat):.0f} ms, p90 {p90} ms")

    out_dir = ROOT / "results"
    out_dir.mkdir(exist_ok=True)
    path = out_dir / f"{time.strftime('%Y%m%d-%H%M%S')}.json"
    path.write_text(json.dumps(results, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"saved {path.relative_to(ROOT.parent)}")


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1:]))
