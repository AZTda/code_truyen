"""FastTranslator cho văn bản NGẮN: không còn gọi Google (tựa sách, tựa chương, từ khoá) — từ điển convert + cache."""

import pytest

from services.fast_translator import fast_translator
from services.title_translation_service import TitleTranslationMixin


class DummyTitleWorker(TitleTranslationMixin):
    def __init__(self):
        self._title_cache = {}
        self._author_cache = {}

    def _get_agy_path(self):
        return None


class RecordingClient:
    """Thay client HTTP của FastTranslator: mọi request (Google hay gì khác) đều bị ghi lại và từ chối."""

    def __init__(self):
        self.calls = []

    async def post(self, url, **kwargs):
        self.calls.append(url)
        raise AssertionError(f"Không được gọi mạng cho văn bản ngắn: {url}")

    get = post


@pytest.fixture
def recording_client(monkeypatch):
    client = RecordingClient()
    monkeypatch.setattr(fast_translator, "_get_client", lambda: client)
    return client


@pytest.mark.asyncio
async def test_fast_translator_text_dung_tu_dien(fixture_dict, recording_client):
    res = await fast_translator.translate_text("斩杀妖魔", sl="zh-CN", tl="vi")
    assert res == "Trảm Sát Yêu Ma"  # tựa sách: Hán-Việt viết hoa từng chữ
    assert recording_client.calls == []


@pytest.mark.asyncio
async def test_fast_translator_text_khong_tu_dien_van_phien_am(recording_client):
    res = await fast_translator.translate_text("风青阳", sl="zh-CN", tl="vi")
    assert res == "Phong Thanh Dương"
    assert recording_client.calls == []


@pytest.mark.asyncio
async def test_fast_translator_query():
    res = await fast_translator.translate_query_to_cjk("Phàm Nhân Tu Tiên")
    assert len(res) > 0
    assert any('一' <= c <= '鿿' for v in res for c in v)


@pytest.mark.asyncio
async def test_fast_translator_query_tu_dien_nguoc(fixture_dict):
    res = await fast_translator.translate_query_to_cjk("kiếm lai")
    assert res[0] == "剑来"


@pytest.mark.asyncio
async def test_fast_translator_batch_titles(fixture_dict, recording_client):
    titles = ["第1章 圣兽战魂", "第2章 林动出关", "第3章 国师的建议"]
    res = await fast_translator.translate_batch_chapter_titles(titles)
    assert res == ["Chương 1: Thánh thú chiến hồn", "Chương 2: Lâm Động xuất quan", "Chương 3: Quốc Sư đích đề nghị"]
    assert recording_client.calls == []


@pytest.mark.asyncio
async def test_title_worker_fast_path(fixture_dict, recording_client):
    worker = DummyTitleWorker()
    assert await worker.translate_novel_title("万古第一神") == "Vạn Cổ Đệ Nhất Thần"
    assert await worker.translate_author_name("风青阳") == "Phong Thanh Dương"
    ch_list = await worker.translate_chapter_titles_batch(["第1章 圣兽战魂"])
    assert ch_list == ["Chương 1: Thánh thú chiến hồn"]
    assert recording_client.calls == []
