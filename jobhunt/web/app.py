"""FastAPI web UI: accounts, resume uploads, sources, listings, runs."""
from __future__ import annotations

import json
import logging
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import Depends, FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import HTMLResponse, PlainTextResponse, RedirectResponse
from fastapi.templating import Jinja2Templates
from sqlalchemy import select
from sqlalchemy.orm import Session

from .. import auth, config, db, pipeline
from ..db import JobSource, Listing, Resume, RunLog, User
from ..matching import match_bar
from ..resume.extract import UnsupportedFormatError, extract_text
from ..resume.parser import parse_resume

log = logging.getLogger(__name__)

@asynccontextmanager
async def _lifespan(app: FastAPI):
    db.init_db()
    yield


app = FastAPI(title="JobHunt", lifespan=_lifespan)
templates = Jinja2Templates(directory=str(Path(__file__).parent / "templates"))
templates.env.globals["match_bar"] = match_bar

SESSION_COOKIE = "jobhunt_session"


def get_db():
    yield from db.session_scope()


def current_user(request: Request, session: Session = Depends(get_db)) -> User | None:
    token = request.cookies.get(SESSION_COOKIE)
    if not token:
        return None
    user_id = auth.read_session_token(token)
    if user_id is None:
        return None
    return session.get(User, user_id)


def require_user(user: User | None = Depends(current_user)) -> User:
    if user is None:
        raise HTTPException(status_code=303, headers={"Location": "/login"})
    return user


def _render(request: Request, template: str, **context) -> HTMLResponse:
    context.setdefault("request", request)
    return templates.TemplateResponse(request, template, context)


# --- Auth ---


@app.get("/register", response_class=HTMLResponse)
def register_page(request: Request):
    return _render(request, "register.html")


@app.post("/register")
def register(
    email: str = Form(...),
    password: str = Form(...),
    name: str = Form(""),
    session: Session = Depends(get_db),
):
    email = email.strip().lower()
    if not email or len(password) < 8:
        raise HTTPException(400, "Email required and password must be 8+ characters.")
    if session.scalar(select(User).where(User.email == email)):
        raise HTTPException(400, "An account with that email already exists.")
    user = User(email=email, password_hash=auth.hash_password(password), name=name)
    session.add(user)
    session.commit()
    response = RedirectResponse("/", status_code=303)
    response.set_cookie(
        SESSION_COOKIE, auth.make_session_token(user.id), httponly=True,
        max_age=auth.SESSION_MAX_AGE, samesite="lax",
    )
    return response


@app.get("/login", response_class=HTMLResponse)
def login_page(request: Request):
    return _render(request, "login.html")


@app.post("/login")
def login(
    email: str = Form(...),
    password: str = Form(...),
    session: Session = Depends(get_db),
):
    user = session.scalar(select(User).where(User.email == email.strip().lower()))
    if not user or not auth.verify_password(password, user.password_hash):
        raise HTTPException(401, "Invalid email or password.")
    response = RedirectResponse("/", status_code=303)
    response.set_cookie(
        SESSION_COOKIE, auth.make_session_token(user.id), httponly=True,
        max_age=auth.SESSION_MAX_AGE, samesite="lax",
    )
    return response


@app.post("/logout")
def logout():
    response = RedirectResponse("/login", status_code=303)
    response.delete_cookie(SESSION_COOKIE)
    return response


# --- Dashboard ---


@app.get("/", response_class=HTMLResponse)
def dashboard(
    request: Request,
    user: User = Depends(require_user),
    session: Session = Depends(get_db),
):
    profile = pipeline.get_profile(session, user)
    listings = session.scalars(
        select(Listing)
        .where(Listing.user_id == user.id, Listing.status != "hidden")
        .order_by(Listing.first_seen.desc(), Listing.score.desc())
        .limit(20)
    ).all()
    last_run = session.scalar(
        select(RunLog).where(RunLog.user_id == user.id).order_by(RunLog.ran_at.desc())
    )
    resume_count = len(
        session.scalars(select(Resume.id).where(Resume.user_id == user.id)).all()
    )
    return _render(
        request, "dashboard.html",
        user=user, profile=profile, listings=listings,
        last_run=last_run, resume_count=resume_count,
    )


# --- Resumes ---


@app.get("/resumes", response_class=HTMLResponse)
def resumes_page(
    request: Request,
    user: User = Depends(require_user),
    session: Session = Depends(get_db),
):
    resumes = session.scalars(
        select(Resume).where(Resume.user_id == user.id).order_by(Resume.uploaded_at.desc())
    ).all()
    return _render(request, "resumes.html", user=user, resumes=resumes)


@app.post("/resumes/upload")
async def upload_resume(
    files: list[UploadFile] = File(...),
    user: User = Depends(require_user),
    session: Session = Depends(get_db),
):
    for upload in files:
        data = await upload.read()
        filename = upload.filename or "resume"
        try:
            text = extract_text(filename, data)
        except UnsupportedFormatError as exc:
            raise HTTPException(400, str(exc))
        parsed = parse_resume(text)
        resume = Resume(user_id=user.id, filename=filename, content_text=text)
        resume.parsed = parsed.to_dict()
        session.add(resume)
    session.commit()
    return RedirectResponse("/resumes", status_code=303)


