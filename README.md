# JobHunt

Self-hosted, multi-user job hunt automation. Upload as many resumes as you
like, let JobHunt extract your **skills, duties, certifications, and job
titles**, and it will scrape matching jobs from popular job boards — plus any
job site you add yourself — on a daily schedule.

JobHunt is a generalized, anyone-can-run version of a personal
`artex_job_hunt` cron pipeline: a deterministic (no LLM, no API keys)
scrape → score → dedupe → digest loop.

## How it works

```
resumes (PDF/DOCX/MD/TXT)
   │  parse: skills, duties, certifications, titles
   ▼
search profile ──► queries (title × location) fanned out to:
                     • LinkedIn (guest search)   • RemoteOK
                     • WeWorkRemotely            • The Muse
                     • + your custom sources (RSS / JSON API / HTML)
   ▼
funnel: fetched → in range (location) → relevant (score ≥ threshold) → new
   ▼
listings DB (dedupe by source:id, 90-day retention)
   ▼
reports/<user>/YYYY-MM-DD.md   ← daily digest (funnel, TL;DR, top matches)
reports/<user>/current_listings.md  ← rolling working list with NEW markers
```

Every listing gets a deterministic match score out of 50, rendered as
`[████░░░░░░] 38% (19/50)`:

| Component | Max | Based on |
|---|---|---|
| Title overlap | 20 | your resume job titles vs. the listing title |
| Skills | 15 | taxonomy + your Skills section, found in the listing |
| Duties | 10 | shared vocabulary between your experience bullets and the description |
| Certifications | 5 | any of your certs mentioned |

## Quick start

```bash
git clone <this repo> && cd JobHunt
python3 -m venv .venv && .venv/bin/pip install -e ".[dev]"

# Web UI (register, upload resumes, add sources, run, browse matches)
.venv/bin/jobhunt serve            # http://127.0.0.1:8000

# Or do everything from the CLI
.venv/bin/jobhunt add-user you@example.com --locations "Albany, NY, Remote"
.venv/bin/jobhunt upload-resume you@example.com resume.pdf old_resume.docx
.venv/bin/jobhunt run --email you@example.com
cat data/reports/1/current_listings.md
```

### Scheduling (emulating the original cron)

Either keep the built-in scheduler running (daily at 07:00 by default):

```bash
jobhunt schedule
```

…or use a real crontab, which runs the identical pipeline:

```cron
0 7 * * * cd /path/to/JobHunt && .venv/bin/jobhunt run
```

## Adding your own job sites

Any user can add custom sources in the web UI (**Sources**) — no code needed.
`{query}` is replaced with each search title and `{location}` with each
preferred location.

**RSS/Atom feed**

```json
{"url": "https://example.com/jobs.rss?q={query}"}
```

**JSON API** (dot-paths into the response)

```json
{
  "url": "https://example.com/api/jobs?q={query}&where={location}",
  "list_path": "results",
  "fields": {
    "id": "id", "title": "name", "company": "company.name",
    "location": "location", "url": "links.apply", "posted": "published"
  }
}
```

**HTML page** (CSS selectors)

```json
{
  "url": "https://example.com/jobs?q={query}",
  "selectors": {
    "item": "li.job", "title": ".job-title", "company": ".employer",
    "location": ".place", "url": "a", "posted": "time"
  }
}
```

If the URL has no `{query}` placeholder, the source is fetched once per run
and filtered against your keywords locally.

## Configuration

All optional, via environment variables:

| Variable | Default | Purpose |
|---|---|---|
| `JOBHUNT_DATA_DIR` | `data/` | DB, uploads, reports |
| `JOBHUNT_DATABASE_URL` | sqlite in data dir | any SQLAlchemy URL |
| `JOBHUNT_RETENTION_DAYS` | `90` | listing retention |
| `JOBHUNT_MIN_SCORE` | `6` | default relevance threshold (0–50) |
| `JOBHUNT_SCHEDULE_HOUR` / `_MINUTE` | `7` / `0` | daily run time |
| `JOBHUNT_SECRET_KEY` | auto-generated | session cookie signing |

## Development

```bash
.venv/bin/python -m pytest        # 19 tests: parser, scoring, scrapers, pipeline
```

Project layout:

```
jobhunt/
├── resume/        # text extraction (pdf/docx/md/txt) + deterministic parser
├── scrapers/      # built-in boards + user-defined source engine
├── matching.py    # query fan-out + 0–50 scoring
├── pipeline.py    # scrape → score → dedupe → retention → reports
├── reports.py     # daily digest + current_listings.md (artex format)
├── scheduler.py   # daily APScheduler loop
├── web/           # FastAPI UI (accounts, uploads, sources, listings)
└── cli.py         # init-db / add-user / upload-resume / run / serve / schedule
```

## A note on scraping

JobHunt only uses public, logged-out endpoints and fetches at a gentle,
once-a-day cadence. Job boards change their markup and rate-limit
aggressively; individual scrapers fail soft (a warning in the digest) so one
broken board never kills your run. Respect each site's terms of service.
