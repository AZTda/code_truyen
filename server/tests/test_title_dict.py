"""Từ điển convert: tải có kiểm sha256, dựng chỉ mục sqlite từ tệp mẫu, tra cứu, fail soft khi thiếu."""

import hashlib
import io
import os
import threading

import pytest

from services import title_dict_sources as sources
from services.hanviet_dict import convert_chapter_subtitle, reverse_hanviet_query, transliterate_to_hanviet
from services.title_dict import TitleDict, TitleDictManager
from services.title_dict_build import build_index, index_is_current, iter_kv
from tests._title_fixtures import build_fixture_dict, write_fixture_sources


def test_iter_kv_bom_dong_noi_tiep_va_dau_bang(tmp_path):
    p = tmp_path / "x.txt"
    p.write_text("﻿a=1\nnối tiếp\nb = 2/3\n\nc=x=y\n", encoding="utf-8")
    assert list(iter_kv(str(p))) == [("a", "1/nối tiếp"), ("b", "2/3"), ("c", "x=y")]


def test_build_va_tra_cuu(tmp_path):
    d = build_fixture_dict(tmp_path)
    assert d.reading("剑") == "kiếm" and d.readings("来") == ("lai", "lại")
    assert d.name("林动") == "Lâm Động"
    assert d.name("第1章") is None  # khoá có chữ số / dạng "第..章" bị loại khỏi Names
    assert d.vp("爱情") == "tình yêu"  # chỉ giữ nghĩa đầu tiên
    assert d.hv_term("化神") == "Hóa Thần" and d.hv_term("爱情") is None
    assert d.prefix_max("斗") == (4, 0)


def test_hanviet_uu_tien_names_nhieu_chu(fixture_dict):
    assert fixture_dict.hanviet("斗破苍穹") == "Đấu Phá Thương Khung"
    assert fixture_dict.hanviet("林动出关！") == "Lâm Động Xuất Quan!"
    assert fixture_dict.hanviet("《剑来》，修仙") == "Kiếm Lai, Tu Tiên"


def test_lookup_whole_giu_dau_cau_cuoi(fixture_dict):
    assert fixture_dict.lookup_whole("剑来！") == ("Kiếm Lai!", "names")
    assert fixture_dict.lookup_whole("爱情") == ("tình yêu", "vietphrase")
    assert fixture_dict.lookup_whole("爱情故事") == (None, None)


def test_convert_khop_dai_nhat_giu_trat_tu_trung(fixture_dict):
    assert fixture_dict.convert("国师的建议") == "Quốc Sư đích đề nghị"
    assert fixture_dict.segments("国师的建议") == ["国师", "的", "建议"]
    assert fixture_dict.convert("鸽") == "Cáp"


def test_name_hints_bo_qua_nguyen_chuoi(fixture_dict):
    assert fixture_dict.name_hints("林动出关") == [("林动", "Lâm Động")]
    assert fixture_dict.name_hints("剑来") == []


def test_doc_song_song_nhieu_luong(fixture_dict):
    errors = []

    def work():
        try:
            for _ in range(50):
                assert fixture_dict.hanviet("斗破苍穹") == "Đấu Phá Thương Khung"
        except Exception as e:  # pragma: no cover - chỉ ghi lại khi lỗi
            errors.append(e)

    threads = [threading.Thread(target=work) for _ in range(4)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert errors == []


def test_build_lan_hai_bo_qua_khi_da_moi(tmp_path, monkeypatch):
    src = tmp_path / "src"
    write_fixture_sources(src)
    out = tmp_path / "idx.sqlite"
    monkeypatch.setattr("services.title_dict_build.sources_fingerprint", lambda: "fp")
    assert "skipped" not in build_index(str(src), str(out))
    assert index_is_current(str(out))
    assert build_index(str(src), str(out)) == {"skipped": True}


def test_hanviet_dict_dung_tu_dien_khi_san_sang(fixture_dict):
    assert transliterate_to_hanviet("《斗破苍穹》") == "Đấu Phá Thương Khung"
    assert reverse_hanviet_query("kiem lai") == "剑来"
    assert convert_chapter_subtitle("国师的建议") == "Quốc Sư đích đề nghị"


def test_khong_co_tu_dien_van_dung_bang_nho():
    # Fixture cách ly: không có từ điển -> bảng HANVIET_CHAR_MAP cũ, không lỗi
    assert transliterate_to_hanviet("斗破苍穹") == "Đấu Phá Thương Khung"
    assert reverse_hanviet_query("kiem lai") == "剑来"
    assert convert_chapter_subtitle("斗破苍穹") == "Đấu Phá Thương Khung"


def test_manager_fail_soft_khi_thieu_tep(tmp_path, monkeypatch):
    monkeypatch.setenv("TITLE_DICT_DIR", str(tmp_path / "empty"))
    mgr = TitleDictManager()
    assert mgr.get() is None
    mgr._prepare()  # TITLE_DICT_AUTO_DOWNLOAD=0 (fixture cách ly) -> không tải, chỉ báo thiếu
    assert mgr.status()["state"] == "missing" and mgr.get() is None


def test_manager_mo_chi_muc_co_san(tmp_path, monkeypatch):
    directory = tmp_path / "dict"
    write_fixture_sources(directory)
    monkeypatch.setenv("TITLE_DICT_DIR", str(directory))
    build_index(str(directory), sources.index_path(str(directory)), force=True)
    mgr = TitleDictManager()
    assert isinstance(mgr.get(), TitleDict)  # bộ mẫu dựng với fingerprint nguồn thật của mã đang chạy
    assert mgr.status()["state"] == "ready"


def test_manager_tu_choi_chi_muc_phien_ban_cu(tmp_path, monkeypatch):
    directory = tmp_path / "dict"
    write_fixture_sources(directory)
    monkeypatch.setenv("TITLE_DICT_DIR", str(directory))
    path = sources.index_path(str(directory))
    build_index(str(directory), path, force=True)
    import sqlite3
    with sqlite3.connect(path) as conn:
        conn.execute("UPDATE meta SET v='0' WHERE k='build_version'")
    mgr = TitleDictManager()
    assert mgr.get() is None and "phiên bản" in (mgr.error or "")


def _fake_source(tmp_path, body: bytes):
    return sources.DictSource("x.txt", "https://raw.githubusercontent.com/x", hashlib.sha256(body).hexdigest(), len(body))


def test_tai_nguon_kiem_sha256(tmp_path, monkeypatch):
    body = b"a=1\n"
    good = _fake_source(tmp_path, body)
    monkeypatch.setattr(sources, "SOURCES", [good])
    monkeypatch.setattr(sources.urllib.request, "urlopen", lambda req, timeout: _Resp(body))
    res = sources.ensure_sources(str(tmp_path / "d"))
    assert res["downloaded"] == ["x.txt"] and sources.missing_sources(str(tmp_path / "d")) == []

    bad = good._replace(sha256="0" * 64)
    monkeypatch.setattr(sources, "SOURCES", [bad])
    with pytest.raises(ValueError):
        sources.ensure_sources(str(tmp_path / "e"))
    assert not os.path.exists(tmp_path / "e" / "x.txt") and not os.path.exists(tmp_path / "e" / "x.txt.part")


def test_nguon_duoc_ghim_commit_va_sha():
    for s in sources.SOURCES:
        assert "raw.githubusercontent.com" in s.url and "/main/" not in s.url and len(s.sha256) == 64 and s.size > 0


class _Resp(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *a):
        self.close()
