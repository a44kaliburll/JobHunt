import datetime as dt

import pytest

from jobhunt import auth, db, pipeline
from jobhunt.db import Listing, Resume, User
from jobhunt.resume.parser import parse_resume
from jobhunt.scrapers.base import JobPosting, Scraper, SearchQuery

RESUME_TEXT = """Jane Doe

Skills
- Instructional Design, Canvas, WCAG, Project Management

Experience

Director of Online Learning | Some University
2018 - Present
- Led a team of instructional designers.
- Implemented accessibility standards across online courses.
"""


class FakeScraper(Scraper):
    name = "FakeBoard"
    key_prefix = "fb"

    POSTINGS = [
        JobPosting(
            key="fb:1",
            title="Director of Online Learning",
            company="Acme U",
            location="Albany, NY",
            url="https://fake.test/1",
            source="FakeBoard",
            posted="2026-06-01",
            description="instructional design canvas wcag project management",
        ),
        JobPosting(
            key="fb:2",
            title="Forklift Operator",
            company="Warehouse Inc",
            location="Albany, NY",
            url="https://fake.test/2",
            source="FakeBoard",
            description="forklift",
        ),
        JobPosting(
            key="fb:3",
            title="Instructional Designer",
            company="Far Away College",
            location="San Francisco, CA",
            url="https://fake.test/3",
            source="FakeBoard",
            description="instructional design",
        ),
    ]

    def search(self, query: SearchQuery):
        return list(self.POSTINGS)


@pytest.fixture()
def session(monkeypatch):
    db.init_db()
    s = db.session()
    # Only the fake scraper; no network.
    monkeypatch.setattr(
        "jobhunt.pipeline.build_scrapers", lambda sources, client=None: [FakeScraper()]
    )
    yield s
    s.query(Listing).delete()
    s.query(Resume).delete()
    s.query(User).delete()
    s.commit()
    s.close()


def _make_user(session) -> User:
    user = User(email="jane@test", password_hash=auth.hash_password("password1"))
    user.locations = ["Albany, NY"]
    session.add(user)
    session.flush()
    resume = Resume(user_id=user.id, filename="resume.md", content_text=RESUME_TEXT)
    resume.parsed = parse_resume(RESUME_TEXT).to_dict()
    session.add(resume)
    session.commit()
    return user


def test_pipeline_funnel_dedupe_and_reports(session, tmp_path):
    user = _make_user(session)
    today = dt.date(2026, 6, 9)

    result = pipeline.run_for_user(session, user, today=today)
    # 3 fetched per query (1 query: 1 title x 1 location), SF one dropped by
    # location, forklift one dropped by score.
    assert result.in_range == 2
    assert result.relevant == 1
    assert result.new_count == 1
    listing = session.query(Listing).filter_by(user_id=user.id).one()
    assert listing.key == "fb:1"
    assert listing.is_new
    assert listing.score >= 20

    # Digest + working list written, artex-style.
    digest = open(result.digest_path).read()
    assert "**Funnel:**" in digest
    assert "Director of Online Learning" in digest
    assert "## Queries fanned" in digest

    # Second run: same postings -> no new listings, NEW flag cleared.
    result2 = pipeline.run_for_user(session, user, today=today)
    assert result2.new_count == 0
    listing = session.query(Listing).filter_by(user_id=user.id).one()
    assert not listing.is_new


def test_pipeline_requires_resume(session):
    user = User(email="empty@test", password_hash=auth.hash_password("password1"))
    session.add(user)
    session.commit()
    result = pipeline.run_for_user(session, user)
    assert result.fetched == 0
    assert any("No resume data" in e for e in result.errors)


def test_retention_purge(session):
    user = _make_user(session)
    old = Listing(
        user_id=user.id, key="fb:old", title="Old Job", first_seen="2025-01-01",
    )
    session.add(old)
    session.commit()
    pipeline.run_for_user(session, user, today=dt.date(2026, 6, 9))
    keys = {l.key for l in session.query(Listing).filter_by(user_id=user.id)}
    assert "fb:old" not in keys
