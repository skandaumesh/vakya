from app.chat_export import contact_from_filename, guess_me, parse_export, senders

ANDROID = """\
12/31/23, 9:15 PM - Messages and calls are end-to-end encrypted. No one outside of this chat can read them.
12/31/23, 9:15 PM - Rahul: Hi, can you send the files?
12/31/23, 9:16 PM - Skanda: ok da will do
12/31/23, 9:17 PM - Rahul: Also the logo
in PNG please
12/31/23, 9:18 PM - Skanda: <Media omitted>
12/31/23, 9:19 PM - Skanda: sent 👍 <This message was edited>
12/31/23, 9:20 PM - Rahul: This message was deleted
"""

ANDROID_24H = """\
31/12/2023, 21:15 - ~ Kiran: standup at 10:30
31/12/2023, 21:16 - Skanda: sari
"""

IOS = """\
‎[31/12/23, 9:15:23 PM] Rahul: Hi there
[31/12/23, 9:16:01 PM] Skanda: haha nice 😂
‎[31/12/23, 9:16:30 PM] Skanda: ‎image omitted
"""


def test_android_format():
    msgs = parse_export(ANDROID)
    assert [(m.sender, m.text) for m in msgs] == [
        ("Rahul", "Hi, can you send the files?"),
        ("Skanda", "ok da will do"),
        ("Rahul", "Also the logo\nin PNG please"),
        ("Skanda", "sent 👍"),
    ]


def test_24h_format_and_tilde_sender():
    msgs = parse_export(ANDROID_24H)
    assert [(m.sender, m.text) for m in msgs] == [("Kiran", "standup at 10:30"), ("Skanda", "sari")]


def test_ios_format_drops_media():
    msgs = parse_export(IOS)
    assert [(m.sender, m.text) for m in msgs] == [("Rahul", "Hi there"), ("Skanda", "haha nice 😂")]


def test_guess_me_from_filename():
    msgs = parse_export(ANDROID)
    contact = contact_from_filename("WhatsApp Chat with Rahul.txt")
    assert contact == "Rahul"
    assert guess_me(msgs, contact) == "Skanda"
    assert senders(msgs)[0] in {"Rahul", "Skanda"}


def test_guess_me_needs_two_senders():
    assert guess_me(parse_export(ANDROID), None) is None


def test_filename_with_copy_suffix():
    assert contact_from_filename("WhatsApp Chat with Priya Bakery (2).txt") == "Priya Bakery"
    assert contact_from_filename("notes.txt") is None
