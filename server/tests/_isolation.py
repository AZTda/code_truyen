"""Cách ly trạng thái bền vững của server cho từng test (được import vào tests/conftest.py).

Trước đây chỉ agy/mạng/cache từ điển/jobs/cache tìm kiếm được cách ly, còn chạy bộ test tại chỗ vẫn ghi vào
server/truyencloud.db, data/novels, data/models_cache.json... của DỮ LIỆU THẬT (ví dụ test_api ghi đè danh mục
model, test_security ghi dòng tiến độ giả). Mỗi test giờ có kho riêng trong tmp_path: CSDL SQLite, thư mục
truyện, danh mục model, sổ tài khoản, hồ sơ dịch, manifest cập nhật app, cache TTS/ảnh bìa.
Test cần dữ liệu thì tự dựng trong kho tạm đó.
"""

import pytest

_MISSING = object()


def _redirect(monkeypatch, module, **targets):
    """setattr nhiều thuộc tính; thiếu thuộc tính thì báo lỗi to (đổi tên biến đường dẫn không được làm hở cách ly)."""
    for name, value in targets.items():
        if getattr(module, name, _MISSING) is _MISSING:
            pytest.fail(f"Cách ly test: {module.__name__}.{name} không còn tồn tại — cập nhật tests/_isolation.py.")
        monkeypatch.setattr(module, name, value)


@pytest.fixture(autouse=True)
def _isolated_server_state(tmp_path, monkeypatch):
    """Mọi kho dữ liệu của server trỏ vào thư mục tạm của test; hạn mức tần suất về 0."""
    import database
    from database import db
    from routes import app_update, images as images_route, settings as settings_route, tts as tts_route
    from services import model_catalog, rate_limit, tts_cache, tts_voice_service
    from services.glossary_store import glossary_store
    from services.library_index import library_index
    from services.storage_service import storage_service

    state = tmp_path / "server_state"
    data_dir = state / "data"
    for sub in ("novels", "glossaries", "tts_cache", "covers_cache"):
        (data_dir / sub).mkdir(parents=True)

    # --- SQLite: bảng mới tạo ở DB tạm; không bao giờ chạm Supabase thật dù .env có khoá
    db_file = str(state / "truyencloud.db")
    _redirect(monkeypatch, database, DB_PATH=db_file)
    monkeypatch.setattr(db, "_mock_tables", {})
    monkeypatch.setattr(db, "client", None)
    monkeypatch.setattr(db, "is_mock", True)

    # --- Truyện trên đĩa, từ điển thuật ngữ, cache TTS / ảnh bìa
    monkeypatch.setattr(storage_service, "base_dir", str(data_dir / "novels"))
    monkeypatch.setattr(glossary_store, "base_dir", str(data_dir / "glossaries"))
    _redirect(monkeypatch, tts_route, CACHE_DIR=str(data_dir / "tts_cache"))
    _redirect(monkeypatch, tts_voice_service, CACHE_DIR=str(data_dir / "tts_cache"))
    _redirect(monkeypatch, tts_cache, CACHE_DIR=str(data_dir / "tts_cache"))
    _redirect(monkeypatch, images_route, CACHE_DIR=str(data_dir / "covers_cache"))

    # --- Danh mục model (get_catalog có thể tự ghi lại tệp khi phiên bản CLI đổi)
    _redirect(monkeypatch, model_catalog, DATA_DIR=data_dir, CATALOG_FILE=data_dir / "models_cache.json")
    monkeypatch.setattr(model_catalog.model_catalog_service, "_cache_data", None)
    monkeypatch.setattr(model_catalog.model_catalog_service, "_cache_mtime", None)

    # --- Hồ sơ dịch dùng chung và manifest cập nhật app
    _redirect(monkeypatch, settings_route, DATA_DIR=data_dir, PROFILE_FILE=data_dir / "translation_profile.json")
    _redirect(monkeypatch, app_update, DATA_DIR=str(data_dir), VERSION_FILE=str(data_dir / "app_version.json"))

    library_index.invalidate()
    rate_limit.reset_all()
    yield state
    rate_limit.reset_all()
    library_index.invalidate()
    database.close_all_connections(db_file)


