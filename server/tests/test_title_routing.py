"""Định tuyến: tựa hiện đại/tựa chương dễ sai trật tự -> Hy-MT2 nền; tựa kiểu Hán-Việt -> từ điển là chốt."""

import pytest

from services import title_routing as R


@pytest.mark.parametrize("zh", [
    "波士顿爱情故事", "博士生恋爱观察", "那么近，又那么远", "我在大学当校长", "科普氧气有毒，全网骂我有病",
    "我和仙子的修行", "我姑奶奶她修仙回来了", "开局不灭混沌体：妖帝老婆求贴贴", "大王饶命", "我在精神病院学斩神",
    "二哈和他的白猫师尊", "偷偷藏不住", "难哄", "学霸的黑科技系统",
])
def test_tua_hien_dai_di_mo_hinh(zh):
    assert R.is_modern_title(zh), (zh, R.modern_score(zh))


@pytest.mark.parametrize("zh", [
    "万古第一神", "大奉打更人", "凡人修仙传之厉飞雨", "三生三世十里桃花", "我欲封天", "修真聊天群", "超神机械师",
    "残烟杂录（三生石前传）", "十日终焉", "天官赐福", "盗墓笔记", "全职高手", "斗破苍穹", "一念永恒",
])
def test_tua_han_viet_giu_tu_dien(zh):
    assert not R.is_modern_title(zh), (zh, R.modern_score(zh))


def test_book_dict_trung_nguyen_tua_la_chot(fixture_dict):
    assert R.book_dict(fixture_dict, "剑来") == ("Kiếm Lai", "names", False)
    vi, src, need = R.book_dict(fixture_dict, "我在大学当校长")
    assert src == "hanviet" and need and vi == "Ngã Tại Đại Học Đương Hiệu Trưởng"


def test_chapter_routing(fixture_dict):
    assert R.chapter_dict(fixture_dict, "圣兽战魂") == ("Thánh thú chiến hồn", "convert", False)
    vi, src, need = R.chapter_dict(fixture_dict, "国师的建议")
    assert vi == "Quốc Sư đích đề nghị" and need  # 的: convert giữ trật tự Trung -> mô hình sửa
    assert R.chapter_dict(fixture_dict, "爱情")[1] == "vietphrase"


def test_ghi_chu_cuoi_dich_rieng(fixture_dict):
    assert R.split_note("潜入兰利（上）") == ("潜入兰利", "上")
    assert R.split_note("（求月票）") == ("（求月票）", "")
    assert R.chapter_dict(fixture_dict, "潜入兰利（上）")[0] == "Lẻn vào Langley (thượng)"
    assert R.chapter_dict(fixture_dict, "圣兽战魂(二)")[0] == "Thánh thú chiến hồn (2)"
    assert R.chapter_dict(fixture_dict, "圣兽战魂（求月票）")[0] == "Thánh thú chiến hồn (Cầu nguyệt phiếu)"
    assert R.note_vi(fixture_dict, "十四") == "14"


def test_goi_y_thuat_ngu_names_va_han_viet(fixture_dict):
    hints = R.term_hints(fixture_dict, "林动进阶化神")
    assert ("林动", "Lâm Động") in hints and ("化神", "Hóa Thần") in hints and ("进阶", "tiến giai") in hints
    assert R.term_hints(None, "林动") == []
