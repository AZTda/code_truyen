"""Dịch hàng loạt, chế độ lỗi của /v1/translate, nội dung do client gửi và recrawl.

AI lỗi thì mỗi kiểu yêu cầu phải có kết cục riêng (ứng dụng lưu nguyên văn trả về vào máy):
tải trước và prompt riêng là 503, dịch lại giữ bản cũ, chương đọc trực tiếp nhận bản Google tạm.
"""

import asyncio
from types import SimpleNamespace

import pytest

from models.translate import TermItem
from services.agy_runner import TaskPriority
from services.auth_token import issue_token
from services.batch_translation_runner import BatchTranslationRunner, batch_translation_runner
from services.detached_task import run_detached
from services.glossary_store import glossary_store
from services.storage_service import storage_service
from services.translation_validation import ChapterTranslation, ENGINE_AI, ENGINE_FALLBACK
from services.translation_worker import translation_worker
from tests._fakes import isolated  # noqa: F401 (fixture)
from tests._translate_support import AUTH, ORIGINAL, client, fake_worker, seed_chapter  # noqa: F401 (fake_worker: fixture)


def test_batch_mot_dot_moi_truyen_gui_lai_thi_gan_lai(isolated, monkeypatch):
    async def no_run(*a, **k):
        return None
    monkeypatch.setattr(batch_translation_runner, "run", no_run)
    monkeypatch.setattr("routes.translate.job_registry.create",
                        lambda kind, owner: SimpleNamespace(id=f"job-{owner}-{len(batch_translation_runner._active)}"))
    body = {"novel_id": "nb", "from_chapter": 1, "to_chapter": 5}
    try:
        first = client.post("/v1/batch-translate", headers=AUTH, json=body)
        assert first.status_code == 200 and first.json()["queued_chapters"] == 5
        job_id = first.json()["job_id"]
        assert isinstance(job_id, str) and job_id, "job_id không bao giờ rỗng/null với đợt mới"

        # Mở lại hộp thoại, gửi đúng dải cũ (hoặc dải con): gắn lại đợt đang chạy, không xếp thêm
        again = client.post("/v1/batch-translate", headers=AUTH, json=body)
        assert again.status_code == 200 and again.json()["job_id"] == job_id
        assert again.json()["queued_chapters"] == 0
        sub = client.post("/v1/batch-translate", headers=AUTH, json={**body, "to_chapter": 3})
        assert sub.json()["job_id"] == job_id

        # Dải mở rộng cùng truyện: từ chối, không xếp chồng
        wider = client.post("/v1/batch-translate", headers=AUTH, json={**body, "to_chapter": 9})
        assert wider.status_code == 429

        # Người khác, cùng truyện: đợt riêng của họ
        other = {"Authorization": f"Bearer {issue_token('user_other', 'other')}"}
        third = client.post("/v1/batch-translate", headers=other, json=body)
        assert third.status_code == 200 and third.json()["job_id"] != job_id
        too_big = client.post("/v1/batch-translate", headers=other,
                              json={"novel_id": "nc", "from_chapter": 1, "to_chapter": 102})
        assert too_big.status_code == 400
    finally:
        batch_translation_runner.release("user_translate", "nb")
        batch_translation_runner.release("user_other", "nb")


def test_runner_gioi_han_moi_nguoi_va_khu_trung_so_chuong():
    runner = BatchTranslationRunner()
    res = runner.reserve("u", "n", [1, 1, 2, 3, 2])
    assert res.chapters == [1, 2, 3] and not res.error and not res.attach_job_id
    runner.activate("u", "n", "j1", res.chapters)
    assert runner.reserve("u", "n", [2, 3]).attach_job_id == "j1"
    assert runner.reserve("u", "n", [4]).error
    runner.activate("u", "m", "j2", [1])
    assert runner.reserve("u", "k", [1]).error, "Tối đa 2 đợt cùng lúc mỗi người"
    assert runner.reserve("v", "n", [1, 2]).chapters == [1, 2], "Giới hạn theo từng người"
    runner.release("u", "n")
    assert runner.reserve("u", "k", [1]).chapters == [1]


