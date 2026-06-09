from jobhunt.resume.parser import merge_profiles, parse_resume

SAMPLE = """John Smith
Albany, NY | john@example.com

Summary
Education leader with 10 years of experience in online learning and
instructional design, focused on accessibility.

Skills
- Instructional Design, Curriculum Development, LMS Administration
- Project Management | Canvas | Articulate Storyline
- WCAG, Accessibility

Experience

Director of Online Learning | Excelsior University
2019 - Present
- Led a team of 12 instructional designers and media developers.
- Implemented WCAG 2.1 accessibility standards across 300 courses.
- Managed a $1.2M annual budget for educational technology.

Instructional Designer at SUNY Albany
2014 - 2019
- Designed online courses in Canvas for 5,000+ students.

Education
M.S. Educational Technology, University at Albany

Certifications
- PMP (Project Management Professional)
- CPACC — Certified Professional in Accessibility Core Competencies
"""


def test_skills_extracted_from_taxonomy_and_section():
    parsed = parse_resume(SAMPLE)
    skills_lower = [s.lower() for s in parsed.skills]
    assert "instructional design" in skills_lower
    assert "curriculum development" in skills_lower
    assert "wcag" in skills_lower
    assert "canvas" in skills_lower
    # From the skills section verbatim, not just taxonomy:
    assert any("lms administration" == s for s in skills_lower)


def test_duties_are_action_bullets():
    parsed = parse_resume(SAMPLE)
    assert any(d.startswith("Led a team of 12") for d in parsed.duties)
    assert any("WCAG 2.1" in d for d in parsed.duties)
    # Non-bullet, non-verb lines are not duties.
    assert not any("Excelsior University" in d and "Led" not in d for d in parsed.duties)


def test_certifications_found():
    parsed = parse_resume(SAMPLE)
    certs_lower = " ".join(parsed.certifications).lower()
    assert "pmp" in certs_lower
    assert "cpacc" in certs_lower


def test_titles_found():
    parsed = parse_resume(SAMPLE)
    assert "Director of Online Learning" in parsed.titles
    assert "Instructional Designer" in parsed.titles


def test_summary_captured():
    parsed = parse_resume(SAMPLE)
    assert parsed.summary.startswith("Education leader")


def test_merge_profiles_dedupes_and_adds_extra_titles():
    p1 = parse_resume(SAMPLE).to_dict()
    p2 = parse_resume(SAMPLE).to_dict()
    merged = merge_profiles([p1, p2], extra_titles=["Director of Teaching and Learning"])
    assert merged["titles"].count("Director of Online Learning") == 1
    assert "Director of Teaching and Learning" in merged["titles"]
    assert len(merged["skills"]) == len(set(s.lower() for s in merged["skills"]))
