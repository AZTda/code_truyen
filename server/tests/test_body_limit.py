"""Giới hạn kích thước body ở tầng ASGI: chặn POST khổng lồ trước khi FastAPI gom vào RAM, nhưng không cản payload hợp lệ lớn."""

import asyncio
import json

import pytest
from fastapi.testclient import TestClient
from starlette.exceptions import HTTPException

import routes.tts as tts_routes
from server import app
from services.auth_token import issue_token
from services.body_limit import (
    DEFAULT_MAX_BODY_BYTES,
    BodySizeLimitMiddleware,
    max_body_bytes,
)

client = TestClient(app, raise_server_exceptions=False)
KB, MB = 1024, 1024 * 1024
AUTH = {"Authorization": f"Bearer {issue_token('user_body', 'body')}"}


def test_default_cap_is_256_kb_with_documented_exceptions():
    assert DEFAULT_MAX_BODY_BYTES == 256 * KB
    for path in ("/v1/tts/prefetch", "/v1/auth/login", "/v1/progress/sync", "/v1/companion/chat", "/"):
        assert max_body_bytes(path) == 256 * KB, path
    assert max_body_bytes("/v1/novels/import") == 26 * MB  # tệp truyện 25 MB + phần bao multipart
    assert max_body_bytes("/v1/novels/import/") == 26 * MB
    assert max_body_bytes("/v1/vocabulary/sync") >= 2 * MB  # lô 200 từ + 1000 log của app
    assert max_body_bytes("/v1/bookmarks/sync") >= 1 * MB
    assert max_body_bytes("/v1/english/progressive-chapter") >= 1 * MB
    assert max_body_bytes("/v1/novels/import_1a2b3c4d") == 256 * KB  # id import_* không được hưởng trần của /import


@pytest.mark.parametrize("path,payload", [
    ("/v1/tts/prefetch", {"texts": ["A" * 300 * KB]}),
    ("/v1/auth/login", {"email": "x", "password": "p" * 300 * KB}),
    ("/v1/progress/sync", {"novel_id": "n", "current_chapter": 1, "shelf_name": "s" * 300 * KB}),
])
def test_oversized_json_is_rejected_with_413_before_it_is_parsed(path, payload):
    res = client.post(path, json=payload, headers=AUTH)
    assert res.status_code == 413
    assert "quá lớn" in res.json()["detail"]
    assert res.headers["connection"] == "close"  # không chờ nốt phần body còn lại


def test_unauthenticated_oversized_body_gets_413_not_401():
    assert client.post("/v1/vocabulary/sync", content=b"x" * (5 * MB), headers={"content-type": "application/json"}).status_code == 413
    assert client.post("/v1/tts/prefetch", content=b"x" * (300 * KB), headers={"content-type": "application/json"}).status_code == 413


def test_import_accepts_files_above_the_default_cap():
    chapters = "\n".join(f"Chương {i}\n" + ("Nội dung chương số %d. " % i) * 120 for i in range(1, 90))
    data = chapters.encode("utf-8")
    assert len(data) > DEFAULT_MAX_BODY_BYTES
    res = client.post("/v1/novels/import", files={"file": ("book.txt", data, "text/plain")}, headers=AUTH)
    assert res.status_code == 200 and res.json()["total_chapters"] >= 2


def test_import_cap_still_ends_in_413(monkeypatch):
    big = b"x" * (26 * MB + 1024)  # vượt 25 MB của route và cả trần middleware
    res = client.post("/v1/novels/import", files={"file": ("huge.txt", big, "text/plain")}, headers=AUTH)
    assert res.status_code == 413


def test_import_route_still_enforces_its_own_25_mb_limit_with_a_clear_message():
    data = b"x" * (25 * MB + 1024)  # lọt qua trần middleware (26 MB) nên do chính route từ chối
    res = client.post("/v1/novels/import", files={"file": ("big.txt", data, "text/plain")}, headers=AUTH)
    assert res.status_code == 413 and "25 MB" in res.json()["detail"]


def test_realistic_vocabulary_and_bookmark_batches_are_not_blocked():
    words = [{"id": f"w{i}", "word": f"w{i}", "contextMeaningVi": "nghĩa " * 400, "updatedAt": 5} for i in range(200)]
    body = json.dumps({"words": words, "review_logs": []})
    assert len(body.encode()) > DEFAULT_MAX_BODY_BYTES
    res = client.post("/v1/vocabulary/sync", content=body, headers={**AUTH, "content-type": "application/json"})
    assert res.status_code == 200 and res.json()["words_synced"] == 200

    marks = [{"id": f"b{i}", "novel_id": "n", "chapter_number": 1, "paragraph_index": 0, "created_at": 1,
              "selected_text": "đoạn " * 200, "note": "ghi chú " * 50} for i in range(400)]
    body = json.dumps({"bookmarks": marks})
    assert len(body.encode()) > DEFAULT_MAX_BODY_BYTES
    res = client.post("/v1/bookmarks/sync", content=body, headers={**AUTH, "content-type": "application/json"})
    assert res.status_code == 200 and res.json()["applied"] == 400


