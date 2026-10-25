"""Xếp hạng "Dành cho bạn" ở mức hàm thuần (không HTTP, không DB)."""

import pytest

import services.discover_ranking as ranking
from services.discover_ranking import (
    CO_READER_CAP, MAX_NOVELS_PER_AUTHOR, MIN_CO_READERS, REASON_AUTHOR, REASON_CO_READERS, REASON_SOURCE,
    SOURCE_MIN_SHARE, W_AUTHOR, W_CO_READER, W_SOURCE, Taste, author_key, co_reader_counts, rank_for_you, taste_of,
)
from services.sync_merge import ms_from_iso
from tests.test_discover_fixtures import make_entry


def candidate(nid, day, author=None, source="69shuba"):
    entry = make_entry(nid, day, author, source)
    return entry, entry["novel"], ms_from_iso(entry["novel"]["updated_at"])


def order(pool, owned=frozenset(), taste=Taste(), co=None):
    return [(entry["id"], reason) for entry, _, reason in rank_for_you(pool, owned, taste, co or {})]


def never_private(_novel):
    return False


def test_weights_keep_the_documented_hierarchy():
    assert 0 < W_SOURCE < W_CO_READER < W_AUTHOR  # nguồn chỉ phân hạng giữa các truyện cùng số người cùng gu
    assert W_CO_READER * CO_READER_CAP < W_AUTHOR  # đám đông không vượt được cùng tác giả


def test_reasons_are_short_distinct_and_generic():
    reasons = [REASON_AUTHOR, REASON_CO_READERS, REASON_SOURCE]
    assert len(set(reasons)) == 3 and all(0 < len(reason) <= 60 for reason in reasons)
    assert REASON_AUTHOR == "Cùng tác giả với truyện bạn đang đọc"
    assert REASON_CO_READERS == "Người cùng gu với bạn cũng đang đọc"
    assert REASON_SOURCE == "Cùng nguồn bạn hay đọc"


def test_author_key_ignores_case_accents_spacing_and_edge_punctuation():
    keys = {author_key({"author": raw}) for raw in ("Thần Đông", "THAN  dong", " than dong. ", "(Thần Đông)")}
    assert keys == {"than dong"}
    assert author_key({"author": "辰东"}) == "辰东"


# Đúng các chuỗi scraper_*.py điền vào `author` khi không đọc được tác giả (tên nguồn, "Unknown", "Không rõ tác giả").
SCRAPER_PLACEHOLDERS = ["69书吧", "晋江文学城", "UU看书", "飘天文学", "Unknown", "Không rõ tác giả", "Khuyết Danh"]


@pytest.mark.parametrize("raw", ["", None, " - ", "Khuyết danh", "未知", "佚名", "Vị Tri", "Ẩn danh", *SCRAPER_PLACEHOLDERS])
def test_placeholder_authors_never_match_anything(raw):
    assert author_key({"author": raw}) == ""


def test_the_placeholder_list_is_stored_already_folded_or_it_would_never_match():
    assert all(ranking._fold(name) == name for name in ranking._UNKNOWN_AUTHORS)


def test_a_source_name_filled_in_for_a_missing_author_does_not_make_a_whole_source_one_author():
    taste = taste_of([{"author": "69书吧", "source_id": "69shuba"}], never_private)
    assert taste.authors == frozenset()
    pool = [candidate(f"n{i}", i + 1, "69书吧") for i in range(3)]
    assert [reason for _, reason in order(pool, taste=Taste(frozenset({"69书吧"}), frozenset()))] == ["", "", ""]


def test_an_author_with_too_many_novels_is_a_placeholder_not_a_person():
    taste = Taste(frozenset({"ten la", "than dong"}), frozenset())
    crowd = [candidate(f"c{i}", 1 + i % 20, "Tên lạ") for i in range(MAX_NOVELS_PER_AUTHOR + 1)]
    pool = crowd + [candidate("real", 1, "Thần Đông")]
    assert dict(order(pool, taste=taste)) == {**{f"c{i}": "" for i in range(MAX_NOVELS_PER_AUTHOR + 1)},
                                              "real": REASON_AUTHOR}
    # đúng ngưỡng thì vẫn là một tác giả nhiều truyện thật
    prolific = [candidate(f"p{i}", 1 + i, "Tên lạ") for i in range(MAX_NOVELS_PER_AUTHOR)]
    assert {reason for _, reason in order(prolific, taste=taste)} == {REASON_AUTHOR}


