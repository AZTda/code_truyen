#!/usr/bin/env python3
"""CLI utility for managing TruyenCloud internal user accounts."""

import sys
import json
import argparse
import os
import tempfile
from pathlib import Path

from services.password import hash_password, new_user_id, DEFAULT_AVATAR, DEFAULT_BANNER
from services.user_data import purge_user_data

DATA_DIR = Path(__file__).parent / "data"
USERS_FILE = DATA_DIR / "users.json"


def load_users():
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    if not USERS_FILE.exists():
        return {}
    try:
        with open(USERS_FILE, "r", encoding="utf-8") as f:
            users = json.load(f)
        return users if isinstance(users, dict) else {}
    except Exception:
        return {}


def save_users(users):
    DATA_DIR.mkdir(parents=True, exist_ok=True)
    fd, temp_path = tempfile.mkstemp(prefix=".users-", suffix=".tmp", dir=DATA_DIR)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(users, f, ensure_ascii=False, indent=2)
            f.flush()
            os.fsync(f.fileno())
        os.chmod(temp_path, 0o600)
        os.replace(temp_path, USERS_FILE)
    finally:
        if os.path.exists(temp_path):
            os.unlink(temp_path)


def cmd_list():
    users = load_users()
    print(f"\n=== DANH SÁCH TÀI KHOẢN NỘI BỘ ({len(users)}) ===")
    if not users:
        print("Chưa có tài khoản nào.")
        return
    for u, data in users.items():
        is_hashed = " (PBKDF2 Hashed)" if str(data.get("password", "")).startswith("pbkdf2:") else ""
        admin_mark = " | QUẢN TRỊ" if data.get("is_admin") is True else ""
        print(f"- Username: {u:15} | Tên: {data.get('display_name', ''):20} | Mật khẩu:{is_hashed:17} | VIP: {data.get('vip_tier', '')}{admin_mark}")
    print("================================================\n")


def cmd_add(username, password, display_name=None, email=None, vip_tier="✦ VIP Tinh Anh 2026", is_admin=False):
    users = load_users()
    uname = username.strip().lower()
    if uname in users:
        print(f"LỖI: Tài khoản '{uname}' đã tồn tại!")
        return

    disp_name = display_name or username
    user_email = email or f"{uname}@truyencloud.vn"
    new_user = {
        # Id ngẫu nhiên: xoá rồi tạo lại cùng tên là một danh tính mới, phiên và dữ liệu cũ không thừa hưởng.
        "id": new_user_id(),
        "username": uname,
        "email": user_email,
        "password": hash_password(password),
        "display_name": disp_name,
        "bio": "Thành viên nội bộ TruyenCloud 🌌",
        "vip_tier": vip_tier,
        "avatar_url": DEFAULT_AVATAR,
        "banner_url": DEFAULT_BANNER,
        "auth_provider": "email",
        "is_logged_in": True,
        "total_reading_hours": 0.0,
        "total_chapters_read": 0,
        "total_words_translated": 0,
        "reading_streak_days": 1,
        "is_admin": bool(is_admin),
    }
    users[uname] = new_user
    save_users(users)
    print(f"THÀNH CÔNG: Đã tạo tài khoản '{uname}' cho '{disp_name}'.")


def cmd_set_password(username, new_password):
    if len(new_password) < 8:
        print("LỖI: Mật khẩu phải có ít nhất 8 ký tự.")
        return
    users = load_users()
    uname = username.strip().lower()
    if uname not in users:
        print(f"LỖI: Không tìm thấy tài khoản '{uname}'.")
        return
    users[uname]["password"] = hash_password(new_password)
    save_users(users)
    print(f"THÀNH CÔNG: Đã cập nhật mật khẩu cho tài khoản '{uname}'.")


def cmd_set_admin(username, is_admin):
    users = load_users()
    uname = username.strip().lower()
    if uname not in users:
        print(f"LỖI: Không tìm thấy tài khoản '{uname}'.")
        return
    users[uname]["is_admin"] = bool(is_admin)
    save_users(users)
    state = "CẤP" if is_admin else "THU HỒI"
    print(f"THÀNH CÔNG: Đã {state} quyền quản trị cho tài khoản '{uname}'.")


