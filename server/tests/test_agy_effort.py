"""Mức suy luận (--effort) phải khớp bậc trong tên mô hình, nếu không Antigravity CLI từ chối mọi lượt dịch."""

import asyncio

import pytest

from services import model_catalog as mc
from services.agy_effort import effort_for_model
from services.model_catalog import ModelCatalogService


@pytest.mark.parametrize("model,requested,expected", [
    ("gemini-3.8-flash-medium", "low", "medium"),   # đúng lỗi thật: mô hình ...-medium + cài đặt "Thấp"
    ("gemini-3.8-flash-high", "low", "high"),
    ("gemini-3.8-flash-low", "high", "low"),
    ("gpt-oss-120b-medium", "low", "medium"),
    ("GEMINI-3.1-PRO-HIGH", None, "high"),
    ("claude-sonnet-4-6", "high", "high"),          # không có bậc trong tên: theo yêu cầu
    ("claude-opus-4-6-thinking", None, "low"),
    (None, "medium", "medium"),
    ("", "weird", "low"),                           # giá trị lạ không bao giờ lọt xuống CLI
])
def test_bac_suy_luan_trong_ten_mo_hinh_luon_thang(model, requested, expected):
    assert effort_for_model(model, requested) == expected


def _catalog(tmp_path, monkeypatch, models, active_model, active_effort):
    svc = ModelCatalogService()
    state = {"models": [{"id": m} for m in models], "active_model": active_model,
             "active_effort": active_effort, "timeout_seconds": 120}
    monkeypatch.setattr(svc, "_load_cache", lambda: dict(state))
    monkeypatch.setattr(svc, "_save_cache", lambda c: state.update(c))
    svc.state = state
    return svc


def test_cai_dat_lech_cu_van_ra_bac_dung(tmp_path, monkeypatch):
    # Dữ liệu thật trên máy chủ: active_model ...-medium nhưng active_effort "low"
    svc = _catalog(tmp_path, monkeypatch, ["gemini-3.8-flash-medium"], "gemini-3.8-flash-medium", "low")
    assert svc.get_active_effort() == "medium"


def test_chon_mo_hinh_dong_bo_bac_suy_luan(tmp_path, monkeypatch):
    svc = _catalog(tmp_path, monkeypatch, ["gemini-3.8-flash-high", "claude-sonnet-4-6"], "claude-sonnet-4-6", "low")
    svc.select_model("gemini-3.8-flash-high", effort="low")
    assert svc.state["active_model"] == "gemini-3.8-flash-high" and svc.state["active_effort"] == "high"
    svc.select_model("claude-sonnet-4-6", effort="medium")
    assert svc.state["active_effort"] == "medium"


def test_lenh_dich_khong_con_lech_mo_hinh_va_effort(monkeypatch):
    """Lệnh CLI thật mà bộ dịch dựng ra: --model ...-medium đi cùng --effort medium (không còn 'low')."""
    from services import translation_worker as tw

    seen = []

    class FakeProc:
        returncode = 0

        async def communicate(self):
            return ("Bản dịch thử".encode("utf-8"), b"")

        def kill(self):
            pass

        async def wait(self):
            return 0

    async def fake_exec(*cmd, **kwargs):
        seen.append(list(cmd))
        return FakeProc()

    monkeypatch.setattr(asyncio, "create_subprocess_exec", fake_exec)
    worker = tw.translation_worker
    monkeypatch.setattr(worker, "_get_agy_path", lambda: "/fake/agy")
    monkeypatch.setattr(worker, "is_agy_ready", lambda: True)  # máy chạy test (ảnh Docker) không có agy thật
    monkeypatch.setattr(tw.model_catalog_service, "get_active_model", lambda: "gemini-3.8-flash-medium")
    monkeypatch.setattr(tw.model_catalog_service, "get_active_effort", lambda: "low")
    monkeypatch.setattr(tw.model_catalog_service, "get_active_timeout", lambda: 30)

    out = asyncio.run(worker.translate_chapter_detailed(
        content="你好，世界。", glossary=[], custom_prompt="giữ nguyên tên riêng", target_language="vi",
        english_level="b1", priority=tw.TaskPriority.BACKGROUND, force=True, allow_fallback=False, max_attempts=1))

    assert out is not None
    cmd = seen[0]
    assert cmd[cmd.index("--model") + 1] == "gemini-3.8-flash-medium"
    assert cmd[cmd.index("--effort") + 1] == "medium"
