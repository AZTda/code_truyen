"""Durable, owner-scoped state for user-visible background jobs."""

from contextlib import contextmanager
from dataclasses import asdict, dataclass
from datetime import datetime, timedelta, timezone
import logging
import os
import sqlite3
import threading
import uuid
from typing import Dict, Iterator, Optional

logger = logging.getLogger("truyencloud.job_registry")


@dataclass
class Job:
    id: str
    kind: str
    owner_id: str
    status: str = "queued"
    progress: float = 0.0
    message: str = ""
    error: str = ""
    created_at: str = ""
    updated_at: str = ""


class JobRegistry:
    """SQLite-backed registry with ownership checks and automatic expiry.

    The previous implementation kept jobs only in process memory. A restart or a
    second Uvicorn worker then made an active job appear to vanish. SQLite keeps
    the small status records durable while the actual worker remains lightweight.
    """

    def __init__(self, ttl_seconds: int = 24 * 3600, db_path: Optional[str] = None):
        self.ttl_seconds = ttl_seconds
        self.db_path = db_path or os.path.join(
            os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
            "data",
            "jobs.db",
        )
        self._jobs: Dict[str, Job] = {}
        self._lock = threading.RLock()
        self._persistent = True
        self._init_db()

    @contextmanager
    def _connect(self) -> Iterator[sqlite3.Connection]:
        conn = sqlite3.connect(self.db_path, timeout=30.0)
        try:
            conn.row_factory = sqlite3.Row
            conn.execute("PRAGMA journal_mode=WAL")
            conn.execute("PRAGMA synchronous=NORMAL")
            conn.execute("PRAGMA busy_timeout=30000")
            with conn:
                yield conn
        finally:
            conn.close()

    def _init_db(self) -> None:
        try:
            parent = os.path.dirname(self.db_path)
            if parent:
                os.makedirs(parent, exist_ok=True)
            with self._connect() as conn:
                conn.execute(
                    """
                    CREATE TABLE IF NOT EXISTS background_jobs (
                        id TEXT PRIMARY KEY,
                        kind TEXT NOT NULL,
                        owner_id TEXT NOT NULL,
                        status TEXT NOT NULL,
                        progress REAL NOT NULL,
                        message TEXT NOT NULL,
                        error TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """
                )
                conn.execute(
                    "CREATE INDEX IF NOT EXISTS idx_background_jobs_created "
                    "ON background_jobs(created_at)"
                )
        except Exception:
            self._persistent = False
            logger.exception("Không khởi tạo được kho job bền vững: %s", self.db_path)

    @staticmethod
    def _row_to_job(row: Optional[sqlite3.Row]) -> Optional[Job]:
        if row is None:
            return None
        return Job(
            id=row["id"],
            kind=row["kind"],
            owner_id=row["owner_id"],
            status=row["status"],
            progress=float(row["progress"]),
            message=row["message"],
            error=row["error"],
            created_at=row["created_at"],
            updated_at=row["updated_at"],
        )

    def _read_locked(self, job_id: str) -> Optional[Job]:
        if not self._persistent:
            return self._jobs.get(job_id)
        try:
            with self._connect() as conn:
                row = conn.execute(
                    "SELECT * FROM background_jobs WHERE id = ?", (job_id,)
                ).fetchone()
            job = self._row_to_job(row)
            if job:
                self._jobs[job.id] = job
            return job
        except Exception:
            logger.exception("Không đọc được job %s", job_id)
            return self._jobs.get(job_id)

    def _write_locked(self, job: Job) -> None:
        self._jobs[job.id] = job
        if not self._persistent:
            return
        try:
            with self._connect() as conn:
                conn.execute(
                    """
                    INSERT INTO background_jobs
                        (id, kind, owner_id, status, progress, message, error, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        status = excluded.status,
                        progress = excluded.progress,
                        message = excluded.message,
                        error = excluded.error,
                        updated_at = excluded.updated_at
                    """,
                    (
                        job.id,
                        job.kind,
                        job.owner_id,
                        job.status,
                        job.progress,
                        job.message,
                        job.error,
                        job.created_at,
                        job.updated_at,
                    ),
                )
        except Exception:
            logger.exception("Không ghi được job %s", job.id)

    def create(self, kind: str, owner_id: str) -> Job:
        now = datetime.now(timezone.utc).isoformat()
        job = Job(
            id=f"job_{uuid.uuid4().hex[:16]}",
            kind=kind,
            owner_id=owner_id,
            created_at=now,
            updated_at=now,
        )
        with self._lock:
            self._purge_locked()
            self._write_locked(job)
        return job

    def update(
        self,
        job_id: str,
        *,
        status: Optional[str] = None,
        progress: Optional[float] = None,
        message: Optional[str] = None,
        error: Optional[str] = None,
    ) -> Optional[Job]:
        with self._lock:
            self._purge_locked()
            job = self._read_locked(job_id)
            if not job:
                return None
            if status is not None:
                job.status = status
            if progress is not None:
                job.progress = max(0.0, min(1.0, float(progress)))
            if message is not None:
                job.message = message
            if error is not None:
                job.error = error
            job.updated_at = datetime.now(timezone.utc).isoformat()
            self._write_locked(job)
            return job

    def get_for_owner(self, job_id: str, owner_id: str) -> Optional[dict]:
        with self._lock:
            self._purge_locked()
            job = self._read_locked(job_id)
            if not job or job.owner_id != owner_id:
                return None
            result = asdict(job)
            result.pop("owner_id", None)
            return result

    def _purge_locked(self) -> None:
        cutoff = datetime.now(timezone.utc) - timedelta(seconds=self.ttl_seconds)
        cutoff_iso = cutoff.isoformat()
        if self._persistent:
            try:
                with self._connect() as conn:
                    conn.execute(
                        "DELETE FROM background_jobs WHERE created_at < ?",
                        (cutoff_iso,),
                    )
            except Exception:
                logger.exception("Không dọn được job hết hạn")

        stale_ids = []
        for job_id, job in self._jobs.items():
            try:
                if datetime.fromisoformat(job.created_at) < cutoff:
                    stale_ids.append(job_id)
            except (TypeError, ValueError):
                stale_ids.append(job_id)
        for job_id in stale_ids:
            self._jobs.pop(job_id, None)


job_registry = JobRegistry()
