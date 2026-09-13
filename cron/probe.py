"""Report what the runner can actually reach, and what NY State Jobs looks like.

Two jobs. First, a plain reachability check, so a run that finds nothing says
why instead of quietly writing an empty digest — the failure mode that killed
the original cron. Second, a structural dump of the NY State Jobs vacancy
table, which cannot be inspected from a sandbox with no route to it, and which
supplied the highest-scoring listings the original cron ever found.
"""
from __future__ import annotations

import sys

import httpx
from bs4 import BeautifulSoup

BOARDS = {
    "RemoteOK": "https://remoteok.com/api",
    "TheMuse": "https://www.themuse.com/api/public/jobs?page=0",
    "WeWorkRemotely": "https://weworkremotely.com/remote-jobs/search?term=teacher",
    "LinkedIn": (
        "https://www.linkedin.com/jobs-guest/jobs/api/seeMoreJobPostings/search"
        "?keywords=Director+of+Online+Learning&location=Albany%2C+New+York"
        "%2C+United+States&f_TPR=r604800&start=0"
    ),
    "NYStateJobs": "https://statejobs.ny.gov/public/vacancyTable.cfm",
}

UA = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0 Safari/537.36 JobHunt/0.1"
)


def main() -> int:
    client = httpx.Client(timeout=30, headers={"User-Agent": UA}, follow_redirects=True)
    unreachable = []

    print("## Reachability\n")
    for name, url in BOARDS.items():
        try:
            resp = client.get(url)
            print(f"{name:<16} {resp.status_code}  {len(resp.content):>8} bytes")
            if resp.status_code >= 400:
                unreachable.append(name)
        except Exception as exc:
            print(f"{name:<16} FAILED  {type(exc).__name__}: {exc}")
            unreachable.append(name)

    print("\n## NY State Jobs structure\n")
    try:
        html = client.get(BOARDS["NYStateJobs"]).text
        soup = BeautifulSoup(html, "html.parser")
        tables = soup.find_all("table")
        print(f"tables: {len(tables)}, forms: {len(soup.find_all('form'))}")
        for i, table in enumerate(tables[:3]):
            rows = table.find_all("tr")
            print(f"\n-- table {i}: id={table.get('id')!r} "
                  f"class={table.get('class')!r} rows={len(rows)}")
            for row in rows[:3]:
                cells = [c.get_text(' ', strip=True)[:40] for c in row.find_all(['th', 'td'])]
                print("   ", cells)
        links = [a.get("href", "") for a in soup.select("a[href*=vacancyDetails]")][:5]
        print("\nvacancy detail links:", links or "(none on the landing page)")
    except Exception as exc:
        print(f"could not inspect: {type(exc).__name__}: {exc}")

    if unreachable:
        print(f"\n::warning::unreachable boards: {', '.join(unreachable)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
