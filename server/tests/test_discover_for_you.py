"""GET /v1/novels/discover?sort=for_you: danh sách theo gu của từng tài khoản."""

import pytest

from services.auth_token import issue_token
from services.discover_ranking import REASON_AUTHOR, REASON_CO_READERS, REASON_SOURCE
from tests.test_discover_fixtures import (  # noqa: F401  (catalog là fixture)
    CATALOG, PRIVATE_ID, bearer, catalog, client, discover, ids, make_entry, reasons, shelve,
)

ALICE, BOB, CAROL, DAVE = "user_alice", "user_bob", "user_carol", "user_dave"
PUBLIC_IDS = [row[0] for row in CATALOG]


def make_tie(nid, day):
    """Truyện không tín hiệu nào với alice (tác giả riêng, nguồn alice không đọc) để thử thứ tự hoà."""
    return make_entry(nid, day, author=f"Tác giả riêng {nid}", source_id="piaotia")


def test_two_accounts_with_different_libraries_get_different_lists_and_reasons(catalog):
    table, _ = catalog
    shelve(table, ALICE, "a1")
    shelve(table, BOB, "b1")
    alice, bob = discover(uid=ALICE), discover(uid=BOB)

    assert alice.json()["personalized"] is True and bob.json()["personalized"] is True
    assert ids(alice) == ["a2", "c1", "b1", "d1", "d2", "c2", "b2"]
    assert ids(bob) == ["b2", "c1", "d1", "d2", "c2", "a1", "a2"]
    assert reasons(alice)["a2"] == "Cùng tác giả với truyện bạn đang đọc"
    assert reasons(bob)["b2"] == REASON_AUTHOR
    assert reasons(alice)["b2"] == "" and reasons(bob)["a2"] == ""


def test_people_who_share_a_novel_still_get_their_own_lists(catalog):
    table, _ = catalog
    shelve(table, ALICE, "a1")
    shelve(table, BOB, "a1", "c2")
    shelve(table, CAROL, "a1", "c2", "d1")
    shelve(table, DAVE, "b1", "d1", "d2")
    alice, bob = discover(uid=ALICE), discover(uid=BOB)

    # alice: a2 cùng tác giả; c2 có 2 người cùng gu (bob, carol) nên được tính; d1 chỉ có 1 (carol) nên không;
    # phần còn lại theo độ mới.
    assert ids(alice) == ["a2", "c2", "c1", "b1", "d1", "d2", "b2"]
    # bob giữ a1 (Thần Đông) và c2 (Lạc Tử) nên c1 và a2 cùng tác giả; d1 chỉ do carol đọc cùng nên không có lý do.
    assert ids(bob) == ["c1", "a2", "b1", "d1", "d2", "b2"]
    assert reasons(alice) == {"a2": REASON_AUTHOR, "c2": REASON_CO_READERS, "c1": "", "b1": "", "d1": "", "d2": "",
                              "b2": ""}
    assert reasons(bob)["c1"] == reasons(bob)["a2"] == REASON_AUTHOR and reasons(bob)["d1"] == ""


def test_co_readers_are_counted_among_people_who_share_a_novel_only(catalog):
    table, _ = catalog
    shelve(table, ALICE, "a1")
    shelve(table, BOB, "a1", "c2")
    shelve(table, CAROL, "a1", "c2", "d1")
    shelve(table, DAVE, "b1", "d1", "d2")  # không trùng truyện nào với alice -> không được tính
    # d1 chỉ có carol là người cùng gu. Đếm cả dave thì d1 thành 2 người, đủ ngưỡng và nhảy lên ngay sau c2.
    res = discover(uid=ALICE)
    assert ids(res) == ["a2", "c2", "c1", "b1", "d1", "d2", "b2"] and reasons(res)["d1"] == ""


