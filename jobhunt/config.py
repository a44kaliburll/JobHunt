"""Application configuration.

Everything is overridable via environment variables so the app can run
anywhere (bare metal, Docker, a Raspberry Pi under cron) with zero config.
"""
from __future__ import annotations

import os
from pathlib import Path

DATA_DIR = Path(os.environ.get("JOBHUNT_DATA_DIR", "data")).resolve()
REPORTS_DIR = DATA_DIR / "reports"
UPLOADS_DIR = DATA_DIR / "uploads"

DATABASE_URL = os.environ.get(
    "JOBHUNT_DATABASE_URL", f"sqlite:///{DATA_DIR / 'jobhunt.db'}"
)

# Secret used to sign session cookies. Generated and persisted on first run
# if not provided, so restarts don't log everyone out.
SECRET_KEY_ENV = "JOBHUNT_SECRET_KEY"

# Listings older than this (by first_seen) are purged each run.
RETENTION_DAYS = int(os.environ.get("JOBHUNT_RETENTION_DAYS", "90"))

# Minimum match score (out of MAX_SCORE) for a listing to be kept.
DEFAULT_MIN_SCORE = int(os.environ.get("JOBHUNT_MIN_SCORE", "6"))
MAX_SCORE = 50

# Daily run time for the scheduler (24h clock, server-local time).
SCHEDULE_HOUR = int(os.environ.get("JOBHUNT_SCHEDULE_HOUR", "7"))
SCHEDULE_MINUTE = int(os.environ.get("JOBHUNT_SCHEDULE_MINUTE", "0"))

HTTP_TIMEOUT = float(os.environ.get("JOBHUNT_HTTP_TIMEOUT", "20"))
USER_AGENT = os.environ.get(
    "JOBHUNT_USER_AGENT",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0 Safari/537.36 JobHunt/0.1",
)

# Cap on fan-out queries per run so a profile with many titles/locations
# doesn't hammer the boards.
MAX_QUERIES_PER_SOURCE = int(os.environ.get("JOBHUNT_MAX_QUERIES_PER_SOURCE", "25"))


def ensure_dirs() -> None:
    for d in (DATA_DIR, REPORTS_DIR, UPLOADS_DIR):
        d.mkdir(parents=True, exist_ok=True)


def get_secret_key() -> str:
    env = os.environ.get(SECRET_KEY_ENV)
    if env:
        return env
    ensure_dirs()
    key_file = DATA_DIR / ".secret_key"
    if key_file.exists():
        return key_file.read_text().strip()
    key = os.urandom(32).hex()
    key_file.write_text(key)
    try:
        key_file.chmod(0o600)
    except OSError:
        pass
    return key
