"""Ghi tiến độ làm "Dành cho bạn" cập nhật ngay khi tủ sách đổi, nhưng không quét lại mỗi lần lật chương."""

import pytest

from services.discover_ranking import REASON_AUTHOR
from services.novel_catalog import reader_counter
from tests.test_discover_fixtures import (  # noqa: F401  (catalog là fixture)
    bearer, catalog, client, discover, ids, reasons,
)

ALICE = "user_alice"


@pytest.fixture
def rebuilds(monkeypatch):
    """Danh sách ghi lại mỗi lần bộ đệm người đọc phải quét lại bảng tiến độ."""
    real_scan = type(reader_counter)._scan
    calls = []

    def counting_scan():
        calls.append(1)
        return real_scan()

    monkeypatch.setattr(type(reader_counter), "_scan", staticmethod(counting_scan))
    return calls


def sync(uid, novel_id, chapter=1, shelf="reading", **extra):
    return client.post("/v1/progress/sync", headers=bearer(uid), json={
        "novel_id": novel_id, "current_chapter": chapter, "scroll_position": 0.0, "shelf_name": shelf, **extra})


def sync_ok(*args, **kwargs):
    res = sync(*args, **kwargs)
    assert res.status_code == 200 and res.json()["status"] == "success", res.text
    return res


def test_a_novel_just_added_through_sync_personalises_the_next_request_without_waiting_for_the_cache(catalog):
    cold = discover(uid=ALICE)  # dựng bộ đệm khi alice chưa có truyện nào
    assert cold.json()["personalized"] is False

    sync_ok(ALICE, "a1")
    warm = discover(uid=ALICE, fresh=False)  # không xoá bộ đệm bằng tay: phải do chính lượt ghi tiến độ làm

    assert warm.json()["personalized"] is True
    assert ids(warm)[0] == "a2" and reasons(warm)["a2"] == REASON_AUTHOR
    assert "a1" not in ids(warm)


def test_removing_a_novel_through_sync_returns_it_to_the_candidates_at_once(catalog):
    sync_ok(ALICE, "a1")
    assert "a1" not in ids(discover(uid=ALICE, fresh=False))

    sync_ok(ALICE, "a1", chapter=2, shelf="removed")
    assert "a1" in ids(discover(uid=ALICE, fresh=False))


def test_turning_pages_or_moving_shelves_keeps_the_cache(catalog, rebuilds):
    sync_ok(ALICE, "a1")
    discover(uid=ALICE, fresh=False)  # dựng bộ đệm với a1 đã trên kệ
    built = len(rebuilds)

    sync_ok(ALICE, "a1", chapter=2)
    sync_ok(ALICE, "a1", chapter=3, shelf="completed")
    discover(uid=ALICE, fresh=False)

    assert len(rebuilds) == built


def test_a_stale_sync_changes_nothing_and_keeps_the_cache(catalog, rebuilds):
    sync_ok(ALICE, "a1")
    discover(uid=ALICE, fresh=False)
    built = len(rebuilds)

    stale = sync(ALICE, "a1", chapter=9, shelf="removed", client_updated_at=1)
    assert stale.json()["status"] == "stale"
    discover(uid=ALICE, fresh=False)

    assert len(rebuilds) == built
