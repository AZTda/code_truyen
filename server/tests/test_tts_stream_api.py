"""Tuyến /v1/tts/*: điều người nghe THỰC SỰ nhận được (engine giả, không mô hình thật, không ra mạng)."""

import json
import re

import pytest
from fastapi.testclient import TestClient

import routes.tts as tts_routes
from server import app
from services import tts_cache
from services.tts_prerender import render_queue
from services.tts_voice_profiles import PROFILE_VOICE_CONFIG
from tests._tts_fakes import FakeEngine, install, wait_until

client = TestClient(app, raise_server_exceptions=False)

VI_TEXT = "Cô ấy khẽ mỉm cười rồi quay đi."


def frames_ok(data: bytes) -> bool:
    return len(data) % 192 == 0 and data[:2] == b"\xff\xf3" and len(data) >= 192


def stream(text=VI_TEXT, **params):
    params = {"text": text, "voice": "mai_phuong", "lang": "vi", **params}
    return client.get("/v1/tts/stream", params=params)


# ---------------------------------------------------------------- đường chính: cache hit / miss

def test_stream_dung_mp3_cbr_co_header_thoi_luong_va_lan_hai_trung_cache():
    engine = install(FakeEngine(seconds_per_char=0.05))
    first = stream()
    assert first.status_code == 200 and first.headers["content-type"] == "audio/mpeg"
    assert frames_ok(first.content), "MP3 CBR 64 kbps/24 kHz: mọi khung 192 byte"
    assert first.headers["accept-ranges"] == "bytes"
    assert first.headers["cache-control"] == "public, max-age=604800"
    assert first.headers["content-length"] == str(len(first.content))
    duration = int(first.headers["x-audio-duration-ms"])
    assert abs(duration - len(VI_TEXT) * 50) <= 60
    second = stream()
    assert second.content == first.content and second.headers["x-audio-duration-ms"] == str(duration)
    assert len(engine.calls) == 1 and engine.calls[0][1] == "Mỹ Duyên"


def test_toc_do_va_cao_do_duoc_nhan_nhung_bo_qua_cung_mot_tep():
    engine = install()
    a = stream(speed=0.5)
    b = stream(speed=2.0, pitch="+9Hz")
    assert a.status_code == b.status_code == 200
    assert a.content == b.content and a.headers["etag"] == b.headers["etag"]
    assert len(engine.calls) == 1


def test_hai_ho_so_cung_giong_that_dung_chung_tep():
    engine = install()
    assert stream(voice="mai_phuong").headers["etag"] == stream(voice="my_duyen").headers["etag"]
    assert len(engine.calls) == 1
    assert stream(voice="minh_quang").headers["etag"] != stream(voice="mai_phuong").headers["etag"]
    assert engine.calls[-1][1] == "Đức Trí"


def test_tieng_anh_dung_cung_giong_da_chon():
    engine = install()
    res = stream("Lin Feng smiled and said that the road was long.", lang="en", voice="xuan_tien")
    assert res.status_code == 200 and engine.calls == [("Lin Feng smiled and said that the road was long.", "Xuân Tiên")]


# ---------------------------------------------------------------- Range (tua chính xác theo thời gian)

def test_range_tra_206_dung_khoang_va_header():
    install()
    full = stream("Một đoạn đủ dài để có nhiều khung âm thanh khi nghe thử tính năng tua theo thời gian.")
    size = len(full.content)
    text = "Một đoạn đủ dài để có nhiều khung âm thanh khi nghe thử tính năng tua theo thời gian."

    part = client.get(
        "/v1/tts/stream", params={"text": text, "voice": "mai_phuong", "lang": "vi"}, headers={"Range": "bytes=192-575"})
    assert part.status_code == 206
    assert part.headers["content-range"] == f"bytes 192-575/{size}"
    assert part.headers["content-length"] == "384"
    assert part.headers["accept-ranges"] == "bytes" and "x-audio-duration-ms" in part.headers
    assert part.content == full.content[192:576]


