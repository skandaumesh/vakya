"""Try Vakya from the terminal, no phone needed.

    python scripts/try_reply.py
    python scripts/try_reply.py --export "WhatsApp Chat with Rahul.txt" --rel client

Type an incoming message and press Enter to get 3 replies. Commands:
    /send N        pretend you sent option N (keeps the chat going)
    /me TEXT       add a message you sent yourself
    /draft TEXT    start typing; the next suggestions continue it (/draft alone clears)
    /style NAME    mine | professional | short | friendly
    /rel NAME      friend | client | professor | partner | family | colleague
    /memory        show what Vakya remembers about this contact
    /quit
"""

import argparse
import asyncio
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app import chat_export, llm, providers  # noqa: E402
from app.schemas import ChatMessage, StyleCard, SuggestRequest  # noqa: E402

CONTEXT_MESSAGES = 10
EXAMPLE_MESSAGES = 10

# One loop for the whole session: the cached async client is bound to it.
LOOP = asyncio.new_event_loop()


def load_export(path: Path, me: str | None):
    msgs = chat_export.parse_export(path.read_text(encoding="utf-8", errors="replace"))
    contact = chat_export.contact_from_filename(path.name)
    me = me or chat_export.guess_me(msgs, contact)
    if not me:
        names = chat_export.senders(msgs)
        sys.exit(f"Couldn't tell which sender is you. Pass --me with one of: {', '.join(names[:10])}")

    mine = [m.text for m in msgs if m.sender == me]
    if len(mine) < 5:
        sys.exit(f"Only {len(mine)} messages from {me!r}; need at least 5.")

    card_path = path.with_suffix(".style.json")
    if card_path.exists():
        card = StyleCard.model_validate_json(card_path.read_text(encoding="utf-8"))
        print(f"Loaded style card from {card_path.name}")
    else:
        print(f"Building your style card from {len(mine)} messages...")
        card = LOOP.run_until_complete(llm.build_style_card(mine))
        card_path.write_text(card.model_dump_json(indent=2), encoding="utf-8")
        print(f"Saved to {card_path.name}")

    examples = [t for t in mine if len(t.split()) >= 2][-EXAMPLE_MESSAGES:]
    convo = [
        ChatMessage(sender="me" if m.sender == me else "them", text=m.text)
        for m in msgs[-CONTEXT_MESSAGES:]
    ]
    return contact or path.stem, card, examples, convo


def show(resp) -> None:
    print(f"\n  [{resp.intent} · {resp.relationship_guess} · {resp.language} · {resp.latency_ms} ms]")
    for i, s in enumerate(resp.suggestions, 1):
        print(f"  {i}. {s.label:<11} {s.text}")
    for m in resp.memory_add:
        print(f"  + remember: {m}")
    for m in resp.memory_resolve:
        print(f"  - done: {m}")
    print()


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--export", type=Path, help="WhatsApp 'Export chat' .txt file")
    ap.add_argument("--me", help="Your name as it appears in the export")
    ap.add_argument("--rel", help="Relationship to this contact")
    ap.add_argument("--style", default="mine")
    ap.add_argument("--title", help="Chat title (defaults to the contact name)")
    args = ap.parse_args()

    try:
        providers.get_provider()
    except llm.MissingCredentials as e:
        sys.exit(
            f"{e}.\nGet a free key at https://console.groq.com/keys, then create backend\\.env "
            "(copy .env.example) and put it after GROQ_API_KEY="
        )
    print(f"Using {providers.describe()}")

    title, card, examples, convo = (args.title, None, [], [])
    if args.export:
        title_from_export, card, examples, convo = load_export(args.export, args.me)
        title = args.title or title_from_export

    rel, style, draft = args.rel, args.style, ""
    memory: list[str] = []
    last = None
    print(__doc__.split("Commands:")[1])

    while True:
        try:
            line = input("them> ").strip()
        except (EOFError, KeyboardInterrupt):
            break
        if not line:
            continue

        cmd, _, arg = line.partition(" ")
        if cmd == "/quit":
            break
        if cmd == "/send":
            if not last or not arg.isdigit() or not 1 <= int(arg) <= len(last.suggestions):
                print("  nothing to send")
                continue
            convo.append(ChatMessage(sender="me", text=last.suggestions[int(arg) - 1].text))
            print(f"  sent: {convo[-1].text}")
            draft = ""
            continue
        if cmd == "/me":
            convo.append(ChatMessage(sender="me", text=arg))
            continue
        if cmd == "/draft":
            draft = arg
            if not convo:
                print("  add an incoming message first")
                continue
        elif cmd == "/style":
            style = arg
            print(f"  style = {style}")
            continue
        elif cmd == "/rel":
            rel = arg or None
            print(f"  relationship = {rel}")
            continue
        elif cmd == "/memory":
            print("\n".join(f"  - {m}" for m in memory) or "  (empty)")
            continue
        elif cmd.startswith("/"):
            print("  unknown command")
            continue
        else:
            convo.append(ChatMessage(sender="them", text=line))

        req = SuggestRequest(
            chat_title=title,
            messages=convo[-30:],
            relationship=rel,
            style=style,
            style_card=card,
            examples=examples,
            memory=memory,
            draft=draft,
        )
        try:
            last = LOOP.run_until_complete(llm.suggest_replies(req))
        except Exception as e:  # show any failure and keep the session alive
            print(f"  error: {type(e).__name__}: {e}")
            continue
        show(last)
        memory = [m for m in memory if m not in last.memory_resolve] + last.memory_add


if __name__ == "__main__":
    main()
