"""Pinned community "convert" dictionaries, downloaded at runtime into the data volume.

The data has no license (partly from commercial dictionaries): private use only. It is NEVER baked into the image
or copied into the repo; the server fetches the exact files below once (raw GitHub at fixed commit SHAs, sha256 +
size verified) into TITLE_DICT_DIR (default server/data/dict) and works offline afterwards.
"""

import contextlib
import hashlib
import logging
import os
import urllib.request
from typing import Dict, Iterator, List, NamedTuple, Optional

logger = logging.getLogger("truyencloud.title_dict.sources")

_HT = "https://raw.githubusercontent.com/hoangtuantk/Vietphrase/e855bd84d611163394dade11a820916f189d87a5/data/"
_TD = "https://raw.githubusercontent.com/ParadoxParadise/TienDich/2164c235bdc5da225072acaf8324705ce51c3667/data/"


class DictSource(NamedTuple):
    name: str  # tên tệp cục bộ trong TITLE_DICT_DIR
    url: str
    sha256: str
    size: int


# Đúng các tệp benchmark đã dùng (khớp git blob của commit được ghim).
SOURCES: List[DictSource] = [
    DictSource("ht_HanViet.txt", _HT + "HanViet.txt",
               "2c40ca73e42d645e25b99fb421658f3e99c3f830eff122bdbb3f3850d109b705", 165973),
    DictSource("td_PhienAm.txt", _TD + "ChinesePhienAmWords.txt",
               "7b808ffe700b28cbb1e6e25e75ac9e9ce5087d9c54ddee8ebf0c3ce5023b1f06", 124347),
    DictSource("ht_Names.txt", _HT + "Names.txt",
               "d043dc59299b70c742632ca3cd0d7528070de69fb50d3e4b08172b604da732e0", 2336576),
    DictSource("td_Names.txt", _TD + "Names.txt",
               "2bf27448d6f235cc760cb27d8f24f74e7bd1bad0382bfed44bbacbf652ca7b54", 4068953),
    DictSource("td_Names2.txt", _TD + "Names2.txt",
               "f7edb474cd981c866470139dd23edbfb4f694ef1f7712be4126d778ac70059cc", 4249506),
    DictSource("ht_VietPhrase.txt", _HT + "Vietphrase.txt",
               "1c4b7c123ad7b70ae994ccbc6d5068fcbc46d098a9a7cfa2d11b4171fea53b9f", 42533593),
]
CHAR_FILES = ("ht_HanViet.txt", "td_PhienAm.txt")
NAME_FILES = ("ht_Names.txt", "td_Names.txt", "td_Names2.txt")
PHRASE_FILE = "ht_VietPhrase.txt"
INDEX_NAME = "title_dict.sqlite"
_CHUNK = 1 << 20


def dict_dir() -> str:
    configured = (os.getenv("TITLE_DICT_DIR") or "").strip()
    if configured:
        return os.path.abspath(configured)
    return os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "data", "dict")


def index_path(directory: Optional[str] = None) -> str:
    return os.path.join(directory or dict_dir(), INDEX_NAME)


def sha256_file(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(_CHUNK), b""):
            h.update(block)
    return h.hexdigest()


def _verified_marker(path: str) -> str:
    return path + ".verified"


def is_verified(src: DictSource, directory: str) -> bool:
    """Đúng kích thước + sha256. Lần băm đầu ghi dấu (size:mtime) để khởi động sau khỏi băm lại 42 MB."""
    path = os.path.join(directory, src.name)
    try:
        st = os.stat(path)
    except OSError:
        return False
    if st.st_size != src.size:
        return False
    stamp = f"{src.sha256}:{st.st_size}:{int(st.st_mtime)}"
    marker = _verified_marker(path)
    try:
        with open(marker, encoding="utf-8") as f:
            if f.read().strip() == stamp:
                return True
    except OSError:
        pass
    if sha256_file(path) != src.sha256:
        return False
    try:
        with open(marker, "w", encoding="utf-8") as f:
            f.write(stamp)
    except OSError:
        pass
    return True


def _download(src: DictSource, directory: str, timeout: float) -> None:
    dest = os.path.join(directory, src.name)
    part = dest + ".part"
    h = hashlib.sha256()
    req = urllib.request.Request(src.url, headers={"User-Agent": "TruyenCloud/1.0"})
    with urllib.request.urlopen(req, timeout=timeout) as resp, open(part, "wb") as out:
        for block in iter(lambda: resp.read(_CHUNK), b""):
            h.update(block)
            out.write(block)
    size = os.path.getsize(part)
    if size != src.size or h.hexdigest() != src.sha256:
        os.remove(part)
        raise ValueError(f"{src.name}: sai kích thước/sha256 ({size} B)")
    os.replace(part, dest)


@contextlib.contextmanager
def dir_lock(directory: str) -> Iterator[None]:
    """Khoá độc quyền giữa các tiến trình (máy chủ đang chạy và lệnh tải tay) khi tải/dựng trong cùng thư mục."""
    os.makedirs(directory, exist_ok=True)
    with open(os.path.join(directory, ".lock"), "w") as handle:
        try:
            import fcntl
            fcntl.flock(handle, fcntl.LOCK_EX)
        except (ImportError, OSError):
            pass
        yield


def missing_sources(directory: Optional[str] = None) -> List[DictSource]:
    directory = directory or dict_dir()
    return [s for s in SOURCES if not is_verified(s, directory)]


def ensure_sources(directory: Optional[str] = None, timeout: float = 120.0) -> Dict[str, object]:
    """Tải các tệp còn thiếu/sai. Ném lỗi nếu sau đó vẫn thiếu (người gọi tự nuốt lỗi: fail soft)."""
    directory = directory or dict_dir()
    fetched = []
    with dir_lock(directory):
        for src in missing_sources(directory):
            logger.info("Đang tải từ điển %s (%d KB)...", src.name, src.size // 1024)
            _download(src, directory, timeout)
            is_verified(src, directory)
            fetched.append(src.name)
        still = missing_sources(directory)
    if still:
        raise RuntimeError("Thiếu tệp từ điển: " + ", ".join(s.name for s in still))
    return {"downloaded": fetched, "dir": directory}


def sources_fingerprint() -> str:
    return hashlib.sha1("|".join(s.sha256 for s in SOURCES).encode()).hexdigest()[:16]