@pytest.mark.parametrize("header,start,end", [("bytes=100-", 100, None), ("bytes=-50", None, None), ("bytes=0-0", 0, 0), ("bytes=10-99999999", 10, None)])
def test_range_mo_va_hau_to(header, start, end):
    install()
    full = stream().content
    res = client.get("/v1/tts/stream", params={"text": VI_TEXT, "voice": "mai_phuong", "lang": "vi"}, headers={"Range": header})
    assert res.status_code == 206
    if header == "bytes=-50":
        assert res.content == full[-50:] and res.headers["content-range"] == f"bytes {len(full) - 50}-{len(full) - 1}/{len(full)}"
    else:
        stop = len(full) if end is None else end + 1
        assert res.content == full[start:stop]


def test_range_vuot_qua_kich_thuoc_tra_416():
    install()
    size = len(stream().content)
    res = client.get("/v1/tts/stream", params={"text": VI_TEXT, "voice": "mai_phuong", "lang": "vi"}, headers={"Range": f"bytes={size + 5}-"})
    assert res.status_code == 416 and res.headers["content-range"] == f"bytes */{size}"


def test_range_nhieu_khoang_hoac_hong_thi_tra_ca_tep():
    install()
    full = stream().content
    for header in ("bytes=0-10,20-30", "bytes=abc", "items=0-5"):
        res = client.get("/v1/tts/stream", params={"text": VI_TEXT, "voice": "mai_phuong", "lang": "vi"}, headers={"Range": header})
        assert res.status_code == 200 and res.content == full, header


def test_if_range_khong_khop_thi_bo_qua_range():
    install()
    first = stream()
    ok = client.get("/v1/tts/stream", params={"text": VI_TEXT, "voice": "mai_phuong", "lang": "vi"},
                    headers={"Range": "bytes=0-9", "If-Range": first.headers["etag"]})
    stale = client.get("/v1/tts/stream", params={"text": VI_TEXT, "voice": "mai_phuong", "lang": "vi"},
                       headers={"Range": "bytes=0-9", "If-Range": '"cu"'})
    assert ok.status_code == 206 and stale.status_code == 200 and stale.content == first.content


# ---------------------------------------------------------------- khi giọng local không dùng được: 503 gọn, không 500

def test_engine_chua_nap_thi_503_local_tts_unavailable_co_retry_after():
    res = stream()
    assert res.status_code == 503 and res.json() == {"detail": "local_tts_unavailable"}
    assert int(res.headers["retry-after"]) >= 1 and res.headers["x-local-tts-reason"] in ("loading", "failed")


def test_tat_bang_bien_moi_truong_thi_503_disabled(monkeypatch):
    from services.tts_local import local_tts
    monkeypatch.setenv("TTS_LOCAL_ENABLED", "0")
    local_tts.start_warmup()
    res = stream()
    assert res.status_code == 503 and res.headers["x-local-tts-reason"] == "disabled"
    assert render_queue.length() == 0


def test_engine_nem_loi_thi_503_khong_phai_500_va_khong_ghi_cache():
    install(FakeEngine(fail_on={"mỉm"}))
    res = stream()
    assert res.status_code == 503 and res.json() == {"detail": "local_tts_unavailable"}
    assert tts_cache.usage(tts_routes.CACHE_DIR)[0] == 0


def test_dung_qua_cham_het_gio_cho_thi_503(monkeypatch):
    from services import tts_synthesis
    engine = FakeEngine()
    import threading
    engine.gate = threading.Event()
    install(engine)
    monkeypatch.setattr(tts_synthesis, "ondemand_timeout", lambda: 0.1)
    res = stream()
    assert res.status_code == 503 and res.headers["x-local-tts-reason"] == "timeout"
    engine.gate.set()
    assert wait_until(lambda: tts_cache.usage(tts_routes.CACHE_DIR)[0] == 1), "việc vẫn chạy tiếp để lần sau trúng cache"
    assert stream().status_code == 200


def test_ngung_chu_den_khi_cache_trung_thi_phuc_vu_du_engine_da_hong():
    install()
    assert stream().status_code == 200
    from services.tts_local import local_tts
    local_tts.reset()
    again = stream()
    assert again.status_code == 200 and frames_ok(again.content)


