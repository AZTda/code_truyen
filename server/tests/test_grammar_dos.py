"""POST /v1/english/grammar-patterns không được làm đứng cả máy chủ bằng một văn bản dài.

Bản cũ: `text` không có trần, regex chạy đồng bộ ngay trong vòng lặp sự kiện, và vài regex có lặp lồng nhau:
câu "It is the It is the ..." tốn O(n^2); "Into the" + hàng nghìn dấu cách tốn O(n^3) (4000 dấu cách > 8 giây,
19000 dấu cách chạy nhiều phút) -- một yêu cầu của bất kỳ tài khoản nào cũng chặn mọi yêu cầu khác.
"""

import threading
import time

import pytest
from fastapi.testclient import TestClient

from database import SqliteBackedTable
from server import app
from services import grammar_extractor
from services.grammar_extractor import MAX_DETECTIONS_PER_REQUEST, MAX_SENTENCE_CHARS, grammar_service
from tests._admin_support import USER_HEADERS, two_accounts  # noqa: F401 (two_accounts: fixture)

client = TestClient(app, raise_server_exceptions=False)


@pytest.fixture
def tmp_table(tmp_path, monkeypatch, two_accounts):
    """Bảng ngữ pháp ở DB tạm: không bao giờ ghi vào truyencloud.db thật."""
    table = SqliteBackedTable("grammar_patterns", db_path=str(tmp_path / "g.db"))
    monkeypatch.setattr(grammar_service, "_get_table", lambda: table)
    return table


def _post(text, **extra):
    return client.post("/v1/english/grammar-patterns", headers=USER_HEADERS, json={"text": text, **extra})


# ---------------------------------------------------------------------------
# Trần kích cỡ
# ---------------------------------------------------------------------------

def test_text_toi_da_20000_ky_tu(tmp_table):
    assert _post("word " * 4000, auto_record=False).status_code == 200            # đúng 20000
    assert _post("word " * 4001, auto_record=False).status_code == 422            # 20005
    assert _post("x", auto_record=False, novel_id="n" * 201).status_code == 422
    assert _post("x", auto_record=False, novel_title="t" * 301).status_code == 422
    assert _post("x", auto_record=False, chapter_number=-1).status_code == 422


def test_cau_qua_dai_bi_bo_qua_con_cau_thuong_van_nhan_dien():
    normal = "Hardly had the swordmaster drawn his blade when the dark sky split open."
    assert [m["pattern_id"] for m in grammar_service.detect_patterns(normal)] == ["negative_inversion"]
    # Văn bản không dấu câu: cả đoạn là MỘT "câu" dài, không quét
    runaway = normal[:-1] + " and the wind howled " * 60
    assert len(runaway) > MAX_SENTENCE_CHARS and grammar_service.detect_patterns(runaway) == []
    assert grammar_service.scan_sentence(runaway) == []


def test_gioi_han_so_mau_phat_hien_va_so_lan_ghi_csdl(tmp_table, monkeypatch):
    sentence = "Hardly had the swordmaster drawn his blade when the dark sky split open. "
    detected = grammar_service.detect_patterns(sentence * 300)
    assert len(detected) == MAX_DETECTIONS_PER_REQUEST

    recorded = []
    real = grammar_service.record_patterns
    monkeypatch.setattr(grammar_service, "record_patterns", lambda p, user_id="local": recorded.append(len(p)) or real(p, user_id))
    res = _post(sentence * 250)
    assert res.status_code == 200 and res.json()["detected_count"] == MAX_DETECTIONS_PER_REQUEST
    assert recorded == [MAX_DETECTIONS_PER_REQUEST]


def test_quet_chay_o_luong_phu_khong_chan_vong_lap_su_kien(tmp_table, monkeypatch):
    seen = []
    real = grammar_service.detect_patterns

    def spy(*args, **kwargs):
        seen.append(threading.current_thread() is threading.main_thread())
        return real(*args, **kwargs)
    monkeypatch.setattr(grammar_service, "detect_patterns", spy)
    assert _post("Hardly had the swordmaster drawn his blade when the dark sky split open.").status_code == 200
    assert seen == [False], "Quét regex phải chạy ở luồng phụ"


# ---------------------------------------------------------------------------
# Chi phí tuyến tính trên đầu vào đối nghịch (tối đa 20000 ký tự)
# ---------------------------------------------------------------------------

ADVERSARIAL = {
    "it-is-the": "It is the " * 2000,
    "what-he-spaces": "What he" + " " * 19000 + "x",
    "into-the-spaces": "Into the" + " " * 19000 + "x",
    "the-more": "The more " * 2200,
    "into-the-words": "Into the " + "word " * 3800,
    "looking": "Looking " + "a " * 9000,
    "had": "Had John " + "x" * 19000,
}


@pytest.mark.parametrize("name", sorted(ADVERSARIAL))
def test_dau_vao_doi_nghich_20000_ky_tu_xu_ly_tuc_thi_adversarial(name):
    started = time.perf_counter()
    grammar_service.detect_patterns(ADVERSARIAL[name])
    assert time.perf_counter() - started < 0.5


@pytest.mark.parametrize("name", sorted(ADVERSARIAL))
def test_cau_sat_tran_do_dai_van_nhanh_adversarial(name):
    """Câu vừa dưới trần 1000 ký tự (không bị bỏ qua) vẫn phải nhanh: regex đã bỏ lặp lồng nhau."""
    # Kết thúc bằng một chữ cái để strip() không cắt mất phần dấu cách đối nghịch
    sentence = ADVERSARIAL[name][:MAX_SENTENCE_CHARS - 1] + "x"
    started = time.perf_counter()
    for _ in range(20):
        grammar_service.scan_sentence(sentence)
    assert time.perf_counter() - started < 0.5


# ---------------------------------------------------------------------------
# Regex viết lại vẫn nhận diện đúng
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("sentence, pattern_id", [
    ("It was the ancient dragon that guarded the jade gate.", "cleft_sentence_it"),
    ("It is this very technique that altered his fate forever.", "cleft_sentence_it"),
    ("It was John who left early in the morning.", "cleft_sentence_it"),
    ("It was the ancient dragon of the northern mountains that guarded the gate.", "cleft_sentence_it"),
    ("What he really wanted was a quiet life far away.", "cleft_sentence_wh"),
    ("What the king needed is courage in dark times.", "cleft_sentence_wh"),
    ("Into the dark hall came a stranger in a grey cloak.", "locative_inversion"),
    ("Beneath the old stone bridge lay a sleeping dragon.", "locative_inversion"),
    ("The more he trained, the stronger he became with time.", "comparative_correlative"),
])
def test_regex_viet_lai_van_nhan_dien_dung(sentence, pattern_id):
    assert pattern_id in {m["pattern_id"] for m in grammar_service.scan_sentence(sentence)}


@pytest.mark.parametrize("sentence, pattern_id", [
    ("It was the dragon, and nothing else mattered at all.", "cleft_sentence_it"),
    ("What he said was nothing of the kind we expected.", "cleft_sentence_wh"),
    ("Into the dark hall he strolled slowly and carefully.", "locative_inversion"),
    ("The more he trained and trained without ever resting at all.", "comparative_correlative"),
])
def test_regex_viet_lai_khong_nhan_dien_nham(sentence, pattern_id):
    assert pattern_id not in {m["pattern_id"] for m in grammar_service.scan_sentence(sentence)}


def test_hang_so_trong_module_khop_voi_gia_tri_khuyen_nghi():
    assert grammar_extractor.MAX_SENTENCE_CHARS == 1000 and grammar_extractor.MAX_DETECTIONS_PER_REQUEST == 100
