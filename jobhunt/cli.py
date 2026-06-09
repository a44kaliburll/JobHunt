"""Command-line interface.

  jobhunt init-db                          create the database
  jobhunt add-user EMAIL [--name NAME]     create an account (prompts for password)
  jobhunt upload-resume EMAIL FILE...      ingest resumes for a user
  jobhunt run [--email EMAIL]              run the pipeline (all users by default)
  jobhunt serve [--host H] [--port P]      start the web UI
  jobhunt schedule                         run the daily scheduler in the foreground
"""
from __future__ import annotations

import argparse
import getpass
import logging
import sys
from pathlib import Path

from sqlalchemy import select

from . import auth, db
from .db import Resume, User
from .resume.extract import extract_text
from .resume.parser import parse_resume


def _get_user(session, email: str) -> User:
    user = session.scalar(select(User).where(User.email == email.lower()))
    if not user:
        sys.exit(f"No user with email {email!r}; run 'jobhunt add-user' first.")
    return user


def cmd_init_db(_args) -> None:
    db.init_db()
    print("Database initialized.")


def cmd_add_user(args) -> None:
    db.init_db()
    session = db.session()
    try:
        if session.scalar(select(User).where(User.email == args.email.lower())):
            sys.exit(f"User {args.email} already exists.")
        password = args.password or getpass.getpass("Password (8+ chars): ")
        if len(password) < 8:
            sys.exit("Password must be at least 8 characters.")
        user = User(
            email=args.email.lower(),
            password_hash=auth.hash_password(password),
            name=args.name or "",
        )
        if args.locations:
            user.locations = [l.strip() for l in args.locations.split(",") if l.strip()]
        session.add(user)
        session.commit()
        print(f"Created user {user.email} (id={user.id}).")
    finally:
        session.close()


def cmd_upload_resume(args) -> None:
    db.init_db()
    session = db.session()
    try:
        user = _get_user(session, args.email)
        for file_path in args.files:
            path = Path(file_path)
            text = extract_text(path.name, path.read_bytes())
            parsed = parse_resume(text)
            resume = Resume(user_id=user.id, filename=path.name, content_text=text)
            resume.parsed = parsed.to_dict()
            session.add(resume)
            print(
                f"{path.name}: {len(parsed.skills)} skills, "
                f"{len(parsed.duties)} duties, "
                f"{len(parsed.certifications)} certifications, "
                f"{len(parsed.titles)} titles"
            )
        session.commit()
    finally:
        session.close()


def cmd_run(args) -> None:
    from .pipeline import run_all_users, run_for_user

    db.init_db()
    session = db.session()
    try:
        if args.email:
            user = _get_user(session, args.email)
            results = [run_for_user(session, user)]
        else:
            results = run_all_users(session)
        for result in results:
            print(f"user={result.user_id} {result.funnel}")
            if result.digest_path:
                print(f"  digest: {result.digest_path}")
            for error in result.errors:
                print(f"  warning: {error}")
    finally:
        session.close()


def cmd_serve(args) -> None:
    import uvicorn

    uvicorn.run("jobhunt.web.app:app", host=args.host, port=args.port)


def cmd_schedule(_args) -> None:
    from . import scheduler

    scheduler.start()


def main(argv: list[str] | None = None) -> None:
    logging.basicConfig(
        level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s"
    )
    parser = argparse.ArgumentParser(prog="jobhunt", description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("init-db").set_defaults(func=cmd_init_db)

    p = sub.add_parser("add-user")
    p.add_argument("email")
    p.add_argument("--name", default="")
    p.add_argument("--password", default="", help="omit to be prompted")
    p.add_argument("--locations", default="", help='e.g. "Albany, NY, Remote"')
    p.set_defaults(func=cmd_add_user)

    p = sub.add_parser("upload-resume")
    p.add_argument("email")
    p.add_argument("files", nargs="+")
    p.set_defaults(func=cmd_upload_resume)

    p = sub.add_parser("run")
    p.add_argument("--email", default="", help="run for one user only")
    p.set_defaults(func=cmd_run)

    p = sub.add_parser("serve")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=8000)
    p.set_defaults(func=cmd_serve)

    sub.add_parser("schedule").set_defaults(func=cmd_schedule)

    args = parser.parse_args(argv)
    args.func(args)


if __name__ == "__main__":
    main()
