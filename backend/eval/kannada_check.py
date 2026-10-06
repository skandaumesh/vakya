"""Does Vakya understand Kannada? 22 real-style messages with their true meaning.

    python eval/kannada_check.py            # all cases, with the configured provider
    python eval/kannada_check.py bike dad   # only these

For each message it prints the true meaning, what the AI understood (`meaning`) and its
first reply, for a person who reads Kannada to judge. Uses about 22 AI calls.
"""

import asyncio
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app import llm  # noqa: E402
from app.prompts import detect_mix  # noqa: E402
from app.schemas import SuggestRequest  # noqa: E402

# (id, their message(s), what it really means)
CASES = [
    ("bike", ["nin bike naale beku, sigutha?"], "THEY want to borrow MY bike tomorrow"),
    ("not-coming", ["naanu baralla kano, ammange husharilla"], "THEY won't come; their mom is unwell"),
    ("only-if", ["neenu bandre maatra naanu barteeni"], "they'll come ONLY IF I come"),
    ("why-no-call", ["yaake call madlilla ninne? kaaytidde"], "why didn't I call yesterday; they were waiting"),
    ("assignment", ["assignment submit maadidya? naale last date ante"], "did I submit; tomorrow is the last date, they heard"),
    ("no-money", ["nan hatra duddu illa, swalpa adjust madko"], "THEY have no money; asking ME to manage/adjust"),
    ("bunk", ["en guru, ivattu class bunk ah?"], "asking if I'm (we're) bunking class today"),
    ("who-told", ["ninge yaaru heldru naanu barteeni anta?"], "who told ME that THEY are coming"),
    ("badmouth", ["avnu ninna bagge ketta maatadtidda"], "HE was speaking badly about ME"),
    ("photo", ["sakkath aagide kano ninna photo, yelli tegdidhu?"], "MY photo is awesome; where was it taken"),
    ("leave-it", ["beda bidu, naane maadtini"], "leave it, THEY'LL do it themselves"),
    ("come-eat", ["oota aytha? illa andre baa nam mane ge"], "did I eat? if not, come to THEIR house"),
    ("movie", ["ivattu sanje free idiya? movie ge hogona"], "am I free this evening; let's go to a movie"),
    ("exam", ["naale exam ide, odhidya enadru?"], "exam tomorrow; have I studied anything"),
    ("script", ["ಊಟ ಆಯ್ತಾ? ಯಾವಾಗ ಬರ್ತೀಯ?"], "(Kannada script) did I eat; when am I coming"),
    ("work-done", ["ninna kelsa aytha illa innu?"], "is MY work done or not yet"),
    ("late", ["swalpa late aagutte, 10 nimisha wait maadi"], "THEY'LL be a bit late; asking ME to wait 10 min"),
    ("ask-him", ["adu nange gottilla, avnige kelu"], "THEY don't know; ask HIM"),
    ("dad", ["nam appa ninna nodbeku anta idru"], "THEIR dad wants to see ME"),
    ("next-time", ["hogli bidu, next time nodona"], "let it go; we'll see next time"),
    ("serious", ["sumne tamashe madbeda, serious aagi helu"], "don't joke, tell me seriously"),
    ("you-promised", ["kodtini andidde alva, yaavaga kodtiya?"], "I said I'd give it; when will I give it"),
]


async def main(only: set[str]) -> None:
    for cid, msgs, truth in CASES:
        if only and cid not in only:
            continue
        req = SuggestRequest(chat_title="Friend", relationship="friend", style="mine",
                             messages=[{"sender": "them", "text": t} for t in msgs])
        try:
            r = await llm.suggest_replies(req)
        except llm.LLMError as e:
            print(f"[{cid}] ERROR {e}")
            continue
        print(f"[{cid}] detected: {detect_mix(msgs)}")
        print(f"    truth: {truth}")
        print(f"    model: {r.meaning}")
        print(f"    reply: {r.suggestions[0].text}")


if __name__ == "__main__":
    asyncio.run(main(set(sys.argv[1:])))
