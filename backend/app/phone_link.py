"""Keep a USB-connected phone's localhost:<port> pointed at this server (adb reverse).

Phones drop the forward when the screen locks or the USB mode changes, and the app
then can't reach the server. Re-applying it every few seconds makes that invisible.
"""

import asyncio
import logging
import os
import shutil
import subprocess
from pathlib import Path

log = logging.getLogger("vakya.phone")


def find_adb() -> str | None:
    candidates = [
        os.environ.get("ADB"),
        shutil.which("adb"),
        str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk" / "platform-tools" / "adb.exe"),
        str(Path.home() / "Android" / "Sdk" / "platform-tools" / "adb"),
    ]
    return next((c for c in candidates if c and Path(c).is_file()), None)


def _reverse(adb: str, port: int) -> bool:
    try:
        done = subprocess.run(
            [adb, "reverse", f"tcp:{port}", f"tcp:{port}"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10,
        )
        return done.returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        return False


async def keep_linked(port: int, interval: float = 5.0) -> None:
    adb = find_adb()
    if not adb:
        return
    linked = None
    while True:
        # In a thread: uvicorn may run a selector loop on Windows, which can't spawn processes.
        ok = await asyncio.to_thread(_reverse, adb, port)
        if ok != linked:
            log.info("phone %s", "linked over USB" if ok else "not connected (plug it in with USB debugging on)")
            linked = ok
        await asyncio.sleep(interval)
