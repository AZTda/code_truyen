"""Hy-MT2: kiểm từng mục đầu ra (lọt dòng, chữ Hán, lời giải thích, sai số lượng) và client không bao giờ ném lỗi."""

import json

import httpx
import pytest

from services import title_mt_validate as V
from services.title_mt_client import TitleMTClient
from services.title_pinyin import is_pinyin_word, pinyin_leak


def test_clean_vi_loai_chu_han_giai_thich_qua_dai():
    assert V.clean_vi("Lâm Động xuất quan.", "林动出关") == "Lâm Động xuất quan"
    assert V.clean_vi("“Lời khuyên của Quốc Sư”", "国师的建议") == "Lời khuyên của Quốc Sư"
    assert V.clean_vi("1. Lời khuyên", "国师的建议") == "Lời khuyên"
    assert V.clean_vi("Lâm 动 xuất quan", "林动出关") is None
    assert V.clean_vi("Bản dịch: Lâm Động xuất quan", "林动出关") is None
    assert V.clean_vi("Lâm Động\nGiải thích thêm", "林动出关") is None
    assert V.clean_vi(" ".join(["chữ"] * 30), "林动出关") is None
    assert V.clean_vi("林动出关", "林动出关") is None
    assert V.clean_vi("", "林动出关") is None


def test_parse_numbered_bo_so_lap_va_ngoai_khoang():
    raw = "1. một\n2. hai\n2. hai lần\n7. bảy\nrác\n3) ba"
    assert V.parse_numbered(raw, 3) == {1: "một", 3: "ba"}


def test_validate_batch_lot_goi_y_va_dong_tron():
    sources = ["林动出关", "国师的建议", "圣兽战魂"]
    hints = [("林动", "Lâm Động"), ("国师", "Quốc Sư")]
    raw = "1. Lâm Động xuất quan\n2. Lời khuyên của Quốc Sư Lâm Động\n3. Thánh thú chiến hồn"
    assert V.validate_batch(raw, sources, hints) == ["Lâm Động xuất quan", None, "Thánh thú chiến hồn"]
    # Thiếu dòng 3: chỉ mục đó phải dịch lại
    assert V.validate_batch("1. Lâm Động xuất quan\n2. Lời khuyên của Quốc Sư", sources, hints)[2] is None
    # Hai nguồn khác nhau mà chung một đoạn dài = lấy nhầm dòng của nhau
    raw = ("1. Thục vương vào Lương đạo sĩ vào núi Lương vương ra khỏi núi\n"
           "2. Đạo sĩ vào núi Lương vương ra khỏi núi\n3. Thánh thú chiến hồn")
    out = V.validate_batch(raw, ["蜀王入凉道士进山凉王出山", "下山挑山", "圣兽战魂"], [])
    assert out[0] is None and out[1] is None and out[2] == "Thánh thú chiến hồn"
    # Nguồn chung đoạn dài (phần thượng/hạ) thì bản dịch giống nhau là bình thường
    out = V.validate_batch("1. Lẻn vào Langley phần trên\n2. Lẻn vào Langley phần dưới", ["潜入兰利上", "潜入兰利下"], [])
    assert out == ["Lẻn vào Langley phần trên", "Lẻn vào Langley phần dưới"]


def test_pinyin_ten_rieng_bi_loai():
    assert is_pinyin_word("Qixian") and is_pinyin_word("yao") and not is_pinyin_word("Langley")
    vn = {"xu", "ha", "lam", "dong"}.__contains__
    assert pinyin_leak("Xu Qixian: tôi còn cơ hội", "许七安：我还有机会", vn)
    assert not pinyin_leak("Lâm Động xuất quan", "林动出关", vn)
    assert not pinyin_leak("Thâm nhập Langley", "潜入兰利", vn)
    assert not pinyin_leak("Đặc vụ CIA Qixian", "被抓现行的CIA特工", vn)  # nguồn có chữ Latin: bỏ qua
    assert pinyin_leak("Nǎi Nǎi của tôi tu tiên trở về", "我姑奶奶她修仙回来了", None)  # dấu thanh pinyin
    assert not pinyin_leak("Bà cô tổ của tôi trở về rồi", "我姑奶奶她修仙回来了", None)
    assert V.clean_vi("Xu Qixian: Tôi vẫn còn cơ hội", "许七安：我还有抢救的机会", vn) is None


def test_clean_zh_query():
    assert V.clean_zh_query("波士顿爱情故事") == "波士顿爱情故事"
    assert V.clean_zh_query("《难哄》。") == "难哄"
    assert V.clean_zh_query("Boston love story") is None
    assert V.clean_zh_query("抱歉，我不知道") is None
    assert V.clean_zh_query("") is None


