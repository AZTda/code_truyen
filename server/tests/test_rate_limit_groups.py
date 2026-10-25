"""Phân nhóm giới hạn tần suất: truyện import_*, IPv6 /64, route quản trị ngoài /v1/admin, recrawl, chấm phát âm."""

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from starlette.requests import Request

from config import settings
from server import app
from services import rate_limit
from services.auth_token import issue_token
from services.rate_limit import client_key, group_for_path

ADMIN_KEY = "rate-limit-admin-key-for-pytest-only-0123456789"
client = TestClient(app, raise_server_exceptions=False)


@pytest.fixture(autouse=True)
def _env(monkeypatch):
    monkeypatch.setenv("ADMIN_KEY", ADMIN_KEY)
    monkeypatch.delenv("ADMIN_SECRET", raising=False)
    monkeypatch.setattr(settings, "TRUSTED_PROXY_IPS", "127.0.0.1,::1")


def _request(peer, headers=None):
    raw = [(k.lower().encode(), v.encode()) for k, v in (headers or {}).items()]
    return Request({"type": "http", "method": "GET", "path": "/", "headers": raw, "client": (peer, 5000)})


# ------------------------------------------------------------------ truyện import_* không bị coi là "scrape"

@pytest.mark.parametrize("path", [
    "/v1/novels/import_1a2b3c4d",
    "/v1/novels/import_1a2b3c4d/chapters/5",
    "/v1/novels/import_1a2b3c4d/glossary",
    "/v1/novels/importer",
    "/v1/novels/search_results_book",
    "/v1/novels/searching/chapters/1",
])
def test_novel_ids_that_merely_start_with_import_or_search_stay_in_default_bucket(path):
    assert group_for_path(path) == "default"


@pytest.mark.parametrize("path", [
    "/v1/novels/import", "/v1/novels/import/", "/v1/novels/search", "/v1/novels/search/", "/v1/scrape",
])
def test_the_import_search_and_scrape_endpoints_themselves_remain_scrape(path):
    assert group_for_path(path) == "scrape"
    assert group_for_path(path + "x") == "default"


def test_reading_an_imported_novel_is_not_throttled_like_a_scrape():
    detail = [client.get("/v1/novels/import_abcd1234").status_code for _ in range(16)]
    chapters = [client.get(f"/v1/novels/import_abcd1234/chapters/{n}").status_code for n in range(1, 17)]
    assert 429 not in detail and 429 not in chapters  # trước đây: 10 yêu cầu đầu qua, từ yêu cầu 11 bị 429


# ------------------------------------------------------------------ IPv6 gom theo /64

def test_client_key_groups_ipv6_addresses_by_slash_64():
    inside = [client_key(_request("127.0.0.1", {"CF-Connecting-IP": f"2001:db8:abcd:12::{n:x}"})) for n in (1, 2, 0xffff)]
    assert len(set(inside)) == 1
    assert client_key(_request("127.0.0.1", {"CF-Connecting-IP": "2001:db8:abcd:13::1"})) != inside[0]
    # Peer IPv6 trực tiếp (không qua proxy tin cậy) và chặng X-Forwarded-For cũng vậy
    assert client_key(_request("2001:db8:1::1")) == client_key(_request("2001:db8:1::ffff"))
    xff = [client_key(_request("127.0.0.1", {"X-Forwarded-For": f"2001:db8:9:9::{n}"})) for n in (1, 2)]
    assert xff[0] == xff[1]
    # IPv4 và IPv4 nhúng trong IPv6 giữ nguyên
    assert client_key(_request("127.0.0.1", {"CF-Connecting-IP": "203.0.113.9"})) == "203.0.113.9"
    assert client_key(_request("127.0.0.1", {"CF-Connecting-IP": "::ffff:203.0.113.9"})) == "203.0.113.9"


def test_login_throttle_survives_rotating_addresses_inside_one_slash_64():
    proxied = TestClient(app, client=("127.0.0.1", 50000), raise_server_exceptions=False)
    codes = [
        proxied.post("/v1/auth/login", json={"email": "bob", "password": f"guess{i}"},
                     headers={"CF-Connecting-IP": f"2001:db8:abcd:12::{i + 1:x}"}).status_code
        for i in range(30)
    ]
    assert 429 in codes  # trước đây: 30 lần đoán, không lần nào bị chặn


# ------------------------------------------------------------------ route quản trị nằm ngoài /v1/admin

@pytest.mark.parametrize("method,path", [
    ("POST", "/v1/app/version"),
    ("POST", "/v1/settings/models/refresh"),
    ("POST", "/v1/settings/models/select"),
    ("POST", "/v1/settings/profile"),
    ("GET", "/v1/health/deep"),
    ("DELETE", "/v1/english/cache"),
])
def test_admin_guarded_routes_outside_v1_admin_use_the_admin_bucket(method, path):
    assert group_for_path(path, "", method) == "admin"


