"""Trình tải mô hình Hy-MT2: URL ghim revision, kiểm kích thước + sha256, tải tiếp được, đổi tên nguyên tử."""

import hashlib
import io
import os

import pytest

from services import title_mt_model_store as M


class _Resp(io.BytesIO):
    def __init__(self, body: bytes, status: int = 200):
        super().__init__(body)
        self.status = status

    def __enter__(self):
        return self

    def __exit__(self, *a):
        self.close()


@pytest.fixture
def small_model(monkeypatch):
    body = b"GGUF" + b"x" * 1000
    monkeypatch.setattr(M, "MODEL_SIZE", len(body))
    monkeypatch.setattr(M, "MODEL_SHA256", hashlib.sha256(body).hexdigest())
    return body


def test_url_duoc_ghim():
    assert M.MODEL_REVISION in M.MODEL_URL and "/resolve/main/" not in M.MODEL_URL
    assert M.MODEL_SIZE == 1133080448 and len(M.MODEL_SHA256) == 64


def test_tai_moi_va_bo_qua_khi_da_co(tmp_path, small_model, monkeypatch):
    calls = []

    def fake_urlopen(req, timeout):
        calls.append(req.headers.get("Range"))
        return _Resp(small_model)
    monkeypatch.setattr(M.urllib.request, "urlopen", fake_urlopen)
    res = M.ensure_model(str(tmp_path))
    assert res["downloaded"] and M.is_ready(str(tmp_path), deep=True)
    assert M.ensure_model(str(tmp_path)) == {"path": M.model_path(str(tmp_path)), "downloaded": False}
    assert calls == [None]


def test_tai_tiep_tu_phan_dang_do(tmp_path, small_model, monkeypatch):
    part = M.model_path(str(tmp_path)) + ".part"
    with open(part, "wb") as f:
        f.write(small_model[:300])
    seen = []

    def fake_urlopen(req, timeout):
        seen.append(req.headers.get("Range"))
        return _Resp(small_model[300:], status=206)
    monkeypatch.setattr(M.urllib.request, "urlopen", fake_urlopen)
    assert M.ensure_model(str(tmp_path))["downloaded"]
    assert seen == ["bytes=300-"] and not os.path.exists(part)


def test_sai_sha_thi_xoa_va_bao_loi(tmp_path, small_model, monkeypatch):
    monkeypatch.setattr(M.urllib.request, "urlopen", lambda req, timeout: _Resp(b"Z" * len(small_model)))
    with pytest.raises(RuntimeError):
        M.ensure_model(str(tmp_path))
    assert not os.path.exists(M.model_path(str(tmp_path))) and not os.path.exists(M.model_path(str(tmp_path)) + ".part")


def test_thu_muc_trung_ten_tep(tmp_path, small_model):
    os.makedirs(M.model_path(str(tmp_path)))
    with pytest.raises(RuntimeError, match="THƯ MỤC"):
        M.ensure_model(str(tmp_path))


def test_cli_check(tmp_path, small_model, capsys):
    assert M.main(["--dir", str(tmp_path), "--check"]) == 0
    assert '"model_ready": false' in capsys.readouterr().out