@app.post("/resumes/{resume_id}/delete")
def delete_resume(
    resume_id: int,
    user: User = Depends(require_user),
    session: Session = Depends(get_db),
):
    resume = session.get(Resume, resume_id)
    if resume and resume.user_id == user.id:
        session.delete(resume)
        session.commit()
    return RedirectResponse("/resumes", status_code=303)


# --- Settings (locations, extra titles, threshold) ---


@app.post("/settings")
def update_settings(
    locations: str = Form(""),
    extra_titles: str = Form(""),
    min_score: int = Form(config.DEFAULT_MIN_SCORE),
    user: User = Depends(require_user),
    session: Session = Depends(get_db),
):
    db_user = session.get(User, user.id)
    db_user.locations = [l.strip() for l in locations.split(",") if l.strip()]
    db_user.extra_titles = [t.strip() for t in extra_titles.split(",") if t.strip()]
    db_user.min_score = max(0, min(config.MAX_SCORE, min_score))
    session.commit()
    return RedirectResponse("/", status_code=303)


# --- Custom sources ---


@app.get("/sources", response_class=HTMLResponse)
def sources_page(
    request: Request,
    user: User = Depends(require_user),
    session: Session = Depends(get_db),
):
    sources = session.scalars(
        select(JobSource).where(JobSource.user_id == user.id)
    ).all()
    return _render(request, "sources.html", user=user, sources=sources)


@app.post("/sources/add")
def add_source(
    name: str = Form(...),
    kind: str = Form(...),
    config_json: str = Form(...),
    user: User = Depends(require_user),
    session: Session = Depends(get_db),
):
    if kind not in {"rss", "json", "html"}:
        raise HTTPException(400, "kind must be rss, json or html")
    try:
        cfg = json.loads(config_json)
    except ValueError as exc:
        raise HTTPException(400, f"Config is not valid JSON: {exc}")
    if not isinstance(cfg, dict) or not cfg.get("url"):
        raise HTTPException(400, "Config must be a JSON object with a 'url' field.")
    source = JobSource(user_id=user.id, name=name.strip(), kind=kind)
    source.config = cfg
    session.add(source)
    session.commit()
    return RedirectResponse("/sources", status_code=303)


@app.post("/sources/{source_id}/toggle")
def toggle_source(
    source_id: int,
    user: User = Depends(require_user),
    session: Session = Depends(get_db),
):
    source = session.get(JobSource, source_id)
    if source and source.user_id == user.id:
        source.enabled = not source.enabled
        session.commit()
    return RedirectResponse("/sources", status_code=303)


@app.post("/sources/{source_id}/delete")
def delete_source(
    source_id: int,
    user: User = Depends(require_user),
    session: Session = Depends(get_db),
):
    source = session.get(JobSource, source_id)
    if source and source.user_id == user.id:
        session.delete(source)
        session.commit()
    return RedirectResponse("/sources", status_code=303)


# --- Listings & runs ---


@app.get("/listings", response_class=HTMLResponse)
def listings_page(
    request: Request,
    user: User = Depends(require_user),
    session: Session = Depends(get_db),
):
    listings = session.scalars(
        select(Listing)
        .where(Listing.user_id == user.id, Listing.status != "hidden")
        .order_by(Listing.first_seen.desc(), Listing.score.desc())
    ).all()
    return _render(request, "listings.html", user=user, listings=listings)


@app.post("/listings/{listing_id}/status")
def set_listing_status(
    listing_id: int,
    status: str = Form(...),
    user: User = Depends(require_user),
    session: Session = Depends(get_db),
):
    if status not in {"", "saved", "applied", "rejected", "hidden"}:
        raise HTTPException(400, "Invalid status")
    listing = session.get(Listing, listing_id)
    if listing and listing.user_id == user.id:
        listing.status = status
        session.commit()
    return RedirectResponse("/listings", status_code=303)


@app.post("/run")
def run_now(
    user: User = Depends(require_user),
    session: Session = Depends(get_db),
):
    db_user = session.get(User, user.id)
    result = pipeline.run_for_user(session, db_user)
    log.info("Manual run for %s: %s", db_user.email, result.funnel)
    return RedirectResponse("/listings", status_code=303)


@app.get("/reports/digest/{date}", response_class=PlainTextResponse)
def digest_report(date: str, user: User = Depends(require_user)):
    path = config.REPORTS_DIR / str(user.id) / f"{date}.md"
    if not path.exists():
        raise HTTPException(404, "No digest for that date.")
    return path.read_text(encoding="utf-8")


@app.get("/reports/current", response_class=PlainTextResponse)
def current_report(user: User = Depends(require_user)):
    path = config.REPORTS_DIR / str(user.id) / "current_listings.md"
    if not path.exists():
        raise HTTPException(404, "No working list yet — trigger a run first.")
    return path.read_text(encoding="utf-8")