@pytest.mark.asyncio
async def test_batch_chay_nen_khong_luu_ban_google_va_nap_lai_thuat_ngu(isolated, monkeypatch):
    storage_service.save_chapter("nb2", 1, {"original_content": ORIGINAL, "title": "第1章"})
    storage_service.save_chapter("nb2", 2, {"original_content": ORIGINAL + "。", "title": "第2章"})
    seen = []

    async def fake_translate(**kwargs):
        seen.append({t.source for t in kwargs["glossary"]})
        assert kwargs["priority"] == TaskPriority.BACKGROUND and kwargs["allow_fallback"] is False
        # Giữa hai chương, auto_glossary trích thêm một tên riêng
        glossary_store.save("nb2", [TermItem(source="山门", target="Sơn Môn")], auto=True)
        return ChapterTranslation("Bản dịch AI.", ENGINE_AI) if len(seen) == 1 else None
    monkeypatch.setattr(translation_worker, "translate_chapter_detailed", fake_translate)
    real_sleep = asyncio.sleep
    monkeypatch.setattr("services.batch_translation_runner.asyncio.sleep", lambda *_: real_sleep(0))
    job = SimpleNamespace(id="jb")
    updates = []
    monkeypatch.setattr("services.batch_translation_runner.job_registry.update",
                        lambda job_id, **kw: updates.append(kw))
    batch_translation_runner.activate("u", "nb2", job.id, [1, 2])
    await batch_translation_runner.run("u", "nb2", [1, 2], [TermItem(source="林动", target="Lâm Động")], job.id)

    assert seen[0] == {"林动"} and seen[1] == {"林动", "山门"}, "Thuật ngữ nạp lại mỗi chương"
    assert storage_service.get_chapter("nb2", 1)["translated_content"] == "Bản dịch AI."
    assert not storage_service.get_chapter("nb2", 2).get("translated_content"), "AI lỗi: không lưu bản Google"
    assert updates[-1]["status"] == "succeeded" and "lỗi 1 chương" in updates[-1]["message"]
    assert ("u", "nb2") not in batch_translation_runner._active


# ---------------------------------------------------------------------------
# Quy tắc lỗi AI, nội dung do client gửi, tách rời khỏi request
# ---------------------------------------------------------------------------

def test_tai_truoc_ai_loi_la_503_khong_luu_ban_google(isolated, fake_worker):
    seed_chapter()
    fake_worker.results = [None]
    res = client.post("/v1/translate", headers=AUTH, json={"novel_id": "n1", "chapter_number": 1, "prefetch": True})
    assert res.status_code == 503
    assert fake_worker.calls[0]["allow_fallback"] is False
    assert not storage_service.get_chapter("n1", 1).get("translated_content")


def test_prompt_rieng_bo_qua_ban_dung_chung_va_ai_loi_la_503(isolated, fake_worker):
    seed_chapter(translated_content="Bản dịch chung.", translation_engine=ENGINE_AI)
    body = {"novel_id": "n1", "chapter_number": 1, "custom_prompt": "Xưng ta - ngươi"}
    # Không cần force: prompt riêng không được trả nhầm bản chung
    res = client.post("/v1/translate", headers=AUTH, json=body)
    assert len(fake_worker.calls) == 1 and res.json()["translated_content"] == "Bản dịch AI mới."
    assert fake_worker.calls[0]["custom_prompt"] == "Xưng ta - ngươi"
    # AI lỗi: không được trả bản chung/bản Google thay cho bản theo phong cách (ứng dụng sẽ lưu nó vào máy)
    fake_worker.results = [None]
    res = client.post("/v1/translate", headers=AUTH, json=body)
    assert res.status_code == 503 and fake_worker.calls[-1]["allow_fallback"] is False
    assert storage_service.get_chapter("n1", 1)["translated_content"] == "Bản dịch chung."


def test_dich_lai_ai_loi_giu_ban_ai_cu(isolated, fake_worker):
    seed_chapter(translated_content="Bản AI cũ.", translation_engine=ENGINE_AI)
    fake_worker.results = [None]
    res = client.post("/v1/translate", headers=AUTH, json={"novel_id": "n1", "chapter_number": 1, "force": True})
    data = res.json()
    assert res.status_code == 200 and data["translated_content"] == "Bản AI cũ."
    assert data["cached"] is True and data["is_fallback"] is False


def test_noi_dung_client_chi_dung_khi_may_chu_khong_co_chuong(isolated, fake_worker, monkeypatch):
    async def missing(*a, **k):
        from fastapi import HTTPException
        raise HTTPException(status_code=404, detail="không có")
    monkeypatch.setattr("routes.translate.get_chapter", missing)
    res = client.post("/v1/translate", headers=AUTH, json={
        "novel_id": "ngoai-may-chu", "chapter_number": 1, "content": "文" * 50})
    assert res.status_code == 200 and fake_worker.calls[0]["content"] == "文" * 50
    assert storage_service.get_chapter("ngoai-may-chu", 1) is None, "Nội dung client không được ghi vào kho chung"
    assert client.post("/v1/translate", headers=AUTH, json={
        "novel_id": "ngoai-may-chu", "chapter_number": 2}).status_code == 404


