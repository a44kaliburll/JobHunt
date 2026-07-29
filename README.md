# JobHunt

An Android app that turns your resumes into a job search that runs itself.

Add as many resumes as you like. JobHunt reads them, extracts your **skills,
duties, certifications, and job titles**, and then uses exactly those to search
popular job boards — plus any job site you add yourself — every day in the
background, scoring and deduping what it finds.

Everything happens on your phone. No account, no server, no API keys, no LLM:
the whole pipeline is deterministic, which makes it free to run and identical
every time.

## How it works

```
resumes (PDF / DOCX / Markdown / TXT)   your own entries, typed in
   │  parse: skills, duties, certs, titles      │  added / removed by hand
   └──────────────────────┬─────────────────────┘
                          ▼
search profile ──► queries (title × location) fanned out to:
                     • LinkedIn (guest search)   • RemoteOK
                     • WeWorkRemotely            • The Muse
                     • + your own sites (RSS / JSON API / HTML)
   ▼
funnel: fetched → in range (location) → relevant (score ≥ threshold) → new
   ▼
Room database (dedupe by source:id, 90-day retention)
   ▼
notification for new matches + shareable Markdown digests
```

Every listing is scored out of 50 and shown as a match meter:

| Component | Max | Based on |
|---|---|---|
| Title overlap | 20 | your resume job titles vs. the listing title |
| Skills | 15 | taxonomy + your Skills section, found in the listing |
| Duties | 10 | shared vocabulary between your experience bullets and the description |
| Certifications | 5 | any of your certs mentioned |

The daily background run is handled by WorkManager, so it survives reboots and
respects Doze. You choose the hour, and whether it should wait for Wi-Fi.

## Your profile is editable

Resumes are a shortcut, not a requirement. The **Profile** tab is the real
input, and everything in it can be edited by hand:

- **Add** job titles, skills, certifications, or experience bullets directly —
  you never have to upload a document.
- **Remove** anything irrelevant. Old roles and stray skills stop generating
  searches and stop counting toward match scores.
- Add the titles you want *next*, not only the ones you have held. Titles are
  what the job boards are actually queried with.

Your edits are stored as a separate layer on top of the parsed resumes, so
adding, replacing, or deleting a resume never discards them. Entries removed
from a resume are suppressed rather than destroyed — the Profile tab lists them
under "Show removed", and one tap puts them back.

## Building the app

Requires JDK 17 and the Android SDK (Android Studio Koala or newer).

```bash
git clone https://github.com/a44kaliburll/JobHunt.git
cd JobHunt/android
./gradlew :app:assembleDebug
# APK lands in app/build/outputs/apk/debug/
```

Or just open the `android/` folder in Android Studio and hit Run.

Minimum Android 8.0 (API 26); targets Android 14 (API 34).

## Project layout

The logic that matters is deliberately kept out of the Android layer, in a
plain Kotlin module that runs — and is tested — on any JVM:

```
android/
├── core/                        # pure Kotlin, no Android APIs, 55 unit tests
│   └── src/main/kotlin/com/jobhunt/core/
│       ├── ResumeParser.kt      # skills, duties, certs, titles from resume text
│       ├── ProfileBuilder.kt    # parsed resumes + hand edits -> search profile
│       ├── Taxonomy.kt          # cross-industry skill + certification vocabulary
│       ├── Matching.kt          # query fan-out and 0–50 scoring
│       ├── Pipeline.kt          # scrape → score → dedupe → retention
│       ├── Reports.kt           # Markdown digest + working list
│       ├── ResumeText.kt        # DOCX / plain-text extraction
│       └── scrapers/            # built-in boards + user-defined source engine
└── app/                         # Android: Room, WorkManager, Compose UI
    └── src/main/java/com/jobhunt/android/
        ├── data/                # Room entities, DAOs, settings
        ├── resume/              # PDF extraction (PDFBox-Android)
        ├── work/                # daily worker + scheduler
        └── ui/                  # Compose screens
```

```bash
cd android/core && ./gradlew test    # core logic, no Android SDK needed
cd android      && ./gradlew :app:testDebugUnitTest
```

## Adding your own job sites

In the **Sources** tab, point JobHunt at any board. `{query}` is replaced with
each of your search titles, `{location}` with each of your locations. A URL
without `{query}` is fetched once per run and filtered locally.

**RSS / Atom feed**

```json
{"url": "https://example.com/jobs.rss?q={query}"}
```

**JSON API** — `list_path` finds the array, `fields` maps with dot-paths

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

**HTML page** — CSS selectors

```json
{
  "url": "https://example.com/jobs?q={query}",
  "selectors": {
    "item": "li.job", "title": ".job-title", "company": ".employer",
    "location": ".place", "url": "a", "posted": "time"
  }
}
```

## Desktop / server mode (optional)

The repository also contains the original Python implementation of the same
pipeline, for running it headless on a machine you control — as a cron job, or
as a small multi-user web app. It is independent of the Android app; use
whichever fits.

```bash
python3 -m venv .venv && .venv/bin/pip install -e ".[dev]"
.venv/bin/jobhunt add-user you@example.com --locations "Albany, NY, Remote"
.venv/bin/jobhunt upload-resume you@example.com resume.pdf
.venv/bin/jobhunt run
.venv/bin/jobhunt serve          # web UI on http://127.0.0.1:8000
```

```cron
0 7 * * * cd /path/to/JobHunt && .venv/bin/jobhunt run
```

See `jobhunt/` for that implementation; `pytest -q` runs its 19 tests.

## A note on scraping

JobHunt uses public, logged-out endpoints and fetches once a day at a gentle
pace. Boards change their markup and rate-limit aggressively, so each scraper
fails soft — a broken board becomes a warning in the digest instead of killing
the run. Respect each site's terms of service.

## License

MIT
