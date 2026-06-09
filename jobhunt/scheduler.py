"""Daily scheduler emulating the artex_job_hunt cron.

Run ``jobhunt schedule`` to keep a foreground process that fires the full
pipeline for every user once a day, or wire ``jobhunt run`` into a real
crontab — both paths share the same pipeline code.
"""
from __future__ import annotations

import logging

from apscheduler.schedulers.blocking import BlockingScheduler
from apscheduler.triggers.cron import CronTrigger

from . import config, db
from .pipeline import run_all_users

log = logging.getLogger(__name__)


def run_once() -> None:
    session = db.session()
    try:
        results = run_all_users(session)
        for result in results:
            log.info("user=%s %s", result.user_id, result.funnel)
    finally:
        session.close()


def start() -> None:
    db.init_db()
    scheduler = BlockingScheduler()
    scheduler.add_job(
        run_once,
        CronTrigger(hour=config.SCHEDULE_HOUR, minute=config.SCHEDULE_MINUTE),
        name="jobhunt-daily",
        misfire_grace_time=3600,
    )
    log.info(
        "Scheduler started; daily run at %02d:%02d",
        config.SCHEDULE_HOUR,
        config.SCHEDULE_MINUTE,
    )
    scheduler.start()
