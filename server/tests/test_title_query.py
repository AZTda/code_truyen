"""Từ khoá Việt -> Trung: từ điển ngược xếp hạng (tức thì) cho âm Hán-Việt, Hy-MT2 cho từ khoá dịch nghĩa."""

import asyncio

import pytest

from services.query_dict import cjk_variants, dictionary_candidates, merge_variants
from services.query_translator import query_translator
from services.title_dict_reverse import profile, query_candidates
from services.title_mt_client import title_mt


def test_query_candidates_xep_hang_va_dau(fixture_dict):
    assert query_candidates(fixture_dict, "kiếm lai")[0] == "剑来"
    assert query_candidates(fixture_dict, "kiem lai")[0] == "剑来"
    assert query_candidates(fixture_dict, "Đấu Phá Thương Khung")[0] == "斗破苍穹"
    assert query_candidates(fixture_dict, "van co")[0] == "万古"
    # Gõ có dấu: chữ đúng dấu đứng trước chữ cùng âm khác dấu (kiếm 剑 > kiểm 检)
    assert query_candidates(fixture_dict, "kiếm")[0] == "剑"
    assert query_candidates(fixture_dict, "kiểm")[0] == "检"


def test_profile_han_viet_hay_dich_nghia(fixture_dict):
    assert profile(fixture_dict, "kiếm lai").looks_hanviet
    assert profile(fixture_dict, "dau pha thuong khung").looks_hanviet
    assert not profile(fixture_dict, "chuyện tình boston").looks_hanviet
    assert not profile(fixture_dict, "kiếm lai " * 4).looks_hanviet  # > 6 âm tiết


def test_dictionary_candidates_va_bien_the(fixture_dict):
    cands, hv = dictionary_candidates("kiếm lai")
    assert hv and cands[0] == "剑来"
    assert dictionary_candidates("chuyện tình boston") == ([], False)
    assert cjk_variants(["波士顿的爱情", "波士顿的爱情"]) == ["波士顿的爱情", "波士顿爱情"]
    assert merge_variants(["a", "b"], ["b", "c"]) == ["a", "b", "c"]


def test_khong_co_tu_dien():
    assert dictionary_candidates("kiếm lai") == ([], False)


@pytest.fixture
def qt(monkeypatch):
    monkeypatch.setattr(query_translator, "_persist_translation", lambda *a, **k: None)
    keys = []
    yield keys
    for k in keys:
        query_translator._cache.pop(k, None)
        query_translator._cache_src.pop(k, None)
        query_translator._neg_cache.pop(k, None)


@pytest.mark.asyncio
async def test_tu_khoa_han_viet_tra_ngay_khong_cho_ai(fixture_dict, qt, monkeypatch):
    q = "kiếm lai đấu phá"
    qt.append(q)

    async def must_not_run(clean, lower):
        raise AssertionError("từ khoá Hán-Việt không được chờ mô hình/AI")
    monkeypatch.setattr(query_translator, "_do_translate", must_not_run)
    res = await query_translator.translate_query(q)
    assert res[0] == "剑来斗破"


@pytest.mark.asyncio
async def test_tu_khoa_dich_nghia_qua_hy_mt2_va_cache(fixture_dict, qt, monkeypatch):
    q = "chuyện tình new york"
    qt.append(q)
    calls = []

    async def fake_vi_to_zh(query, timeout=None):
        calls.append(query)
        return "纽约的爱情故事"
    monkeypatch.setattr(title_mt, "vi_to_zh", fake_vi_to_zh)
    res = await query_translator.translate_query(q)
    assert res[:2] == ["纽约的爱情故事", "纽约爱情故事"]
    assert query_translator._cache_src[q] == "mt"
    assert await query_translator.translate_query(q) == res and calls == [q]  # lần sau lấy cache


@pytest.mark.asyncio
async def test_hy_mt2_cham_thi_chay_nen_roi_cache(fixture_dict, qt, monkeypatch):
    q = "tôi làm hiệu trưởng"
    qt.append(q)
    monkeypatch.setattr(query_translator, "INTERACTIVE_WAIT_SECONDS", 0.05)

    async def slow(query, timeout=None):
        await asyncio.sleep(0.2)
        return "我当校长"
    monkeypatch.setattr(title_mt, "vi_to_zh", slow)
    assert await query_translator.translate_query(q) == [q]
    await asyncio.sleep(0.3)
    assert (await query_translator.translate_query(q))[0] == "我当校长"


