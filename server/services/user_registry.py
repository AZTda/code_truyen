"""Bộ nhớ đệm users.json theo (mtime, kích thước, inode).

`current_user` chạy trên MỌI request có token và phải biết tài khoản còn tồn tại hay không; đọc và parse lại
tệp mỗi lần là lãng phí, còn giữ bản trong RAM vô thời hạn thì không thấy lúc quản trị xoá tài khoản.
Khoá đệm đổi mỗi khi tệp bị thay (save_users dùng os.replace nên inode và mtime đều đổi).
"""

import os
import threading
from pathlib import Path
from typing import Any, Callable, Dict, Optional, Tuple

Users = Dict[str, Dict[str, Any]]


class UsersCache:
    def __init__(self, path_getter: Callable[[], Path], loader: Callable[[], Users]):
        # path_getter đọc đường dẫn lúc gọi để test đổi được USERS_FILE; loader là hàm đọc thật.
        self._path_getter = path_getter
        self._loader = loader
        self._lock = threading.Lock()
        self._key: Optional[Tuple[Any, ...]] = None
        self._users: Users = {}

    def get(self) -> Users:
        """Bản users.json hiện hành. Chỉ ĐỌC kết quả — nó được dùng chung giữa các request."""
        path = self._path_getter()
        try:
            st = os.stat(path)
            key: Tuple[Any, ...] = (str(path), st.st_mtime_ns, st.st_size, st.st_ino)
        except FileNotFoundError:
            key = (str(path), None)
        except OSError:
            return self._loader()
        with self._lock:
            if key == self._key:
                return self._users
        # Đọc NGOÀI khoá: loader tự giữ khoá của users.json, giữ cả hai sẽ có nguy cơ khoá chéo.
        users = self._loader()
        with self._lock:
            self._users, self._key = users, key
        return users

    def invalidate(self) -> None:
        self._key = None
