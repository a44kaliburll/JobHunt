"""Seed the scheduled hunt's database from cron/profile.json.

The scheduled hunt has no one to upload a resume, so the committed profile is
written in as a synthetic one. That keeps the runner on exactly the same code
path as the app and the CLI — parse once here, score identically everywhere —
rather than growing a second notion of what a profile is.

Idempotent: editing profile.json and re-running replaces the synthetic resume,
and never touches resumes added any other way.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

from sqlalchemy import select

from jobhunt import auth, db
from jobhunt.db import Resume, User

CRON_EMAIL = "cron@jobhunt.local"
SYNTHETIC_RESUME = "cron/profile.json"


def main(profile_path: str = "cron/profile.json") -> int:
    profile = json.loads(Path(profile_path).read_text())
    db.init_db()
    session = db.session()
    try:
        user = session.scalar(select(User).where(User.email == CRON_EMAIL))
        if user is None:
            user = User(
                email=CRON_EMAIL,
                password_hash=auth.hash_password("unused-no-login"),
                name="Scheduled hunt",
            )
            session.add(user)
            session.flush()

        user.locations = list(profile.get("locations", []))
        user.min_score = int(profile.get("min_score", 6))
        # Titles reach the profile through the synthetic resume below, so the
        # legacy extra-titles field stays empty and cannot drift from it.
        user.extra_titles = []

        for stale in session.scalars(
            select(Resume).where(
                Resume.user_id == user.id, Resume.filename == SYNTHETIC_RESUME
            )
        ):
            session.delete(stale)

        resume = Resume(
            user_id=user.id,
            filename=SYNTHETIC_RESUME,
            content_text="(profile maintained by hand in cron/profile.json)",
        )
        resume.parsed = {
            "titles": profile.get("titles", []),
            "skills": profile.get("skills", []),
            "certifications": profile.get("certifications", []),
            "duties": profile.get("duties", []),
            "summary": "",
        }
        session.add(resume)
        session.commit()

        print(
            f"seeded {CRON_EMAIL}: "
            f"{len(profile.get('titles', []))} titles, "
            f"{len(profile.get('skills', []))} skills, "
            f"{len(user.locations)} locations, min_score={user.min_score}"
        )
        return 0
    finally:
        session.close()


if __name__ == "__main__":
    sys.exit(main(*sys.argv[1:]))
