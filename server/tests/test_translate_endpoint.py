"""Hợp đồng /v1/translate và GET /v1/novels/{id}/glossary.

Kho chung (bản gốc + bản dịch mọi độc giả cùng đọc) không được để khách vãng lai,
nội dung tuỳ ý hay prompt riêng của một người ghi đè.
"""

from models.translate import TermItem
from services.agy_runner import TaskPriority
from services.glossary_store import glossary_store
from services.storage_service import storage_service
from services.translation_validation import ChapterTranslation, ENGINE_AI, ENGINE_FALLBACK
from services import chapter_translation_service
from tests._admin_support import ADMIN_HEADERS, two_accounts  # noqa: F401 (two_accounts: fixture)
from tests._fakes import isolated  # noqa: F401 (fixture)
from tests._translate_support import AUTH, ORIGINAL, client, fake_worker, seed_chapter  # noqa: F401 (fake_worker: fixture)


def test_khach_chua_dang_nhap_bi_401(isolated, fake_worker):
    assert client.post("/v1/translate", json={"novel_id": "n1", "chapter_number": 1}).status_code == 401
    assert client.post("/v1/batch-translate",
                       json={"novel_id": "n1", "from_chapter": 1, "to_chapter": 2}).status_code == 401
    assert fake_worker.calls == []


def test_ban_goc_da_luu_thang_noi_dung_client_gui(isolated, fake_worker):
    seed_chapter()
    res = client.post("/v1/translate", headers=AUTH, json={
        "novel_id": "n1", "chapter_number": 1, "content": "NỘI DUNG GIẢ MẠO", "force": True,
    })
    assert res.status_code == 200
    assert ORIGINAL in fake_worker.calls[0]["content"]
    assert "NỘI DUNG GIẢ MẠO" not in fake_worker.calls[0]["content"]
    assert storage_service.get_chapter("n1", 1)["original_content"] == ORIGINAL


def test_prompt_rieng_khong_ghi_de_ban_dung_chung(isolated, fake_worker):
    seed_chapter(translated_content="Bản dịch chung.", translation_engine=ENGINE_AI)
    fake_worker.results = [ChapterTranslation("Bản dịch kiểu kiếm hiệp riêng.", ENGINE_AI)]
    res = client.post("/v1/translate", headers=AUTH, json={
        "novel_id": "n1", "chapter_number": 1, "force": True, "custom_prompt": "Xưng ta - ngươi",
    })
    assert res.json()["translated_content"] == "Bản dịch kiểu kiếm hiệp riêng."
    assert storage_service.get_chapter("n1", 1)["translated_content"] == "Bản dịch chung."


def test_prefetch_chay_lan_nen(isolated, fake_worker):
    seed_chapter()
    client.post("/v1/translate", headers=AUTH, json={"novel_id": "n1", "chapter_number": 1, "prefetch": True})
    assert fake_worker.calls[0]["priority"] == TaskPriority.BACKGROUND


def test_force_dich_lai_du_da_co_ban_luu(isolated, fake_worker):
    seed_chapter(translated_content="Bản cũ.", translation_engine=ENGINE_AI)
    res = client.post("/v1/translate", headers=AUTH, json={"novel_id": "n1", "chapter_number": 1})
    assert res.json()["cached"] is True and fake_worker.calls == []
    res = client.post("/v1/translate", headers=AUTH, json={"novel_id": "n1", "chapter_number": 1, "force": True})
    assert fake_worker.calls[0]["force"] is True
    assert res.json()["translated_content"] == "Bản dịch AI mới."
    # AI lỗi khi dịch lại thì giữ bản AI cũ, không nhận bản Google
    assert fake_worker.calls[0]["allow_fallback"] is False