def test_book_case():
    assert V.book_case("tôi là hiệu trưởng") == "Tôi Là Hiệu Trưởng"


class _Server:
    """llama-server giả qua httpx.MockTransport: trả lời theo hàm `reply(body)`."""

    def __init__(self, reply, status=200, healthy=True):
        self.reply, self.status, self.healthy, self.bodies = reply, status, healthy, []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        if request.url.path == "/health":
            return httpx.Response(200 if self.healthy else 503, json={"status": "ok"})
        body = json.loads(request.content)
        self.bodies.append(body)
        if self.status != 200:
            return httpx.Response(self.status, json={"error": "x"})
        return httpx.Response(200, json={"choices": [{"message": {"content": self.reply(body)}}]})


@pytest.fixture
def mt(monkeypatch):
    monkeypatch.setenv("TITLE_MT_ENABLED", "1")
    monkeypatch.setenv("TITLE_MT_URL", "http://mt.test")
    client = TitleMTClient()

    def use(server):
        client._client = httpx.AsyncClient(transport=httpx.MockTransport(server))
        return server
    client.use = use
    return client


def _prompt(body):
    return body["messages"][0]["content"]


@pytest.mark.asyncio
async def test_client_prompt_va_tham_so(mt):
    srv = mt.use(_Server(lambda b: "Lâm Động xuất quan"))
    assert await mt.zh_to_vi("林动出关", [("林动", "Lâm Động")]) == "Lâm Động xuất quan"
    body = srv.bodies[0]
    assert _prompt(body) == ("参考下面的翻译：\n林动 翻译成 Lâm Động\n\n将以下文本翻译为越南语，注意只需要输出翻译后的结果，"
                             "不要额外解释：\n\n林动出关")
    assert body["temperature"] == 0 and body["repeat_penalty"] == 1.05


@pytest.mark.asyncio
async def test_client_lo_hong_mot_muc_thi_dich_le(mt):
    def reply(body):
        p = _prompt(body)
        if "逐行" in p:
            return "1. Lâm Động xuất quan\n2. 国师 đề nghị"
        return "Lời khuyên của Quốc Sư"
    srv = mt.use(_Server(reply))
    out = await mt.zh_to_vi_batch(["林动出关", "国师的建议"], [], [[], [("国师", "Quốc Sư")]])
    assert out == ["Lâm Động xuất quan", "Lời khuyên của Quốc Sư"]
    assert len(srv.bodies) == 2 and "国师 翻译成 Quốc Sư" in _prompt(srv.bodies[1])


@pytest.mark.asyncio
async def test_client_vi_to_zh(mt):
    mt.use(_Server(lambda b: "波士顿爱情故事"))
    assert await mt.vi_to_zh("chuyện tình boston") == "波士顿爱情故事"


@pytest.mark.asyncio
async def test_client_khong_bao_gio_nem_loi(mt, monkeypatch):
    mt.use(_Server(lambda b: "x", status=500))
    assert await mt.zh_to_vi("林动出关") is None
    assert await mt.healthy(max_age=0) is True  # /health vẫn 200

    def boom(request):
        raise httpx.ConnectError("refused")
    mt._client = httpx.AsyncClient(transport=httpx.MockTransport(boom))
    assert await mt.zh_to_vi("林动出关") is None
    assert await mt.vi_to_zh("kiem lai") is None
    assert await mt.zh_to_vi_batch(["a", "b"]) == [None, None]
    assert await mt.healthy(max_age=0) is False


@pytest.mark.asyncio
async def test_client_tat_thi_khong_goi(monkeypatch):
    monkeypatch.setenv("TITLE_MT_ENABLED", "0")
    client = TitleMTClient()
    assert await client.healthy() is False
    assert await client.zh_to_vi("林动出关") is None and client.stats["requests"] == 0


@pytest.mark.asyncio
async def test_client_may_chu_that_khong_chay(monkeypatch):
    """Cổng loopback không ai nghe: lỗi kết nối được nuốt, trả None nhanh."""
    import socket
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    monkeypatch.setenv("TITLE_MT_ENABLED", "1")
    monkeypatch.setenv("TITLE_MT_URL", f"http://127.0.0.1:{port}")
    client = TitleMTClient()
    assert await client.healthy(max_age=0) is False
    assert await client.zh_to_vi("林动出关", timeout=1.0) is None
    await client.aclose()