@pytest.mark.parametrize("owned", [["a1"], ["b1", "c2"], ["a1", "a2", "b1", "d2"], ["d1", PRIVATE_ID]])
def test_a_user_never_sees_novels_already_in_their_own_library(catalog, owned):
    table, _ = catalog
    shelve(table, ALICE, *owned)
    shelve(table, BOB, "a1", "b2", "c1")
    body = discover(uid=ALICE).json()
    shown = [novel["id"] for novel in body["novels"]]
    assert not set(owned) & set(shown)
    assert set(shown) == set(PUBLIC_IDS) - set(owned)
    assert body["total"] == len(shown)


def test_a_shared_source_lifts_novels_that_have_no_stronger_signal(catalog):
    table, _ = catalog
    shelve(table, ALICE, "d1")  # Hà Phong, uukanshu
    res = discover(uid=ALICE)
    assert ids(res) == ["d2", "c1", "b1", "c2", "b2", "a1", "a2"]
    assert reasons(res)["d2"] == "Cùng nguồn bạn hay đọc"
    assert {reasons(res)[nid] for nid in ids(res)[1:]} == {""}


def test_a_library_that_covers_the_whole_catalog_leaves_nothing_to_suggest(catalog):
    table, _ = catalog
    shelve(table, ALICE, *PUBLIC_IDS)
    body = discover(uid=ALICE).json()
    assert body == {"novels": [], "total": 0, "personalized": True}


def test_removed_entries_are_not_treated_as_owned(catalog):
    table, _ = catalog
    shelve(table, ALICE, "a1")
    shelve(table, ALICE, "a2", shelf="removed")
    body = discover(uid=ALICE).json()
    assert [novel["id"] for novel in body["novels"]][0] == "a2" and body["total"] == 7
    assert body["novels"][0]["reason"] == REASON_AUTHOR


def test_removing_the_only_novel_hands_the_user_back_to_the_cold_start_list(catalog):
    table, _ = catalog
    shelve(table, ALICE, "a1", shelf="removed")
    body = discover(uid=ALICE).json()
    assert body["personalized"] is False
    assert [novel["id"] for novel in body["novels"]] == ids(discover("recent"))


def test_a_removed_novel_does_not_make_its_former_owner_a_like_minded_reader(catalog):
    table, _ = catalog
    shelve(table, ALICE, "a1")
    shelve(table, BOB, "a1", shelf="removed")
    shelve(table, BOB, "c1")
    res = discover(uid=ALICE)
    assert reasons(res)["c1"] == "" and ids(res) == ["a2", "c1", "b1", "d1", "d2", "c2", "b2"]


def test_a_removed_novel_is_not_counted_among_the_co_readers_keepers(catalog):
    table, _ = catalog
    shelve(table, ALICE, "a1")
    shelve(table, BOB, "a1", "c2")
    shelve(table, BOB, "c2", shelf="removed")
    assert reasons(discover(uid=ALICE))["c2"] == ""


def test_private_imports_never_appear_in_any_list(catalog):
    table, _ = catalog
    for uid in (ALICE, BOB, CAROL):
        shelve(table, uid, PRIVATE_ID, "a1")
    for uid in (ALICE, BOB, CAROL, None):
        assert PRIVATE_ID not in ids(discover(uid=uid))


def test_your_own_import_shapes_your_list_through_its_author_only(catalog):
    table, _ = catalog
    shelve(table, ALICE, PRIVATE_ID)  # tác giả Lạc Tử, nguồn local_import
    res = discover(uid=ALICE)
    assert res.json()["personalized"] is True
    assert ids(res) == ["c1", "c2", "b1", "d1", "d2", "b2", "a1", "a2"]
    assert reasons(res)["c1"] == reasons(res)["c2"] == REASON_AUTHOR


def test_someone_elses_import_never_shapes_your_list(catalog):
    table, _ = catalog
    shelve(table, ALICE, "a1")
    shelve(table, BOB, "a1", PRIVATE_ID)  # tác giả Lạc Tử: không được hiện thành "cùng tác giả" cho alice
    res = discover(uid=ALICE)
    assert ids(res) == ["a2", "c1", "b1", "d1", "d2", "c2", "b2"]
    assert reasons(res)["c1"] == reasons(res)["c2"] == ""


