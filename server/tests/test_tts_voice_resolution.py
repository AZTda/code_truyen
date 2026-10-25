"""Giọng NGHE ĐƯỢC phải đúng giọng người dùng chọn và đúng loại việc của đoạn văn (phần giải quyết giọng).

Giọng đọc là VieNeu chạy trên máy chủ; tiếng Trung do giọng máy của điện thoại đọc. Mỗi test tái hiện một lỗi "nghe sai
giọng" có thật của máy chủ cũ và chạy hoàn toàn offline. Phần tổng hợp / route nằm ở test_tts_stream_api.py.
"""

import pytest

from services.tts_local_catalog import by_id, legacy_map
from services.tts_voice_profiles import PROFILE_VOICE_CONFIG, identify_voice
from services.tts_voice_service import KIND_DEVICE, KIND_RENDER, KIND_SILENT, resolve_tts_plan, resolve_voice_params

VI_TEXT = "Cô ấy khẽ mỉm cười rồi quay đi."
EN_TEXT = "Lin Feng smiled and said that the road was long."
ZH_TEXT = "这是一场注定碾压的死战"
FEMALE_DEFAULT = "Mỹ Duyên"
MALE_DEFAULT = "Đức Trí"


def _plan(voice, text=VI_TEXT, lang=None, tmp="/tmp/x"):
    return resolve_tts_plan(text, voice, lang, cache_dir=str(tmp))


# ---------------------------------------------------------------- nhận ra giọng

@pytest.mark.parametrize("pid,cfg", list(PROFILE_VOICE_CONFIG.items()))
def test_moi_ho_so_cu_ra_dung_giong_that_da_ghi_trong_bang(pid, cfg, tmp_path):
    assert _plan(pid, tmp=tmp_path).preset_name == by_id()[legacy_map()[pid]]["preset_name"] == cfg["voice"]


@pytest.mark.parametrize("variant", ["MINH_QUANG", " minh_quang ", "Minh Quang", "minh-quang", "minh quang", "Minh Quang (Bắc Trầm Ấm)"])
def test_id_viet_hoa_thua_khoang_trang_hoac_ten_hien_thi_van_ra_dung_ho_so(variant, tmp_path):
    """'MINH_QUANG' hay ' minh_quang' (nam) từng rơi xuống giọng nữ mặc định."""
    assert _plan(variant, tmp=tmp_path).preset_name == _plan("minh_quang", tmp=tmp_path).preset_name == MALE_DEFAULT


def test_id_giong_moi_va_ten_giong_co_dau_deu_nhan_ra(tmp_path):
    for variant in ("anh_khoi", "Anh Khôi", "ANH KHOI", "anh-khoi", "anh khôi"):
        assert _plan(variant, tmp=tmp_path).preset_name == "Anh Khôi", variant
    assert _plan("Mạnh Dũng", tmp=tmp_path).voice_id == "manh_dung"
    assert _plan("Ái Hân", tmp=tmp_path).voice_id == "ai_han"
    assert _plan("adam", tmp=tmp_path).preset_name == "Adam"


def test_minh_quan_cua_nano_khong_lan_voi_ho_so_cu_minh_quang(tmp_path):
    assert identify_voice("minh_quan").preset_name == "Minh Quân" and identify_voice("minh_quan").profile_id is None
    assert identify_voice("minh_quang").profile_id == "minh_quang" and identify_voice("minh_quang").voice_id == "duc_tri"
    assert identify_voice("minh_quang_bac_tram_am").profile_id == "minh_quang"


@pytest.mark.parametrize("raw,expected", [
    ("thien_minh", MALE_DEFAULT), ("Thiền Tâm Đức", MALE_DEFAULT), ("quang_son", MALE_DEFAULT), ("adam_bua", MALE_DEFAULT),
    ("ngoc_huyen", FEMALE_DEFAULT), ("Ngọc Trân", FEMALE_DEFAULT), ("quynh_anh", FEMALE_DEFAULT),
])
def test_id_giong_turbo_khi_chay_nano_doi_theo_gioi_tinh(raw, expected, tmp_path):
    """Máy chủ đổi Turbo -> Nano: giọng nam đã chọn ở Turbo KHÔNG được rơi về giọng nữ mặc định."""
    assert _plan(raw, tmp=tmp_path).preset_name == expected, raw


def test_id_giong_nano_khi_chay_turbo_doi_theo_gioi_tinh(monkeypatch, tmp_path):
    monkeypatch.setenv("TTS_LOCAL_MODEL", "v3turbo")
    assert _plan("anh_khoi", tmp=tmp_path).preset_name == "Thiện Minh"
    assert _plan("ai_han", tmp=tmp_path).preset_name == "Mai Anh"
    assert _plan("Ngọc Huyền", tmp=tmp_path).preset_name == "Ngọc Huyền"
    assert _plan("adam", tmp=tmp_path).preset_name == "Adam", "tên ngắn không bị nuốt bởi 'adam_bua'"


