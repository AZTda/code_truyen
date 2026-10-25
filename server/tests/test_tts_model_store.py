"""Tệp mô hình VieNeu trên đĩa: thư mục trong data/, danh sách tệp theo mô hình, quirk symlink của bộ đệm HF."""

import os
import sys
import types

import pytest

from services import tts_model_store


def test_thay_symlink_bang_tep_that_hardlink_khong_ton_them_dung_luong(tmp_path):
    blob = tmp_path / "blobs" / "abc"
    blob.parent.mkdir()
    blob.write_bytes(b"x" * 4096)
    snap = tmp_path / "hub" / "models--a--b" / "snapshots" / "rev"
    snap.mkdir(parents=True)
    link = snap / "weights.data"
    os.symlink(blob, link)
    assert os.path.islink(link)
    assert tts_model_store.materialize_tree(str(tmp_path)) == 1
    assert not os.path.islink(link) and link.read_bytes() == b"x" * 4096
    assert os.stat(link).st_ino == os.stat(blob).st_ino, "hardlink: cùng inode, không nhân đôi dung lượng"
    assert os.path.realpath(link) == str(link.resolve()) and tts_model_store.materialize_tree(str(tmp_path)) == 0


def test_chi_dong_vao_snapshots_khong_dong_vao_noi_khac(tmp_path):
    other = tmp_path / "hub" / "models--a--b" / "refs"
    other.mkdir(parents=True)
    target = tmp_path / "t"
    target.write_bytes(b"1")
    os.symlink(target, other / "main")
    assert tts_model_store.materialize_tree(str(tmp_path)) == 0 and os.path.islink(other / "main")


def test_thu_muc_mo_hinh_nam_trong_data_hoac_theo_bien_moi_truong(monkeypatch, tmp_path):
    monkeypatch.delenv("TTS_MODEL_DIR", raising=False)
    default = tts_model_store.model_root()
    assert default.endswith(os.path.join("server", "data", "hf")) or default.endswith(os.path.join("data", "hf"))
    monkeypatch.setenv("TTS_MODEL_DIR", str(tmp_path / "models"))
    assert tts_model_store.model_root() == str(tmp_path / "models")


def test_cau_hinh_hf_home_theo_thu_muc_mo_hinh(monkeypatch, tmp_path):
    monkeypatch.setenv("HF_HOME", "/nowhere")
    monkeypatch.setenv("HF_HUB_CACHE", "/nowhere/hub")
    root = tts_model_store.configure_environment(str(tmp_path / "hf"))
    assert os.environ["HF_HOME"] == root == str(tmp_path / "hf") and "HF_HUB_CACHE" not in os.environ
    assert os.path.isdir(root)


def test_danh_sach_tep_bat_buoc_cua_nano_mac_dinh_chi_gom_tep_doc_giong_co_san():
    items = tts_model_store.required_files()
    assert {repo for repo, _, _ in items} == {tts_model_store.NANO_REPO}
    names = {name for _, name, _ in items}
    assert names == {"text_encoder.onnx", "duration_predictor.onnx", "vector_estimator.onnx", "codec_decoder.onnx",
                     "config.json", "constants.npz"}, "không tải tệp nhân bản giọng"
    assert all(sub is None for _, _, sub in items)


def test_danh_sach_tep_bat_buoc_du_cho_ca_mo_hinh_va_codec_cua_turbo(monkeypatch):
    monkeypatch.setenv("TTS_LOCAL_MODEL", "v3turbo")
    items = tts_model_store.required_files()
    assert items == tts_model_store.required_files("v3turbo")
    repos = {repo for repo, _, _ in items}
    assert repos == {tts_model_store.BACKBONE_REPO, tts_model_store.CODEC_REPO}
    names = {name for _, name, _ in items}
    assert {"vieneu_prefill.onnx", "vieneu_backbone_shared.data", "moss_audio_tokenizer_decode_full.onnx"} <= names
    assert ("pnnbao-ump/VieNeu-TTS-v3-Turbo", "vieneu_prefill.onnx", "onnx_update") in items