def test_an_author_that_is_really_the_title_is_not_an_author():
    assert author_key({"author": "Kiếm Lai", "title": "剑来", "title_vi": "Kiếm Lai"}) == ""
    assert author_key({"author": "剑来", "title": "剑来"}) == ""


def test_taste_keeps_only_sources_that_make_up_a_real_share_of_the_library():
    four_one = [{"author": f"x{i}", "source_id": "69shuba"} for i in range(4)] + [{"author": "y", "source_id": "jjwxc"}]
    assert taste_of(four_one, never_private).sources == frozenset({"69shuba"})  # jjwxc: 1/5 = 0.2
    three_one = four_one[1:]
    assert taste_of(three_one, never_private).sources == frozenset({"69shuba", "jjwxc"})  # 1/4 = SOURCE_MIN_SHARE
    assert SOURCE_MIN_SHARE == 0.25
    assert taste_of([], never_private) == Taste()


def test_a_private_import_adds_its_author_but_not_its_source():
    imported = {"author": "Lạc Tử", "source_id": "local_import", "id": "import_1"}
    public = {"author": "Mặc Vũ", "source_id": "jjwxc", "id": "b1"}
    taste = taste_of([imported, public, imported], lambda novel: novel["id"].startswith("import_"))
    assert taste == Taste(frozenset({"lac tu", "mac vu"}), frozenset({"jjwxc"}))


def test_co_readers_are_a_count_over_people_who_share_a_public_novel():
    libraries = {"u1": {"a", "x"}, "u2": {"a", "x", "y"}, "me": {"a"}, "u3": {"z", "x"}}
    keepers = {"a": {"u1", "u2", "me"}, "x": {"u1", "u2", "u3"}, "y": {"u2"}, "z": {"u3"}}
    counts = co_reader_counts(frozenset({"a"}), "me", libraries, keepers, {"a", "x", "y", "z"})
    # u3 không trùng truyện nào với "me" nên không được tính (x vẫn là 2 chứ không phải 3); y chỉ có u2 nên bị giấu.
    assert counts == {"x": 2}


def test_only_public_novels_link_people_or_get_counted():
    libraries = {"me": {"p", "a"}, "u1": {"p", "q", "a2", "a3"}, "u2": {"a", "a3", "q"}, "u4": {"a", "a2", "a3", "q"}}
    keepers = {"p": {"me", "u1"}, "a": {"me", "u2", "u4"}, "q": {"u1", "u2", "u4"}, "a2": {"u1", "u4"},
               "a3": {"u1", "u2", "u4"}}
    # p, q là truyện riêng tư: u1 chỉ trùng "me" ở p nên không phải người cùng gu (a2 vẫn chỉ có u4, a3 chỉ có u2 và u4);
    # u2, u4 là người cùng gu (trùng a) nhưng q trong tủ họ không bao giờ được đếm.
    counts = co_reader_counts(frozenset({"p", "a"}), "me", libraries, keepers, {"a", "a2", "a3"})
    assert counts == {"a3": 2}


def test_a_count_of_one_reader_is_never_reported_because_it_is_that_readers_library():
    libraries = {"me": {"rare"}, "victim": {"rare", "x", "y", "z"}}
    keepers = {"rare": {"me", "victim"}, "x": {"victim"}, "y": {"victim"}, "z": {"victim"}}
    assert co_reader_counts(frozenset({"rare"}), "me", libraries, keepers, {"rare", "x", "y", "z"}) == {}
    libraries["twin"] = {"rare", "x"}
    keepers["rare"], keepers["x"] = {"me", "victim", "twin"}, {"victim", "twin"}
    assert co_reader_counts(frozenset({"rare"}), "me", libraries, keepers, {"rare", "x", "y", "z"}) == {"x": 2}
    assert MIN_CO_READERS == 2