# ---------------------------------------------------------------- tiếng Trung, im lặng, văn bản quá dài

def test_tieng_trung_422_unsupported_language_va_khong_vao_hang_doi():
    engine = install()
    for lang in ("zh", "vi", None):
        res = client.get("/v1/tts/stream", params={"text": "林风缓缓抬起头，目光冷冽地望向远方。", **({"lang": lang} if lang else {})})
        assert res.status_code == 422 and res.json() == {"detail": "unsupported_language"}
    assert engine.calls == [] and render_queue.length() == 0


@pytest.mark.parametrize("separator", ["***", "......", "◆◆◆", "——", "( )"])
def test_dau_ngan_canh_tra_200_mp3_im_lang_ke_ca_khi_engine_chua_nap(separator):
    res = stream(separator)
    assert res.status_code == 200 and res.headers["content-type"] == "audio/mpeg"
    assert frames_ok(res.content) and res.headers["x-audio-duration-ms"] == "800"


def test_van_ban_qua_dai_422_text_too_long(monkeypatch):
    install()
    monkeypatch.setenv("TTS_MAX_TEXT_CHARS", "100")
    res = stream("Từ " * 60)
    assert res.status_code == 422 and res.json() == {"detail": "text_too_long"}
    assert stream("Ngắn thôi.").status_code == 200


def test_text_rong_la_400():
    assert client.get("/v1/tts/stream", params={"text": "   "}).status_code == 400


# ---------------------------------------------------------------- tiền nạp

def test_prefetch_next_duoc_dung_san_sau_doan_dang_nghe():
    engine = install()
    res = stream("Đoạn đang nghe.", prefetch_next="Đoạn kế tiếp ngay sau.")
    assert res.status_code == 200
    assert wait_until(lambda: [t for t, _ in engine.calls] == ["Đoạn đang nghe.", "Đoạn kế tiếp ngay sau."])
    assert wait_until(lambda: tts_cache.usage(tts_routes.CACHE_DIR)[0] == 2)
    assert len(stream("Đoạn kế tiếp ngay sau.").content) > 0 and len(engine.calls) == 2


def test_prefetch_xep_hang_uu_tien_1_va_tra_dung_hinh_dang_cu():
    engine = install()
    res = client.post("/v1/tts/prefetch", json={"texts": ["Câu một.", "Câu hai.", "   ", "林风缓缓抬起头"], "voice": "minh_quang", "speed": 1.5, "lang": "vi"})
    assert res.status_code == 200
    body = res.json()
    assert body["status"] == "ok" and body["queued"] == 2 and body["device"] == 1
    assert wait_until(lambda: [t for t, _ in engine.calls] == ["Câu một.", "Câu hai."])
    assert {v for _, v in engine.calls} == {"Đức Trí"}


def test_prefetch_mo_cho_khach_va_khong_loi_khi_engine_chua_nap():
    res = client.post("/v1/tts/prefetch", json={"texts": ["a", "b"]})
    assert res.status_code == 200 and res.json()["queued"] == 0
    assert render_queue.length() == 0


def test_prefetch_voice_rong_ra_cung_giong_mai_phuong():
    engine = install()
    client.post("/v1/tts/prefetch", json={"texts": ["Câu ba."], "voice": ""})
    assert wait_until(lambda: engine.calls == [("Câu ba.", "Mỹ Duyên")])


# ---------------------------------------------------------------- /synthesize, /voices, /status

def test_synthesize_audio_url_giu_nguyen_voice_da_chon_va_ma_hoa():
    install()
    from services.auth_token import issue_token
    headers = {"Authorization": f"Bearer {issue_token('tts_user', 'tts_user')}"}
    res = client.post("/v1/tts/synthesize", json={"text": "Tom & Jerry #1 + bạn", "voice": "minh_quang", "speed": 1.25, "lang": "vi"}, headers=headers)
    assert res.status_code == 200
    body = res.json()
    assert body["cached"] is True and body["profile"] == "minh_quang"
    assert "voice=minh_quang" in body["audio_url"] and "speed=1.25" in body["audio_url"] and "lang=vi" in body["audio_url"]
    assert "Tom & Jerry" not in body["audio_url"] and "%26" in body["audio_url"]


