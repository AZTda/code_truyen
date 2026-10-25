import os
import shutil
import pytest
from fastapi.testclient import TestClient
from server import app
from services.storage_service import StorageService

client = TestClient(app)

@pytest.fixture
def temp_storage(tmp_path):
    storage = StorageService(base_dir=str(tmp_path / "novels"))
    return storage

def test_novel_directory_and_chapter_storage(temp_storage):
    novel_id = "test-boston-novel"
    novel_data = {
        "title": "波士顿爱情故事",
        "title_vi": "Chuyện Tình Boston",
        "author": "Test Author",
        "source_url": "https://test.com/novel/boston",
        "total_chapters": 10
    }

    # 1. Save novel info
    info_path = temp_storage.save_novel_info(novel_id, novel_data)
    assert os.path.exists(info_path)
    assert os.path.basename(info_path) == "info.json"

    # 2. Check novel info read
    loaded_info = temp_storage.get_novel_info(novel_id)
    assert loaded_info is not None
    assert loaded_info["title_vi"] == "Chuyện Tình Boston"

    # 3. Save whole translated chapter
    chapter_data = {
        "chapter_number": 1,
        "title": "第一章 命运的邂逅",
        "title_vi": "Chương 1: Cuộc gặp gỡ định mệnh",
        "original_content": "岑星提着行李箱走出波士顿机场...",
        "translated_content": "Sầm Tinh kéo chiếc vali bước ra khỏi sân bay Boston...",
        "translation_engine": "ANTIGRAVITY"
    }
    ch_path = temp_storage.save_chapter(novel_id, 1, chapter_data)
    assert os.path.exists(ch_path)
    assert os.path.basename(ch_path) == "chapter_1.json"

    # 4. Read chapter back
    loaded_ch = temp_storage.get_chapter(novel_id, 1)
    assert loaded_ch is not None
    assert loaded_ch["translated_content"] == chapter_data["translated_content"]
    assert loaded_ch["translation_engine"] == "ANTIGRAVITY"

    # 5. Verify list chapters
    chapters = temp_storage.list_chapters(novel_id)
    assert len(chapters) == 1
    assert chapters[0]["chapter_number"] == 1

def test_retranslate_overwrites_cache(temp_storage):
    novel_id = "test-retrans-novel"
    # Initial chapter translation
    temp_storage.save_chapter(novel_id, 1, {
        "title": "Chương 1",
        "title_vi": "Chương 1: Khởi đầu",
        "original_content": "岑星来到波士顿",
        "translated_content": "Bản dịch cũ chưa vừa ý",
        "translation_engine": "ANTIGRAVITY"
    })
    
    old_ch = temp_storage.get_chapter(novel_id, 1)
    assert old_ch["translated_content"] == "Bản dịch cũ chưa vừa ý"

    # Retranslate with custom instructions and force overwrite
    new_translated = "Sầm Tinh đến Boston (bản dịch mới hoàn hảo theo phong cách yêu cầu)"
    temp_storage.save_chapter(novel_id, 1, {
        "title": "Chương 1",
        "title_vi": "Chương 1: Khởi đầu",
        "original_content": "岑星来到波士顿",
        "translated_content": new_translated,
        "translation_engine": "ANTIGRAVITY"
    })

    new_ch = temp_storage.get_chapter(novel_id, 1)
    assert new_ch["translated_content"] == new_translated


def test_unsafe_novel_ids_do_not_collide(temp_storage):
    first = temp_storage.get_novel_dir("a/b")
    second = temp_storage.get_novel_dir("ab")
    assert first != second
    assert first.startswith(str(temp_storage.base_dir))
    assert second.startswith(str(temp_storage.base_dir))