def test_only_the_closest_people_are_counted_when_there_are_too_many(monkeypatch):
    monkeypatch.setattr(ranking, "MAX_NEIGHBOURS", 2)
    libraries = {"me": {"a", "b", "c"}, "close1": {"a", "b", "c", "n1", "n2"}, "close2": {"a", "b", "n1"},
                 "far1": {"a", "n1", "n2"}, "far2": {"a", "n1", "n3"}}
    keepers = {"a": {"me", "close1", "close2", "far1", "far2"}, "b": {"me", "close1", "close2"}, "c": {"me", "close1"}}
    counts = co_reader_counts(frozenset({"a", "b", "c"}), "me", libraries, keepers, {"a", "b", "c", "n1", "n2", "n3"})
    assert counts == {"n1": 2}  # chỉ close1, close2; n2 chỉ còn close1 (far1 bị cắt) nên bị giấu


def test_owned_novels_are_never_ranked():
    pool = [candidate("a", 5), candidate("b", 4), candidate("c", 3)]
    assert [nid for nid, _ in order(pool, owned=frozenset({"b"}))] == ["a", "c"]


def test_signals_rank_author_over_crowd_over_source_over_recency():
    taste = Taste(frozenset({"than dong"}), frozenset({"jjwxc"}))
    pool = [
        candidate("newest", 28, source="uukanshu"),
        candidate("source", 1, source="jjwxc"),
        candidate("crowd", 2, source="uukanshu"),
        candidate("author", 3, "Thần Đông", source="uukanshu"),
    ]
    assert order(pool, taste=taste, co={"crowd": 10}) == [
        ("author", REASON_AUTHOR), ("crowd", REASON_CO_READERS), ("source", REASON_SOURCE), ("newest", ""),
    ]


def test_the_reason_names_the_strongest_signal_when_several_apply():
    taste = Taste(frozenset({"than dong"}), frozenset({"jjwxc"}))
    pool = [
        candidate("everything", 1, "Thần Đông", source="jjwxc"),
        candidate("crowd_and_source", 2, source="jjwxc"),
        candidate("author_and_crowd", 3, "Thần Đông", source="uukanshu"),
    ]
    assert dict(order(pool, taste=taste, co={"everything": 3, "crowd_and_source": 1, "author_and_crowd": 2})) == {
        "everything": REASON_AUTHOR, "crowd_and_source": REASON_CO_READERS, "author_and_crowd": REASON_AUTHOR,
    }


def test_the_crowd_is_capped_so_ten_readers_do_not_beat_four():
    co = {"four": CO_READER_CAP, "ten": 10}
    # Trần làm hai bên hoà điểm nên truyện mới hơn đứng trước, bất kể số người đọc thật.
    assert [nid for nid, _ in order([candidate("four", 1), candidate("ten", 2)], co=co)] == ["ten", "four"]
    assert [nid for nid, _ in order([candidate("four", 5), candidate("ten", 2)], co=co)] == ["four", "ten"]


def test_without_signals_the_order_is_by_recency_then_id_with_unknown_dates_last():
    undated = make_entry("undated", 1)
    undated["novel"].pop("updated_at")
    pool = [candidate("b", 5), candidate("a", 5), candidate("old", 1), (undated, undated["novel"], 0),
            candidate("new", 9)]
    assert [nid for nid, _ in order(pool)] == ["new", "a", "b", "old", "undated"]


def test_ranking_is_deterministic_whatever_the_input_order():
    pool = [candidate(f"n{i}", 1 + i % 4, author=f"Tác giả {i % 3}") for i in range(12)]
    taste = Taste(frozenset({"tac gia 1"}), frozenset())
    forward = order(pool, taste=taste, co={"n5": 2})
    assert forward == order(list(reversed(pool)), taste=taste, co={"n5": 2})
    assert forward[0][0] in {"n1", "n4", "n7", "n10"} and forward[0][1] == REASON_AUTHOR
