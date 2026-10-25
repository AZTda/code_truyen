"""Rào chắn cho TOÀN BỘ bộ test: không test nào được gọi Antigravity CLI thật hay ra mạng.

Vì sao: nhiều endpoint AI (tra từ, dịch, companion...) sẽ spawn `agy` thật và chạm
Google Translate nếu máy chạy test có cài CLI và có mạng — test chậm, không lặp lại
được, và đốt hạn mức thật. Mọi tính năng đều có nhánh dự phòng khi CLI/mạng hỏng, nên
chặn ở tầng spawn/socket làm máy trông như "CLI lỗi + mất mạng" mà không cần sửa từng test.
Test nào cần hành vi giả thì tự monkeypatch lên trên (monkeypatch của test thắng).

Cách ly DỮ LIỆU thật (truyencloud.db, data/novels, models_cache.json, users.json, hồ sơ dịch, manifest app...)
nằm ở tests/_isolation.py: mỗi test có kho riêng trong tmp_path. Fixture `strict_accounts` bật kiểm tra
"tài khoản còn tồn tại" thật cho token tổng hợp.
"""

import asyncio
import errno
import os
import socket
import subprocess

import pytest

from tests._isolation import (  # noqa: F401  (fixture)
    _isolated_accounts,
    _isolated_local_tts,
    _isolated_server_state,
    _isolated_titles,
    strict_accounts,
)
from tests._title_fixtures import fixture_dict  # noqa: F401  (fixture)

_LOCAL_HOSTS = {None, "", "localhost", "testserver", "::1", "0.0.0.0"}


def _is_local_host(host) -> bool:
    if isinstance(host, bytes):
        host = host.decode("ascii", "ignore")
    return host in _LOCAL_HOSTS or str(host).startswith("127.")


def _is_agy(argv0) -> bool:
    return os.path.basename(str(argv0)) == "agy"


def _refuse_agy(argv0) -> None:
    if _is_agy(argv0):
        raise RuntimeError(f"Test không được chạy Antigravity CLI thật ({argv0}). Hãy giả hàm AI.")


@pytest.fixture(autouse=True)
def _no_real_agy_cli(monkeypatch):
    from services.agy_pool import agy_pool

    # Warm pool tắt: execute()/execute_stream() trả None/rỗng, không spawn tiến trình nào.
    monkeypatch.setattr(agy_pool, "_enabled", False)

    real_popen = subprocess.Popen

    class GuardedPopen(real_popen):
        def __init__(self, args, *a, **kw):
            _refuse_agy(args[0] if isinstance(args, (list, tuple)) and args else args)
            super().__init__(args, *a, **kw)

    real_exec = asyncio.create_subprocess_exec

    async def guarded_exec(program, *args, **kw):
        _refuse_agy(program)
        return await real_exec(program, *args, **kw)

    monkeypatch.setattr(subprocess, "Popen", GuardedPopen)
    monkeypatch.setattr(asyncio, "create_subprocess_exec", guarded_exec)


@pytest.fixture(autouse=True)
def _no_real_network(monkeypatch):
    real_getaddrinfo = socket.getaddrinfo
    real_connect = socket.socket.connect
    real_connect_ex = socket.socket.connect_ex

    def _blocked(host):
        return OSError(errno.ENETUNREACH, f"Mạng bị chặn trong test: {host}")

    def guarded_getaddrinfo(host, *a, **kw):
        if not _is_local_host(host):
            raise socket.gaierror(socket.EAI_NONAME, f"Mạng bị chặn trong test: {host}")
        return real_getaddrinfo(host, *a, **kw)

    def _remote(sock, address) -> bool:
        return sock.family in (socket.AF_INET, socket.AF_INET6) and \
            isinstance(address, tuple) and not _is_local_host(address[0])

    def guarded_connect(self, address):
        if _remote(self, address):
            raise _blocked(address[0])
        return real_connect(self, address)

    def guarded_connect_ex(self, address):
        if _remote(self, address):
            return errno.ENETUNREACH
        return real_connect_ex(self, address)

    monkeypatch.setattr(socket, "getaddrinfo", guarded_getaddrinfo)
    monkeypatch.setattr(socket.socket, "connect", guarded_connect)
    monkeypatch.setattr(socket.socket, "connect_ex", guarded_connect_ex)


@pytest.fixture(autouse=True)
def _isolated_dictionary_cache(tmp_path, monkeypatch):
    """Cache từ điển/tóm tắt/trắc nghiệm của test nằm ở DB tạm, không bao giờ chạm data/dictionary_cache.db thật.

    Nếu không, lượt tra từ cache trúng của test còn cộng `hit_count` vào mục cache thật của người dùng.
    """
    from services.dictionary_cache import dictionary_cache

    monkeypatch.setattr(dictionary_cache, "db_path", str(tmp_path / "dictionary_cache.db"))
    dictionary_cache._init_db()


@pytest.fixture(autouse=True)
def _isolated_job_registry(tmp_path, monkeypatch):
    """Kho job của test nằm ở DB tạm, không ghi thêm dòng nào vào data/jobs.db thật."""
    from services.job_registry import job_registry

    monkeypatch.setattr(job_registry, "db_path", str(tmp_path / "jobs.db"))
    monkeypatch.setattr(job_registry, "_persistent", True)
    job_registry._init_db()


@pytest.fixture(autouse=True)
def _isolated_search_and_translation_cache(tmp_path, monkeypatch):
    """Cache tìm kiếm và cache dịch từ khóa của test nằm ở DB tạm, không chạm truyencloud.db thật.

    Hai module này `from database import get_db_connection` theo tên nên phải vá ngay trong module của chúng.
    Bảng được tạo lại trên DB tạm để cache vẫn hoạt động bình thường trong test.
    """
    import sqlite3

    from services import query_translator as qt_module
    from services import search_cache_service as sc_module

    conn = sqlite3.connect(str(tmp_path / "search_and_query_cache.db"), check_same_thread=False)
    monkeypatch.setattr(sc_module, "get_db_connection", lambda *a, **k: conn)
    monkeypatch.setattr(qt_module, "get_db_connection", lambda *a, **k: conn)
    sc_module.search_cache_service._init_db()
    qt_module.query_translator._init_db()
    yield
    conn.close()
