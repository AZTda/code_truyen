"""Chuẩn bị tệp mô hình VieNeu-TTS trên đĩa: tải một lần, nằm trong data/ (sống sót khi tạo lại container).

Hai thứ không tự có:
1. Thư mục mô hình phải nằm trong volume: HF_HOME = TTS_MODEL_DIR (mặc định server/data/hf).
2. onnxruntime TỪ CHỐI đọc dữ liệu ngoài (`.data`) khi đường dẫn đi qua symlink của bộ đệm HuggingFace
   ("External data path escapes model directory"; gặp ở Turbo). Nên sau khi tải, mọi symlink dưới snapshots/ được thay
   bằng tệp thật (hardlink tới blob, không tốn thêm dung lượng; khác hệ tệp thì chép). Nano không có tệp `.data` nhưng
   vẫn làm như vậy cho đồng nhất.
Có đủ tệp rồi thì bật chế độ offline của huggingface_hub: khởi động không còn chờ HEAD request (máy mất mạng cũng chạy).
Dung lượng tải lần đầu: Nano ~270 MB (4 đồ thị ONNX + config + constants), Turbo ~550 MB.
"""

import logging
import os
import shutil
import sys
import uuid
from typing import Callable, Dict, List, Optional, Tuple

from services.tts_catalog_data import MODE_NANO
from services.tts_local_catalog import active_mode

logger = logging.getLogger("truyencloud.tts.model")

BACKBONE_REPO = "pnnbao-ump/VieNeu-TTS-v3-Turbo"
ONNX_SUBFOLDER = "onnx_update"
CODEC_REPO = "OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX"
NANO_REPO = "pnnbao-ump/VieNeu-TTS-v3-Nano"
# Chỉ các tệp để đọc bằng giọng có sẵn; tệp nhân bản giọng (speaker/codec/reference encoder) không tải.
_NANO_FILES = ["text_encoder.onnx", "duration_predictor.onnx", "vector_estimator.onnx", "codec_decoder.onnx",
               "config.json", "constants.npz"]
_GRAPH_FILES = [
    "vieneu_prefill.onnx", "vieneu_decode_step.onnx", "vieneu_acoustic_cached.onnx",
    "vieneu_backbone_shared.data", "vieneu_v3_heads.npz", "config.json", "tokenizer.json",
]
_CODEC_FILES = [
    "moss_audio_tokenizer_decode_full.onnx", "moss_audio_tokenizer_decode_shared.data",
    "moss_audio_tokenizer_decode_step.onnx", "codec_browser_onnx_meta.json",
    "moss_audio_tokenizer_encode.onnx", "moss_audio_tokenizer_encode.data",
]
_OPTIONAL_SUFFIX = (".json",)


def model_root() -> str:
    configured = (os.getenv("TTS_MODEL_DIR") or "").strip()
    if configured:
        return os.path.abspath(configured)
    return os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "data", "hf")


def configure_environment(root: Optional[str] = None) -> str:
    """Đặt HF_HOME trước khi huggingface_hub/vieneu được nạp (và vá hằng số nếu đã nạp)."""
    root = root or model_root()
    os.makedirs(root, exist_ok=True)
    os.environ["HF_HOME"] = root
    os.environ.pop("HF_HUB_CACHE", None)
    os.environ.pop("HUGGINGFACE_HUB_CACHE", None)
    os.environ.setdefault("HF_HUB_DISABLE_TELEMETRY", "1")
    constants = sys.modules.get("huggingface_hub.constants")
    if constants is not None:
        hub = os.path.join(root, "hub")
        for name, value in (("HF_HOME", root), ("HF_HUB_CACHE", hub), ("HUGGINGFACE_HUB_CACHE", hub)):
            if hasattr(constants, name):
                setattr(constants, name, value)
    return root


def _nano_files() -> List[Tuple[str, str, Optional[str]]]:
    names, repo = list(_NANO_FILES), NANO_REPO
    try:
        from vieneu import v3nano

        names = list(getattr(v3nano, "_GRAPH_FILES", names))
        repo = getattr(v3nano, "_NANO_REPO", repo)
    except Exception:
        pass
    return [(repo, name, None) for name in names]


