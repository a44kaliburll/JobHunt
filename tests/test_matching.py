from jobhunt.matching import (
    build_queries,
    location_in_range,
    match_bar,
    score_posting,
)
from jobhunt.scrapers.base import JobPosting

PROFILE = {
    "titles": ["Director of Online Learning", "Instructional Designer"],
    "skills": ["instructional design", "wcag", "canvas", "project management"],
    "duties": [
        "Led a team of 12 instructional designers",
        "Implemented WCAG accessibility standards across 300 courses",
    ],
    "certifications": ["pmp", "cpacc"],
}


def test_exact_title_scores_high():
    posting = JobPosting(
        key="x:1",
        title="Director of Online Learning",
        description="Seeking experience with instructional design, WCAG, "
        "Canvas and project management. PMP preferred. You will lead a team "
        "of instructional designers and implement accessibility standards "
        "across courses.",
    )
    breakdown = score_posting(posting, PROFILE)
    assert breakdown.title == 20
    assert breakdown.skills == 12  # 4 skills x 3
    assert breakdown.certifications == 5
    assert breakdown.duties > 0
    assert breakdown.total <= 50


def test_unrelated_posting_scores_low():
    posting = JobPosting(key="x:2", title="Forklift Operator", description="Warehouse.")
    assert score_posting(posting, PROFILE).total <= 2


def test_match_bar_format():
    assert match_bar(19) == "[████░░░░░░] 38% (19/50)"
    assert match_bar(50) == "[██████████] 100% (50/50)"
    assert match_bar(0) == "[░░░░░░░░░░] 0% (0/50)"


def test_location_in_range():
    assert location_in_range("Albany, NY", ["Albany, NY"])
    assert location_in_range("Greater Albany Area", ["Albany, NY"])
    assert location_in_range("USA Remote", ["Remote"])
    assert location_in_range("Flexible / Remote", ["Remote"])
    assert not location_in_range("San Francisco, CA", ["Albany, NY"])
    assert location_in_range("Anywhere At All", [])  # no prefs = pass


def test_build_queries_fans_titles_by_locations():
    queries = build_queries(PROFILE, ["Albany, NY", "Remote"])
    combos = {(q.keywords, q.location) for q in queries}
    assert ("Director of Online Learning", "Albany, NY") in combos
    assert ("Instructional Designer", "Remote") in combos


def test_build_queries_falls_back_to_skills():
    queries = build_queries({"titles": [], "skills": ["welding", "cnc"]}, [])
    assert any(q.keywords == "welding" for q in queries)