def test_ban_du_phong_la_ban_tam_lan_sau_thu_ai_lai(isolated, fake_worker):
    seed_chapter()
    fake_worker.results = [ChapterTranslation("Bản Google.", ENGINE_FALLBACK, is_fallback=True)]
    body = {"novel_id": "n1", "chapter_number": 1}
    data = client.post("/v1/translate", headers=AUTH, json=body).json()
    assert data["is_fallback"] is True
    assert data["engine"] == data["translation_engine"] == ENGINE_FALLBACK
    assert storage_service.get_chapter("n1", 1)["translation_engine"] == ENGINE_FALLBACK

    # Trong thời gian chờ: trả ngay bản tạm, không bắt độc giả chờ AI
    data = client.post("/v1/translate", headers=AUTH, json=body).json()
    assert data["is_fallback"] is True and len(fake_worker.calls) == 1

    # Hết thời gian chờ: thử AI lại (1 lượt, không cho dự phòng); AI vẫn lỗi -> trả bản tạm
    chapter_translation_service._ai_retry_after.clear()
    fake_worker.results = [None]
    data = client.post("/v1/translate", headers=AUTH, json=body).json()
    assert fake_worker.calls[-1]["allow_fallback"] is False and fake_worker.calls[-1]["max_attempts"] == 1
    assert data["translated_content"] == "Bản Google." and data["is_fallback"] is True

    # AI hồi phục: bản AI thay bản tạm trong kho chung
    chapter_translation_service._ai_retry_after.clear()
    data = client.post("/v1/translate", headers=AUTH, json=body).json()
    assert data["is_fallback"] is False and data["translation_engine"] == ENGINE_AI
    stored = storage_service.get_chapter("n1", 1)
    assert stored["translated_content"] == "Bản dịch AI mới." and stored["translation_engine"] == ENGINE_AI


def test_get_chuong_bao_ban_tam(isolated, fake_worker):
    seed_chapter(translated_content="Bản Google.", translation_engine=ENGINE_FALLBACK)
    data = client.get("/v1/novels/n1/chapters/1").json()
    assert data["is_fallback"] is True


def test_ban_ai_tieng_viet_len_lich_trich_ten_rieng(isolated, fake_worker):
    seed_chapter()
    client.post("/v1/translate", headers=AUTH, json={"novel_id": "n1", "chapter_number": 1})
    assert isolated.scheduled and isolated.scheduled[0][:2] == ("n1", 1)


def test_bang_thuat_ngu_bi_chan_do_dai_va_hien_o_endpoint(isolated, fake_worker, two_accounts):
    """Bản cũ ghi thẳng thuật ngữ của MỌI tài khoản vào bảng dùng chung (tài khoản thường sửa được bảng
    áp cho mọi độc giả); giờ chỉ quản trị viên ghi, còn thuật ngữ của tài khoản thường chỉ áp cho họ."""
    seed_chapter()
    glossary = [{"source": "林动", "target": "Lâm Động"}, {"source": "长" * 100, "target": "x"}]
    client.post("/v1/translate", headers=AUTH, json={"novel_id": "n1", "chapter_number": 1, "glossary": glossary})
    assert glossary_store.list_entries("n1") == [], "Tài khoản thường không được ghi bảng dùng chung"
    assert "林动" in {t.source for t in fake_worker.calls[0]["glossary"]}, "...nhưng vẫn áp cho bản dịch của họ"

    client.post("/v1/translate", headers=ADMIN_HEADERS, json={
        "novel_id": "n1", "chapter_number": 1, "force": True, "glossary": glossary})
    glossary_store.save("n1", [TermItem(source="山门", target="Sơn Môn")], auto=True)
    terms = client.get("/v1/novels/n1/glossary").json()["terms"]
    assert {"source": "林动", "target": "Lâm Động", "auto": False} in terms
    assert {"source": "山门", "target": "Sơn Môn", "auto": True} in terms
    assert all(len(t["source"]) <= 32 for t in terms)
    # Bảng đã lưu được áp cho lời dịch tiếng Việt
    sent = {t.source for t in fake_worker.calls[-1]["glossary"]}
    assert "林动" in sent


def test_get_chapter_recrawl_requires_login():
    """GET ...?recrawl=true ép cào lại trang nguồn: khách (không token) phải nhận 401, đọc thường vẫn mở."""
    from fastapi.testclient import TestClient
    from server import app

    client = TestClient(app)
    guest = client.get("/v1/novels/khong-ton-tai/chapters/1?recrawl=true")
    assert guest.status_code == 401
    # Không recrawl: không bị chặn bởi dependency này (404/200 tuỳ dữ liệu, nhưng không phải 401)
    assert client.get("/v1/novels/khong-ton-tai/chapters/1").status_code != 401
