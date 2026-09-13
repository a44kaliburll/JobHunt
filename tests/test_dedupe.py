import pytest

from jobhunt import dedupe
from jobhunt.scrapers.base import JobPosting


def posting(key, title, company="", location="Albany, NY", posted="", description=""):
    return JobPosting(
        key=key, title=title, company=company, location=location,
        posted=posted, description=description, source=key.split(":")[0],
    )


def test_cross_post_under_a_different_company_collapses():
    # From a real run: the same Troy NY role, once under the employer and once
    # under a board reposting it.
    merged = dedupe.collapse([
        (posting("li:4376424817", "Associate Director, Digital Lab Orchestration",
                 company="Regeneron", location="Troy, NY", posted="2026-05-21"), 12),
        (posting("li:4420251030", "Associate Director, Digital Lab Orchestration",
                 company="BioSpace", location="Troy, NY", posted="2026-05-28"), 12),
    ])

    assert len(merged) == 1
    assert merged[0].posting.company == "Regeneron"
    assert merged[0].also_posted_by == ["BioSpace"]


def test_repeated_reposts_of_one_req_collapse():
    # Also from a real run: one continuously-recruited req, three vacancy ids.
    merged = dedupe.collapse([
        (posting(key, "Design Project Manager - Continuous Recruitment",
                 company="State University Construction Fund",
                 location="Albany County, NY", posted=date), 16)
        for key, date in [
            ("nys:214159", "2026-04-16"),
            ("nys:216265", "2026-05-18"),
            ("nys:216988", "2026-05-28"),
        ]
    ])

    assert len(merged) == 1
    assert merged[0].posting.key == "nys:214159"


def test_two_employers_hiring_the_same_generic_role_stay_separate():
    merged = dedupe.collapse([
        (posting("li:1", "Project Manager", company="Acme"), 10),
        (posting("li:2", "Project Manager", company="Beta Industries"), 10),
    ])
    assert len(merged) == 2


def test_one_employer_reposting_a_generic_role_still_collapses():
    merged = dedupe.collapse([
        (posting("li:1", "Project Manager", company="Acme", posted="2026-05-01"), 10),
        (posting("li:2", "Project Manager", company="Acme", posted="2026-06-01"), 10),
    ])
    assert len(merged) == 1


def test_word_order_does_not_create_a_duplicate():
    assert dedupe.group_key("Director of Online Learning", "Albany, NY", "Acme") == \
        dedupe.group_key("Online Learning Director", "Albany, NY", "Acme")


def test_county_and_bare_city_spellings_agree():
    assert dedupe.group_key("Education Program Manager One", "Albany County, NY", "X") == \
        dedupe.group_key("Education Program Manager One", "Albany, NY", "X")


@pytest.mark.parametrize("raw", ["USA Remote", "Flexible / Remote", "Anywhere (100% Remote)"])
def test_every_flavour_of_remote_agrees(raw):
    assert dedupe.normalize_location(raw) == "remote"


def test_a_real_city_is_not_remote():
    assert dedupe.normalize_location("Albany, NY") == "albany ny"


def test_different_cities_never_merge():
    merged = dedupe.collapse([
        (posting("li:1", "Associate Director, Digital Lab Orchestration", location="Troy, NY"), 12),
        (posting("li:2", "Associate Director, Digital Lab Orchestration", location="Boston, MA"), 12),
    ])
    assert len(merged) == 2


def test_the_copy_carrying_a_description_is_kept():
    merged = dedupe.collapse([
        (posting("li:1", "Senior Instructional Designer Lead", company="Acme"), 6),
        (posting("wwr:2", "Senior Instructional Designer Lead", company="Acme",
                 description="Full description here"), 24),
    ])
    assert len(merged) == 1
    assert merged[0].posting.key == "wwr:2"
    assert merged[0].score == 24


def test_title_distinctiveness_ignores_filler_words():
    assert dedupe.is_distinctive_title("Associate Director, Digital Lab Orchestration")
    assert not dedupe.is_distinctive_title("Project Manager")
    assert not dedupe.is_distinctive_title("Senior Project Manager II")


def test_an_empty_run_collapses_to_nothing():
    assert dedupe.collapse([]) == []
