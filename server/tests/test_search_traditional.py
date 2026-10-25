"""Tìm kiếm khớp chữ phồn thể (uukanshu) với từ khóa giản thể, và bỏ kết quả nhiễu khi đã có kết quả khớp chắc chắn."""

from models.novel import Novel
from services.search_ranker import drop_weak_web_results, score_novel
from services.text_similarity import similarity_cjk, to_simplified


def _web(title, source="uukanshu"):
    return Novel(id=title, title=title, url=f"https://example.com/{title}", source_id=source)


def test_to_simplified_converts_traditional_titles():
    assert to_simplified("劍來") == "剑来"
    assert to_simplified("斗破蒼穹") == "斗破苍穹"


def test_simplified_query_matches_traditional_title():
    assert similarity_cjk("剑来", "劍來：開局斷劍救寧姚") >= 0.9


def test_traditional_exact_title_scores_as_exact_match():
    assert score_novel("斗破苍穹", _web("斗破蒼穹")) == 1.0


def test_weak_results_dropped_when_strong_match_exists():
    novels = [_web("借剑", "69shuba"), _web("剑来（1", "69shuba"), _web("斗罗绝世：我霍雨浩", "69shuba")]
    kept = drop_weak_web_results("剑来", novels)
    assert [n.title for n in kept] == ["剑来（1"]


def test_all_results_kept_when_nothing_matches_strongly():
    novels = [_web("借剑"), _web("斗罗绝世")]
    assert drop_weak_web_results("完全无关的词", novels) == novels


def test_local_library_results_are_never_dropped():
    local = Novel(id="l1", title="借剑", source_id="local", url="local://l1")
    kept = drop_weak_web_results("剑来", [_web("剑来（1"), local])
    assert local in kept
