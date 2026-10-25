"""/v1/novels/discover: recent / popular không đổi, khách và app cũ không vỡ, không lộ danh tính, bộ đệm quét."""

import json
import threading
import time

import pytest

from services.discover_ranking import REASON_AUTHOR, REASON_CO_READERS, REASON_SOURCE
from services.novel_catalog import PROGRESS_TABLE, reader_counter
from services.user_data import purge_user_data
from tests.test_discover_fixtures import (  # noqa: F401  (catalog là fixture)
    CountingTable, LEGACY_ITEM_KEYS, PRIVATE_ID, catalog, discover, ids, reasons, shelve,
)

ALICE, BOB = "user_alice", "user_bob"
# Id đủ đặc trưng để nhận ra nếu chúng lọt vào bất kỳ phản hồi nào.
SECRETS = ("user_secret_bob_7f3a", "tk_secret_carol_91c2", "user_secret_dave_d00d")
RECENT_ORDER = ["c1", "b1", "d1", "d2", "c2", "b2", "a1", "a2"]
ALLOWED_REASONS = {"", REASON_AUTHOR, REASON_CO_READERS, REASON_SOURCE}


def _readers(table):
    shelve(table, ALICE, "a1", "b1")
    shelve(table, BOB, "a1")


def test_recent_and_popular_are_identical_for_every_kind_of_caller(catalog):
    table, _ = catalog
    _readers(table)
    callers = [{}, {"uid": ALICE}, {"uid": BOB}, {"headers": {"Authorization": "Bearer not.a.real.token"}}]
    expected = {"recent": RECENT_ORDER, "popular": ["a1", "b1", "c1", "d1", "d2", "c2", "b2", "a2"]}
    for sort, order in expected.items():
        bodies = [discover(sort, **caller).json() for caller in callers]
        assert all(body == bodies[0] for body in bodies)
        assert [novel["id"] for novel in bodies[0]["novels"]] == order
        assert bodies[0]["total"] == 8


def test_recent_and_popular_keep_the_exact_legacy_shape(catalog):
    table, _ = catalog
    _readers(table)
    for sort in ("recent", "popular", None):
        for uid in (None, ALICE):
            body = discover(sort, uid=uid).json()
            assert set(body) == {"novels", "total"}
            assert all(set(novel) == LEGACY_ITEM_KEYS for novel in body["novels"])


def test_popular_keeps_counting_every_progress_row_even_a_removed_one(catalog):
    # Hành vi cũ giữ nguyên có chủ ý: "popular" đếm người từng có dòng tiến độ, kể cả dòng đã gỡ khỏi tủ.
    table, _ = catalog
    shelve(table, ALICE, "a1")
    shelve(table, BOB, "a1", shelf="removed")
    shelve(table, "user_carol", "b1")
    assert ids(discover("popular"))[:2] == ["a1", "b1"]  # a1: 2 người, b1: 1 người


def test_old_clients_that_never_send_for_you_get_the_recent_list(catalog):
    table, _ = catalog
    _readers(table)
    assert ids(discover(None)) == RECENT_ORDER
    assert ids(discover(None, uid=ALICE, limit=30)) == RECENT_ORDER


def test_for_you_adds_fields_without_touching_the_legacy_ones(catalog):
    table, _ = catalog
    shelve(table, ALICE, "a1")
    body = discover(uid=ALICE).json()
    assert set(body) == {"novels", "total", "personalized"}
    assert all(set(novel) == LEGACY_ITEM_KEYS | {"reason"} for novel in body["novels"])
    legacy = {novel["id"]: {k: novel[k] for k in LEGACY_ITEM_KEYS} for novel in discover("recent").json()["novels"]}
    assert all({k: v for k, v in novel.items() if k != "reason"} == legacy[novel["id"]] for novel in body["novels"])


def test_every_reason_is_short_generic_vietnamese_or_empty(catalog):
    table, _ = catalog
    shelve(table, ALICE, "a1", "c2")
    shelve(table, BOB, "a1", "c1", "d1")
    shelve(table, "user_carol", "a1", "d1", "d2")  # alice có hai người cùng gu cùng giữ d1 -> đủ ngưỡng
    seen = set()
    for uid in (ALICE, BOB, None):
        seen |= set(reasons(discover(uid=uid)).values())
    assert seen <= ALLOWED_REASONS and {REASON_AUTHOR, REASON_CO_READERS} <= seen
    assert all(len(reason) <= 60 for reason in ALLOWED_REASONS)


def test_a_guest_or_a_broken_token_gets_the_recent_list_unpersonalized(catalog):
    table, _ = catalog
    shelve(table, ALICE, "a1")
    recent = discover("recent").json()
    for caller in ({}, {"headers": {"Authorization": "Bearer garbage"}}, {"headers": {"X-Auth-Token": "x.y"}}):
        res = discover(**caller)
        assert res.status_code == 200
        body = res.json()
        assert body["personalized"] is False and body["total"] == recent["total"]
        assert all(novel["reason"] == "" for novel in body["novels"])
        assert [{k: v for k, v in n.items() if k != "reason"} for n in body["novels"]] == recent["novels"]


def test_an_account_without_any_novel_gets_the_recent_list_unpersonalized(catalog):
    body = discover(uid="user_brand_new").json()
    assert body["personalized"] is False
    assert [novel["id"] for novel in body["novels"]] == RECENT_ORDER


@pytest.mark.parametrize("shared_id", ["local", "user_internal"])
def test_ids_shared_by_many_people_never_get_a_personal_list(catalog, shared_id):
    table, _ = catalog
    shelve(table, shared_id, "a1")
    assert discover(uid=shared_id).json()["personalized"] is False


