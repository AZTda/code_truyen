"""Test bảng thuật ngữ theo truyện và endpoint báo chương mới.

Bối cảnh: tham số `glossary` đã được nối sẵn từ ứng dụng xuống tới máy chủ từ lâu
nhưng KHÔNG chỗ nào điền vào, nên mọi chương đều được dịch như thể chưa từng có
chương nào trước đó — tên nhân vật đổi cách viết giữa các chương. Máy chủ từng vá
tạm bằng một dict hardcode 6 từ cho đúng một bộ truyện.

Các test dưới đây giữ cho lỗi đó không quay lại.
"""

import importlib
import os
import tempfile

import pytest
from fastapi.testclient import TestClient

from server import app
from models.translate import TermItem
from services.glossary_store import GlossaryStore
from services.translation_worker import translation_worker
from services.auth_token import issue_token

client = TestClient(app)
AUTH_HEADERS = {"Authorization": f"Bearer {issue_token('test_updates', 'test_updates')}"}


@pytest.fixture
def store():
    with tempfile.TemporaryDirectory() as tmp:
        yield GlossaryStore(base_dir=tmp)


# ---------------------------------------------------------------------------
# Thay chuỗi trong văn bản
# ---------------------------------------------------------------------------

def test_khong_con_thuat_ngu_hardcode():
    """Sáu từ của riêng một bộ truyện không được chèn vào bản dịch của bộ khác."""
    text = "岑星 đi tới 哈佛"
    assert translation_worker._apply_glossary_to_text(text, None) == text


def test_tu_dai_duoc_thay_truoc_tu_ngan():
    """麻省 mà chạy trước 麻省理工 thì tên trường bị ăn mất một nửa.

    Đây là lý do thứ tự phải theo độ dài chứ không theo thứ tự chèn vào dict.
    """
    glossary = [
        TermItem(source="麻省", target="Massachusetts"),
        TermItem(source="麻省理工", target="MIT"),
    ]
    out = translation_worker._apply_glossary_to_text("她考上了麻省理工。", glossary)
    assert "MIT" in out
    assert "Massachusetts" not in out


def test_bo_qua_muc_thieu_nguon_hoac_dich():
    glossary = [
        TermItem(source="", target="X"),
        TermItem(source="岑星", target=""),
        TermItem(source="岑星", target="Sầm Tinh"),
    ]
    assert translation_worker._apply_glossary_to_text("岑星", glossary) == "Sầm Tinh"


# ---------------------------------------------------------------------------
# Kho thuật ngữ phía máy chủ
# ---------------------------------------------------------------------------

def test_luu_roi_doc_lai(store):
    store.save("novel1", [TermItem(source="岑星", target="Sầm Tinh")])
    loaded = store.load("novel1")
    assert [(t.source, t.target) for t in loaded] == [("岑星", "Sầm Tinh")]


def test_truyen_khac_nhau_khong_dung_chung_bang(store):
    store.save("novel1", [TermItem(source="岑星", target="Sầm Tinh")])
    assert store.load("novel2") == []


def test_luu_lan_hai_la_GOP_chu_khong_de_len(store):
    """Ứng dụng chỉ gửi phần đầu bảng đã cắt bớt; ghi đè sẽ làm rụng dần thuật ngữ."""
    store.save("n", [TermItem(source="A", target="a"), TermItem(source="B", target="b")])
    store.save("n", [TermItem(source="C", target="c")])
    sources = {t.source for t in store.load("n")}
    assert sources == {"A", "B", "C"}


def test_luu_lai_cung_nguon_thi_cap_nhat_cach_dich(store):
    store.save("n", [TermItem(source="岑星", target="Sâm Tinh")])
    store.save("n", [TermItem(source="岑星", target="Sầm Tinh")])
    loaded = store.load("n")
    assert len(loaded) == 1
    assert loaded[0].target == "Sầm Tinh"


def test_doc_ve_theo_thu_tu_dai_truoc(store):
    store.save("n", [
        TermItem(source="麻省", target="Massachusetts"),
        TermItem(source="麻省理工", target="MIT"),
    ])
    lengths = [len(t.source) for t in store.load("n")]
    assert lengths == sorted(lengths, reverse=True)


