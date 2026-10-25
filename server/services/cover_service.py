import re
import asyncio
import urllib.parse
import httpx
from services.http_shared import get_shared_client
from typing import Optional, Dict
from bs4 import BeautifulSoup

USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

# Dictionary of popular titles in Vietnamese / Latin -> Chinese
TITLE_MAPPING: Dict[str, str] = {
    # Kinh điển Tiên Hiệp & Tu Chân
    "pham nhan tu tien": "凡人修仙传",
    "phàm nhân tu tiên": "凡人修仙传",
    "van co de nhat than": "万古第一神",
    "vạn cổ đệ nhất thần": "万古第一神",
    "dai phung da canh nhan": "大奉打更人",
    "đại phụng đả canh nhân": "大奉打更人",
    "quy bi chi chu": "诡秘之主",
    "quỷ bí chi chủ": "诡秘之主",
    "kiem lai": "剑来",
    "kiếm lai": "剑来",
    "dau la dai luc": "斗罗大陆",
    "đấu la đại lục": "斗罗大陆",
    "dau pha thuong khung": "斗破苍穹",
    "đấu phá thương khung": "斗破苍穹",
    "tuyet trung han dao hanh": "雪中悍刀行",
    "tuyết trung hãn đao hành": "雪中悍刀行",
    "khanh du nien": "庆余年",
    "khánh dư niên": "庆余年",
    "tien nghich": "仙逆",
    "tiên nghịch": "仙逆",
    "hoan my the gioi": "完美世界",
    "hoàn mỹ thế giới": "完美世界",
    "gia thien": "遮天",
    "già thiên": "遮天",
    "thon phe tinh khong": "吞噬星空",
    "thôn phệ tinh không": "吞噬星空",
    "chuyen tinh boston": "波士顿爱情故事",
    "chuyện tình boston": "波士顿爱情故事",
    "toan chuc cao thu": "全职高手",
    "toàn chức cao thủ": "全职高手",
    "muc than ky": "牧神记",
    "mục thần ký": "牧神记",
    "vu dong can khon": "武动乾坤",
    "vũ động càn khôn": "武动乾坤",
    "nguyen ton": "元尊",
    "nguyên tôn": "元尊",
    "de ba": "帝霸",
    "đế bá": "帝霸",
    "than mo": "神墓",
    "thần mộ": "神墓",
    "nhat niem vinh hang": "一念永恒",
    "nhất niệm vĩnh hằng": "一念永恒",
    "cau ma": "求魔",
    "cầu ma": "求魔",
    "nga duc phong thien": "我欲封天",
    "ngã dục phong thiên": "我欲封天",
    "tinh than bien": "星辰变",
    "tinh thần biến": "星辰变",
    "yeu than ky": "妖神记",
    "yêu thần ký": "妖神记",
    "dai chua te": "大主宰",
    "đại chúa tể": "大主宰",
    "tu chan lieu thien quan": "修真聊天群",
    "tu chân liêu thiên quần": "修真聊天群",
    "chap ma": "执魔",
    "chấp ma": "执魔",
    "cuu tinh ba the quyet": "九星霸体诀",
    "cửu tinh bá thể quyết": "九星霸体诀",
    "van toc chi kiep": "万族之劫",
    "vạn tộc chi kiếp": "万族之劫",
    "toan cau cao vo": "全球高武",
    "toàn cầu cao võ": "全球高武",
    "tham khong bi ngan": "深空彼岸",
    "thâm không bỉ ngạn": "深空彼岸",
    "tuc menh chi hoan": "宿命之环",
    "túc mệnh chi hoàn": "宿命之环",
    "luan hoi nhac vien": "轮回乐园",
    "luân hồi nhạc viên": "轮回乐园",
    "ma dao to su": "魔道祖师",
    "ma đạo tổ sư": "魔道祖师",
    "thien quan tu phuc": "天官赐福",
    "thiên quan tứ phúc": "天官赐福",
    "na ma vien na ma can": "那么远那么近",
    "na ma viễn na ma cận": "那么远那么近",
    "xa tan chan troi": "那么远那么近",
    "xa tận chân trời": "那么远那么近",
    "noi nao do xa xoi": "那么远那么近",
    "nơi nào đó xa xôi": "那么远那么近",
    "mai mai la bao xa": "当爱情遇上科学家",
    "mãi mãi là bao xa": "当爱情遇上科学家",
}

# Reverse title dictionary (Chinese -> Vietnamese)
ZH_TO_VI_TITLES: Dict[str, str] = {v: k.title() for k, v in TITLE_MAPPING.items() if any(c in k for c in "áàảãạăắằẳẵặâấầẩẫậéèẻẽẹêếềểễệíìỉĩịóòỏõọôốồổỗộơớờởỡợúùủũụưứừửữựýỳỷỹỵđ")}