def required_files(mode: Optional[str] = None) -> List[Tuple[str, str, Optional[str]]]:
    """[(repo, tên tệp, thư mục con)]. Danh sách tệp lấy từ chính gói vieneu để khớp phiên bản đã cài."""
    if (mode or active_mode()) == MODE_NANO:
        return _nano_files()
    graph, codec = list(_GRAPH_FILES), list(_CODEC_FILES)
    repo, codec_repo = BACKBONE_REPO, CODEC_REPO
    try:
        from vieneu._v3_turbo_engine import onnx_runtime_lite as lite

        graph = list(getattr(lite, "_GRAPH_FILES", graph))
        codec = list(getattr(lite, "_CODEC_FILES", codec))
        repo = getattr(lite, "_V3_REPO", repo)
        codec_repo = getattr(lite, "_CODEC_REPO", codec_repo)
    except Exception:
        pass
    items: List[Tuple[str, str, Optional[str]]] = [(repo, name, ONNX_SUBFOLDER) for name in graph]
    items += [(codec_repo, name, None) for name in codec]
    items.append((repo, "config.json", None))
    return items


def materialize(path: str) -> bool:
    """Thay symlink bằng tệp thật (hardlink, hoặc chép nếu khác hệ tệp). True nếu đã đổi."""
    if not os.path.islink(path):
        return False
    target = os.path.realpath(path)
    if not os.path.isfile(target):
        return False
    tmp = f"{path}.tmp_{uuid.uuid4().hex}"
    try:
        try:
            os.link(target, tmp)
        except OSError:
            shutil.copy2(target, tmp)
        os.replace(tmp, path)
    except BaseException:
        try:
            os.remove(tmp)
        except OSError:
            pass
        raise
    return True


def materialize_tree(root: str) -> int:
    """Thay mọi symlink dưới <root>/hub/**/snapshots/**. Trả về số tệp đã đổi."""
    changed = 0
    for current, _dirs, files in os.walk(os.path.join(root, "hub")):
        if os.sep + "snapshots" + os.sep not in current + os.sep:
            continue
        for name in files:
            full = os.path.join(current, name)
            try:
                if materialize(full):
                    changed += 1
            except OSError as exc:
                logger.warning("Không đổi được symlink mô hình %s: %s", full, exc)
    return changed


def _cached_path(repo: str, name: str, sub: Optional[str]) -> Optional[str]:
    from huggingface_hub import try_to_load_from_cache

    filename = f"{sub}/{name}" if sub else name
    found = try_to_load_from_cache(repo, filename)
    return found if isinstance(found, str) and os.path.exists(found) else None


def is_complete(items: Optional[List[Tuple[str, str, Optional[str]]]] = None) -> bool:
    """Mọi tệp bắt buộc (trừ các tệp .json tuỳ chọn) đã có trong bộ đệm và là tệp thật."""
    try:
        for repo, name, sub in items or required_files():
            path = _cached_path(repo, name, sub)
            if path is None:
                if name.endswith(_OPTIONAL_SUFFIX):
                    continue
                return False
            if os.path.islink(path):
                return False
        return True
    except Exception:
        return False


def ensure_model_files(progress: Optional[Callable[[str], None]] = None, mode: Optional[str] = None) -> Dict[str, object]:
    """Tải tệp còn thiếu (cần mạng lần đầu), thay symlink bằng tệp thật, rồi bật chế độ offline nếu đã đủ.

    Trả về {"downloaded", "materialized", "model_dir"}; `model_dir` là thư mục snapshot chứa đồ thị (Nano nạp thẳng từ
    đó, không gọi mạng). Ném ngoại lệ khi thiếu tệp bắt buộc và không tải được: tts_local báo "không sẵn sàng", thử lại sau.
    """
    root = configure_environment()
    from huggingface_hub import hf_hub_download

    items = required_files(mode)
    downloaded = 0
    if not is_complete(items):
        for repo, name, sub in items:
            if _cached_path(repo, name, sub) is not None:
                continue
            if progress:
                progress(f"{repo}/{name}")
            try:
                hf_hub_download(repo, name, subfolder=sub)
                downloaded += 1
            except Exception:
                if name.endswith(_OPTIONAL_SUFFIX):
                    continue
                raise
    changed = materialize_tree(root)
    complete = is_complete(items)
    if complete:
        os.environ["HF_HUB_OFFLINE"] = "1"
        constants = sys.modules.get("huggingface_hub.constants")
        if constants is not None and hasattr(constants, "HF_HUB_OFFLINE"):
            constants.HF_HUB_OFFLINE = True
    else:
        raise RuntimeError("Thiếu tệp mô hình VieNeu-TTS sau khi tải")
    first = _cached_path(*items[0])
    return {"downloaded": downloaded, "materialized": changed, "model_dir": os.path.dirname(first) if first else None}