def test_synthesize_khi_engine_chua_san_sang_van_200_nhung_cached_false():
    from services.auth_token import issue_token
    headers = {"Authorization": f"Bearer {issue_token('tts_user', 'tts_user')}"}
    res = client.post("/v1/tts/synthesize", json={"text": "Xin chào", "voice": "mai_phuong"}, headers=headers)
    assert res.status_code == 200 and res.json()["cached"] is False


def test_synthesize_can_dang_nhap():
    assert client.post("/v1/tts/synthesize", json={"text": "Xin chào"}).status_code == 401


SERVICE_NAMES = re.compile(r"microsoft|hoài my|hoai my|nam minh|namminh|hoaimy|neural|edge", re.IGNORECASE)


def test_voices_giu_khoa_cu_va_them_danh_muc_that():
    data = client.get("/v1/tts/voices").json()
    assert data["status"] == "ok"
    assert [v["id"] for v in data["voices"]] == list(PROFILE_VOICE_CONFIG)
    for v in data["voices"]:
        for key in ("id", "name", "voice_model", "gender", "pitch_offset", "rate_offset", "description", "voice_models"):
            assert key in v, (v["id"], key)
        assert v["voice_model"] == PROFILE_VOICE_CONFIG[v["id"]]["voice"]
    assert len(data["catalog"]) == 11 and data["default_voice"] == "my_duyen"
    assert data["default_voices"] == {"female": "my_duyen", "male": "duc_tri"}
    assert {c["region"] for c in data["catalog"]} == {"Bắc", "Nam"}
    assert all(c["description"].startswith("Giọng thật, miền ") and c["model"] == "v3nano" for c in data["catalog"])
    assert data["engine"]["name"] == "vieneu" and data["engine"]["model"] == "v3nano"
    assert data["engine"]["device_languages"] == ["zh"] and data["engine"]["languages"] == ["vi", "en"]
    assert data["legacy_map"]["mai_phuong"] == "my_duyen" and data["legacy_map"]["hoang_nam"] == "duc_tri"
    assert [b["gender"] for b in data["base_voices"]] == ["Nữ", "Nam"] and data["note"]
    assert [b["voice_model"] for b in data["base_voices"]] == ["Mỹ Duyên", "Đức Trí"]


def test_voices_theo_mo_hinh_turbo_khi_cau_hinh(monkeypatch):
    monkeypatch.setenv("TTS_LOCAL_MODEL", "v3turbo")
    data = client.get("/v1/tts/voices").json()
    assert len(data["catalog"]) == 25 and data["default_voice"] == "mai_anh" and data["engine"]["model"] == "v3turbo"
    assert {c["region"] for c in data["catalog"]} == {"Bắc", "Trung", "Nam"}
    assert [v["id"] for v in data["voices"]] == list(PROFILE_VOICE_CONFIG), "mười id cũ vẫn đủ ở mọi mô hình"


def test_voices_khong_con_nhac_toi_dich_vu_cu():
    raw = json.dumps(client.get("/v1/tts/voices").json(), ensure_ascii=False)
    assert not SERVICE_NAMES.search(raw), SERVICE_NAMES.search(raw)


def test_status_dung_hinh_dang_va_khong_lo_bi_mat():
    cold = client.get("/v1/tts/status").json()
    assert cold["engine"] == "vieneu" and cold["model_loaded"] is False and cold["queue_length"] == 0
    assert cold["avg_rtf"] is None and cold["cache_files"] == 0
    install(FakeEngine(delay=0.01))
    stream()
    warm = client.get("/v1/tts/status").json()
    assert warm["model_loaded"] is True and warm["cache_files"] == 1 and warm["cache_mb"] >= 0
    assert warm["avg_rtf"] is not None and warm["rendered_in_process"] == 1 and warm["model"] == "v3nano"
    for key in ("cache_files", "cache_mb", "queue_length", "avg_rtf", "model_loaded", "engine"):
        assert key in warm
    assert not re.search(r"token|secret|key|password", json.dumps(warm), re.IGNORECASE)