@pytest.fixture(autouse=True)
def _isolated_accounts(tmp_path, monkeypatch):
    """Sổ tài khoản (users.json) ở thư mục tạm.

    Phần lớn test dùng token tổng hợp `issue_token("user_x", "x")` cho tài khoản không có trong sổ nào (hoặc
    dùng sổ riêng chỉ có vài tài khoản mà vẫn gửi token của người khác). Production giờ từ chối token của tài
    khoản không còn trong sổ, nên mặc định bộ test bỏ qua phép kiểm tra đó; test nào kiểm chính hành vi này
    phải xin fixture `strict_accounts`.
    """
    from config import settings
    from routes import auth as auth_routes
    from services import auth_token
    import manage_users

    accounts_dir = tmp_path / "accounts"
    accounts_dir.mkdir()
    default_file = accounts_dir / "users.json"  # chưa tồn tại: load_users() dựng test1 khi DEBUG (môi trường dev)
    _redirect(monkeypatch, auth_routes, DATA_DIR=accounts_dir, USERS_FILE=default_file)
    _redirect(monkeypatch, manage_users, DATA_DIR=accounts_dir, USERS_FILE=default_file)
    monkeypatch.setattr(settings, "DEBUG", True)  # không phụ thuộc DEBUG trong .env của máy chạy test
    auth_routes.reset_users_cache()

    def accept_any_signed_token(payload):
        return True

    accept_any_signed_token.real = auth_token.account_exists
    monkeypatch.setattr(auth_token, "account_exists", accept_any_signed_token)
    yield default_file
    auth_routes.reset_users_cache()


@pytest.fixture
def strict_accounts(monkeypatch):
    """Bật kiểm tra tài khoản thật cho test này: token chỉ hợp lệ nếu tài khoản có trong sổ USERS_FILE."""
    from services import auth_token

    real = getattr(auth_token.account_exists, "real", None)
    if real is not None:
        monkeypatch.setattr(auth_token, "account_exists", real)


@pytest.fixture(autouse=True)
def _isolated_local_tts(monkeypatch):
    """Giọng local + hàng đợi dựng sạch cho từng test, và KHÔNG BAO GIỜ nạp mô hình VieNeu thật trong bộ test.

    Test cần engine thì gắn FakeEngine qua tests/_tts_fakes.install().
    """
    from services import tts_cache, tts_prerender_plan
    from services.tts_local import local_tts
    from services.tts_prerender import render_queue

    def refuse_real_model(threads):
        raise RuntimeError("Bộ test không được nạp mô hình VieNeu thật: dùng tests/_tts_fakes.install()")

    def clean():
        render_queue.reset()
        local_tts.reset()
        local_tts.set_factory(refuse_real_model)
        tts_cache.sweeper.reset()
        tts_prerender_plan.clear_plan_cache()

    for name in ("TTS_LOCAL_ENABLED", "TTS_ONDEMAND_TIMEOUT", "TTS_LOCAL_MODEL", "TTS_ASSUMED_RTF"):
        monkeypatch.delenv(name, raising=False)  # mặc định: Nano, RTF giả định theo mô hình
    clean()
    monkeypatch.setenv("TTS_LOCAL_WARMUP", "0")
    yield
    clean()
    local_tts.set_factory(None)


@pytest.fixture(autouse=True)
def _isolated_titles(tmp_path, monkeypatch):
    """Dịch tựa đề không Google: KHÔNG dùng từ điển thật trong data/dict, không gọi Hy-MT2, cache tựa ở thư mục tạm.

    Mặc định test chạy như máy chưa có từ điển (bảng Hán-Việt nhỏ) và dịch vụ mô hình tắt. Test cần từ điển thì dùng
    fixture `fixture_dict` (tests/_title_fixtures.py, dựng từ tệp mẫu nhỏ); test cần mô hình thì giả title_mt.
    """
    from services import title_patcher
    from services.title_cache_store import title_cache
    from services.title_dict import title_dict
    from services.title_mt_client import title_mt
    from services.title_refiner import title_refiner

    monkeypatch.setenv("TITLE_MT_ENABLED", "0")
    monkeypatch.setenv("TITLE_DICT_AUTO_DOWNLOAD", "0")
    monkeypatch.setenv("TITLE_DICT_DIR", str(tmp_path / "title_dict"))
    monkeypatch.setenv("TITLE_MT_MODEL_DIR", str(tmp_path / "title_mt"))
    title_dict.install(None)
    title_cache.use_path(str(tmp_path / "title_cache.sqlite"))
    title_refiner.reset()
    title_mt.mark_down()
    title_patcher._scan_memo.clear()
    yield
    title_refiner.reset()
    title_cache.close()
    title_dict.install(None)
