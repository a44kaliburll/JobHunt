import datetime as dt
from dataclasses import replace

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
    supports_descriptions = False

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


class DescribingScraper(Scraper):
    """A board that returns bare cards and can fill in descriptions on demand."""

    name = "DescribingBoard"
    key_prefix = "fb"
    supports_descriptions = True

    def __init__(self, postings, descriptions=None, explode=False):
        super().__init__()
        self._postings = postings
        self._descriptions = descriptions or {}
        self.explode = explode
        self.described: list[str] = []

    def search(self, query):
        return list(self._postings)

    def describe(self, posting):
        self.described.append(posting.key)
        if self.explode:
            raise RuntimeError("429 Too Many Requests")
        return self._descriptions.get(posting.key)


def _bare(key="fb:1", title="Director of Online Learning", company="Acme U",
          location="Albany, NY", posted="2026-06-01"):
    return JobPosting(
        key=key, title=title, company=company, location=location,
        url=f"https://fake.test/{key}", source="DescribingBoard", posted=posted,
    )


def test_fetching_a_description_unlocks_more_of_the_score(session, monkeypatch):
    user = _make_user(session)
    without = DescribingScraper([_bare()])
    with_desc = DescribingScraper(
        [_bare()], {"fb:1": "instructional design canvas wcag"}
    )

    monkeypatch.setattr(
        "jobhunt.pipeline.build_scrapers", lambda s, client=None: [without]
    )
    low = pipeline.run_for_user(session, user, today=dt.date(2026, 6, 9))

    session.query(Listing).delete()
    session.commit()
    monkeypatch.setattr(
        "jobhunt.pipeline.build_scrapers", lambda s, client=None: [with_desc]
    )
    high = pipeline.run_for_user(session, user, today=dt.date(2026, 6, 9))

    assert low.enriched == 0
    assert high.enriched == 1
    assert high.new_listings[0].score > low.new_listings[0].score


def test_a_board_refusing_detail_requests_degrades_to_a_warning(session, monkeypatch):
    user = _make_user(session)
    board = DescribingScraper([_bare()], explode=True)
    monkeypatch.setattr(
        "jobhunt.pipeline.build_scrapers", lambda s, client=None: [board]
    )

    result = pipeline.run_for_user(session, user, today=dt.date(2026, 6, 9))

    assert result.enriched == 0
    assert any("429" in e for e in result.errors)
    assert result.new_count == 1, "the run still produces its listings"


def test_a_cross_posted_job_becomes_one_listing(session, monkeypatch):
    user = _make_user(session)
    employer = _bare(key="fb:10", title="Associate Director, Digital Lab Orchestration")
    aggregator = replace(employer, key="fb:11", company="BioSpace", posted="2026-06-08")
    board = DescribingScraper(
        [employer, aggregator],
        {"fb:10": "instructional design", "fb:11": "instructional design"},
    )
    monkeypatch.setattr(
        "jobhunt.pipeline.build_scrapers", lambda s, client=None: [board]
    )

    result = pipeline.run_for_user(session, user, today=dt.date(2026, 6, 9))

    assert result.relevant == 2, "both copies passed scoring"
    assert result.new_count == 1, "but only one job is stored"
    assert result.duplicates == 1
    listing = session.query(Listing).filter_by(user_id=user.id).one()
    assert listing.also_posted_by == "BioSpace"


def test_a_repost_of_a_stored_job_is_not_surfaced_as_new(session, monkeypatch):
    user = _make_user(session)
    first = DescribingScraper([_bare(key="fb:20")], {"fb:20": "instructional design"})
    monkeypatch.setattr(
        "jobhunt.pipeline.build_scrapers", lambda s, client=None: [first]
    )
    pipeline.run_for_user(session, user, today=dt.date(2026, 6, 9))

    # Same job, new vacancy id.
    again = DescribingScraper([_bare(key="fb:21")], {"fb:21": "instructional design"})
    monkeypatch.setattr(
        "jobhunt.pipeline.build_scrapers", lambda s, client=None: [again]
    )
    result = pipeline.run_for_user(session, user, today=dt.date(2026, 6, 10))

    assert result.new_count == 0
    assert session.query(Listing).filter_by(user_id=user.id).count() == 1
