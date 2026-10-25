"""publish_apk.sh: giá trị nhập vào không bao giờ thành mã Python, và lỗi giữa chừng không để APK lệch sha256.

Chạy bản sao của kịch bản trong thư mục tạm (SCRIPT_DIR suy ra từ vị trí tệp) — không đụng static/ hay data/ thật.
"""

import hashlib
import json
import os
import shutil
import stat
import subprocess
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parent.parent / "publish_apk.sh"
OLD_APK = b"OLD-APK-BYTES"
NEW_APK = b"NEW-APK-BYTES" * 1000


@pytest.fixture
def release(tmp_path):
    root = tmp_path / "server"
    (root / "data").mkdir(parents=True)
    (root / "static").mkdir()
    shutil.copy(SCRIPT, root / "publish_apk.sh")
    (root / "static" / "truyencloud-latest.apk").write_bytes(OLD_APK)
    (root / "data" / "app_version.json").write_text(
        json.dumps({"version_code": 4, "version_name": "1.0.4", "sha256": "0" * 64, "keep_me": "yes"}), encoding="utf-8")
    (tmp_path / "new.apk").write_bytes(NEW_APK)
    return root


def run(root, *args, path_prefix=None):
    env = dict(os.environ)
    if path_prefix:
        env["PATH"] = f"{path_prefix}{os.pathsep}{env['PATH']}"
    return subprocess.run(["bash", str(root / "publish_apk.sh"), *args], capture_output=True, text=True,
                          cwd=root, env=env, timeout=60)


def manifest(root):
    return json.loads((root / "data" / "app_version.json").read_text(encoding="utf-8"))


def assert_untouched(root):
    assert (root / "static" / "truyencloud-latest.apk").read_bytes() == OLD_APK
    assert manifest(root)["version_code"] == 4 and manifest(root)["sha256"] == "0" * 64
    leftovers = [p.name for d in ("static", "data") for p in (root / d).iterdir() if p.name.startswith(".")]
    assert leftovers == [], f"tệp tạm còn sót: {leftovers}"


def shim(tmp_path, name, body):
    bin_dir = tmp_path / "shims"
    bin_dir.mkdir(exist_ok=True)
    path = bin_dir / name
    path.write_text("#!/bin/bash\n" + body, encoding="utf-8")
    path.chmod(path.stat().st_mode | stat.S_IEXEC)
    return str(bin_dir)


def test_script_has_valid_bash_syntax():
    assert subprocess.run(["bash", "-n", str(SCRIPT)], capture_output=True).returncode == 0


HOSTILE = [
    ("5", "1.0.5", 'Sua loi "khong tai duoc chuong"'),
    ("5", "1.0.5", "Fix crash on C:\\users\\new profile \\u12 and \\N{x}"),
    ("5", '1.0.7 "beta"', "ok"),
    ("5", "1.0.5", '""" + str(__import__("os").getcwd()) + """'),
    ("5", "1.0.5", "- dong 1\n- dong 2 voi 'nhay don' va $HOME va `whoami` va $(touch pwned)"),
    ("5", "1.0.5", "Tiếng Việt có dấu 🌌 và ký tự lạ ’ “ ”"),
    ("5", "1.0.5", "'; touch pwned; '"),
]


@pytest.mark.parametrize("code,name,changelog", HOSTILE)
def test_values_with_quotes_and_backslashes_reach_the_manifest_verbatim(release, tmp_path, code, name, changelog):
    res = run(release, str(tmp_path / "new.apk"), code, name, changelog)
    assert res.returncode == 0, res.stderr
    data = manifest(release)
    assert data["changelog"] == changelog and data["version_name"] == name and data["version_code"] == int(code)
    assert data["keep_me"] == "yes"  # các trường khác của manifest được giữ
    served = (release / "static" / "truyencloud-latest.apk").read_bytes()
    assert served == NEW_APK
    assert data["sha256"] == hashlib.sha256(NEW_APK).hexdigest() and data["file_size_bytes"] == len(NEW_APK)
    assert not (release / "pwned").exists() and not (tmp_path / "pwned").exists()  # không có gì bị thực thi
    assert [p.name for d in ("static", "data") for p in (release / d).iterdir() if p.name.startswith(".")] == []


def test_publishing_into_an_empty_install_works(release, tmp_path):
    (release / "static" / "truyencloud-latest.apk").unlink()
    (release / "data" / "app_version.json").unlink()
    res = run(release, str(tmp_path / "new.apk"), "1", "1.0.1", "first")
    assert res.returncode == 0, res.stderr
    assert manifest(release)["version_code"] == 1


def test_invalid_version_code_changes_nothing(release, tmp_path):
    res = run(release, str(tmp_path / "new.apk"), "five", "1.0.5", "x")
    assert res.returncode != 0
    assert_untouched(release)


def test_missing_apk_changes_nothing(release, tmp_path):
    res = run(release, str(tmp_path / "does-not-exist.apk"), "5", "1.0.5", "x")
    assert res.returncode != 0
    assert_untouched(release)


def test_failure_while_building_the_manifest_leaves_old_apk_and_manifest_in_place(release, tmp_path):
    # python3 đọc chương trình từ stdin (python3 -) là bước dựng manifest: cho nó hỏng sau khi APK đã được chép sang tệp tạm.
    real = shutil.which("python3")
    bin_dir = shim(tmp_path, "python3", f'if [ "$1" = "-" ]; then echo boom >&2; exit 1; fi\nexec "{real}" "$@"\n')
    res = run(release, str(tmp_path / "new.apk"), "5", "1.0.5", "x", path_prefix=bin_dir)
    assert res.returncode != 0
    assert_untouched(release)  # trước đây: APK mới đã đè lên nhưng sha256/version vẫn của bản cũ


def test_failure_replacing_the_manifest_restores_the_previous_apk(release, tmp_path):
    bin_dir = shim(tmp_path, "mv", 'for last; do :; done\ncase "$last" in *app_version.json) exit 1;; esac\nexec /bin/mv "$@"\n')
    res = run(release, str(tmp_path / "new.apk"), "5", "1.0.5", "x", path_prefix=bin_dir)
    assert res.returncode != 0
    assert_untouched(release)
