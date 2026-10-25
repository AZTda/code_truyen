"""Rào chắn của tests/conftest.py thật sự chặn CLI Antigravity và mạng ngoài."""

import asyncio
import socket
import subprocess

import pytest

from services.agy_pool import agy_pool
from services.dictionary_cache import dictionary_cache
from services.fast_translator import fast_translator


def test_khong_the_chay_agy_bang_subprocess():
    with pytest.raises(RuntimeError, match="Antigravity"):
        subprocess.run(["/home/x/.local/bin/agy", "--version"], capture_output=True)
    with pytest.raises(RuntimeError, match="Antigravity"):
        subprocess.Popen(["agy", "-p", "xin chào"])


@pytest.mark.asyncio
async def test_khong_the_chay_agy_bang_asyncio():
    with pytest.raises(RuntimeError, match="Antigravity"):
        await asyncio.create_subprocess_exec("agy", "-p", "xin chào")


def test_tien_trinh_khac_van_chay_duoc():
    assert subprocess.run(["echo", "ok"], capture_output=True, text=True).stdout.strip() == "ok"


@pytest.mark.asyncio
async def test_warm_pool_tat_trong_test():
    assert await agy_pool.execute("xin chào", timeout=1.0) is None
    assert [chunk async for chunk in agy_pool.execute_stream("xin chào", timeout=1.0)] == []


def test_mang_ngoai_bi_chan():
    with pytest.raises(socket.gaierror):
        socket.getaddrinfo("translate.googleapis.com", 443)
    with pytest.raises(OSError):
        socket.create_connection(("93.184.216.34", 80), timeout=1)


def test_loopback_van_dung_duoc():
    with socket.socket() as server:
        server.bind(("127.0.0.1", 0))
        server.listen(1)
        with socket.create_connection(server.getsockname(), timeout=2):
            pass


@pytest.mark.asyncio
async def test_dich_du_phong_khi_mat_mang_tra_none_nhanh():
    assert await fast_translator.translate_chapter_text("第一段。\n第二段。", tl="vi") is None


def test_cache_tu_dien_nam_o_thu_muc_tam():
    assert "dictionary_cache.db" in dictionary_cache.db_path
    assert "/data/" not in dictionary_cache.db_path.replace("\\", "/")
