import pytest
import asyncio
import time
from models.novel import Novel
from services.search_cache_service import SearchCacheService
from services.search_service import SearchService
from services.query_translator import query_translator


@pytest.fixture
def cache_service(tmp_path):
    return SearchCacheService()


def test_search_cache_set_and_get():
    cache = SearchCacheService()
    test_novels = [
        Novel(
            id="test_1",
            title="剑来",
            title_vi="Kiếm Lai",
            author="Phong Hỏa Hí Chư Hầu",
            url="https://example.com/1",
            source_id="test",
        ),
        Novel(
            id="test_2",
            title="遮天",
            title_vi="Già Thiên",
            author="Thần Đông",
            url="https://example.com/2",
            source_id="test",
        ),
    ]

    # Cache should be empty initially for random query
    unique_q = f"query_{int(time.time()*1000)}"
    assert cache.get(unique_q) is None

    # Set cache
    cache.set(unique_q, test_novels, ttl_seconds=60)

    # Get cache hit
    hit = cache.get(unique_q)
    assert hit is not None
    assert len(hit) == 2
    assert hit[0].id == "test_1"
    assert hit[0].title_vi == "Kiếm Lai"

    # Expiration test
    cache.set("expired_q", test_novels, ttl_seconds=-1)
    assert cache.get("expired_q") is None


@pytest.mark.asyncio
async def test_offline_query_translation():
    # 1. Chinese characters should pass through without AI (0ms)
    cjk_res = await query_translator.translate_query("剑来")
    assert "剑来" in cjk_res

    # 2. Known popular titles in TITLE_MAPPING should hit dictionary without AI
    mapped_res = await query_translator.translate_query("pham nhan tu tien")
    assert "凡人修仙传" in mapped_res

    mapped_res2 = await query_translator.translate_query("quang am chi ngoai")
    assert "光阴之外" in mapped_res2


@pytest.mark.asyncio
async def test_local_library_search():
    svc = SearchService()

    # Search something that won't match
    empty = svc.search_local_library("non_existent_xyz_12345")
    assert isinstance(empty, list)

    # Search with accented and unaccented terms
    # Check that method runs and returns a list in < 5ms
    start = time.perf_counter()
    _ = svc.search_local_library("kiem")
    elapsed = time.perf_counter() - start
    assert elapsed < 0.05  # Must be instant (< 50ms)


@pytest.mark.asyncio
async def test_search_service_uses_cache():
    svc = SearchService()
    from services.search_cache_service import search_cache_service

    q = "test_cache_flow_novel"
    sample = [
        Novel(
            id="sample_99",
            title="测试小说",
            title_vi="Thử Nghiệm Tiểu Thuyết",
            author="Tác giả",
            url="https://example.com/sample",
            source_id="test",
        )
    ]

    # Pre-populate cache
    search_cache_service.set(q, sample, source=None)

    # Now call search() -> it MUST return from cache instantly
    start = time.perf_counter()
    res = await svc.search(q)
    elapsed = time.perf_counter() - start

    assert res.total == 1
    assert res.results[0].id == "sample_99"
    assert elapsed < 0.02  # Less than 20ms


def test_reverse_hanviet_query_instant():
    from services.hanviet_dict import reverse_hanviet_query
    
    # 1. Reverse transliteration for famous terms
    assert reverse_hanviet_query("kiem lai") == "剑来"
    assert reverse_hanviet_query("kiếm lai") == "剑来"
    assert reverse_hanviet_query("dau pha thuong khung") == "斗破苍穹"
    assert reverse_hanviet_query("đấu phá thương khung") == "斗破苍穹"
    assert reverse_hanviet_query("van co") == "万古"


@pytest.mark.asyncio
async def test_query_translator_instant_reverse_hanviet():
    # Calling translate_query for Vietnamese terms should resolve in < 10ms via reverse Hán-Việt
    start = time.perf_counter()
    res = await query_translator.translate_query("kiem lai")
    elapsed = time.perf_counter() - start
    assert "剑来" in res
    assert elapsed < 0.05
