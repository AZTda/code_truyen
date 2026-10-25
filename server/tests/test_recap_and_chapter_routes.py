"""Recap không được bịa "chương mở đầu" cho chương xa; số chương không hợp lệ là 422 chứ không phải HTTP 500."""

import asyncio
import time

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient

from models.chapter import ChapterContent
from server import app
from services import chapter_recap_service as recap_module
from services.chapter_recap_service import chapter_recap_service
from services.storage_service import storage_service
from services.translation_worker import translation_worker
from tests._admin_support import USER_HEADERS, two_accounts  # noqa: F401 (two_accounts: fixture)
from tests._fakes import isolated  # noqa: F401 (fixture)

client = TestClient(app, raise_server_exceptions=False)


@pytest.fixture
def recap_env(isolated, two_accounts, monkeypatch):
    """AI tóm tắt giả + đường cào chương trước giả (`routes.chapters.get_chapter`)."""
    env = type("Env", (), {})()
    env.prompts, env.fetched, env.available = [], [], {}

    async def fake_json(prompt, timeout=30, priority=None, task_key=None, **kw):
        env.prompts.append(prompt)
        return {"bullets": ["Sự kiện then chốt"], "characters": ["Lâm Động"], "context": "Cuối chương"}

    async def fake_get_chapter(novel_id, chapter_num, recrawl=False):
        env.fetched.append(chapter_num)
        if chapter_num not in env.available:
            raise HTTPException(status_code=404, detail="Không tìm thấy chương trong mục lục.")
        return ChapterContent(novel_id=novel_id, chapter_number=chapter_num, title=f"第{chapter_num}章",
                              original_content=env.available[chapter_num])

    monkeypatch.setattr(translation_worker, "_run_agy_json", fake_json)
    monkeypatch.setattr("routes.chapters.get_chapter", fake_get_chapter)
    return env


def _recap(chapter, novel="nv"):
    return client.get(f"/v1/novels/{novel}/chapters/{chapter}/recap", headers=USER_HEADERS)


def test_chuong_xa_khong_co_chuong_truoc_tra_danh_sach_rong_kem_loi_khong_bia_chuong_mo_dau(recap_env):
    storage_service.save_chapter("nv", 50, {"title": "第50章", "original_content": "林动" * 50})
    data = _recap(50).json()
    assert data["bullets"] == [] and data["context"] == "" and data["chapters_covered"] == []
    assert data["is_fallback"] is True and data["error"]
    assert "Khởi đầu" not in str(data) and "mở đầu" not in str(data)
    assert recap_env.fetched == [48, 49] and recap_env.prompts == []


def test_chuong_1_van_la_noi_dung_tinh_gan_co(recap_env):
    data = _recap(1).json()
    assert data["is_fallback"] is True and data["error"] is None
    assert data["bullets"] and "Khởi đầu" in data["bullets"][0] and "mở đầu" in data["context"]
    assert recap_env.fetched == []


def test_chuong_truoc_chua_luu_duoc_cao_ve_va_tom_tat_dung_nhung_chuong_nho_hon(recap_env):
    recap_env.available = {48: "MARK48_" + "林动握紧长剑。" * 20, 49: "MARK49_" + "林动握紧长剑。" * 20,
                           50: "MARK50_SPOILER", 51: "MARK51_SPOILER"}
    data = _recap(50).json()
    assert data["is_fallback"] is False and data["error"] is None and data["chapters_covered"] == [48, 49]
    prompts = "\n".join(recap_env.prompts)
    assert "MARK48_" in prompts and "MARK49_" in prompts
    assert "SPOILER" not in prompts and recap_env.fetched == [48, 49]


def test_chi_cao_duoc_mot_trong_hai_chuong_truoc_thi_recap_chi_gom_chuong_do(recap_env):
    recap_env.available = {49: "MARK49_" + "林动握紧长剑。" * 20}
    data = _recap(50).json()
    assert data["is_fallback"] is False and data["chapters_covered"] == [49]


def test_chuong_truoc_da_luu_thi_khong_cao_lai(recap_env):
    for n in (8, 9):
        storage_service.save_chapter("nv", n, {"original_content": f"MARK{n}_" + "林动握紧长剑。" * 20})
    data = _recap(10).json()
    assert data["chapters_covered"] == [8, 9] and recap_env.fetched == []


def test_cao_chuong_truoc_qua_cham_thi_bo_qua_chu_khong_treo_request(recap_env, monkeypatch):
    monkeypatch.setattr(recap_module, "FETCH_TIMEOUT_SECONDS", 0.05)

    async def slow(novel_id, chapter_num, recrawl=False):
        await asyncio.sleep(5)
    monkeypatch.setattr("routes.chapters.get_chapter", slow)
    started = time.perf_counter()
    data = _recap(50).json()
    assert time.perf_counter() - started < 2 and data["bullets"] == [] and data["error"]


@pytest.mark.asyncio
async def test_build_recap_chi_dung_noi_dung_tinh_khi_biet_chac_la_chuong_1(isolated):
    args = ("nv", "Truyện", "Chương X", [])
    assert (await chapter_recap_service.build_recap(*args, chapter_number=1))["error"] is None
    for number in (2, 50, None):
        res = await chapter_recap_service.build_recap(*args, chapter_number=number)
        assert res["bullets"] == [] and res["is_fallback"] is True and res["error"], number


# ---------------------------------------------------------------------------
# Số chương không hợp lệ -> 422, không bao giờ 500
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("path", [
    "/v1/novels/n1/chapters/0",
    "/v1/novels/n1/chapters/-3",
    "/v1/novels/n1/chapters/2000001",
    "/v1/novels/n1/chapters/0/recap",
    "/v1/novels/n1/chapters/-3/recap",
    "/v1/novels/n1/chapters/2000001/recap",
])
def test_so_chuong_ngoai_khoang_la_422(isolated, two_accounts, path):
    res = client.get(path, headers=USER_HEADERS)
    assert res.status_code == 422, (path, res.status_code)


@pytest.mark.parametrize("number", [0, -1, 2_000_001])
def test_recrawl_so_chuong_ngoai_khoang_la_422(isolated, two_accounts, number):
    res = client.post(f"/v1/novels/n1/chapters/{number}/recrawl", headers=USER_HEADERS)
    assert res.status_code == 422


def test_so_chuong_hop_le_nhung_truyen_khong_co_van_la_404_khong_phai_500(isolated, two_accounts):
    assert client.get("/v1/novels/khong-co/chapters/1").status_code == 404
    assert client.get("/v1/novels/khong-co/chapters/2000000").status_code == 404
