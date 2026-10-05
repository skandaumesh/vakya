"""Fair use of the shared AI key: how many AI replies each phone gets per day.

Kept in memory, so it resets when the server restarts. That is fine for a soft
limit among friends; a real user base would move this to a database.
"""

import threading
from datetime import datetime, timedelta, timezone

IST = timezone(timedelta(hours=5, minutes=30))

_lock = threading.Lock()
_counts: dict[str, int] = {}
_day = ""


def _today() -> str:
    return datetime.now(IST).strftime("%Y-%m-%d")


def take(device: str, limit: int) -> bool:
    """Count one AI use for [device]; False if it already used [limit] today."""
    global _day
    with _lock:
        today = _today()
        if today != _day:  # new day (IST): everyone starts again
            _counts.clear()
            _day = today
        used = _counts.get(device, 0)
        if used >= limit:
            return False
        _counts[device] = used + 1
        return True


def used_today(device: str) -> int:
    with _lock:
        return _counts.get(device, 0) if _day == _today() else 0


def reset() -> None:
    """For tests."""
    global _day
    with _lock:
        _counts.clear()
        _day = ""
