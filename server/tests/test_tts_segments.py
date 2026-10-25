"""Cắt mảnh TTS phải GIỐNG HỆT ParagraphSplitter.kt của app (khoá cache của máy chủ và app chỉ trùng khi mảnh trùng).

Mỗi vector bên dưới là kết quả CHẠY THẬT của ParagraphSplitter.kt đã biên dịch (Kotlin 2.2.0, JVM) trên cùng đầu vào;
ngoài ra bản Python đã được so với bản Kotlin đó trên 3.020 chuỗi ngẫu nhiên (khoảng trắng Unicode, emoji, thẻ HTML)
mà không lệch chuỗi nào. Đổi ParagraphSplitter.kt thì chạy lại đối chiếu rồi cập nhật vector.
"""

import pytest

from services.tts_segments import (
    TTS_SEGMENT_MAX,
    TTS_SPLIT_THRESHOLD,
    build_tts_segments,
    split_display_paragraphs,
    tts_texts,
    utf16_length,
)


def segs(text):
    return [(s.paragraph_index, s.text) for s in build_tts_segments(split_display_paragraphs(text))]


def test_hang_so_giong_app():
    assert (TTS_SEGMENT_MAX, TTS_SPLIT_THRESHOLD) == (250, 300)


@pytest.mark.parametrize("text,expected", [
    ("<p>Xin chào</p><p>Tạm biệt</p>", [(0, "Xin chào"), (1, "Tạm biệt")]),
    ("Một<br>Hai<BR/>Ba<br />Bốn", [(0, "Một"), (1, "Hai"), (2, "Ba"), (3, "Bốn")]),
    ("A\r\nB\rC\n\nD", [(0, "A"), (1, "B"), (2, "C"), (3, "D")]),
    (" \t 　 ", []),
    ("", []),
    ("<br>", [(0, "<br>")]),                      # toàn thẻ: rơi về text.trim()
    ("\u0085abc\u0085", [(0, "\u0085abc\u0085")]),  # U+0085 KHÔNG phải khoảng trắng của Kotlin
    (" 　xin chào ", [(0, "xin chào")]),  # NBSP, khoảng trắng CJK, U+2028 đều bị trim
])
def test_doan_hien_thi_va_mang_ngan(text, expected):
    assert segs(text) == expected


def test_doan_300_giu_nguyen_301_khong_co_ranh_gioi_cau_cung_giu_nguyen():
    assert segs("x" * 300) == [(0, "x" * 300)]
    assert segs("x" * 301) == [(0, "x" * 301)]


def test_cat_theo_ranh_gioi_cau_va_gop_toi_250():
    # 150 + 148 ký tự, hai câu: tổng 301 > 300 nên cắt, mỗi câu một mảnh (gộp sẽ vượt 250)
    text = ("a" * 150 + ". ") + ("b" * 148 + ".")
    assert [(i, len(t)) for i, t in segs(text)] == [(0, 151), (0, 149)]
    # ba câu 101 ký tự: hai câu đầu gộp (203 <= 250), câu thứ ba vượt 250 nên sang mảnh sau
    text = ("a" * 100 + ". ") + ("b" * 100 + ". ") + ("c" * 100 + ".")
    got = segs(text)
    assert [(i, len(t)) for i, t in got] == [(0, 203), (0, 101)]
    assert got[0][1] == "a" * 100 + ". " + "b" * 100 + "."


def test_dau_cham_lung_va_chu_han_cung_la_ranh_gioi_nhung_can_khoang_trang_sau_dau():
    text = ("a" * 120 + "… ") + ("b" * 120 + "… ") + ("c" * 100 + "…")
    assert [(i, len(t)) for i, t in segs(text)] == [(0, 243), (0, 101)]
    # '。' không theo sau bởi khoảng trắng thì không phải ranh giới (như Kotlin): một mảnh 393 ký tự
    assert [len(t) for _, t in segs(("一" * 130 + "。") * 3)] == [393]


def test_do_dai_tinh_theo_don_vi_utf16_nhu_kotlin():
    assert utf16_length("😀") == 2 and len("😀") == 1
    # 150 chữ + 75 emoji = 300 đơn vị UTF-16 (225 code point): chưa vượt ngưỡng nên giữ một mảnh
    assert [len(t.encode("utf-16-le")) // 2 for _, t in segs("a" * 150 + "😀" * 75)] == [300]
    assert [len(t.encode("utf-16-le")) // 2 for _, t in segs("a" * 151 + "😀" * 75)] == [301]


def test_chi_so_doan_cha_theo_dung_doan_hien_thi():
    long = ("a" * 100 + ". ") + ("b" * 100 + ". ") + ("c" * 100 + ".")
    got = segs("Ngắn một.\n" + long + "\nNgắn hai.")
    assert [i for i, _ in got] == [0, 1, 1, 2]


def test_tts_texts_la_danh_sach_chuoi_app_dua_cho_bo_phat():
    assert tts_texts("<p>A</p><p>B</p>") == ["A", "B"]


def test_moi_manh_co_the_tong_hop_lai_thanh_van_ban_goc_khi_cat_cau():
    sentences = [("Câu số %d có nội dung khá dài để vượt ngưỡng cắt mảnh của giọng đọc." % i) for i in range(20)]
    text = " ".join(sentences)
    pieces = [t for _, t in segs(text)]
    assert " ".join(pieces).split() == text.split()
    assert all(utf16_length(p) <= TTS_SEGMENT_MAX for p in pieces)