@pytest.mark.parametrize("raw,male", [
    ("male", True), ("female", False), ("nam", True), ("nu", False), ("giong_nam", True), ("giong_nu", False),
    ("hoang_nam2", True), ("vietnamese_female", False), ("vi_vietnam_nu", False),
    ("vi-VN-NamMinhNeural", True), ("vi-VN-HoaiMyNeural", False),  # tên thô app cũ từng lưu làm id
])
def test_dau_hieu_gioi_tinh_xet_theo_tung_tu_khong_theo_chuoi_con(raw, male, tmp_path):
    """'vietnamese_female' chứa 'nam' (Vietnamese) nên bản cũ đọc bằng giọng NAM; 'male' thì rơi xuống giọng nữ."""
    assert _plan(raw, tmp=tmp_path).preset_name == (MALE_DEFAULT if male else FEMALE_DEFAULT), raw


@pytest.mark.parametrize("raw", ["", "   ", None, "garbage", "tuan_anh"])
def test_id_khong_nhan_ra_doc_bang_giong_nu_mac_dinh(raw, tmp_path):
    assert _plan(raw, tmp=tmp_path).preset_name == FEMALE_DEFAULT


def test_voice_rong_hoac_thieu_giong_het_mai_phuong_de_prefetch_va_stream_trung_khoa_cache(tmp_path):
    ref = _plan("mai_phuong", tmp=tmp_path)
    for raw in ("", "   ", None):
        assert _plan(raw, tmp=tmp_path).cache_file == ref.cache_file


def test_identify_voice_tra_gioi_tinh_va_ho_so_goc():
    who = identify_voice("dinh_trong")
    assert who.is_male and who.profile_id == "dinh_trong" and who.voice_id == "duc_tri" and who.mode == "v3nano"
    assert not identify_voice("xuan_tien").is_male and identify_voice("xuan_tien").profile_id is None


# ---------------------------------------------------------------- ngôn ngữ: loại việc của đoạn

def test_tieng_viet_va_tieng_anh_cung_dung_mot_giong_thuc_su(tmp_path):
    """VieNeu đọc được cả Anh lẫn Việt bằng cùng một giọng; giọng không đổi theo ngôn ngữ."""
    vi, en = _plan("minh_quang", VI_TEXT, "vi", tmp_path), _plan("minh_quang", EN_TEXT, "en", tmp_path)
    assert vi.kind == en.kind == KIND_RENDER
    assert vi.preset_name == en.preset_name == MALE_DEFAULT


@pytest.mark.parametrize("hint", ["zh", "vi", "en", None])
def test_chi_co_chu_han_luon_la_giong_may(hint, tmp_path):
    """Đoạn toàn chữ Hán: VieNeu không đọc được; dù app gửi lang nào máy chủ cũng không dựng mà báo 'device'."""
    assert _plan("mai_phuong", ZH_TEXT, hint, tmp_path).kind == KIND_DEVICE
    assert _plan("mai_phuong", "九阳神功", hint, tmp_path).kind == KIND_DEVICE


def test_lang_zh_tuong_minh_thang_ke_ca_khi_van_ban_la_chu_viet(tmp_path):
    assert _plan("mai_phuong", VI_TEXT, "zh", tmp_path).kind == KIND_DEVICE


def test_cau_viet_xen_vai_chu_han_van_doc_bang_giong_local(tmp_path):
    text = "Hắn thi triển 九阳神功, một chiêu thức vô cùng mạnh mẽ."
    assert _plan("mai_phuong", text, "vi", tmp_path).kind == KIND_RENDER
    assert _plan("mai_phuong", text, None, tmp_path).kind == KIND_RENDER


def test_cau_anh_xen_ten_trung_quoc_van_la_giong_local(tmp_path):
    assert _plan("mai_phuong", "He met 李白 near the river and said nothing.", None, tmp_path).kind == KIND_RENDER


@pytest.mark.parametrize("separator", ["***", "* * *", "......", "…", "——", "---", "◆◆◆", "※※※", "( )", "“”", "①②③", "%", "~~~", "😀"])
def test_dau_ngan_canh_la_im_lang_khong_goi_engine(separator, tmp_path):
    plan = _plan("mai_phuong", separator, "vi", tmp_path)
    assert plan.kind == KIND_SILENT
    assert plan.key == "v5_silence"


def test_moi_dau_ngan_canh_dung_chung_mot_tep_im_lang_cho_moi_giong(tmp_path):
    files = {_plan(v, t, l, tmp_path).cache_file
             for t in ("***", "......", "◆◆◆") for v in ("mai_phuong", "minh_quang", "ngoc_tran") for l in ("vi", "en")}
    assert len(files) == 1


