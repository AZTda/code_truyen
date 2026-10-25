"""PCM float -> MP3 hằng bitrate (CBR) cho bộ đệm TTS, thời lượng chính xác và MP3 im lặng.

Mọi tệp trong data/tts_cache có CÙNG định dạng: MPEG-2 Layer III, 24 kHz, mono, 64 kbps CBR. Mỗi khung 576 mẫu = 24 ms
= đúng 192 byte, nên tua theo thời gian của MediaPlayer/AVPlayer là chính xác (không có bảng Xing/VBR để đoán).
Thời lượng báo ở header X-Audio-Duration-Ms tính từ độ dài PCM; trình phát sẽ thấy dài hơn ~25-70 ms vì độ trễ bộ mã
hoá LAME (1105 mẫu) và khung cuối được đệm.
numpy và lameenc được nạp lười: thiếu chúng thì chỉ giọng local báo "không sẵn sàng", cả máy chủ vẫn chạy bình thường.
"""

import logging
import threading
from typing import Any, Optional, Tuple

logger = logging.getLogger("truyencloud.tts.audio")

MP3_SAMPLE_RATE = 24_000
MP3_BITRATE_KBPS = 64
BYTES_PER_SECOND = MP3_BITRATE_KBPS * 1000 // 8  # 8000: ước lượng thời lượng từ kích thước tệp CBR
SILENCE_MS = 800

# Một khung MPEG-2 Layer III 24 kHz mono 64 kbps (192 byte) toàn 0 = ~24 ms im lặng: dự phòng khi thiếu lameenc
_SILENT_FRAME = b"\xff\xf3\x84\xc0" + bytes(188)
_FRAMES_FOR_SILENCE = SILENCE_MS // 24

_silence_cache: Optional[Tuple[bytes, int]] = None
_silence_lock = threading.Lock()


def to_int16_pcm(samples: Any) -> bytes:
    """float32 [-1, 1] (mọi mảng một chiều) -> PCM int16 little-endian; NaN/vô cực thành 0, vượt biên bị cắt."""
    import numpy as np

    arr = np.asarray(samples, dtype=np.float32).reshape(-1)
    arr = np.nan_to_num(arr, nan=0.0, posinf=1.0, neginf=-1.0)
    return (np.clip(arr, -1.0, 1.0) * 32767.0).astype("<i2").tobytes()


def duration_ms(sample_count: int, sample_rate: int) -> int:
    if sample_rate <= 0:
        return 0
    return int(round(sample_count * 1000.0 / sample_rate))


def encode_mp3(pcm_int16: bytes, sample_rate: int) -> bytes:
    """Mã hoá PCM int16 mono thành MP3 CBR 64 kbps/24 kHz (LAME tự đổi tần số lấy mẫu về 24 kHz)."""
    import lameenc

    encoder = lameenc.Encoder()
    encoder.set_bit_rate(MP3_BITRATE_KBPS)
    encoder.set_in_sample_rate(int(sample_rate))
    encoder.set_out_sample_rate(MP3_SAMPLE_RATE)
    encoder.set_channels(1)
    encoder.set_quality(2)
    return bytes(encoder.encode(pcm_int16)) + bytes(encoder.flush())


def pcm_to_mp3(samples: Any, sample_rate: int) -> Tuple[bytes, int]:
    """(mp3, thời lượng ms) từ mảng PCM float của engine. Mảng rỗng -> đoạn im lặng chuẩn."""
    count = int(getattr(samples, "size", None) if hasattr(samples, "size") else len(samples))
    if count == 0:
        return silence_mp3()
    data = encode_mp3(to_int16_pcm(samples), sample_rate)
    if not data:
        raise RuntimeError("Bộ mã hoá MP3 trả về dữ liệu rỗng")
    return data, duration_ms(count, sample_rate)


def silence_mp3() -> Tuple[bytes, int]:
    """(MP3 im lặng ~0,8 giây cho đoạn không có chữ nào đọc được, thời lượng ms). Cùng định dạng mọi tệp khác."""
    global _silence_cache
    with _silence_lock:
        if _silence_cache is None:
            data = b""
            try:
                data = encode_mp3(bytes(2 * MP3_SAMPLE_RATE * SILENCE_MS // 1000), MP3_SAMPLE_RATE)
            except Exception as exc:
                logger.info("Dùng khung im lặng dựng sẵn (không mã hoá được): %s", exc)
            if not data:
                data = _SILENT_FRAME * _FRAMES_FOR_SILENCE
            _silence_cache = (data, SILENCE_MS)
        return _silence_cache


def estimate_duration_ms(mp3_size_bytes: int) -> int:
    """Ước lượng từ kích thước tệp CBR (tệp thiếu sidecar thời lượng)."""
    return int(round(mp3_size_bytes * 1000.0 / BYTES_PER_SECOND))
