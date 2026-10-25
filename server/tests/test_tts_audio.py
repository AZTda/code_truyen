"""MP3 của bộ đệm TTS: hằng bitrate (tua theo thời gian chính xác), đúng định dạng, thời lượng từ độ dài PCM."""

import numpy as np
import pytest

from services import tts_audio

FRAME_BYTES = 192  # MPEG-2 Layer III, 24 kHz, mono, 64 kbps: 72 * 64000 / 24000


def _frames(data: bytes):
    """Danh sách (kích thước, bitrate kbps, tần số) của từng khung MPEG-2 Layer III; không bỏ sót byte nào."""
    out, i = [], 0
    rates = {0: 22050, 1: 24000, 2: 16000}
    bitrates = [0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0]
    while i + 4 <= len(data):
        h = data[i:i + 4]
        assert h[0] == 0xFF and (h[1] & 0xE0) == 0xE0, f"mất đồng bộ khung ở byte {i}"
        assert (h[1] >> 3) & 3 == 2 and (h[1] >> 1) & 3 == 1, "phải là MPEG-2 Layer III"
        kbps, rate, pad = bitrates[(h[2] >> 4) & 15], rates[(h[2] >> 2) & 3], (h[2] >> 1) & 1
        size = 72 * kbps * 1000 // rate + pad
        out.append((size, kbps, rate))
        i += size
    assert i == len(data), "tệp phải kết thúc đúng ranh giới khung"
    return out


def _speech_like(seconds: float, rate: int = 48_000) -> np.ndarray:
    t = np.arange(int(seconds * rate), dtype=np.float32) / rate
    return (0.3 * np.sin(2 * np.pi * 180 * t) + 0.1 * np.sin(2 * np.pi * 2200 * t)).astype(np.float32)


def test_mp3_la_cbr_64kbps_24khz_mono_moi_khung_192_byte():
    data, ms = tts_audio.pcm_to_mp3(_speech_like(3.0), 48_000)
    frames = _frames(data)
    assert {f[0] for f in frames} == {FRAME_BYTES}, "khung cố định kích thước thì tua theo thời gian là chính xác"
    assert {f[1] for f in frames} == {64} and {f[2] for f in frames} == {24000}
    assert ms == 3000
    # thời lượng ước từ kích thước CBR khớp PCM trong ~70 ms (độ trễ bộ mã hoá + khung cuối)
    assert abs(tts_audio.estimate_duration_ms(len(data)) - ms) <= 80


@pytest.mark.parametrize("rate", [48_000, 24_000, 22_050, 16_000])
def test_moi_tan_so_lay_mau_dau_vao_deu_ra_mp3_24khz(rate):
    data, ms = tts_audio.pcm_to_mp3(_speech_like(1.0, rate), rate)
    assert {f[2] for f in _frames(data)} == {24000} and ms == 1000


def test_thoi_luong_tinh_tu_do_dai_pcm():
    assert tts_audio.duration_ms(48_000, 48_000) == 1000
    assert tts_audio.duration_ms(24_001, 48_000) == 500
    assert tts_audio.duration_ms(10, 0) == 0


def test_pcm_roi_nan_vo_cuc_va_vuot_bien_khong_lam_hong_ma_hoa():
    wav = np.array([0.0, np.nan, np.inf, -np.inf, 5.0, -5.0] * 4000, dtype=np.float32)
    data, ms = tts_audio.pcm_to_mp3(wav, 48_000)
    assert _frames(data) and ms == 500


def test_mang_rong_thanh_doan_im_lang_chuan():
    data, ms = tts_audio.pcm_to_mp3(np.zeros(0, dtype=np.float32), 48_000)
    assert (data, ms) == tts_audio.silence_mp3() and ms == 800


def test_mp3_im_lang_hop_le_cung_dinh_dang_va_dai_0_8_giay():
    data, ms = tts_audio.silence_mp3()
    frames = _frames(data)
    assert {f[0] for f in frames} == {FRAME_BYTES} and ms == 800
    assert 0.75 <= len(frames) * 0.024 <= 0.9


def test_khung_im_lang_du_phong_cung_dinh_dang():
    assert len(tts_audio._SILENT_FRAME) == FRAME_BYTES
    assert _frames(tts_audio._SILENT_FRAME * 33)[0] == (FRAME_BYTES, 64, 24000)
