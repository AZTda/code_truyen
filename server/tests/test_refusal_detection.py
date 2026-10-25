"""Lời từ chối của AI phải bị bắt kể cả khi nguồn ngắn (< 200 chữ Hán) hay viết bằng nháy cong/markdown.

Bản cũ: danh sách mở đầu ít và khớp chuỗi thô ("I'm sorry, but I can" mà không phải "I’m sorry"), còn với nguồn < 200
chữ Hán is_truncated() trả False nên mọi lời từ chối khác đều được nhận là bản dịch, lưu chung cho mọi độc giả.
"""

import pytest

from services.translation_validation import (
    is_refusal,
    is_truncated,
    is_untranslated_reply,
    is_usable_translation,
    validate_ai_translation,
)

SHORT_SOURCE = "林动握紧长剑，目光冷冽地望向远处的山门。" * 6      # 120 chữ Hán: chương ngắn / ghi chú tác giả
LONG_SOURCE = "林动握紧长剑，目光冷冽地望向远处的山门。" * 30      # 600 chữ Hán

REFUSALS = [
    "I'm sorry, but I can't assist with that request.",
    "I’m sorry, but I can’t assist with that request.",
    "Sorry, I can't help with that.",
    "I apologize, but I cannot translate this content.",
    "I am sorry, but I am unable to help with this.",
    "Unfortunately, I cannot provide a translation of this text.",
    "I can't assist with that.",
    "I cannot fulfill this request.",
    "As an AI language model, I cannot translate this.",
    "**I'm sorry, but I can't help with that.**",
    "> I'm sorry, but I can't assist",
    "# I cannot fulfill this request",
    "_Sorry, I can't help with that._",
    "Dưới đây là bản dịch: I'm sorry, but I can't assist",
    "Xin lỗi, tôi không thể dịch nội dung này.",
    "Xin lỗi, nhưng tôi không thể dịch nội dung này.",
    "Tôi xin lỗi, tôi không thể dịch đoạn này.",
    "Rất tiếc, tôi không thể hỗ trợ yêu cầu này.",
    "Tôi không thể dịch nội dung này.",
    "**Xin lỗi, tôi không thể giúp với yêu cầu này.**",
    "抱歉，我无法翻译这段内容。",
    "The prompt could not be submitted because it may violate Google's policy",
]


@pytest.mark.parametrize("text", REFUSALS)
def test_moi_cach_viet_loi_tu_choi_deu_bi_bat(text):
    assert is_refusal(text)
    assert not is_usable_translation(text)


@pytest.mark.parametrize("text", REFUSALS)
@pytest.mark.parametrize("source", [SHORT_SOURCE, LONG_SOURCE], ids=["nguon-ngan", "nguon-dai"])
def test_nguon_ngan_hay_dai_deu_khong_nhan_loi_tu_choi_la_ban_dich(text, source):
    assert validate_ai_translation(source, text, "vi") == "refusal"


@pytest.mark.parametrize("text", [
    "“Xin lỗi, tôi không thể đi cùng ngươi.” hắn nói, quay lưng bước đi.",
    "— Xin lỗi, tôi không thể ở lại đây thêm nữa.",
    "\"I'm sorry, but I can't let you go,\" she whispered.",
    "Tôi không thể tin nổi vào mắt mình, hắn lẩm bẩm.",
    "I can see the mountain from here, the old man said.",
    "Sorry to keep you waiting, said the old man with a smile.",
    "He was sorry, but he could not stop.",
    "As an AI researcher, she had learned patience long ago.",
    "Unfortunately for him, the sword was already gone.",
    "Lâm Động siết chặt thanh trường kiếm, ánh mắt lạnh lẽo nhìn về sơn môn phía xa.",
])
def test_loi_thoai_va_van_xuoi_binh_thuong_khong_bi_coi_la_tu_choi(text):
    assert not is_refusal(text)


def test_loi_thoai_giua_chuong_khong_bi_coi_la_tu_choi_du_chuong_dai():
    body = "Lâm Động siết chặt thanh trường kiếm, ánh mắt lạnh lẽo. " * 40
    assert not is_refusal(body + "\n“Xin lỗi, tôi không thể đi cùng ngươi.”\n" + "Hắn quay đi. " * 20)


