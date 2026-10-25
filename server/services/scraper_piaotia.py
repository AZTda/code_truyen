import re
import urllib.parse
from typing import List
import httpx
from services.http_shared import get_shared_client
from bs4 import BeautifulSoup
from models.novel import Novel, NovelDetail, ChapterSummary
from services.scraper_intro import clean_intro

USER_AGENT = "Mozilla/5.0 (Linux; Android 15; Pixel 9a) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

class PiaotiaScraper:
    """Scraper for piaotia.com handling GBK/GB2312 encoding."""
    SOURCE_ID = "piaotia"
    NAME = "Piaotia"

    def supports(self, url: str) -> bool:
        clean = url.lower()
        return "piaotia.com" in clean or "piaotian.com" in clean or "ptwxz.com" in clean

    def normalize_book_url(self, url: str) -> str:
        clean = url.strip()
        match = re.search(r"/(?:html|bookinfo)/(\d+)/(\d+)", clean, re.IGNORECASE)
        if not match:
            raise ValueError(f"Invalid Piaotia book URL: {url}")
        cat, book_id = match.groups()
        return f"https://www.piaotia.com/html/{cat}/{book_id}/index.html"

    async def fetch_page(self, url: str) -> str:
        headers = {"User-Agent": USER_AGENT}
        client = get_shared_client()
        alt_url = (
            url.replace("www.piaotia.com", "www.ptwxz.com")
            if "www.piaotia.com" in url
            else url.replace("www.ptwxz.com", "www.piaotia.com")
        )
        for target in (url, alt_url):
            try:
                resp = await client.get(target, headers=headers, timeout=20.0)
                if resp.status_code == 200 and len(resp.content) > 500:
                    return resp.content.decode("gbk", errors="replace")
            except Exception:
                pass
        resp = await client.get(alt_url, headers=headers, timeout=25.0)
        resp.raise_for_status()
        return resp.content.decode("gbk", errors="replace")

    async def fetch_novel_info(self, url: str) -> NovelDetail:
        normalized_url = self.normalize_book_url(url)
        html = await self.fetch_page(normalized_url)

        # Title
        title_match = re.search(r"<h1>(.*?)</h1>", html, re.IGNORECASE)
        raw_title = title_match.group(1) if title_match else "Unknown Book"
        title = raw_title.replace("最新章节", "").strip()

        # Author
        author_match = re.search(r"作者[：:]\s*([^\s&<]+)", html)
        author = author_match.group(1).strip() if author_match else "Unknown"

        # Chapters
        base_url = normalized_url.rsplit("/", 1)[0] + "/"
        chapter_matches = re.findall(r'<a[^>]+href=["\'](\d+\.html)["\'][^>]*>(.*?)</a>', html, re.I)
        if not chapter_matches:
            chapter_matches = re.findall(r'<li><a href="(\d+\.html)">(.*?)</a></li>', html)

        raw_items = []
        for filename, ch_title in chapter_matches:
            ch_url = urllib.parse.urljoin(base_url, filename)
            raw_items.append({"title": ch_title.strip(), "url": ch_url, "id": filename.replace(".html", "")})

        from services.toc_normalizer import normalize_chapters
        chapters = normalize_chapters(raw_items, base_url=base_url)

        book_id_match = re.search(r"/(\d+)/(\d+)/index\.html", normalized_url)
        cat = book_id_match.group(1) if book_id_match else "0"
        b_id = book_id_match.group(2) if book_id_match else "0"
        book_id = f"{cat}_{b_id}"
        cover_url = f"https://www.piaotia.com/files/article/image/{cat}/{b_id}/{b_id}s.jpg"

        # Giới thiệu nằm ở trang bookinfo (khối "内容简介"); trang mục lục chỉ có mô tả SEO chung
        desc = await self._fetch_description(cat, b_id)

        return NovelDetail(
            id=book_id,
            title=title,
            author=author,
            source_url=normalized_url,
            source_id=self.SOURCE_ID,
            cover_url=cover_url,
            description=desc,
            total_chapters=len(chapters),
            chapters=chapters
        )

    async def _fetch_description(self, cat: str, b_id: str) -> str:
        """Khối "内容简介" nằm ở trang bookinfo; lỗi mạng thì trả rỗng, không làm hỏng cả trang truyện."""
        try:
            info_html = await self.fetch_page(f"https://www.piaotia.com/bookinfo/{cat}/{b_id}.html")
        except Exception:
            return ""
        match = re.search(r"内容简介[：:](.*?)</td>", info_html, re.DOTALL)
        if not match:
            return ""
        text = re.sub(r"<br\s*/?>", "\n", match.group(1), flags=re.IGNORECASE)
        return clean_intro(re.sub(r"<[^>]+>", "", text).replace("&nbsp;", " "))

    async def fetch_chapter_content(self, chapter_url: str) -> str:
        html = await self.fetch_page(chapter_url)
        content_match = re.search(r"<br>\s*\n(.*?)</div>", html, re.DOTALL)
        raw_content = content_match.group(1) if content_match else ""

        # Clean tags
        clean_text = re.sub(r"<br\s*/?>", "\n", raw_content, flags=re.IGNORECASE)
        clean_text = re.sub(r"<[^>]+>", "", clean_text)
        clean_text = clean_text.replace("&nbsp;", " ").replace("\r", "")

        lines = [line.strip() for line in clean_text.splitlines() if line.strip()]
        return "\n\n".join(lines)

    async def search(self, query: str) -> List[Novel]:
        clean_query = query.strip()
        if not clean_query:
            return []

        try:
            encoded = urllib.parse.quote(clean_query.encode("gbk"))
            headers = {"User-Agent": USER_AGENT}
            client = get_shared_client()
            for host in ("www.ptwxz.com", "www.piaotia.com"):
                search_url = f"https://{host}/modules/article/search.php?searchtype=articlename&searchkey={encoded}"
                try:
                    resp = await client.get(search_url, headers=headers, timeout=4.0)
                except Exception:
                    continue
                if resp.status_code != 200:
                    continue
                final_url = str(resp.url)

                # Case 1: Direct single match redirect to bookinfo or html
                if "/bookinfo/" in final_url or "/html/" in final_url:
                    m = re.search(r"/(?:bookinfo|html)/(\d+)/(\d+)", final_url)
                    if m:
                        cat, bid = m.groups()
                        canon_url = f"https://www.piaotia.com/html/{cat}/{bid}/index.html"
                        cover = f"https://www.piaotia.com/files/article/image/{cat}/{bid}/{bid}s.jpg"
                        html = resp.content.decode("gbk", errors="replace")
                        h1 = re.search(r"<h1>(.*?)</h1>", html, re.I)
                        title = h1.group(1).replace("最新章节", "").strip() if h1 else clean_query
                        author_match = re.search(r"作者[：:]\s*([^\s&<]+)", html)
                        author = author_match.group(1).strip() if author_match else ""
                        return [Novel(
                            id=f"{cat}_{bid}",
                            title=title,
                            author=author,
                            url=canon_url,
                            cover_url=cover,
                            source_id=self.SOURCE_ID
                        )]

                # Case 2: Multi-row results table on search.php
                html = resp.content.decode("gbk", errors="replace")
                soup = BeautifulSoup(html, "html.parser")
                results = []
                for row in soup.select("table.grid tr"):
                    cells = row.select("td")
                    if len(cells) < 3:
                        continue
                    link = cells[0].select_one("a")
                    if not link:
                        continue
                    href = link.get("href", "")
                    m = re.search(r"/(?:html|bookinfo)/(\d+)/(\d+)", href)
                    if not m:
                        continue
                    cat, bid = m.groups()
                    title = link.get_text(strip=True)
                    author = cells[2].get_text(strip=True) if len(cells) > 2 else ""
                    canon_url = f"https://www.piaotia.com/html/{cat}/{bid}/index.html"
                    cover = f"https://www.piaotia.com/files/article/image/{cat}/{bid}/{bid}s.jpg"
                    results.append(Novel(
                        id=f"{cat}_{bid}",
                        title=title,
                        author=author,
                        url=canon_url,
                        cover_url=cover,
                        source_id=self.SOURCE_ID
                    ))
                if results:
                    return results[:20]
        except Exception:
            pass
        return await self._search_via_yahoo(clean_query)

    async def _search_via_yahoo(self, clean_query: str) -> List[Novel]:
        """Dự phòng khi piaotia.com báo giới hạn 20 giây giữa 2 lần tìm kiếm từ cùng IP."""
        results: List[Novel] = []
        seen = set()
        try:
            q = urllib.parse.quote(f"site:piaotia.com {clean_query}")
            url = f"https://search.yahoo.com/search?p={q}"
            client = get_shared_client()
            resp = await client.get(url, headers={"User-Agent": USER_AGENT}, timeout=3.5)
            if resp.status_code != 200:
                return []
            soup = BeautifulSoup(resp.text, "html.parser")
            for link in soup.select("a[href]"):
                href = urllib.parse.unquote(link.get("href", ""))
                m = re.search(r"piaotia\.com/(?:bookinfo|html)/(\d+)/(\d+)", href)
                if not m:
                    continue
                cat, bid = m.groups()
                book_id = f"{cat}_{bid}"
                if book_id in seen:
                    continue
                parts = [p.strip() for p in link.get_text("|", strip=True).split("|") if p.strip()]
                raw_t = parts[-1] if parts else ""
                if not raw_t or "piaotia.com" in raw_t.lower():
                    continue
                seen.add(book_id)
                author = ""
                m_auth = re.search(r"[,，]\s*([^\s,，]+?)作品选", raw_t)
                if m_auth:
                    author = m_auth.group(1).strip()
                title = raw_t.split(",")[0].split("，")[0].split("-")[0].split("_")[0]
                for noise in ("最新章节", "无弹窗", "全文阅读", "飘天文学", "PT文学"):
                    title = title.replace(noise, "")
                title = title.strip(" _-–—.")
                if not title:
                    continue
                results.append(Novel(
                    id=book_id,
                    title=title,
                    author=author,
                    url=f"https://www.piaotia.com/html/{cat}/{bid}/index.html",
                    cover_url=f"https://www.piaotia.com/files/article/image/{cat}/{bid}/{bid}s.jpg",
                    source_id=self.SOURCE_ID,
                ))
        except Exception:
            pass
        return results[:15]
