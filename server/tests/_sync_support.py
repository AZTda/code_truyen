"""Tiện ích dùng chung cho các test đồng bộ (tiến độ / bookmark / từ vựng)."""

import time

import pytest
from fastapi.testclient import TestClient

from server import app
from services.auth_token import issue_token

client = TestClient(app, raise_server_exceptions=False)
YEAR_MS = 365 * 24 * 3600 * 1000
MINUTE_MS = 60 * 1000


def bearer(uid, name):
    return {"Authorization": f"Bearer {issue_token(uid, name)}"}


class Clock:
    def __init__(self, start):
        self.t = start

    def __call__(self):
        return self.t

    def advance(self, ms):
        self.t += ms


@pytest.fixture
def clock(monkeypatch):
    """Đồng hồ server giả, đóng băng cho tới khi test chủ động tua."""
    fake = Clock(int(time.time() * 1000))
    for target in ("services.sync_merge", "routes.bookmarks", "routes.progress", "routes.vocabulary_sync"):
        monkeypatch.setattr(f"{target}.now_ms", fake)
    return fake


def bm(bid, **kw):
    item = {"id": bid, "novel_id": "n1", "chapter_number": 2, "paragraph_index": 3, "created_at": 1, "note": "x"}
    item.update(kw)
    return item


def push_bookmarks(headers, *items):
    return client.post("/v1/bookmarks/sync", headers=headers, json={"bookmarks": list(items)})


def pull_bookmarks(headers, since=0):
    return client.get(f"/v1/bookmarks?since={since}", headers=headers)


def push_words(headers, words=(), logs=()):
    return client.post("/v1/vocabulary/sync", headers=headers, json={"words": list(words), "review_logs": list(logs)})


def pull_vocab(headers, since=0):
    return client.get(f"/v1/vocabulary/sync?since={since}", headers=headers).json()
