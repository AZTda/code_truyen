"""Chuẩn hoá văn bản trước khi đưa cho VieNeu: chỉ làm điều VieNeu KHÔNG tự làm (đo trên mô hình thật).

VieNeu tự đọc số, %, giờ (12:30), tiền, ngày và "Chương 12:"; hai phép viết lại cũ ('50%' -> '50 phần trăm',
'12:30' -> '12 trên 30') làm hỏng giờ và câu tiếng Anh nên đã bị bỏ.
"""

import pytest

from services.tts_voice_service import normalize_storyteller_text as norm


@pytest.mark.parametrize("text", ["Hắn tăng 50% sức mạnh.", "Lúc 12:30 hắn rời đi.", "Tăng 15,5% so với năm 2024.", "It is 5% better."])
def test_phan_tram_va_gio_giu_nguyen_de_vieneu_tu_doc(text):
    assert norm(text, True) == text and norm(text, False) == text


@pytest.mark.parametrize("raw,expected", [
    ("Hắn *bị thương* nặng.", "Hắn bị thương nặng."),
    ("Hắn #nặng# bị ~thương~ _rất_ nặng.", "Hắn nặng bị thương rất nặng."),
    ("Hắn @@ nói.", "Hắn nói."),
    ("xx|yy\\zz `dd`", "xx yy zz dd"),
])
def test_ky_hieu_ma_vieneu_se_doc_thanh_tieng_bi_bo(raw, expected):
    """'*' bị đọc là 'sao', '#' là 'thăng', '~' là 'khoảng', '@' là 'a còng'."""
    assert norm(raw) == expected


def test_ngoac_vuong_thanh_ngoac_don_de_so_trong_ngoac_duoc_doc():
    assert norm("[Hệ thống] Chúc mừng ký chủ đột phá!") == "(Hệ thống) Chúc mừng ký chủ đột phá!"
    assert norm("【Cấp độ】99") == "(Cấp độ)99"


def test_ma_hoa_html_duoc_giai_ma():
    assert norm("Anh ta nói &quot;xin chào&quot; &amp; đi.") == "Anh ta nói “xin chào” & đi."


def test_than_tu_tieng_viet_them_nhip_nhung_tieng_anh_khong():
    assert norm("Ôi trời ơi, sao lại thế này?", True) == "Ôi, trời ơi, sao lại thế này?"
    assert norm("This is a test of the voice.", False) == "This is a test of the voice."
    assert norm("Chết tiệt rồi", True) == "Chết tiệt, rồi", "thán từ tiếng Việt thêm nhịp; chữ 'a' tiếng Anh không bị đụng"
    assert norm("This is a test of the voice.", True) == "This is a test of the voice."


def test_tieu_de_chuong_va_dau_cau_duoc_don():
    assert norm("Chương 12: Quyết chiến") == "Chương 12: Quyết chiến"
    assert norm("Hắn xuất kiếm!!!") == "Hắn xuất kiếm!"
    assert norm("Thật sao?!!") == "Thật sao?!"
    assert norm("Hừm...đúng rồi") == "Hừm... đúng rồi"


def test_chu_a_dung_mot_minh_la_nhan_hang_khong_phai_than_tu():
    """Bản cũ chèn dấu phẩy sau mọi chữ 'A' đứng riêng ('hạng A, khu B'); 'Á' có dấu mới chắc chắn là thán từ."""
    assert norm("Phòng A khu B", True) == "Phòng A khu B"
    assert norm("Á đau quá", True) == "Á, đau quá"


def test_chuoi_rong_va_toan_ky_hieu():
    assert norm("") == "" and norm("***") == "" and norm("◆◆◆ ■■") == ""


def test_dau_ngoac_kep_thang_thanh_ngoac_kep_cong():
    assert norm('Hắn nói: "xin chào" rồi đi.') == "Hắn nói: “xin chào”, rồi đi."
    assert norm("「Ngươi là ai」") == "“Ngươi là ai”"


@pytest.mark.parametrize("raw,expected", [
    ("ＯＫ, １２３, ﬁnal.", "OK, 123, final."),
    ("Thứ Ⅸ, chương Ⅳ.", "Thứ IX, chương IV."),
    ("Tên　hắn　là Lâm.", "Tên hắn là Lâm."),
])
def test_chu_toan_goc_chu_ghep_va_so_la_ma_duoc_gap_ve_dang_thuong(raw, expected):
    """Đo trên Nano thật: bộ chuẩn hoá của VieNeu XOÁ các ký tự này ('ＯＫ, １２３, ﬁnal.' chỉ còn 'nal')."""
    assert norm(raw) == expected


def test_dau_cau_toan_goc_chuan_hoa_y_het_dang_ascii():
    assert norm("Ｈắn nói：＂được＂！") == norm('Hắn nói:"được"!')


def test_ky_hieu_vieneu_bo_qua_duoc_doc_thanh_chu():
    """Đo trên Nano thật: '30‰' chỉ còn 'ba mươi', '№ 7' chỉ còn 'bảy'."""
    assert norm("Giá 30‰, № 7.", True) == "Giá 30 phần nghìn, số 7."
    assert norm("Rate 30‰, № 7.", False) == "Rate 30 per mille, number 7."


def test_chu_viet_va_dau_cau_thuong_khong_bi_gap_nfkc():
    """Chỉ gấp đúng các khối toàn góc/chữ ghép/số La Mã: '²' (VieNeu đọc 'bình phương'), '…', '½' giữ nguyên."""
    text = "Diện tích 5m² của Nguyễn Thị Ánh, ½ cái…"
    assert norm(text).startswith("Diện tích 5m² của Nguyễn Thị Ánh, ½ cái")