def test_khach_khong_gop_duoc_thuat_ngu(isolated, fake_worker):
    client.post("/v1/translate", json={
        "novel_id": "n1", "chapter_number": 1, "glossary": [{"source": "林动", "target": "Bậy"}]})
    assert glossary_store.list_entries("n1") == []


def test_ban_goc_khong_bi_xoa_khi_dong_bo_tu_db(isolated, fake_worker):
    seed_chapter()
    isolated.db.table("chapters").upsert({"novel_id": "n1", "chapter_number": 1,
                                          "translated_content": "Bản dịch từ DB. " * 20, "original_content": ""})
    res = client.post("/v1/translate", headers=AUTH, json={"novel_id": "n1", "chapter_number": 1})
    assert res.json()["cached"] is True and fake_worker.calls == []
    assert storage_service.get_chapter("n1", 1)["original_content"] == ORIGINAL


@pytest.mark.asyncio
async def test_huy_nguoi_cho_khong_huy_viec_dang_dich():
    finished = []

    async def job():
        await asyncio.sleep(0.05)
        finished.append("luu xong")
        return "ket-qua"

    waiter = asyncio.ensure_future(run_detached(job()))
    await asyncio.sleep(0.01)
    waiter.cancel()
    with pytest.raises(asyncio.CancelledError):
        await waiter
    await asyncio.sleep(0.1)
    assert finished == ["luu xong"], "Client ngắt kết nối vẫn phải lưu xong bản dịch"
    assert await run_detached(job()) == "ket-qua"


def test_get_chuong_an_moi_dang_tu_choi_cua_ai(isolated, fake_worker):
    for text in ("The prompt could not be submitted", "I'm sorry, but I can't translate this chapter.",
                 "Xin lỗi, tôi không thể dịch nội dung này."):
        seed_chapter(translated_content=text)
        data = client.get("/v1/novels/n1/chapters/1").json()
        assert data["translated_content"] is None, text


def test_recrawl_khong_ghi_de_ban_chung_khi_co_prompt_rieng(isolated, fake_worker, monkeypatch):
    class Scraper:
        async def fetch_chapter_content(self, url):
            return "新的正文内容。" * 30

    async def titles(batch):
        return ["Chương 1"] * len(batch)
    monkeypatch.setattr("routes.chapters.get_scraper_for_url", lambda url: Scraper())
    monkeypatch.setattr(translation_worker, "translate_chapter_titles_batch", titles)
    storage_service.save_novel_info("nr2", {"source_url": "https://src.example/book"})
    seed_chapter("nr2", url="https://src.example/c1", translated_content="Bản chung.",
                      translation_engine=ENGINE_AI)
    url = "/v1/novels/nr2/chapters/1/recrawl"

    # Có prompt riêng: trả cho người yêu cầu, KHÔNG thay bản chung, và AI lỗi thì để trống (không bản Google)
    fake_worker.results = [ChapterTranslation("Bản theo phong cách riêng.", ENGINE_AI)]
    data = client.post(url, headers=AUTH, params={"custom_prompt": "Xưng ta - ngươi"}).json()
    assert data["translated_content"] == "Bản theo phong cách riêng."
    assert fake_worker.calls[0]["force"] is True and fake_worker.calls[0]["allow_fallback"] is False
    assert storage_service.get_chapter("nr2", 1)["translated_content"] == "Bản chung."
    assert "新的正文内容" in storage_service.get_chapter("nr2", 1)["original_content"], "Bản gốc mới thì vẫn lưu"
    fake_worker.results = [None]
    assert client.post(url, headers=AUTH, params={"custom_prompt": "Xưng ta - ngươi"}).json()["translated_content"] is None

    # Không prompt riêng: bản AI mới thay bản chung và lên lịch trích tên riêng
    fake_worker.results = [ChapterTranslation("Bản AI mới.", ENGINE_AI)]
    data = client.post(url, headers=AUTH).json()
    assert data["translated_content"] == "Bản AI mới." and data["is_fallback"] is False
    assert storage_service.get_chapter("nr2", 1)["translated_content"] == "Bản AI mới."
    assert isolated.scheduled and isolated.scheduled[-1][:2] == ("nr2", 1)

    # AI lỗi, không prompt riêng: bản Google lưu TẠM và được gắn cờ
    fake_worker.results = [ChapterTranslation("Bản Google.", ENGINE_FALLBACK, is_fallback=True)]
    data = client.post(url, headers=AUTH).json()
    assert data["is_fallback"] is True and data["translation_engine"] == ENGINE_FALLBACK
    assert fake_worker.calls[-1]["allow_fallback"] is True
    assert storage_service.get_chapter("nr2", 1)["translation_engine"] == ENGINE_FALLBACK


