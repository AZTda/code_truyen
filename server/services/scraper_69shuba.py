import re
import logging
import urllib.parse
from typing import List, Optional, Tuple
import httpx
from services.http_shared import get_shared_client
from bs4 import BeautifulSoup
from models.novel import Novel, NovelDetail, ChapterSummary
from services.scraper_intro import clean_intro

logger = logging.getLogger("truyencloud.scraper.69shuba")

USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4.1 Safari/605.1.15"

class Shuba69Scraper:
    """Scraper for 69shuba.com and its mirrors, routing through Google Translate proxy to bypass Cloudflare."""
    SOURCE_ID = "69shuba"
    NAME = "69Shuba"

    def supports(self, url: str) -> bool:
        return "69shu" in url.lower() or "69shuba" in url.lower() or "twkan" in url.lower()

    def get_proxy_url(self, url: str) -> str:
        clean = url.strip()
        if "69shuba.com" in clean.lower() and "translate.goog" not in clean.lower():
            temp = re.sub(r"www\.69shuba\.com", "69shuba.com", clean, flags=re.IGNORECASE)
            temp = re.sub(r"69shuba\.com", "69shuba-com.translate.goog", temp, flags=re.IGNORECASE)
            sep = "&" if "?" in temp else "?"
            return f"{temp}{sep}_x_tr_sl=auto&_x_tr_tl=zh-CN"
        return clean

    def clean_proxy_url(self, url: str) -> str:
        clean = url.strip()
        if "translate.google.com/website" in clean.lower() and "u=" in clean:
            try:
                u_param = clean.split("u=")[1].split("&")[0]
                clean = urllib.parse.unquote(u_param)
            except Exception:
                pass

        if "69shuba-com.translate.goog" in clean.lower():
            clean = re.sub(r"69shuba-com\.translate\.goog", "69shuba.com", clean, flags=re.IGNORECASE)

        clean = re.sub(r"[?&]_x_tr_.*", "", clean)
        return clean

    async def fetch_page_bytes(self, url: str) -> bytes:
        headers = {
            "User-Agent": USER_AGENT,
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language": "en-US,en;q=0.9",
        }
        client = get_shared_client()
        resp = await client.get(url, headers=headers, timeout=25.0)
        resp.raise_for_status()
        return resp.content

    async def fetch_novel_info(self, url: str) -> NovelDetail:
        # Extract book ID
        book_id_match = re.search(r"/(?:book|txt)/(\d+)", url) or re.search(r"/(\d+)", url)
        b_id = book_id_match.group(1) if book_id_match else "0"

        info_url = f"https://www.69shuba.com/book/{b_id}.htm"
        catalog_url = f"https://www.69shuba.com/book/{b_id}/"

        # 1. Fetch book info (.htm)
        info_bytes = await self.fetch_page_bytes(self.get_proxy_url(info_url))
        info_soup = BeautifulSoup(info_bytes.decode("gbk", errors="replace"), "html.parser")

        title = ""
        for selector in [".muluh1 a", ".booknav2 h1 a", ".booknav2 h1", "h1"]:
            el = info_soup.select_one(selector)
            if el and el.get_text(strip=True):
                title = el.get_text(strip=True)
                break
        title = title.replace("最新章节", "").strip() or f"69Shuba #{b_id}"

        author = "69书吧"
        author_el = info_soup.select_one(".booknav2 a[href*=author]")
        if author_el:
            author = author_el.get_text(strip=True)
        else:
            for p in info_soup.select(".booknav2 p"):
                if "作者" in p.get_text():
                    author = p.get_text().replace("作者：", "").replace("作者:", "").strip()
                    break

        prefix = str(int(b_id) // 1000) if b_id.isdigit() else "0"
        cover_img = info_soup.select_one('.bookimg2 img, .bookcover img, .booknav2 img, img[src*="/files/article/image"]')
        cover_url = ""
        if cover_img and (cover_img.get("src") or cover_img.get("data-src")):
            cover_url = (cover_img.get("src") or cover_img.get("data-src")).strip()
            if cover_url.startswith("//"):
                cover_url = "https:" + cover_url
        if not cover_url:
            cover_url = f"https://cdn.cdnshu.com/files/article/image/{prefix}/{b_id}/{b_id}s.jpg"

        # 2. Giới thiệu nằm trong khối .navtxt của trang thông tin (meta description chỉ là câu SEO chung)
        desc = ""
        for el in info_soup.select(".navtxt p, .booknav2 .navtxt p, .jianjie, .intro"):
            desc = clean_intro(el.get_text("\n"))
            if desc:
                break

        # 3. Fetch chapter list (/book/{id}/)
        chapters: List[ChapterSummary] = []
        try:
            cat_bytes = await self.fetch_page_bytes(self.get_proxy_url(catalog_url))
            cat_soup = BeautifulSoup(cat_bytes.decode("gbk", errors="replace"), "html.parser")
            chapter_links = cat_soup.select(".catalog ul li a")
            if not chapter_links:
                chapter_links = cat_soup.select('a[href*="/txt/"]')

            raw_items = []
            for el in chapter_links:
                raw_href = el.get("href", "").strip()
                if not raw_href:
                    continue
                ch_url = self.clean_proxy_url(urllib.parse.urljoin(catalog_url, raw_href))
                ch_title = el.get_text(strip=True)
                if not ch_title or not ch_url.startswith("http") or "/txt/" not in ch_url:
                    continue
                raw_items.append({"title": ch_title, "url": ch_url})

            from services.toc_normalizer import normalize_chapters
            chapters = normalize_chapters(raw_items, base_url=catalog_url)
        except Exception as e:
            logger.warning(f"Lỗi khi cào mục lục 69shuba: {e}")

        clean_original_url = self.clean_proxy_url(url)
        return NovelDetail(
            id=b_id or clean_original_url,
            title=title,
            author=author,
            source_url=clean_original_url,
            source_id=self.SOURCE_ID,
            cover_url=cover_url,
            description=desc,
            total_chapters=len(chapters),
            chapters=chapters
        )

    async def fetch_chapter_content(self, chapter_url: str) -> str:
        mapped_url = self.get_proxy_url(chapter_url)
        body_bytes = await self.fetch_page_bytes(mapped_url)
        html = body_bytes.decode("gbk", errors="replace")
        soup = BeautifulSoup(html, "html.parser")

        content_el = soup.select_one(".txtnav") or soup.select_one("#txtcontent") or soup.select_one(".txtcontent")
        if not content_el:
            raise ValueError(f"Could not find chapter content for {chapter_url}")

        # Remove ads, scripts, headings
        for tag in content_el.select("h1, div.txtnav_span, script, a"):
            tag.decompose()

        raw_text = content_el.get_text(separator="\n")
        lines = []
        for line in raw_text.splitlines():
            line_str = line.strip()
            if not line_str:
                continue
            if any(bad in line_str for bad in ["69shu", "六九书吧", "txtnav_span", "wuxia", "chuyển ngữ"]):
                continue
            lines.append(line_str)

        return "\n\n".join(lines)

    async def search(self, query: str) -> List[Novel]:
        clean_query = query.strip()
        if not clean_query:
            return []

        # Check if direct book ID or URL
        if clean_query.isdigit():
            book_url = f"https://www.69shuba.com/book/{clean_query}.htm"
            try:
                detail = await self.fetch_novel_info(book_url)
                return [Novel(
                    id=detail.id,
                    title=detail.title,
                    author=detail.author,
                    url=book_url,
                    cover_url=detail.cover_url,
                    source_id=self.SOURCE_ID,
                    total_chapters=detail.total_chapters
                )]
            except Exception:
                return [Novel(
                    id=clean_query,
                    title=f"Truyện #{clean_query}",
                    url=book_url,
                    source_id=self.SOURCE_ID
                )]

        # Search Yahoo first (fast, no bot challenge), fallback to DuckDuckGo via Translate proxy
        results = []
        seen = set()
        ddg_query = urllib.parse.quote(f"site:69shuba.com {clean_query}")
        search_urls = (
            f"https://search.yahoo.com/search?p={ddg_query}",
            f"https://html-duckduckgo-com.translate.goog/html/?q={ddg_query}&_x_tr_sl=auto&_x_tr_tl=vi&_x_tr_hl=vi",
        )
        client = get_shared_client()
        for proxy_url in search_urls:
            try:
                resp = await client.get(proxy_url, headers={"User-Agent": USER_AGENT}, timeout=4.0)
                if resp.status_code != 200:
                    continue
                soup = BeautifulSoup(resp.text, "html.parser")
                for link in soup.select("a[href]"):
                    href = urllib.parse.unquote(link.get("href", ""))
                    match = re.search(r"/book/(\d+)", href)
                    if not match:
                        continue
                    book_id = match.group(1)
                    b_url = f"https://www.69shuba.com/book/{book_id}.htm"
                    if b_url in seen:
                        continue
                    parts = [p.strip() for p in link.get_text("|", strip=True).split("|") if p.strip()]
                    raw_t = parts[-1] if parts else ""
                    if not raw_t or "69shuba.com" in raw_t.lower():
                        continue
                    seen.add(b_url)
                    title = raw_t.split("-")[0].replace("69书吧", "").strip()
                    for noise in [
                        "最新章节列表", "最新章节阅读", "最新章节", "无弹窗广告",
                        "无弹窗", "全文阅读", "txt全集下载", "全集txt下载", "txt下载",
                    ]:
                        title = title.replace(noise, "")
                    if "," in title:
                        title = title.split(",")[0].strip()
                    title = title.strip(" _-–—.")
                    results.append(Novel(
                        id=book_id,
                        title=title or f"Truyện #{book_id}",
                        url=b_url,
                        cover_url=f"https://cdn.cdnshu.com/files/article/image/{int(book_id)//1000}/{book_id}/{book_id}s.jpg",
                        source_id=self.SOURCE_ID
                    ))
                if results:
                    break
            except Exception:
                continue

        return results[:10]
