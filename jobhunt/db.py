"""Database models and session helpers (SQLAlchemy 2.0, SQLite by default)."""
from __future__ import annotations

import datetime as dt
import json
from typing import Any, Iterator

from sqlalchemy import (
    Boolean,
    DateTime,
    ForeignKey,
    Integer,
    String,
    Text,
    UniqueConstraint,
    create_engine,
)
from sqlalchemy.orm import (
    DeclarativeBase,
    Mapped,
    Session,
    mapped_column,
    relationship,
    sessionmaker,
)

from . import config


class Base(DeclarativeBase):
    pass


def _utcnow() -> dt.datetime:
    return dt.datetime.now(dt.timezone.utc)


class JSONColumn:
    """Tiny helper for storing JSON in a Text column without a dialect dep."""

    @staticmethod
    def dumps(value: Any) -> str:
        return json.dumps(value, ensure_ascii=False)

    @staticmethod
    def loads(raw: str | None, default: Any) -> Any:
        if not raw:
            return default
        try:
            return json.loads(raw)
        except (ValueError, TypeError):
            return default


class User(Base):
    __tablename__ = "users"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    email: Mapped[str] = mapped_column(String(255), unique=True, index=True)
    password_hash: Mapped[str] = mapped_column(String(255))
    name: Mapped[str] = mapped_column(String(255), default="")
    # JSON list of preferred locations, e.g. ["Albany, NY", "Remote"]
    locations_json: Mapped[str] = mapped_column(Text, default="[]")
    # JSON list of extra search titles the user adds on top of resume-derived ones
    extra_titles_json: Mapped[str] = mapped_column(Text, default="[]")
    min_score: Mapped[int] = mapped_column(Integer, default=config.DEFAULT_MIN_SCORE)
    created_at: Mapped[dt.datetime] = mapped_column(DateTime, default=_utcnow)

    resumes: Mapped[list["Resume"]] = relationship(
        back_populates="user", cascade="all, delete-orphan"
    )
    sources: Mapped[list["JobSource"]] = relationship(
        back_populates="user", cascade="all, delete-orphan"
    )
    listings: Mapped[list["Listing"]] = relationship(
        back_populates="user", cascade="all, delete-orphan"
    )

    @property
    def locations(self) -> list[str]:
        return JSONColumn.loads(self.locations_json, [])

    @locations.setter
    def locations(self, value: list[str]) -> None:
        self.locations_json = JSONColumn.dumps(value)

    @property
    def extra_titles(self) -> list[str]:
        return JSONColumn.loads(self.extra_titles_json, [])

    @extra_titles.setter
    def extra_titles(self, value: list[str]) -> None:
        self.extra_titles_json = JSONColumn.dumps(value)


class Resume(Base):
    __tablename__ = "resumes"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    filename: Mapped[str] = mapped_column(String(512))
    content_text: Mapped[str] = mapped_column(Text, default="")
    # JSON: {"skills": [...], "duties": [...], "certifications": [...],
    #        "titles": [...], "summary": "..."}
    parsed_json: Mapped[str] = mapped_column(Text, default="{}")
    uploaded_at: Mapped[dt.datetime] = mapped_column(DateTime, default=_utcnow)

    user: Mapped[User] = relationship(back_populates="resumes")

    @property
    def parsed(self) -> dict:
        return JSONColumn.loads(self.parsed_json, {})

    @parsed.setter
    def parsed(self, value: dict) -> None:
        self.parsed_json = JSONColumn.dumps(value)


class JobSource(Base):
    """A user-defined job site (the built-in boards live in code)."""

    __tablename__ = "job_sources"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    name: Mapped[str] = mapped_column(String(255))
    # "rss" | "json" | "html"
    kind: Mapped[str] = mapped_column(String(32))
    # JSON config; see jobhunt.scrapers.custom for the schema per kind.
    config_json: Mapped[str] = mapped_column(Text, default="{}")
    enabled: Mapped[bool] = mapped_column(Boolean, default=True)
    created_at: Mapped[dt.datetime] = mapped_column(DateTime, default=_utcnow)

    user: Mapped[User] = relationship(back_populates="sources")

    @property
    def config(self) -> dict:
        return JSONColumn.loads(self.config_json, {})

    @config.setter
    def config(self, value: dict) -> None:
        self.config_json = JSONColumn.dumps(value)


class Listing(Base):
    __tablename__ = "listings"
    __table_args__ = (UniqueConstraint("user_id", "key", name="uq_listing_user_key"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    # Stable dedupe key, e.g. "li:4412327417" or "ro:1131568"
    key: Mapped[str] = mapped_column(String(512))
    title: Mapped[str] = mapped_column(String(512))
    company: Mapped[str] = mapped_column(String(512), default="")
    location: Mapped[str] = mapped_column(String(512), default="")
    url: Mapped[str] = mapped_column(Text, default="")
    source: Mapped[str] = mapped_column(String(128), default="")
    posted: Mapped[str] = mapped_column(String(32), default="")  # ISO date or ""
    score: Mapped[int] = mapped_column(Integer, default=0)
    first_seen: Mapped[str] = mapped_column(String(32), index=True)  # ISO date
    is_new: Mapped[bool] = mapped_column(Boolean, default=True)
    # User workflow status: "" | "saved" | "applied" | "rejected" | "hidden"
    status: Mapped[str] = mapped_column(String(32), default="")

    user: Mapped[User] = relationship(back_populates="listings")


class RunLog(Base):
    __tablename__ = "run_logs"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id"), index=True)
    ran_at: Mapped[dt.datetime] = mapped_column(DateTime, default=_utcnow)
    fetched: Mapped[int] = mapped_column(Integer, default=0)
    in_range: Mapped[int] = mapped_column(Integer, default=0)
    relevant: Mapped[int] = mapped_column(Integer, default=0)
    new_count: Mapped[int] = mapped_column(Integer, default=0)
    queries_json: Mapped[str] = mapped_column(Text, default="[]")

    @property
    def queries(self) -> list[str]:
        return JSONColumn.loads(self.queries_json, [])

    @queries.setter
    def queries(self, value: list[str]) -> None:
        self.queries_json = JSONColumn.dumps(value)


_engine = None
_SessionLocal: sessionmaker | None = None


def get_engine():
    global _engine
    if _engine is None:
        config.ensure_dirs()
        _engine = create_engine(
            config.DATABASE_URL, connect_args={"check_same_thread": False}
            if config.DATABASE_URL.startswith("sqlite")
            else {},
        )
    return _engine


def init_db() -> None:
    Base.metadata.create_all(get_engine())


def get_sessionmaker() -> sessionmaker:
    global _SessionLocal
    if _SessionLocal is None:
        _SessionLocal = sessionmaker(bind=get_engine(), expire_on_commit=False)
    return _SessionLocal


def session() -> Session:
    return get_sessionmaker()()


def session_scope() -> Iterator[Session]:
    """FastAPI dependency yielding a session."""
    db = session()
    try:
        yield db
    finally:
        db.close()