def test_chu_latin_toan_goc_va_chu_ghep_van_duoc_doc(tmp_path):
    """'ＯＫ' (toàn góc) đọc được; coi là 'không đọc được' thì nội dung bị nuốt mất."""
    for text in ("ＯＫ", "ﬁnal", "１２３"):
        assert _plan("mai_phuong", text, "vi", tmp_path).kind == KIND_RENDER, text


def test_chu_viet_o_dang_tach_dau_duoc_gop_ve_nfc(tmp_path):
    import unicodedata
    text = "Cô ấy khẽ mỉm cười rồi quay đi, tiếng nói dịu dàng như gió."
    decomposed = unicodedata.normalize("NFD", text)
    assert decomposed != text
    assert _plan("mai_phuong", decomposed, None, tmp_path).norm_text == text
    assert _plan("mai_phuong", decomposed, None, tmp_path).cache_file == _plan("mai_phuong", text, None, tmp_path).cache_file


# ---------------------------------------------------------------- khoá cache

def test_khoa_cache_chi_phu_thuoc_van_ban_va_giong_that(tmp_path):
    base = _plan("mai_phuong", tmp=tmp_path).cache_file
    assert base == _plan("mai_phuong", tmp=tmp_path).cache_file
    assert base != _plan("minh_quang", tmp=tmp_path).cache_file, "đổi giọng phải ra tệp khác"
    assert base != _plan("mai_phuong", "Câu khác hẳn.", tmp=tmp_path).cache_file


def test_hai_id_cung_tro_toi_mot_giong_that_dung_chung_mot_tep(tmp_path):
    assert _plan("mai_phuong", tmp=tmp_path).cache_file == _plan("google_std", tmp=tmp_path).cache_file
    assert _plan("mai_phuong", tmp=tmp_path).cache_file == _plan("my_duyen", tmp=tmp_path).cache_file
    assert _plan("thao_vy", tmp=tmp_path).cache_file == _plan("Mỹ Duyên", tmp=tmp_path).cache_file
    assert _plan("mai_phuong", tmp=tmp_path).cache_file != _plan("mai_anh", tmp=tmp_path).cache_file


def test_lang_vi_hay_en_khong_doi_khoa_cache_cua_cung_mot_doan(tmp_path):
    """App đoán lang khác máy chủ ở đoạn quá ngắn: khoá vẫn phải trùng để lần stream trúng đoạn đã dựng sẵn."""
    for text in ("Minh.", "12", VI_TEXT, EN_TEXT, "Chương 5: Ôi trời ơi!"):
        assert _plan("mai_phuong", text, "vi", tmp_path).cache_file == _plan("mai_phuong", text, "en", tmp_path).cache_file, text
        assert _plan("mai_phuong", text, None, tmp_path).cache_file == _plan("mai_phuong", text, "vi", tmp_path).cache_file, text


def test_khoa_co_tien_to_phien_ban_va_mo_hinh(tmp_path):
    plan = _plan("mai_phuong", tmp=tmp_path)
    assert plan.key == f"v5_vieneu|v3nano|{plan.norm_text}|Mỹ Duyên" and plan.mode == "v3nano"


def test_doi_mo_hinh_doi_khoa_du_cung_ten_giong(monkeypatch, tmp_path):
    """'Mai Anh' có ở cả hai mô hình nhưng là hai giọng khác nhau: không được dùng chung tệp."""
    nano = _plan("mai_anh", tmp=tmp_path)
    monkeypatch.setenv("TTS_LOCAL_MODEL", "v3turbo")
    turbo = _plan("mai_anh", tmp=tmp_path)
    assert nano.preset_name == turbo.preset_name == "Mai Anh"
    assert turbo.key.startswith("v5_vieneu|v3turbo|") and nano.cache_file != turbo.cache_file


def test_cac_ten_cu_van_import_duoc_tu_routes_va_service():
    from routes.tts import PROFILE_VOICE_CONFIG as from_routes, is_chinese, is_english, resolve_voice_params as resolve  # noqa: F401
    assert from_routes is PROFILE_VOICE_CONFIG
    assert is_chinese("这是") and not is_english("这是") and is_english("ephemeral")


def test_resolve_voice_params_cu_nhan_toc_do_va_cao_do_nhung_bo_qua(tmp_path):
    a = resolve_voice_params(VI_TEXT, "mai_phuong", 0.5, "+9Hz", "vi", cache_dir=str(tmp_path))
    b = resolve_voice_params(VI_TEXT, "mai_phuong", 2.0, None, "vi", cache_dir=str(tmp_path))
    assert a == b and a[0] == FEMALE_DEFAULT and a[3].endswith(".mp3")
