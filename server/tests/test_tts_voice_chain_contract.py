"""Nửa phía máy chủ của hợp đồng "giọng nghe được == giọng đã chọn".

App (TtsVoiceChainScenarioTest.kt) luôn gửi `voice=<id hồ sơ>` và `lang=vi|en|zh` cho từng đoạn. Bảng dưới là những cặp
(id, lang) mà app gửi cho các kịch bản đó; mỗi dòng nêu GIỌNG THẬT mà người nghe nhận được và loại việc
(render = máy chủ dựng bằng VieNeu, device = giọng máy của điện thoại đọc, silent = không có chữ nào để đọc).
"""

import pytest

from services.tts_voice_profiles import PROFILE_VOICE_CONFIG
from services.tts_voice_service import resolve_tts_plan

# (id app gửi, lang app gửi, đoạn mẫu, giọng thật mong đợi, loại việc)
CHAIN = [
    # (i) truyện Việt, nữ
    ("mai_phuong", "vi", "Lâm Phong chậm rãi ngẩng đầu.", "Mỹ Duyên", "render"),
    ("mai_phuong", "vi", "Minh.", "Mỹ Duyên", "render"),         # tên không dấu theo chương Việt (app gửi lang=vi)
    ("mai_phuong", "vi", "12", "Mỹ Duyên", "render"),            # số trong truyện Việt
    ("mai_phuong", "en", "Hello, how are you?", "Mỹ Duyên", "render"),  # cùng một giọng cho tiếng Anh
    # (ii) truyện Việt, nam
    ("minh_quang", "vi", "Lâm Phong chậm rãi ngẩng đầu.", "Đức Trí", "render"),
    ("minh_quang", "vi", "12", "Đức Trí", "render"),
    ("minh_quang", "en", "Hello, how are you?", "Đức Trí", "render"),
    # (iii) song ngữ: đoạn Anh và đoạn Việt xen kẽ vẫn cùng một giọng đã chọn
    ("minh_quang", "en", "She walked into the room and looked around.", "Đức Trí", "render"),
    ("minh_quang", "vi", "Xin chào, bạn khỏe không?", "Đức Trí", "render"),
    ("mai_phuong", "en", "She walked into the room and looked around.", "Mỹ Duyên", "render"),
    # (iv) truyện gốc tiếng Trung: giọng máy của điện thoại đọc, máy chủ không dựng
    ("mai_phuong", "zh", "林风缓缓抬起头，目光冷冽地望向远方。", "Mỹ Duyên", "device"),
    ("minh_quang", "zh", "林风缓缓抬起头，目光冷冽地望向远方。", "Đức Trí", "device"),
    ("minh_quang", "zh", "12", "Đức Trí", "device"),          # lang=zh tường minh thắng
    ("minh_quang", "en", "Lin Feng", "Đức Trí", "render"),
    # (v) giọng mới chọn từ danh mục
    ("anh_khoi", "vi", "Gió thổi qua mái hiên cũ.", "Anh Khôi", "render"),
    ("xuan_tien", "vi", "Gió thổi qua mái hiên cũ.", "Xuân Tiên", "render"),
    ("my_duyen", "en", "She walked into the room and looked around.", "Mỹ Duyên", "render"),
]


@pytest.mark.parametrize("profile,lang,text,expected_voice,kind", CHAIN)
def test_id_va_lang_cua_app_ra_dung_giong_that(tmp_path, profile, lang, text, expected_voice, kind):
    plan = resolve_tts_plan(text, profile, lang, cache_dir=str(tmp_path))
    assert (plan.preset_name, plan.kind) == (expected_voice, kind)


def test_nghe_thu_moi_ho_so_cung_giong_voi_luc_doc(tmp_path):
    """Nghe thử dùng đúng URL builder và id như phiên đọc: cùng (id, lang) -> cùng tệp âm thanh."""
    for pid, cfg in PROFILE_VOICE_CONFIG.items():
        sample = f"Xin chào! Tôi là {cfg['name']}."
        preview = resolve_tts_plan(sample, pid, "vi", cache_dir=str(tmp_path))
        reading = resolve_tts_plan(sample, pid, "vi", cache_dir=str(tmp_path))
        assert preview == reading
        assert preview.preset_name == cfg["voice"]
