"""Deterministic resume parser.

Extracts a structured profile from resume plain text:

- skills: matched against the taxonomy + anything under a Skills section
- duties: bullet lines that start with an action verb (experience bullets)
- certifications: lines under a Certifications/Licenses section + known
  certification phrases found anywhere
- titles: job titles from the experience section (used to build job-board
  search queries, exactly like the original artex_job_hunt cron did)
- summary: the opening summary/objective paragraph, if present

No LLM required, so runs are free, fast, and reproducible. The output of
several resumes is merged by ``merge_profiles`` into one search profile.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field

from .taxonomy import KNOWN_CERTIFICATIONS, KNOWN_SKILLS

SECTION_ALIASES: dict[str, tuple[str, ...]] = {
    "summary": ("summary", "professional summary", "profile", "objective",
                "career objective", "about", "about me"),
    "skills": ("skills", "technical skills", "core competencies",
               "key skills", "areas of expertise", "competencies",
               "skills & abilities", "core skills", "highlights"),
    "experience": ("experience", "work experience", "professional experience",
                   "employment", "employment history", "work history",
                   "relevant experience"),
    "education": ("education", "education & training", "academic background"),
    "certifications": ("certifications", "certificates", "licenses",
                       "licenses & certifications", "certifications & licenses",
                       "credentials", "professional certifications"),
    "projects": ("projects", "selected projects", "personal projects"),
}

ACTION_VERBS = (
    "managed", "led", "developed", "designed", "created", "built",
    "implemented", "coordinated", "directed", "supervised", "trained",
    "taught", "facilitated", "organized", "launched", "improved",
    "increased", "reduced", "streamlined", "negotiated", "analyzed",
    "evaluated", "assessed", "maintained", "administered", "oversaw",
    "produced", "authored", "wrote", "presented", "delivered", "mentored",
    "recruited", "hired", "budgeted", "planned", "executed", "established",
    "founded", "spearheaded", "collaborated", "partnered", "supported",
    "resolved", "monitored", "audited", "researched", "tested", "deployed",
    "automated", "migrated", "optimized", "engineered", "architected",
    "drafted", "scheduled", "tracked", "processed", "operated", "installed",
    "repaired", "inspected", "performed", "provided", "ensured", "achieved",
)

BULLET_RE = re.compile(r"^\s*[-*•·▪◦‣–—]\s+(.*)$")
# "Director of X | Acme Corp" / "Director of X, Acme Corp — 2019-2023"
DATE_RANGE_RE = re.compile(
    r"(19|20)\d{2}\s*[-–—to]+\s*((19|20)\d{2}|present|current)", re.IGNORECASE
)
TITLE_LINE_RE = re.compile(
    r"^(?P<title>[A-Z][A-Za-z0-9&/().'’+ -]{2,60}?)"
    r"(\s*[|,@–—-]\s*|\s+at\s+)(?P<rest>.+)$"
)
TITLE_WORDS = (
    "director", "manager", "coordinator", "specialist", "engineer",
    "developer", "designer", "analyst", "consultant", "teacher", "instructor",
    "professor", "administrator", "assistant", "associate", "lead", "head",
    "officer", "supervisor", "technician", "nurse", "therapist", "counselor",
    "architect", "scientist", "librarian", "writer", "editor", "producer",
    "president", "vice president", "principal", "superintendent", "dean",
    "chief", "founder", "owner", "representative", "agent", "advisor",
    "strategist", "trainer", "facilitator", "planner", "buyer", "recruiter",
)


@dataclass
class ParsedResume:
    skills: list[str] = field(default_factory=list)
    duties: list[str] = field(default_factory=list)
    certifications: list[str] = field(default_factory=list)
    titles: list[str] = field(default_factory=list)
    summary: str = ""

    def to_dict(self) -> dict:
        return {
            "skills": self.skills,
            "duties": self.duties,
            "certifications": self.certifications,
            "titles": self.titles,
            "summary": self.summary,
        }


def _normalize_heading(line: str) -> str | None:
    """Return the canonical section name if this line is a section heading."""
    stripped = line.strip().strip(":#").strip()
    if not stripped or len(stripped) > 45:
        return None
    lowered = stripped.lower()
    for canonical, aliases in SECTION_ALIASES.items():
        if lowered in aliases:
            return canonical
    return None


def split_sections(text: str) -> dict[str, list[str]]:
    """Split resume text into canonical sections; unmatched lines go to 'body'."""
    sections: dict[str, list[str]] = {"body": []}
    current = "body"
    for raw_line in text.splitlines():
        heading = _normalize_heading(raw_line)
        if heading:
            current = heading
            sections.setdefault(current, [])
            continue
        sections.setdefault(current, []).append(raw_line.rstrip())
    return sections


def _find_known_phrases(text: str, phrases: tuple[str, ...]) -> list[str]:
    lowered = text.lower()
    found: list[str] = []
    for phrase in phrases:
        pattern = r"(?<![\w+#])" + re.escape(phrase) + r"(?![\w+#])"
        if re.search(pattern, lowered):
            found.append(phrase)
    return found


def _skills_from_section(lines: list[str]) -> list[str]:
    """Parse a Skills section: comma/pipe/bullet separated short phrases."""
    skills: list[str] = []
    for line in lines:
        m = BULLET_RE.match(line)
        content = m.group(1) if m else line
        # "Category: a, b, c" -> keep only the list part
        if ":" in content:
            content = content.split(":", 1)[1]
        for part in re.split(r"[,|;•·/]| {3,}", content):
            part = part.strip().strip(".").strip()
            if 1 < len(part) <= 40 and not DATE_RANGE_RE.search(part):
                skills.append(part)
    return skills


def _duties_from_lines(lines: list[str]) -> list[str]:
    duties: list[str] = []
    for line in lines:
        m = BULLET_RE.match(line)
        candidate = (m.group(1) if m else line).strip()
        if not candidate or len(candidate) < 12:
            continue
        first_word = re.split(r"[\s,]", candidate, 1)[0].lower()
        if m or first_word in ACTION_VERBS:
            if first_word in ACTION_VERBS or m:
                duties.append(candidate.rstrip("."))
    return duties


def _looks_like_title(text: str) -> bool:
    lowered = text.lower()
    return any(re.search(rf"\b{re.escape(w)}\b", lowered) for w in TITLE_WORDS)


def _titles_from_experience(lines: list[str]) -> list[str]:
    titles: list[str] = []
    for line in lines:
        line = line.strip()
        if not line or BULLET_RE.match(line):
            continue
        # "Title | Company" or "Title at Company" or "Title, Company"
        m = TITLE_LINE_RE.match(line)
        if m and _looks_like_title(m.group("title")):
            titles.append(m.group("title").strip())
            continue
        # Bare title line followed by company/date lines
        if _looks_like_title(line) and len(line) <= 60 and not DATE_RANGE_RE.search(line):
            titles.append(line)
    return titles


def _certifications_from_section(lines: list[str]) -> list[str]:
    certs: list[str] = []
    for line in lines:
        m = BULLET_RE.match(line)
        content = (m.group(1) if m else line).strip().rstrip(".")
        if 2 < len(content) <= 120:
            certs.append(content)
    return certs


def _dedupe_keep_order(items: list[str]) -> list[str]:
    seen: set[str] = set()
    result: list[str] = []
    for item in items:
        key = item.lower().strip()
        if key and key not in seen:
            seen.add(key)
            result.append(item.strip())
    return result


def parse_resume(text: str) -> ParsedResume:
    sections = split_sections(text)
    full_text = text

    skills = _find_known_phrases(full_text, KNOWN_SKILLS)
    if "skills" in sections:
        skills += _skills_from_section(sections["skills"])

    certifications = _find_known_phrases(full_text, KNOWN_CERTIFICATIONS)
    if "certifications" in sections:
        certifications += _certifications_from_section(sections["certifications"])

    experience_lines = sections.get("experience", [])
    duty_lines = experience_lines if experience_lines else sections.get("body", [])
    duties = _duties_from_lines(duty_lines)

    titles = _titles_from_experience(experience_lines or sections.get("body", []))

    summary = ""
    if "summary" in sections:
        summary = " ".join(l.strip() for l in sections["summary"] if l.strip())

    return ParsedResume(
        skills=_dedupe_keep_order(skills),
        duties=_dedupe_keep_order(duties),
        certifications=_dedupe_keep_order(certifications),
        titles=_dedupe_keep_order(titles),
        summary=summary[:1000],
    )


def merge_profiles(parsed_list: list[dict], extra_titles: list[str] | None = None) -> dict:
    """Merge several parsed resumes into one search profile for the pipeline."""
    merged = ParsedResume()
    for parsed in parsed_list:
        merged.skills += parsed.get("skills", [])
        merged.duties += parsed.get("duties", [])
        merged.certifications += parsed.get("certifications", [])
        merged.titles += parsed.get("titles", [])
        if not merged.summary and parsed.get("summary"):
            merged.summary = parsed["summary"]
    merged.titles += list(extra_titles or [])
    return ParsedResume(
        skills=_dedupe_keep_order(merged.skills),
        duties=_dedupe_keep_order(merged.duties),
        certifications=_dedupe_keep_order(merged.certifications),
        titles=_dedupe_keep_order(merged.titles),
        summary=merged.summary,
    ).to_dict()