def test_novel_id_doc_hai_khong_thoat_ra_ngoai_thu_muc(store):
    """novel_id đi thẳng vào đường dẫn file — không được cho phép ../"""
    store.save("../../etc/passwd", [TermItem(source="A", target="a")])
    files = os.listdir(store.base_dir)
    assert all(".." not in f and "/" not in f for f in files)
    # Và không ghi ra ngoài thư mục của kho
    assert not os.path.exists("/tmp/passwd.json")


def test_chan_tren_so_muc(store):
    many = [TermItem(source="x" * (i + 1), target=f"t{i}") for i in range(500)]
    total = store.save("n", many)
    assert total <= 400
    assert len(store.load("n")) <= 400


def test_khong_co_file_thi_tra_ve_rong(store):
    assert store.load("chua-ton-tai") == []


def test_file_hong_khong_lam_do_ung_dung(store):
    path = os.path.join(store.base_dir, "n.json")
    with open(path, "w", encoding="utf-8") as f:
        f.write("{ khong phai json")
    assert store.load("n") == []


# ---------------------------------------------------------------------------
# Endpoint báo chương mới
# ---------------------------------------------------------------------------

def test_check_batch_danh_sach_rong():
    res = client.post("/v1/novels/updates/check-batch", json={"novels": []}, headers=AUTH_HEADERS)
    assert res.status_code == 200
    assert res.json()["updates"] == []
    assert res.json()["checked"] == 0


def test_check_batch_truyen_khong_ton_tai_thi_im_lang():
    res = client.post(
        "/v1/novels/updates/check-batch",
        json={"novels": [{"novel_id": "khong-ton-tai-abc", "known_chapters": 5}]},
        headers=AUTH_HEADERS,
    )
    assert res.status_code == 200
    assert res.json()["updates"] == []
    assert res.json()["checked"] == 1


def test_check_batch_tu_choi_danh_sach_qua_dai():
    payload = {"novels": [{"novel_id": f"n{i}", "known_chapters": 0} for i in range(101)]}
    res = client.post("/v1/novels/updates/check-batch", json=payload, headers=AUTH_HEADERS)
    assert res.status_code == 400


def test_check_batch_bao_dung_so_chuong_moi(tmp_path, monkeypatch):
    """Có 120 chương trên máy chủ, ứng dụng biết 100 → báo 20 chương mới."""
    from services import storage_service as ss_module

    monkeypatch.setattr(
        ss_module.storage_service,
        "get_novel_info",
        lambda novel_id: {"title": "Test", "title_vi": "Thử", "total_chapters": 120},
    )
    res = client.post(
        "/v1/novels/updates/check-batch",
        json={"novels": [{"novel_id": "n1", "known_chapters": 100}]},
        headers=AUTH_HEADERS,
    )
    assert res.status_code == 200
    updates = res.json()["updates"]
    assert len(updates) == 1
    assert updates[0]["new_chapters"] == 20
    assert updates[0]["total_chapters"] == 120


def test_check_batch_khong_bao_khi_da_doc_het(monkeypatch):
    from services import storage_service as ss_module

    monkeypatch.setattr(
        ss_module.storage_service,
        "get_novel_info",
        lambda novel_id: {"title": "Test", "total_chapters": 120},
    )
    res = client.post(
        "/v1/novels/updates/check-batch",
        json={"novels": [{"novel_id": "n1", "known_chapters": 120}]},
        headers=AUTH_HEADERS,
    )
    assert res.json()["updates"] == []


def test_check_batch_khong_bao_khi_so_chuong_khong_dang_tin(monkeypatch):
    """total_chapters = 0 nghĩa là máy chủ chưa biết — im lặng còn hơn báo nhầm."""
    from services import storage_service as ss_module

    monkeypatch.setattr(
        ss_module.storage_service,
        "get_novel_info",
        lambda novel_id: {"title": "Test", "total_chapters": 0},
    )
    res = client.post(
        "/v1/novels/updates/check-batch",
        json={"novels": [{"novel_id": "n1", "known_chapters": 0}]},
        headers=AUTH_HEADERS,
    )
    assert res.json()["updates"] == []