def test_get_requests_and_small_posts_are_unaffected():
    assert client.get("/v1/health").status_code == 200
    assert client.post("/v1/progress/sync", json={"novel_id": "n", "current_chapter": 1}, headers=AUTH).status_code == 200


# ------------------------------------------------------------------ cắt luồng không có Content-Length (chunked)

def _drive(middleware, chunk_sizes, path="/v1/tts/prefetch", declared=None):
    """Chạy middleware với `receive` giả phát từng chunk; trả (số message đã bị lấy ra, ngoại lệ hoặc None)."""
    state = {"taken": 0}
    messages = [{"type": "http.request", "body": b"x" * size, "more_body": index < len(chunk_sizes) - 1}
                for index, size in enumerate(chunk_sizes)]

    async def receive():
        state["taken"] += 1
        return messages[state["taken"] - 1]

    async def send(message):
        pass

    headers = [(b"content-length", str(declared).encode())] if declared is not None else []
    scope = {"type": "http", "method": "POST", "path": path, "headers": headers}

    async def run():
        try:
            await middleware(scope, receive, send)
        except HTTPException as exc:
            return exc
        return None

    return state, asyncio.run(run())


def _reader_app():
    async def app_(scope, receive, send):
        while True:
            message = await receive()
            if not message.get("more_body"):
                break
        await send({"type": "http.response.start", "status": 200, "headers": []})
        await send({"type": "http.response.body", "body": b"ok"})
    return app_


def test_chunked_stream_is_cut_as_soon_as_it_passes_the_cap():
    state, error = _drive(BodySizeLimitMiddleware(_reader_app()), [100 * KB] * 10)
    assert error is not None and error.status_code == 413
    assert state["taken"] == 3  # 100 + 100 + 100 KB > 256 KB: các chunk còn lại không bị đọc


def test_chunked_stream_under_the_cap_passes_through():
    state, error = _drive(BodySizeLimitMiddleware(_reader_app()), [50 * KB] * 4)
    assert error is None and state["taken"] == 4


def test_chunked_oversize_body_gets_a_clean_413_through_the_whole_app_stack():
    """Qua cả middleware giới hạn tần suất (BaseHTTPMiddleware) và bộ parse body của FastAPI: không bị đổi thành 400."""
    chunks = [b'{"texts":["'] + [b"A" * (100 * KB)] * 20 + [b'"]}']
    taken = []

    async def receive():
        index = len(taken)
        taken.append(index)
        if index < len(chunks):
            return {"type": "http.request", "body": chunks[index], "more_body": index < len(chunks) - 1}
        return {"type": "http.disconnect"}

    sent = []

    async def send(message):
        sent.append(message)

    scope = {"type": "http", "method": "POST", "path": "/v1/tts/prefetch", "raw_path": b"/v1/tts/prefetch",
             "query_string": b"", "root_path": "", "scheme": "http", "http_version": "1.1",
             "headers": [(b"content-type", b"application/json")], "server": ("testserver", 80), "client": ("203.0.113.5", 5000)}
    asyncio.run(app(scope, receive, send))
    status = next(m["status"] for m in sent if m["type"] == "http.response.start")
    assert status == 413
    assert len(taken) <= 5  # dừng đọc ngay khi vượt trần, không đọc hết 2 MB


def test_non_http_scopes_are_not_touched():
    seen = []

    async def downstream(scope, receive, send):
        seen.append(scope["type"])

    asyncio.run(BodySizeLimitMiddleware(downstream)({"type": "lifespan"}, None, None))
    assert seen == ["lifespan"]


# ------------------------------------------------------------------ từng đoạn TTS prefetch bị giới hạn ngay lúc parse

def test_prefetch_items_are_capped_at_parse_time(monkeypatch):
    calls = []

    def fake_enqueue(plan, priority=1):
        calls.append(len(plan.text))
        return True

    monkeypatch.setattr(tts_routes, "enqueue_ahead", fake_enqueue)
    assert client.post("/v1/tts/prefetch", json={"texts": ["a" * 12_001]}).status_code == 422
    assert client.post("/v1/tts/prefetch", json={"texts": ["a" * 12_000]}).status_code == 200
    assert calls == [12_000]
