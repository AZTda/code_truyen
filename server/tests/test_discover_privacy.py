"""for_you không được để một tài khoản đọc ra tủ sách của tài khoản khác, kể cả bằng tài khoản dò."""

import pytest

from services.discover_ranking import REASON_CO_READERS
from tests.test_discover_fixtures import (  # noqa: F401  (catalog là fixture)
    bearer, catalog, client, discover, ids, reasons, shelve,
)

PROBE, VICTIM, TWIN = "user_probe", "user_victim", "user_twin"


def sync(uid, novel_id):
    body = {"novel_id": novel_id, "current_chapter": 1}
    assert client.post("/v1/progress/sync", headers=bearer(uid), json=body).status_code == 200


def co_reader_hits(res):
    return [nid for nid, reason in reasons(res).items() if reason == REASON_CO_READERS]


def test_a_probe_holding_one_rare_novel_learns_nothing_about_the_person_who_also_holds_it(catalog):
    table, _ = catalog
    shelve(table, VICTIM, "a1", "b2", "c1", "d2")  # d2 hiếm: chỉ victim giữ
    sync(PROBE, "d2")  # đồng bộ tiến độ không kiểm truyện có tồn tại: ai cũng nạp được truyện bất kỳ vào tủ của mình
    res = discover(uid=PROBE)
    assert co_reader_hits(res) == []
    # Đúng thứ tự của một người chỉ có d2 (tác giả Tô Hạ, nguồn uukanshu): tủ của victim không làm đổi gì.
    assert ids(res) == ["d1", "c1", "b1", "c2", "b2", "a1", "a2"]
    assert discover(uid=PROBE).json() == res.json()


def test_the_like_minded_signal_needs_two_readers_before_it_shows_up(catalog):
    table, _ = catalog
    shelve(table, PROBE, "a1")
    shelve(table, VICTIM, "a1", "c2")
    assert reasons(discover(uid=PROBE))["c2"] == ""  # một mình victim: con số 1 chính là tủ của victim
    shelve(table, TWIN, "a1", "c2")
    assert reasons(discover(uid=PROBE))["c2"] == REASON_CO_READERS


@pytest.mark.parametrize("shared_id", ["local", "user_internal"])
def test_a_shared_id_is_not_a_person_so_it_cannot_make_one_reader_look_like_two(catalog, shared_id):
    table, _ = catalog
    shelve(table, PROBE, "a1")
    shelve(table, VICTIM, "a1", "c2")
    shelve(table, shared_id, "a1", "c2")  # id dùng chung gom dòng của nhiều người, hoặc dòng cũ từ thời chưa có tài khoản
    assert co_reader_hits(discover(uid=PROBE)) == []


def test_popular_keeps_counting_rows_of_shared_ids_as_it_always_did(catalog):
    table, _ = catalog
    shelve(table, "local", "b2")
    assert ids(discover("popular"))[0] == "b2"


def test_for_you_items_carry_no_field_that_could_hold_another_readers_data(catalog):
    table, _ = catalog
    shelve(table, PROBE, "a1")
    shelve(table, VICTIM, "a1", "c2")
    shelve(table, TWIN, "a1", "c2")
    body = discover(uid=PROBE).json()
    assert set(body) == {"novels", "total", "personalized"}
    assert all(isinstance(novel["reason"], str) and len(novel["reason"]) <= 60 for novel in body["novels"])
    text = discover(uid=PROBE).text
    assert VICTIM not in text and TWIN not in text
