"""Công cụ đo khi triển khai: RTF giọng đọc lúc rảnh và lúc Hy-MT2 đang bận (engine giả, máy chủ mô hình giả)."""

import asyncio

import pytest

from services import title_mt_loadcheck as LC
from services.title_mt_client import title_mt
from tests._tts_fakes import FakeEngine, install


@pytest.mark.asyncio
async def test_chi_do_luc_ranh_khi_khong_co_may_chu_mo_hinh():
    install(FakeEngine(seconds_per_char=0.01, delay=0.01))
    report = await LC.run(2, "Xin chào các bạn")
    assert len(report["rtf_idle"]) == 2 and "truyencloud-mt" in report["mt"]


@pytest.mark.asyncio
async def test_do_khi_mo_hinh_ban(monkeypatch):
    install(FakeEngine(seconds_per_char=0.01, delay=0.01))
    sent = []
    real_sleep = asyncio.sleep

    async def up(max_age=None):
        return True

    async def fake_zh_to_vi(text, hints=(), timeout=None, vn_check=None):
        sent.append(text)
        await real_sleep(0.01)
        return "x"
    monkeypatch.setattr(title_mt, "healthy", up)
    monkeypatch.setattr(title_mt, "zh_to_vi", fake_zh_to_vi)

    async def short_sleep(s):
        await real_sleep(min(s, 0.05))
    monkeypatch.setattr(LC.asyncio, "sleep", short_sleep)
    report = await LC.run(2, "Xin chào các bạn")
    assert len(report["rtf_with_mt"]) == 2 and report["verdict"] == "OK" and sent
    assert "slowdown_pct" in report and report["mt_titles_per_min_during_tts"] > 0
