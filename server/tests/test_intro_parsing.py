"""Giới thiệu truyện phải lấy đúng khối của từng trang nguồn, không lấy câu SEO chung trong thẻ meta.

Không gọi mạng: mọi trang HTML là bản giả lập tối thiểu do test tự đặt.
"""

import asyncio

from services import scraper_uukanshu
from services.scraper_intro import clean_intro
from services.scraper_69shuba import Shuba69Scraper
from services.scraper_piaotia import PiaotiaScraper
from services.scraper_uukanshu import UuKanshuScraper

SEO_META = '<meta name="description" content="提供某书最新章节免费阅读，请将本书加入收藏。">'
INTRO_PIAOTIA = "这是飘天测试书的简介，用来验证解析结果是否正确。"
INTRO_69 = "这是六九书吧测试书的简介，用来验证解析结果是否正确。"
INTRO_UU = "这是UU测试书的简介，用来验证解析结果是否正确。"

PIAOTIA_INDEX = f"""<html><head>{SEO_META}</head><body>
<h1>测试书</h1><div>作者：测试作者</div>
<li><a href="1.html">第1章 开始</a></li>
<li><a href="2.html">第2章 继续</a></li>
<li><a href="3.html">第3章 结束</a></li>
</body></html>"""

PIAOTIA_BOOKINFO = f"""<html><head>{SEO_META}</head><body><table><tr><td>
<span class="hottext">内容简介：</span><br />{INTRO_PIAOTIA}<br /></td></tr></table></body></html>"""

SHUBA_INFO = f"""<html><head>{SEO_META}</head><body>
<div class="booknav2"><h1><a>测试书</a></h1><p>作者：测试作者</p>
<div class="navtxt"><p>{INTRO_69}<br>第二行说明。<br></p><p>注</p></div>
</div></body></html>"""

UUKANSHU_PAGE = f"""<html><head>{SEO_META}</head><body>
<div class="book-info"><h1>测试书</h1><p>作者：测试作者</p></div>
<p class="bookintro"><img alt="测试书" src="x.jpg">{INTRO_UU}</p>
</body></html>"""


class _Resp:
    def __init__(self, url, text):
        self.url = url
        self.text = text


class _Client:
    def __init__(self, resp):
        self._resp = resp

    async def get(self, url, **kwargs):
        return self._resp


def _run(coro):
    return asyncio.run(coro)


def test_piaotia_intro_comes_from_bookinfo_not_meta(monkeypatch):
    pages = {
        "https://www.piaotia.com/html/15/15976/index.html": PIAOTIA_INDEX,
        "https://www.piaotia.com/bookinfo/15/15976.html": PIAOTIA_BOOKINFO,
    }

    async def fake_fetch(self, url):
        return pages[url]

    monkeypatch.setattr(PiaotiaScraper, "fetch_page", fake_fetch)
    detail = _run(PiaotiaScraper().fetch_novel_info("https://www.piaotia.com/html/15/15976/index.html"))
    assert detail.description == INTRO_PIAOTIA
    assert len(detail.chapters) == 3
    assert detail.author == "测试作者"


def test_piaotia_missing_bookinfo_gives_empty_intro_not_meta(monkeypatch):
    async def fake_fetch(self, url):
        if "bookinfo" in url:
            raise RuntimeError("bookinfo không tải được")
        return PIAOTIA_INDEX

    monkeypatch.setattr(PiaotiaScraper, "fetch_page", fake_fetch)
    detail = _run(PiaotiaScraper().fetch_novel_info("https://www.piaotia.com/html/15/15976/index.html"))
    assert detail.description == ""
    assert len(detail.chapters) == 3


def test_69shuba_intro_comes_from_navtxt_not_meta(monkeypatch):
    async def fake_bytes(self, url):
        if "/book/1384.htm" in url:
            return SHUBA_INFO.encode("gbk")
        raise RuntimeError("test này không có mục lục")

    monkeypatch.setattr(Shuba69Scraper, "fetch_page_bytes", fake_bytes)
    detail = _run(Shuba69Scraper().fetch_novel_info("https://www.69shuba.com/book/1384.htm"))
    assert detail.description == f"{INTRO_69}\n第二行说明。"
    assert "最新章节" not in detail.description


def test_uukanshu_intro_comes_from_bookintro_not_meta(monkeypatch):
    async def fake_fetch(self, url):
        return UUKANSHU_PAGE

    monkeypatch.setattr(UuKanshuScraper, "fetch_page", fake_fetch)
    detail = _run(UuKanshuScraper().fetch_novel_info("https://uukanshu.cc/book/12528/"))
    assert detail.description == INTRO_UU


def test_uukanshu_search_single_match_leaves_author_empty(monkeypatch):
    page = "<html><body><h1>测试书</h1></body></html>"  # không có khối tác giả
    monkeypatch.setattr(scraper_uukanshu, "get_shared_client",
                        lambda: _Client(_Resp("https://uukanshu.cc/book/12528/", page)))
    results = _run(UuKanshuScraper().search("测试书"))
    assert len(results) == 1
    assert results[0].title == "测试书"
    assert results[0].author == ""


def test_uukanshu_search_list_never_uses_site_name_as_author(monkeypatch):
    page = '<html><body><div><a href="/book/111/">测试书一</a></div></body></html>'
    monkeypatch.setattr(scraper_uukanshu, "get_shared_client",
                        lambda: _Client(_Resp("https://uukanshu.cc/search/x_1.html", page)))
    results = _run(UuKanshuScraper().search("测试书"))
    assert results
    assert all(n.author != "UU看书" for n in results)


def test_clean_intro_rejects_seo_text_and_short_text():
    assert clean_intro("这是一段很长的SEO文字，提供免费阅读和最新章节") == ""
    assert clean_intro("太短") == ""
    assert clean_intro("简介：" + INTRO_UU) == INTRO_UU
