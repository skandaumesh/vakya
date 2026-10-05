import asyncio

from app import phone_link


def test_reverse_fails_cleanly_without_adb():
    assert phone_link._reverse("definitely-not-adb.exe", 8000) is False


def test_keep_linked_does_nothing_without_adb(monkeypatch):
    monkeypatch.setattr(phone_link, "find_adb", lambda: None)
    asyncio.run(asyncio.wait_for(phone_link.keep_linked(8000), timeout=1))  # returns at once


def test_keep_linked_reapplies_until_cancelled(monkeypatch):
    calls = []
    monkeypatch.setattr(phone_link, "find_adb", lambda: "adb")
    monkeypatch.setattr(phone_link, "_reverse", lambda adb, port: calls.append(port) or True)

    async def run_briefly():
        task = asyncio.create_task(phone_link.keep_linked(8000, interval=0.01))
        await asyncio.sleep(0.1)
        task.cancel()

    asyncio.run(run_briefly())
    assert len(calls) >= 3 and set(calls) == {8000}