# ---------------------------------------------------------------------------
# Nguồn ngắn: không còn lọt qua bước kiểm tra độ dài
# ---------------------------------------------------------------------------

def test_nguon_ngan_ma_dau_ra_ngan_hon_nua_ban_goc_la_cut():
    assert len(SHORT_SOURCE) > 100
    assert is_truncated(SHORT_SOURCE, "Ok, đã dịch.", "vi")
    assert validate_ai_translation(SHORT_SOURCE, "Đã dịch xong.", "vi") == "truncated"


def test_nguon_ngan_dich_that_van_hop_le():
    real = ("Lâm Động siết chặt thanh trường kiếm, ánh mắt lạnh lẽo nhìn về sơn môn phía xa. " * 6).strip()
    assert validate_ai_translation(SHORT_SOURCE, real, "vi") is None
    assert validate_ai_translation("新书已肥", "Sách mới đã béo", "vi") is None
    assert validate_ai_translation("第一章", "Chương 1", "vi") is None


def test_dich_vi_ma_dau_ra_chi_co_chu_la_tinh_khong_dau_la_tra_loi_bang_tieng_anh():
    english = "Here is a summary of the chapter you provided, written in plain English for you to read."
    assert len(english) > 0.5 * len(SHORT_SOURCE)
    assert is_untranslated_reply(SHORT_SOURCE, english, "vi")
    assert validate_ai_translation(SHORT_SOURCE, english, "vi") == "not_vietnamese"
    # Đích tiếng Anh / song ngữ thì tiếng Anh là đúng
    assert validate_ai_translation(SHORT_SOURCE, english, "en") is None
    assert not is_untranslated_reply(SHORT_SOURCE, "[EN] Hello there my friend.\n[VI] Xin chào bạn.", "bilingual")
    # Nguồn dài / không phải tiếng Trung: không áp quy tắc này
    assert not is_untranslated_reply(LONG_SOURCE, english, "vi")
    assert not is_untranslated_reply("Plain English source text here.", english, "vi")


def test_ban_dich_vi_co_dau_khong_bi_nham_la_tieng_anh():
    assert not is_untranslated_reply(SHORT_SOURCE, "Lâm Động nắm chặt trường kiếm, nhìn về phía sơn môn.", "vi")


@pytest.mark.asyncio
async def test_chuong_dai_bi_refusal_tu_dong_tach_doi_dich_qua_ai_khong_ton_cpu(monkeypatch):
    from services import translation_cache_store
    from services.local_chapter_mt import translate_with_refusal_split
    from services.translation_worker import translation_worker

    long_src = "\n".join(
        f"第{i}段。林动握紧长剑，目光冷冽地望向远处的山门，心中暗暗发誓一定要变强。" for i in range(24)
    )
    assert len(long_src) >= 600
    vi_line = (
        "Lâm Động siết chặt thanh trường kiếm, ánh mắt lạnh lẽo nhìn về sơn môn phía xa, "
        "trong lòng thầm thề nhất định phải trở nên mạnh mẽ."
    )
    calls = []

    async def fake_ai(part, *args, **kwargs):
        calls.append(len(part.splitlines()))
        if len(part.splitlines()) > 12:
            return "The prompt could not be submitted because it may violate Google policy"
        return "\n".join(f"Đoạn {i}. {vi_line}" for i in range(len(part.splitlines())))

    monkeypatch.setattr(translation_cache_store, "read", lambda *a, **k: None)
    monkeypatch.setattr(translation_cache_store, "write", lambda *a, **k: None)
    monkeypatch.setattr(translation_worker, "is_agy_ready", lambda *a, **k: True)
    monkeypatch.setattr(translation_worker, "_translate_via_agy_cli", fake_ai)

    res = await translation_worker.translate_chapter_detailed(long_src, allow_fallback=False)
    assert res is not None and not res.is_fallback
    assert calls == [24, 12, 12], "Nguyên chương bị từ chối thì tách đôi dịch luôn qua AI, không thử lại nguyên chương lần 2"