@pytest.mark.asyncio
async def test_dong_cu_thoi_google_khong_che_tu_dien(fixture_dict, qt):
    q = "kiếm lai"
    qt.append(q)
    query_translator._cache[q] = ["剑来了"]  # bản Google cũ (dịch nghĩa sai) đã lưu SQLite, không có nguồn
    query_translator._cache_src[q] = "legacy"
    res = await query_translator.translate_query(q)
    assert res[:2] == ["剑来", "剑来了"] and "剑莱" in res
    query_translator._cache_src[q] = "ai"  # bản AI tra tên gốc: tin hơn
    assert (await query_translator.translate_query(q))[0] == "剑来了"


def test_vietphrase_reverse_va_dao_cum_danh_tinh_tu(fixture_dict):
    from services.query_vp_reverse import extract_query_segments_and_hints

    cands, hints = extract_query_segments_and_hints("bắt đầu đánh dấu hoang cổ thánh thể", fixture_dict)
    assert cands[0] == "开局签到荒古圣体"
    assert ("bắt đầu đánh dấu", "开局签到") in hints

    cands_inv, _ = extract_query_segments_and_hints("thế giới hoàn mỹ", fixture_dict)
    assert cands_inv[0] == "完美世界"

    cands_sys, _ = extract_query_segments_and_hints("hệ thống tối cường", fixture_dict)
    assert cands_sys[0] == "最强系统"

    cands_ghost, _ = extract_query_segments_and_hints("ta có một tòa nhà ma", fixture_dict)
    assert "我有一座恐怖屋" in cands_ghost


def test_ten_rieng_tieng_anh_viet_tat_va_sua_loi_chinh_ta(fixture_dict):
    from models.novel import Novel
    from services.query_vp_reverse import extract_query_segments_and_hints
    from services.search_ranker import score_novel

    c_mit, _ = extract_query_segments_and_hints("MIT", fixture_dict)
    assert "麻省" in c_mit and "麻省理工" in c_mit

    c_harv, _ = extract_query_segments_and_hints("harvard", fixture_dict)
    assert c_harv[0] == "哈佛"

    # Gõ sai chính tả 'havard' (thiếu chữ r) hoặc 'berkley' (thiếu chữ e) vẫn tự động nhận diện đúng
    c_havard, _ = extract_query_segments_and_hints("havard", fixture_dict)
    assert c_havard[0] == "哈佛"

    c_berkley, _ = extract_query_segments_and_hints("berkley", fixture_dict)
    assert c_berkley[0] == "伯克利"

    # Tra ngược trực tiếp từ bảng VietPhrase (idx_vp_vi_lower)
    assert "爱情" in fixture_dict.vp_val("tình yêu")
    assert "建议" in fixture_dict.vp_val("đề nghị")

    # Chấm điểm kết quả web đối chiếu trực tiếp từ khoá tiếng Trung đã dịch
    n_harvard = Novel(id="1", title="分手吧，我要考哈佛", title_vi="Phân Thủ Ba, Ngã Yếu Khảo Cáp Phật", url="http://x", source_id="uukanshu")
    assert score_novel("havard", n_harvard) >= 0.80
    assert score_novel("harvard", n_harvard) >= 0.80

    n_marvel = Novel(id="2", title="漫威：开局签到", title_vi="Mạn Uy: Khai Cục Thiêm Đáo", url="http://x", source_id="69shuba")
    assert score_novel("marvel", n_marvel) >= 0.80


def test_polish_tua_hien_dai_va_goi_y_tuc_thi(fixture_dict):
    from services.search_suggest_service import suggest_queries
    from services.search_title_polisher import polish_modern_search_title

    p1 = polish_modern_search_title("霍格沃茨的路人教授", "Hogwarts Đích Lộ Nhân Giáo Thụ")
    assert "Giáo Sư" in p1 and "Hogwarts" in p1 and "Đích" not in p1

    p2 = polish_modern_search_title("加油啊，武医生", "Gia Du A, Vũ Y Sinh")
    assert "Cố Lên" in p2 and "Bác Sĩ" in p2

    s_havard = suggest_queries("havard")
    assert s_havard and "哈佛" in s_havard[0]["sub"]

    s_mit = suggest_queries("MIT")
    assert s_mit and "麻省" in s_mit[0]["sub"]
