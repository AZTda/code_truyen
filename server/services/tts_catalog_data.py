"""Dữ liệu giọng có sẵn của từng mô hình VieNeu-TTS, chép NGUYÊN VĂN từ `Vieneu(mode=...).list_preset_voices()`.

Mỗi dòng: (tên giọng đúng như gói nhận ở `voice=`, giới tính, miền, nhãn phong cách, thứ tự ⭐ hoặc None).
Nguồn: gói vieneu 3.8.3 — v3nano: assets/voices_v3_nano.json (nhãn "Mỹ Duyên — Nữ · Bắc · Phong cách đọc truyện");
v3turbo: assets/voices_v3_turbo.json (nhãn "⭐ Mai Anh — Nữ · Bắc · Phong cách tin tức"). CÙNG một tên ở hai mô hình
là hai bản ghi khác nhau (vd. Mỹ Duyên: Nano ghi miền Bắc, Turbo ghi miền Nam), nên danh mục tách theo mô hình.
Đổi phiên bản vieneu thì chạy lại test_tts_local_catalog (so với tệp assets của gói nếu có cài).
"""

MODE_NANO = "v3nano"
MODE_TURBO = "v3turbo"
MODES = (MODE_NANO, MODE_TURBO)

NANO_PRESETS = (
    ("Adam", "Nam", "Nam", "Giọng đọc tự nhiên", None),
    ("Ái Hân", "Nữ", "Nam", "Phong cách tin tức", None),
    ("Mỹ Duyên", "Nữ", "Bắc", "Phong cách đọc truyện", None),
    ("Đức Trí", "Nam", "Bắc", "Phong cách đọc truyện", None),
    ("Hữu Quân", "Nam", "Bắc", "Phong cách tin tức", None),
    ("Xuân Tiên", "Nữ", "Bắc", "Phong cách tin tức", None),
    ("Mai Anh", "Nữ", "Bắc", "Phong cách tin tức", None),
    ("Trúc Ly", "Nữ", "Bắc", "Phong cách tự nhiên", None),
    ("Anh Khôi", "Nam", "Bắc", "Phong cách kể chuyện", None),
    ("Minh Quân", "Nam", "Bắc", "Phong cách tự nhiên", None),
    ("Mạnh Dũng", "Nam", "Bắc", "Phong cách tự nhiên", None),
)

TURBO_PRESETS = (
    ("Adam bựa", "Nam", "Bắc", "Phong cách tự nhiên", 1),
    ("Trúc Ly", "Nữ", "Bắc", "Phong cách tự nhiên", 2),
    ("Thiện Minh", "Nam", "Bắc", "Phong cách kể chuyện", 3),
    ("Mai Anh", "Nữ", "Bắc", "Phong cách tin tức", 4),
    ("Hải Đăng", "Nam", "Bắc", "Phong cách tự nhiên", 5),
    ("Thùy Dung", "Nữ", "Nam", "Phong cách tin tức", 6),
    ("Thiền Tâm Đức", "Nam", "Bắc", "Phong cách kể chuyện", 7),
    ("Ngọc Huyền", "Nữ", "Bắc", "Giọng đọc tự nhiên", 8),
    ("Quang Sơn", "Nam", "Trung", "Phong cách tự nhiên", 9),
    ("Ngọc Trân", "Nữ", "Trung", "Phong cách tự nhiên", 10),
    ("Minh Đức", "Nam", "Bắc", "Phong cách tin tức", None),
    ("Phạm Tuyên", "Nam", "Bắc", "Phong cách tự nhiên", None),
    ("Thái Sơn", "Nam", "Nam", "Phong cách kể chuyện", None),
    ("Xuân Vĩnh", "Nam", "Bắc", "Phong cách tự nhiên", None),
    ("Thanh Bình", "Nam", "Bắc", "Phong cách kể chuyện", None),
    ("Ngọc Linh", "Nữ", "Bắc", "Phong cách kể chuyện", None),
    ("Đoan Trang", "Nữ", "Bắc", "Phong cách tự nhiên", None),
    ("Thục Đoan", "Nữ", "Nam", "Phong cách kể chuyện", None),
    ("Minh Triết", "Nam", "Nam", "Phong cách tin tức", None),
    ("Mỹ Duyên", "Nữ", "Nam", "Phong cách đọc truyện", None),
    ("Quỳnh Anh", "Nữ", "Bắc", "Phong cách đọc truyện", None),
    ("Đức Trí", "Nam", "Nam", "Phong cách đọc truyện", None),
    ("Kim Thanh", "Nữ", "Nam", "Phong cách đọc truyện", None),
    ("Adam", "Nam", "Nam", "Giọng đọc tự nhiên", None),
    ("Quốc Tuấn", "Nam", "Bắc", "Phong cách tự nhiên", None),
)

PRESETS = {MODE_NANO: NANO_PRESETS, MODE_TURBO: TURBO_PRESETS}

# (id giọng nữ mặc định, id giọng nam mặc định). Nano: hai giọng "đọc truyện" theo quyết định của chủ app.
# Turbo: hai giọng chủ app đã nghe và thích.
DEFAULTS = {MODE_NANO: ("my_duyen", "duc_tri"), MODE_TURBO: ("mai_anh", "thien_minh")}

# Thông số đo thật, dùng để ước lượng trước khi máy chủ tự đo được (giây âm thanh / ký tự, RTF giả định).
# Nano: MiniPC 3 nhân RTF 0,58-0,91 (trung bình ~0,66) -> giả định 0,8 cho an toàn; ~0,09 giây/ký tự tiếng Việt.
# Turbo: MiniPC RTF ~1,25 -> 1,3; ~0,065 giây/ký tự.
PRIOR_SECONDS_PER_CHAR = {MODE_NANO: 0.09, MODE_TURBO: 0.065}
ASSUMED_RTF = {MODE_NANO: 0.8, MODE_TURBO: 1.3}
SAMPLE_RATE = {MODE_NANO: 24_000, MODE_TURBO: 48_000}