def cmd_delete(username, keep_data=False):
    users = load_users()
    uname = username.strip().lower()
    if uname not in users:
        print(f"LỖI: Không tìm thấy tài khoản '{uname}'.")
        return
    removed = users.pop(uname)
    save_users(users)  # máy chủ đang chạy thấy tệp đổi và thu hồi mọi phiên của tài khoản này ngay
    print(f"THÀNH CÔNG: Đã xoá tài khoản '{uname}'.")
    if keep_data:
        print("Giữ nguyên dữ liệu đồng bộ (tiến độ, bookmark, từ vựng) — dòng này sẽ mồ côi.")
        return
    purged = purge_user_data(str(removed.get("id") or ""))
    if purged:
        print("Đã xoá dữ liệu đồng bộ: " + ", ".join(f"{table}={count}" for table, count in purged.items()))


def main():
    parser = argparse.ArgumentParser(description="Quản lý tài khoản người dùng nội bộ TruyenCloud")
    subparsers = parser.add_subparsers(dest="command", help="Lệnh cần thực hiện")

    # list
    subparsers.add_parser("list", help="Xem danh sách tài khoản hiện có")

    # add
    add_parser = subparsers.add_parser("add", help="Cấp tài khoản mới")
    add_parser.add_argument("username", nargs="?", default=None, help="Tên đăng nhập")
    add_parser.add_argument("password", nargs="?", default=None, help="Mật khẩu")
    add_parser.add_argument("display_name", nargs="?", default=None, help="Tên hiển thị (tuỳ chọn)")
    add_parser.add_argument("email", nargs="?", default=None, help="Email (tuỳ chọn)")
    add_parser.add_argument("-u", "--username-opt", dest="opt_username", help="Tên đăng nhập (flag)")
    add_parser.add_argument("-p", "--password-opt", dest="opt_password", help="Mật khẩu (flag)")
    add_parser.add_argument("-n", "--name", dest="opt_name", help="Tên hiển thị (flag)")
    add_parser.add_argument("-e", "--email-opt", dest="opt_email", help="Email (flag)")
    add_parser.add_argument("-t", "--tier", dest="opt_tier", default="✦ VIP Tinh Anh 2026", help="Hạng VIP (flag)")
    add_parser.add_argument("--admin", action="store_true", help="Cấp quyền quản trị (is_admin)")

    # set-admin: bật/tắt is_admin (quản trị server-wide: model AI, hồ sơ dịch, phát hành APK)
    admin_parser = subparsers.add_parser("set-admin", help="Cấp / thu hồi quyền quản trị")
    admin_parser.add_argument("username", help="Tên đăng nhập")
    admin_parser.add_argument("--revoke", action="store_true", help="Thu hồi quyền quản trị")

    # set-password
    pw_parser = subparsers.add_parser("set-password", help="Đổi mật khẩu")
    pw_parser.add_argument("username", help="Tên đăng nhập")
    pw_parser.add_argument("password", help="Mật khẩu mới")

    # delete
    del_parser = subparsers.add_parser("delete", help="Xoá tài khoản")
    del_parser.add_argument("username", help="Tên đăng nhập cần xoá")
    del_parser.add_argument("--keep-data", action="store_true",
                            help="Không xoá dữ liệu đồng bộ của tài khoản (tiến độ, bookmark, từ vựng)")

    args = parser.parse_args()

    if not args.command or args.command == "list":
        cmd_list()
    elif args.command == "add":
        username = args.username or args.opt_username
        password = args.password or args.opt_password
        display_name = args.display_name or args.opt_name
        email = args.email or args.opt_email
        tier = args.opt_tier
        if not username or not password:
            print("LỖI: Cần cung cấp username và password.")
            print("Ví dụ: python3 manage_users.py add myuser mypass 'Tên Người Dùng'")
            sys.exit(1)
        cmd_add(username, password, display_name, email, tier, is_admin=args.admin)
    elif args.command == "set-password":
        cmd_set_password(args.username, args.password)
    elif args.command == "set-admin":
        cmd_set_admin(args.username, not args.revoke)
    elif args.command == "delete":
        cmd_delete(args.username, keep_data=args.keep_data)


if __name__ == "__main__":
    main()