# Canonical HD covers for top masterpieces
KNOWN_COVERS: Dict[str, str] = {
    "万古第一神": "https://www.piaotia.com/files/article/image/10/10527/10527s.jpg",
    "凡人修仙传": "https://www.piaotia.com/files/article/image/0/760/760s.jpg",
    "诡秘之主": "https://www.piaotia.com/files/article/image/9/9459/9459s.jpg",
    "大奉打更人": "https://www.piaotia.com/files/article/image/11/11014/11014s.jpg",
    "剑来": "https://www.piaotia.com/files/article/image/8/8717/8717s.jpg",
    "雪中悍刀行": "https://www.piaotia.com/files/article/image/3/3155/3155s.jpg",
    "庆余年": "https://www.piaotia.com/files/article/image/0/212/212s.jpg",
    "斗罗大陆": "https://www.piaotia.com/files/article/image/0/49/49s.jpg",
    "斗破苍穹": "https://www.piaotia.com/files/article/image/0/144/144s.jpg",
    "完美世界": "https://www.piaotia.com/files/article/image/4/4134/4134s.jpg",
    "遮天": "https://www.piaotia.com/files/article/image/0/682/682s.jpg",
    "仙逆": "https://www.piaotia.com/files/article/image/0/566/566s.jpg",
    "全职高手": "https://www.piaotia.com/files/article/image/1/1234/1234s.jpg",
    "牧神记": "https://www.piaotia.com/files/article/image/7/7772/7772s.jpg",
    "修真聊天群": "https://www.piaotia.com/files/article/image/6/6211/6211s.jpg",
    "宿命之环": "https://www.piaotia.com/files/article/image/14/14299/14299s.jpg",
}

class CoverService:
    """Intelligent Cover Resolver ensuring 100% of novels across any website have valid covers."""

    def __init__(self):
        self._memory_cache: Dict[str, str] = dict(KNOWN_COVERS)

    def normalize_key(self, text: str) -> str:
        clean = re.sub(r"[《》\s\-_:：]", "", text).lower().strip()
        return clean

    def map_query_to_chinese(self, query: str) -> Optional[str]:
        q_norm = query.strip().lower()
        if q_norm in TITLE_MAPPING:
            return TITLE_MAPPING[q_norm]
        
        # Substring / partial match
        for k, v in TITLE_MAPPING.items():
            if k in q_norm or q_norm in k:
                return v
        return None

    def get_vietnamese_title(self, zh_title: str) -> str:
        clean_zh = re.sub(r"[《》最新章节列表全文阅读]", "", zh_title).strip()
        return ZH_TO_VI_TITLES.get(clean_zh, "")

    async def find_cover_by_title(self, title: str) -> str:
        """Finds or resolves a high-resolution cover URL for a novel title."""
        clean_title = re.sub(r"[《》最新章节列表全文阅读]", "", title).strip()
        if not clean_title or clean_title == "Truyện Web":
            return "/static/fallback_cover.jpg"

        # 1. Direct Memory / Known Masterpiece Cache
        if clean_title in self._memory_cache:
            return self._memory_cache[clean_title]

        # 2. Check title mapping (Vietnamese -> Chinese)
        zh_title = self.map_query_to_chinese(clean_title)
        if zh_title and zh_title in self._memory_cache:
            return self._memory_cache[zh_title]

        search_term = zh_title or clean_title

        # 3. Truy vấn 3 nguồn bìa SONG SONG, chọn kết quả theo thứ tự ưu tiên
        # (piaotia > uukanshu) — thay vì chờ tuần tự tối đa 24 giây.
        results = await asyncio.gather(
            self._query_piaotia_cover(search_term),
            self._query_uukanshu_cover(search_term),
            return_exceptions=True,
        )
        for cover in results:
            if isinstance(cover, str) and cover:
                self._memory_cache[clean_title] = cover
                return cover

        # 4. Fallback to TruyenCloud luxury book cover
        return "/static/fallback_cover.jpg"

    async def _query_piaotia_cover(self, title: str) -> Optional[str]:
        """Queries Piaotia for a novel title to extract its cover URL."""
        headers = {
            "User-Agent": USER_AGENT,
            "Referer": "https://www.piaotia.com/",
        }
        encoded = urllib.parse.quote(title.encode("gbk", errors="ignore"))
        url = f"https://www.piaotia.com/modules/article/search.php?searchtype=articlename&searchkey={encoded}"
        
        client = get_shared_client()
        resp = await client.get(url, headers=headers, timeout=8.0)
        final_url = str(resp.url)
            
        # Direct redirect to bookinfo/cat/bid.html
        m = re.search(r"bookinfo/(\d+)/(\d+)\.html", final_url)
        if m:
            cat, bid = m.groups()
            return f"https://www.piaotia.com/files/article/image/{cat}/{bid}/{bid}s.jpg"
            
        # Parse result table
        html = resp.content.decode("gbk", errors="replace")
        soup = BeautifulSoup(html, "html.parser")
        for a in soup.select('a[href*="bookinfo/"]'):
            m2 = re.search(r"bookinfo/(\d+)/(\d+)\.html", a.get("href", ""))
            if m2:
                cat, bid = m2.groups()
                return f"https://www.piaotia.com/files/article/image/{cat}/{bid}/{bid}s.jpg"
        return None

    async def _query_uukanshu_cover(self, title: str) -> Optional[str]:
        """Queries UUKanshu for a novel title to extract its cover URL."""
        try:
            from services.scraper_uukanshu import UuKanshuScraper
            s = UuKanshuScraper()
            res = await s.search(title)
            if res and res[0].cover_url:
                return res[0].cover_url
        except Exception:
            pass
        return None

cover_service = CoverService()