def test_a_library_of_unknown_novels_still_counts_as_personalized(catalog):
    table, _ = catalog
    shelve(table, ALICE, "ghost_novel_not_on_server")
    body = discover(uid=ALICE).json()
    assert body["personalized"] is True
    assert [novel["id"] for novel in body["novels"]] == ids(discover("recent"))
    assert {novel["reason"] for novel in body["novels"]} == {""}


def test_the_alternative_session_header_personalises_too(catalog):
    table, _ = catalog
    shelve(table, ALICE, "a1")
    res = discover(headers={"X-Auth-Token": issue_token(ALICE, ALICE)})
    assert res.json()["personalized"] is True and ids(res)[0] == "a2"


def test_novels_with_a_scraper_placeholder_author_are_not_called_the_same_author(catalog):
    table, entries = catalog
    # scraper_69shuba.py điền "69书吧" vào `author` khi không đọc được tác giả: không phải một người.
    entries.extend([make_entry("ph1", 10, author="69书吧"), make_entry("ph2", 11, author="69书吧")])
    shelve(table, ALICE, "ph1")
    res = discover(uid=ALICE)
    assert REASON_AUTHOR not in reasons(res).values() and reasons(res)["ph2"] == REASON_SOURCE


def test_ties_break_by_recency_then_id_and_repeat_exactly(catalog):
    table, entries = catalog
    entries.extend([make_tie("t_b", 9), make_tie("t_a", 9), make_tie("t_c", 9), make_tie("t_old", 1)])
    shelve(table, ALICE, "a1")
    first = discover(uid=ALICE)
    shown = ids(first)
    assert shown[shown.index("t_a"):shown.index("t_a") + 3] == ["t_a", "t_b", "t_c"]
    assert shown.index("t_c") < shown.index("t_old")
    assert discover(uid=ALICE).json() == first.json()


def test_limit_trims_the_list_but_total_counts_every_candidate(catalog):
    table, entries = catalog
    entries.extend(make_tie(f"bulk{i}", 5) for i in range(520))
    shelve(table, ALICE, "a1")
    capped = discover(uid=ALICE, limit=600).json()
    assert len(capped["novels"]) == 500 and capped["total"] == 527
    assert len(discover(uid=ALICE, limit=2).json()["novels"]) == 2
    assert len(discover(uid=ALICE, limit=-3).json()["novels"]) == 1
    assert discover(uid=ALICE, limit=2).json()["total"] == 527
    # limit chỉ cắt đuôi: hai truyện đầu của limit=2 chính là hai truyện đầu của danh sách đầy đủ.
    assert ids(discover(uid=ALICE, limit=2)) == [novel["id"] for novel in capped["novels"][:2]]


def test_progress_written_through_the_real_route_drives_the_list(catalog):
    def sync(**body):
        return client.post("/v1/progress/sync", headers=bearer(ALICE), json=body).status_code

    assert sync(novel_id="a1", current_chapter=4, client_updated_at=1_000) == 200
    res = discover(uid=ALICE)
    assert ids(res)[0] == "a2" and "a1" not in ids(res) and res.json()["personalized"] is True

    # App báo gỡ truyện bằng cách đẩy kệ "removed": a1 hết thuộc tủ, tủ trống nên về bảng mặc định.
    assert sync(novel_id="a1", current_chapter=4, shelf_name="removed", client_updated_at=2_000) == 200
    res = discover(uid=ALICE)
    assert res.json()["personalized"] is False and "a1" in ids(res)


def test_two_accounts_syncing_through_the_real_route_see_different_lists(catalog):
    for uid, novel in ((ALICE, "a1"), (BOB, "b1")):
        body = {"novel_id": novel, "current_chapter": 2}
        assert client.post("/v1/progress/sync", headers=bearer(uid), json=body).status_code == 200
    alice, bob = discover(uid=ALICE), discover(uid=BOB)
    assert ids(alice)[0] == "a2" and ids(bob)[0] == "b2"
    assert "a1" not in ids(alice) and "b1" not in ids(bob) and ids(alice) != ids(bob)