def test_overnight_round_robin_va_cooldown_chuong_loi(isolated, monkeypatch):
    from services.overnight_translator import OvernightTranslator

    ov = OvernightTranslator()
    storage_service.save_novel_info("na", {"overnight_enabled": True, "overnight_from": 1, "overnight_to": 3, "total_chapters": 3})
    storage_service.save_novel_info("nb", {"overnight_enabled": True, "overnight_from": 1, "overnight_to": 2, "total_chapters": 2})
    monkeypatch.setattr("services.overnight_translator.os.listdir", lambda *_: ["na", "nb"])

    # Lượt 1 lấy na, lượt 2 tự xoay vòng sang nb, lượt 3 quay lại na
    w1 = ov._pick_next_work()
    w2 = ov._pick_next_work()
    w3 = ov._pick_next_work()
    assert w1 == ("na", [1, 2, 3])
    assert w2 == ("nb", [1, 2])
    assert w3 == ("na", [1, 2, 3])

    # Khi chương 1 của na bị lỗi và vào cooldown, đợt tiếp theo bỏ qua chương 1 để dịch chương 2, 3
    batch_translation_runner.mark_chapter_failed("na", 1, cooldown_sec=600.0)
    try:
        assert ov._find_untranslated_range("na", 1, 3, batch_size=20, skip_cooldown=True) == [2, 3]
    finally:
        batch_translation_runner.clear_cooldown("na")


def test_overnight_pivot_theo_tien_do_doc(isolated):
    from database import db
    from services.overnight_translator import OvernightTranslator

    ov = OvernightTranslator()
    db.table("reading_progress_backend").upsert({
        "id": "u1:npivot", "user_id": "u1", "novel_id": "npivot", "current_chapter": 4, "shelf_name": "reading", "updated_at_ms": 1000
    }).execute()
    db.table("reading_progress_backend").upsert({
        "id": "u2:npivot", "user_id": "u2", "novel_id": "npivot", "current_chapter": 6, "shelf_name": "reading", "updated_at_ms": 5000
    }).execute()
    db.table("reading_progress_backend").upsert({
        "id": "u3:npivot", "user_id": "u3", "novel_id": "npivot", "current_chapter": 8, "shelf_name": "removed", "updated_at_ms": 9000
    }).execute()

    # Dịch từ chương đang đọc mới nhất (chương 6 -> 8) trước rồi mới vòng lại (1 -> 5)
    assert ov._find_untranslated_range("npivot", 1, 8, batch_size=20) == [6, 7, 8, 1, 2, 3, 4, 5]


def test_epub_loai_bo_tieu_de_trung_va_nhung_anh_bia(isolated, tmp_path, monkeypatch):
    import hashlib
    import io
    import zipfile
    from services import epub_exporter

    monkeypatch.setattr(epub_exporter, "DATA_DIR", str(tmp_path))
    cover_url = "https://cdn.example.com/covers/book1.jpg"
    covers_dir = tmp_path / "covers_cache"
    covers_dir.mkdir(parents=True, exist_ok=True)
    cover_hash = hashlib.md5(cover_url.encode("utf-8")).hexdigest()
    (covers_dir / f"{cover_hash}.jpg").write_bytes(b"\xff\xd8\xff\xe0FAKE_JPEG_DATA")

    storage_service.save_novel_info("nepub", {
        "id": "nepub",
        "title": "Đại Đạo Tranh Phong",
        "title_vi": "Đại Đạo Tranh Phong",
        "cover_url": cover_url,
    })
    storage_service.save_chapter("nepub", 1, {
        "chapter_number": 1,
        "title": "第1章 仙门",
        "title_vi": "Chương 1: Tiên Môn",
        "translated_content": "Chương 1: Tiên Môn\n\nTrương Diễn bước lên bậc đá cổ kính giữa sương mù dày đặc.\n" * 8,
        "translation_engine": ENGINE_AI,
    })

    built = epub_exporter.build_novel_epub("nepub")
    assert built is not None
    epub_bytes, _ = built
    with zipfile.ZipFile(io.BytesIO(epub_bytes), "r") as zf:
        assert "OEBPS/cover.jpg" in zf.namelist()
        assert zf.read("OEBPS/cover.jpg") == b"\xff\xd8\xff\xe0FAKE_JPEG_DATA"
        opf = zf.read("OEBPS/content.opf").decode("utf-8")
        assert '<item id="cover-image" href="cover.jpg" media-type="image/jpeg" properties="cover-image"/>' in opf
        assert '<meta name="cover" content="cover-image"/>' in opf
        ch1 = zf.read("OEBPS/chapter_1.xhtml").decode("utf-8")
        assert "<h2>Chương 1: Tiên Môn</h2>" in ch1
        assert not ch1.split("</h2>", 1)[1].lstrip().startswith("<p>Chương 1")