class FakeHub:
    """Giả huggingface_hub: dựng bộ đệm kiểu HF (blobs + symlink trong snapshots) để kiểm tra ensure_model_files."""

    def __init__(self, root, missing=()):
        self.root, self.missing, self.downloads = root, set(missing), []

    def _pointer(self, repo, filename):
        return os.path.join(self.root, "hub", "models--" + repo.replace("/", "--"), "snapshots", "rev", *filename.split("/"))

    def try_to_load_from_cache(self, repo, filename, **kw):
        path = self._pointer(repo, filename)
        return path if os.path.lexists(path) and os.path.exists(path) else None

    def hf_hub_download(self, repo, name, subfolder=None, **kw):
        if name in self.missing:
            raise OSError(f"404 {name}")
        filename = f"{subfolder}/{name}" if subfolder else name
        self.downloads.append(filename)
        pointer = self._pointer(repo, filename)
        blob = os.path.join(self.root, "hub", "blobs", f"{abs(hash(repo + filename)):x}")
        os.makedirs(os.path.dirname(pointer), exist_ok=True)
        os.makedirs(os.path.dirname(blob), exist_ok=True)
        with open(blob, "wb") as handle:
            handle.write(b"data:" + filename.encode())
        os.symlink(blob, pointer)
        return pointer


@pytest.fixture
def fake_hub(monkeypatch, tmp_path):
    monkeypatch.setenv("TTS_MODEL_DIR", str(tmp_path / "hf"))
    monkeypatch.setenv("HF_HUB_OFFLINE", "0")  # monkeypatch trả lại giá trị cũ khi test xong dù ensure_model_files có sửa
    monkeypatch.setenv("HF_HOME", "/chua-cau-hinh")
    hub = FakeHub(str(tmp_path / "hf"))
    module = types.ModuleType("huggingface_hub")
    module.try_to_load_from_cache = hub.try_to_load_from_cache
    module.hf_hub_download = hub.hf_hub_download
    monkeypatch.setitem(sys.modules, "huggingface_hub", module)
    monkeypatch.delitem(sys.modules, "huggingface_hub.constants", raising=False)
    monkeypatch.setattr(tts_model_store, "required_files", lambda mode=None: [
        ("a/model", "vieneu_prefill.onnx", "onnx_update"), ("a/model", "vieneu_backbone_shared.data", "onnx_update"),
        ("a/model", "config.json", None), ("c/codec", "codec_meta.json", None), ("c/codec", "decode.onnx", None),
    ])
    return hub


def test_ensure_model_files_tai_du_doi_symlink_thanh_tep_that_va_bat_offline(fake_hub, tmp_path):
    fake_hub.missing = {"codec_meta.json"}  # tệp .json tuỳ chọn thiếu không làm hỏng
    result = tts_model_store.ensure_model_files()
    assert result["downloaded"] == 4 and result["materialized"] == 4
    assert result["model_dir"] == os.path.dirname(fake_hub._pointer("a/model", "onnx_update/vieneu_prefill.onnx"))
    assert tts_model_store.is_complete()
    for repo, name, sub in tts_model_store.required_files():
        path = fake_hub._pointer(repo, f"{sub}/{name}" if sub else name)
        if os.path.exists(path):
            assert not os.path.islink(path), "onnxruntime từ chối đường dẫn đi qua symlink"
    assert os.environ["HF_HUB_OFFLINE"] == "1" and os.environ["HF_HOME"] == str(tmp_path / "hf")


def test_ensure_model_files_lan_hai_khong_tai_lai(fake_hub):
    tts_model_store.ensure_model_files()
    first = len(fake_hub.downloads)
    result = tts_model_store.ensure_model_files()
    assert result["downloaded"] == 0 and len(fake_hub.downloads) == first


def test_ensure_model_files_thieu_tep_bat_buoc_thi_nem_loi_va_khong_bat_offline(fake_hub):
    fake_hub.missing = {"vieneu_backbone_shared.data"}
    with pytest.raises(OSError):
        tts_model_store.ensure_model_files()
    assert os.environ["HF_HUB_OFFLINE"] != "1"