def test_public_reads_next_to_admin_routes_are_not_throttled_as_admin():
    assert group_for_path("/v1/settings/profile", "", "GET") == "default"
    assert group_for_path("/v1/settings/models", "", "GET") == "default"
    assert group_for_path("/v1/health", "", "GET") == "default"
    assert group_for_path("/v1/english/cache/stats", "", "GET") == "ai"


def test_admin_key_guessing_on_health_deep_is_throttled():
    codes = [client.get("/v1/health/deep", headers={"X-Admin-Key": f"wrong-key-{i:040d}"}).status_code for i in range(20)]
    assert codes[0] == 401 and 429 in codes  # trước đây: 150 lần đoán liên tiếp không lần nào bị chặn


# ------------------------------------------------------------------ recrawl và check-updates

CHAPTER = "/v1/novels/n1/chapters/1"


@pytest.mark.parametrize("query", [
    "recrawl=true", "recrawl=TRUE", "recrawl=1", "recrawl=yes", "recrawl=on", "recrawl=t", "recrawl=y",
    "recrawl=%74rue", "recrawl=tru%65", "x=1&recrawl=on", "recrawl=false&recrawl=on", "%72ecrawl=1",
])
def test_every_truthy_recrawl_spelling_uses_the_ai_bucket(query):
    assert group_for_path(CHAPTER, query) == "ai"


@pytest.mark.parametrize("query", [
    "recrawl=false", "recrawl=0", "recrawl=off", "recrawl=no", "recrawl=", "recrawl", "recrawlx=1", "other=recrawl=1",
])
def test_non_truthy_recrawl_values_stay_in_default_bucket(query):
    assert group_for_path(CHAPTER, query) == "default"


def test_recrawl_classification_matches_how_fastapi_parses_the_bool():
    probe = FastAPI()

    @probe.get("/p")
    def read(recrawl: bool = False):
        return {"recrawl": recrawl}

    probe_client = TestClient(probe)
    spellings = ["true", "True", "TRUE", "1", "yes", "Yes", "on", "ON", "t", "T", "y", "Y",
                 "false", "0", "no", "off", "f", "n", "", "2", "maybe", "truee", "%74", "tru%65"]
    for spelling in spellings:
        res = probe_client.get(f"/p?recrawl={spelling}")
        framework_says_true = res.status_code == 200 and res.json()["recrawl"] is True
        if framework_says_true:
            assert rate_limit.wants_recrawl(f"recrawl={spelling}"), spelling


def test_check_updates_of_one_novel_is_a_scrape_but_the_shelf_batch_is_not():
    assert group_for_path("/v1/novels/abc/check-updates", "", "POST") == "scrape"
    assert group_for_path("/v1/novels/import_1a2b3c4d/check-updates", "", "POST") == "scrape"
    assert group_for_path("/v1/novels/updates/check-batch", "", "POST") == "default"
    codes = [client.post("/v1/novels/abc/check-updates").status_code for _ in range(12)]
    assert codes[:10] == [401] * 10 and codes[10] == 429


# ------------------------------------------------------------------ chấm phát âm: hạn mức riêng theo người dùng

def test_pronunciation_has_its_own_small_per_user_limit():
    url = "/v1/english/pronunciation/evaluate"
    assert group_for_path(url, "", "POST") == "pronunciation"
    first = {"Authorization": f"Bearer {issue_token('user_pron_a', 'a')}"}
    second = {"Authorization": f"Bearer {issue_token('user_pron_b', 'b')}"}
    body = {"word": "hello", "spoken_text": "hello"}
    codes = [client.post(url, headers=first, json=body).status_code for _ in range(17)]
    assert codes[:15] == [200] * 15 and codes[-1] == 429
    assert client.post(url, headers=second, json=body).status_code == 200  # người khác không bị ảnh hưởng
    # Không dùng chung xô "ai": tra từ AI của chính người đó vẫn đi qua
    assert group_for_path("/v1/english/explain-word", "", "POST") == "ai"


def test_using_the_admin_page_is_not_throttled_like_a_login(monkeypatch):
    """Quản trị viên có khoá đúng mở trang nhiều lần (mỗi lần 2-4 yêu cầu) không bị 429; khoá sai vẫn bị chặn sớm."""
    key = "k" * 16 + "test-admin-key-for-rate-limit-0123456789"
    monkeypatch.setenv("ADMIN_KEY", key)
    proxied = TestClient(app, client=("127.0.0.1", 50000), raise_server_exceptions=False)
    good = {"CF-Connecting-IP": "203.0.113.77", "X-Admin-Key": key}
    codes = [proxied.get("/v1/admin/users", headers=good).status_code for _ in range(24)]
    assert codes.count(200) == 24
    bad = {"CF-Connecting-IP": "203.0.113.78", "X-Admin-Key": "wrong-" + "x" * 40}
    wrong = [proxied.get("/v1/admin/users", headers=bad).status_code for _ in range(12)]
    assert wrong[0] == 401 and 429 in wrong
    # Lượt sai từ IP khác không làm quản trị viên thật bị chặn
    assert proxied.get("/v1/admin/users", headers=good).status_code == 200