def test_a_token_whose_account_no_longer_exists_is_treated_as_a_guest(catalog, strict_accounts):
    table, _ = catalog
    shelve(table, "user_deleted", "a1")
    assert discover(uid="user_deleted").json()["personalized"] is False
    shelve(table, "user_test1", "a1")  # tài khoản mặc định của sổ test vẫn tồn tại
    assert discover(uid="user_test1").json()["personalized"] is True


def test_nobody_elses_id_appears_in_any_response(catalog):
    table, _ = catalog
    for secret in SECRETS:
        shelve(table, secret, "a1", "c2", "d1")
    shelve(table, ALICE, "a1")
    for sort in ("recent", "popular", "for_you", None):
        for uid in (None, ALICE, SECRETS[0]):
            res = discover(sort, uid=uid)
            text = res.text + json.dumps(dict(res.headers))
            for secret in SECRETS[1:] if uid == SECRETS[0] else SECRETS:
                assert secret not in text


def test_for_you_is_marked_private_but_the_legacy_sorts_get_no_new_headers(catalog):
    res = discover(uid=ALICE)
    assert res.headers["cache-control"] == "private, no-store"
    assert "authorization" in res.headers["vary"].lower()
    for sort in ("recent", "popular", None):
        assert "cache-control" not in discover(sort).headers


def test_unknown_sorts_are_still_rejected(catalog):
    assert discover("random").status_code == 422
    assert discover("For_You").status_code == 422


def _scans():
    return CountingTable.selects.get(PROGRESS_TABLE, 0)


def test_one_scan_serves_every_user_and_sort_until_the_cache_is_invalidated(catalog):
    table, _ = catalog
    _readers(table)
    discover(uid=ALICE)
    for sort, uid in (("for_you", BOB), ("for_you", ALICE), ("popular", None), ("popular", BOB)):
        discover(sort, uid=uid, fresh=False)
    assert _scans() == 1
    reader_counter.invalidate()
    discover(uid=ALICE, fresh=False)
    assert _scans() == 2
    reader_counter._built_at = time.monotonic() - 61  # quá TTL 60 giây (fixture `catalog` xoá bộ đệm khi dọn)
    discover(uid=ALICE, fresh=False)
    assert _scans() == 3
    discover("recent", fresh=False)  # recent không cần số liệu người đọc
    discover(uid=None, fresh=False)  # khách không có tủ nên không có gì để tra
    assert _scans() == 3


def test_a_guest_for_you_request_never_triggers_a_progress_scan(catalog):
    discover()
    discover("recent")
    assert _scans() == 0


def test_deleting_an_account_drops_its_influence_through_the_same_invalidation_hook(catalog):
    table, _ = catalog
    shelve(table, ALICE, "a1")
    shelve(table, BOB, "a1", "c2")
    shelve(table, "user_carol", "a1", "c2")
    assert reasons(discover(uid=ALICE))["c2"] == REASON_CO_READERS
    purge_user_data(BOB)  # còn một mình carol: dưới ngưỡng nên hết lý do; bộ đệm cũ thì vẫn nói 2 người
    assert reasons(discover(uid=ALICE, fresh=False))["c2"] == ""


def test_a_failing_progress_scan_degrades_to_the_recent_list_instead_of_an_error(catalog, monkeypatch):
    table, _ = catalog
    shelve(table, ALICE, "a1")

    def broken(self, *args, **kwargs):
        raise RuntimeError("db down")

    monkeypatch.setattr(CountingTable, "select", broken)
    res = discover(uid=ALICE)
    assert res.status_code == 200 and res.json()["personalized"] is False and ids(res) == RECENT_ORDER
    assert ids(discover("popular")) == RECENT_ORDER  # không đếm được người đọc thì mọi truyện hoà 0, xếp theo độ mới


def test_malformed_progress_rows_are_ignored_not_fatal(catalog):
    table, _ = catalog
    shelve(table, ALICE, "a1")
    for row in ({"id": "j1", "novel_id": "a2"}, {"id": "j2", "user_id": ALICE}, {"id": "j3", "user_id": "", "novel_id": "b1"}):
        table(PROGRESS_TABLE).upsert(row)
    body = discover(uid=ALICE).json()
    assert body["personalized"] is True and body["novels"][0]["id"] == "a2" and "a1" not in ids(discover(uid=ALICE))


def test_an_invalidation_that_lands_during_a_scan_is_not_lost(catalog, monkeypatch):
    table, _ = catalog
    shelve(table, ALICE, "a1")
    shelve(table, BOB, "a1", "c2")
    shelve(table, "user_carol", "a1", "c2")
    scanned, release = threading.Event(), threading.Event()
    real_scan = type(reader_counter)._scan

    def scan_then_wait():
        snapshot = real_scan()  # đã đọc xong các dòng CŨ (còn bob)
        scanned.set()
        release.wait(5)
        return snapshot

    monkeypatch.setattr(type(reader_counter), "_scan", staticmethod(scan_then_wait))
    reader_counter.invalidate()
    worker = threading.Thread(target=reader_counter.snapshot)
    worker.start()
    assert scanned.wait(5)
    purge_user_data(BOB)  # xoá dòng của bob rồi invalidate, trong lúc lượt quét cũ vẫn đang chạy
    release.set()
    worker.join()
    monkeypatch.setattr(type(reader_counter), "_scan", staticmethod(real_scan))
    assert reasons(discover(uid=ALICE, fresh=False))["c2"] == ""  # chỉ còn carol: dưới ngưỡng 2 người


def test_private_imports_stay_out_of_every_sort(catalog):
    table, _ = catalog
    for uid in (ALICE, BOB):
        shelve(table, uid, PRIVATE_ID, "a1")
    for sort in ("recent", "popular", "for_you"):
        for uid in (None, ALICE, BOB):
            assert PRIVATE_ID not in ids(discover(sort, uid=uid))
