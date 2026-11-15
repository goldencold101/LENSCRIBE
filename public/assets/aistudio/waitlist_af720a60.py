"""SQLite-backed waitlist for paid plans (stdlib only)."""

from __future__ import annotations

import csv
import io
import re
import sqlite3
import threading
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

EMAIL_PATTERN = re.compile(
    r"^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
    r"(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$"
)
MAX_EMAIL_LENGTH = 254


def normalize_email(value: object) -> Optional[str]:
    if not isinstance(value, str):
        return None
    email = value.strip().lower()
    if len(email) > MAX_EMAIL_LENGTH or not EMAIL_PATTERN.match(email):
        return None
    if ".." in email:
        return None
    return email


def _csv_safe(value: str) -> str:
    # Neutralize spreadsheet formula injection when the export is opened in Excel.
    return "'" + value if value[:1] in {"=", "+", "-", "@"} else value


class WaitlistStore:
    def __init__(self, path: Optional[Path]) -> None:
        if path is not None:
            path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self._db = sqlite3.connect(
            str(path) if path is not None else ":memory:", check_same_thread=False
        )
        with self._lock, self._db:
            self._db.execute(
                """
                CREATE TABLE IF NOT EXISTS waitlist (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    email TEXT NOT NULL UNIQUE,
                    plan TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )
                """
            )

    def add(self, email: str, plan: str) -> bool:
        created = datetime.now(timezone.utc).isoformat(timespec="seconds")
        with self._lock, self._db:
            cursor = self._db.execute(
                "INSERT OR IGNORE INTO waitlist (email, plan, created_at) VALUES (?, ?, ?)",
                (email, plan, created),
            )
            return cursor.rowcount == 1

    def count(self) -> int:
        with self._lock:
            return int(self._db.execute("SELECT COUNT(*) FROM waitlist").fetchone()[0])

    def export_csv(self) -> str:
        with self._lock:
            rows = self._db.execute(
                "SELECT email, plan, created_at FROM waitlist ORDER BY id"
            ).fetchall()
        buffer = io.StringIO()
        writer = csv.writer(buffer)
        writer.writerow(["email", "plan", "joined_at"])
        for email, plan, created in rows:
            writer.writerow([_csv_safe(email), plan, created])
        return buffer.getvalue()

    def close(self) -> None:
        with self._lock:
            self._db.close()
