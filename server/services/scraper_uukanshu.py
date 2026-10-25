import re
import urllib.parse
from typing import List
import httpx
from services.http_shared import get_shared_client
from bs4 import BeautifulSoup
from models.novel import Novel, NovelDetail, ChapterSummary
from services.scraper_intro import clean_intro

USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

class UuKanshuScraper:
    """Scraper for uukanshu.cc with Google Translate proxy bypass."""
    SOURCE_ID = "uukanshu"
    NAME = "UUKanshu"

    def supports(self, url: str) -> bool:
        return "uukanshu" in url.lower()

    def get_proxy_url(self, url: str) -> str:
        clean = url.strip()
        if "uukanshu.cc" in clean.lower() and "translate.goog" not in clean.lower():
            temp = re.sub(r"www\.uukanshu\.cc", "uukanshu.cc", clean, flags=re.IGNORECASE)
            temp = re.sub(r"uukanshu\.cc", "uukanshu-cc.translate.goog", temp, flags=re.IGNORECASE)
            sep = "&" if "?" in temp else "?"
            return f"{temp}{sep}_x_tr_sl=auto&_x_tr_tl=zh-CN"
        return clean

    def clean_proxy_url(self, url: str) -> str:
        clean = url.strip()
        if "uukanshu-cc.translate.goog" in clean.lower():
            clean = re.sub(r"uukanshu-cc\.translate\.goog", "uukanshu.cc", clean, flags=re.IGNORECASE)
        clean = re.sub(r"[?&]_x_tr_.*", "", clean)
        return clean

    def normalize_book_url(self, url: str) -> str:
        clean = self.clean_proxy_url(url)
        match = re.search(r"/book/(\d+)", clean, re.IGNORECASE)
        if not match:
            raise ValueError(f"Invalid UUKanshu book URL: {url}")
        return f"https://uukanshu.cc/book/{match.group(1)}/"

    async def fetch_page(self, url: str) -> str:
        headers = {
            "User-Agent": USER_AGENT,
            "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
        }
        # Try direct access first (fast, reliable, no Google 429)
        clean_target = self.clean_proxy_url(url)
        try:
            client = get_shared_client()
            resp = await client.get(clean_target, headers=headers, timeout=15.0)
            if resp.status_code == 200:
                return resp.text
        except Exception:
            pass

        # Fallback to translate proxy if direct access is restricted
        proxy_url = self.get_proxy_url(url)
        client = get_shared_client()
        resp = await client.get(proxy_url, headers=headers, timeout=20.0)
        resp.raise_for_status()
        return resp.text

    async def fetch_novel_info(self, url: str) -> NovelDetail:
        normalized_url = self.normalize_book_url(url)
        html = await self.fetch_page(normalized_url)
        soup = BeautifulSoup(html, "html.parser")

        # 1. Title
        title = ""
        for sel in [".book-info h1", ".booktitle", "h1"]:
            el = soup.select_one(sel)
            if el and el.get_text(strip=True):
                title = el.get_text(strip=True)
                break
        title = title.replace("最新章节", "").strip() or "Unknown Book"

        # 2. Author
        author = "Unknown"
        author_el = soup.select_one(".book-info a[href*=author], .booktag a[href*=author]")
        if author_el:
            author = author_el.get_text(strip=True)
        else:
            for p in soup.select(".book-info p, .book-info span"):
                if "作者" in p.get_text():
                    author = p.get_text().replace("作者：", "").replace("作者:", "").strip()
                    break

        # 3. Giới thiệu nằm trong p.bookintro (meta description chỉ là câu SEO chung)
        desc = ""
        for sel in ["p.bookintro", ".book-info .jieshao", ".book-intro", ".intro", ".jieshao", "#bookIntro", ".desc-info"]:
            el = soup.select_one(sel)
            if el:
                desc = clean_intro(el.get_text("\n"))
                if desc:
                    break

        # 4. Chapters
        chapter_links = soup.select("#chapterList li a, #chapterList a, .chapter-list a, ul.volume-list li a, .chapterlist dd a")
        raw_items = []
        for el in chapter_links:
            raw_href = el.get("href", "").strip()
            if not raw_href:
                continue
            ch_url = self.clean_proxy_url(urllib.parse.urljoin(normalized_url, raw_href))
            ch_title = el.get_text(strip=True)
            if not ch_title or not ch_url.startswith("http"):
                continue
            raw_items.append({"title": ch_title, "url": ch_url})

        from services.toc_normalizer import normalize_chapters
        chapters = normalize_chapters(raw_items, base_url=normalized_url)

        book_id = re.search(r"/book/(\d+)", normalized_url).group(1)
        cover_url = ""
        img_el = soup.select_one(".book-info img, .bookimg img, .book-img img, img.cover, #fmimg img, .pic img")
        if img_el and (img_el.get("src") or img_el.get("data-src")):
            cover_url = (img_el.get("src") or img_el.get("data-src")).strip()
            if cover_url.startswith("//"):
                cover_url = "https:" + cover_url

        if not cover_url or "default" in cover_url.lower():
            prefix = str(int(book_id) // 1000)
            cover_url = f"https://image.uukanshu.cc/{prefix}/{book_id}/{book_id}s.jpg"
        return NovelDetail(
            id=f"uukanshu_{book_id}",
            title=title,
            author=author,
            source_url=normalized_url,
            source_id=self.SOURCE_ID,
            cover_url=cover_url,
            description=desc,
            total_chapters=len(chapters),
            chapters=chapters
        )

    async def fetch_chapter_content(self, chapter_url: str) -> str:
        html = await self.fetch_page(chapter_url)
        soup = BeautifulSoup(html, "html.parser")

        content_el = soup.select_one("#contentbox") or soup.select_one("#content") or soup.select_one(".readcotent") or soup.select_one(".content")
        if not content_el:
            raise ValueError(f"Could not find chapter content for {chapter_url}")

        for tag in content_el.select("h1, script, div.ad, a"):
            tag.decompose()

        raw_text = content_el.get_text(separator="\n")
        lines = []
        for line in raw_text.splitlines():
            clean = line.strip()
            if not clean:
                continue
            if any(bad in clean for bad in ["uukanshu", "UU看书", "uukanshu.com", "uukanshu.cc"]):
                continue
            lines.append(clean)

        return "\n\n".join(lines)

    async def search(self, query: str) -> List[Novel]:
        clean_query = query.strip()
        if not clean_query:
            return []

        try:
            try:
                import zhconv
                trad_query = zhconv.convert(clean_query, "zh-hant")
            except Exception:
                trad_query = clean_query
            encoded = urllib.parse.quote(trad_query)
            search_url = f"https://uukanshu.cc/search/{encoded}_1.html"
            proxy_url = self.get_proxy_url(search_url)
            headers = {
                "User-Agent": USER_AGENT,
                "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
            }
            client = get_shared_client()
            resp = await client.get(proxy_url, headers=headers, timeout=4.0)
            final_url = str(resp.url)

            # Case 1: Direct single match redirect to book page
            m_single = re.search(r"/book/(\d+)/", final_url)
            if m_single:
                book_id = m_single.group(1)
                prefix = str(int(book_id) // 1000)
                soup = BeautifulSoup(resp.text, "html.parser")
                h1 = soup.select_one(".book-info h1, .booktitle, h1")
                title = h1.get_text(strip=True).replace("最新章节", "").strip() if h1 else clean_query
                author_el = soup.select_one(".book-info a[href*=author], .booktag a[href*=author]")
                author = author_el.get_text(strip=True) if author_el else ""
                cover_url = f"https://image.uukanshu.cc/{prefix}/{book_id}/{book_id}s.jpg"
                return [Novel(
                    id=f"uukanshu_{book_id}",
                    title=title,
                    author=author,
                    url=f"https://uukanshu.cc/book/{book_id}/",
                    cover_url=cover_url,
                    source_id=self.SOURCE_ID
                )]

            # Case 2: Multi-row results list
            soup = BeautifulSoup(resp.text, "html.parser")
            results = []
            seen_ids = set()
            for link in soup.select('a[href*="/book/"]'):
                href = self.clean_proxy_url(link.get("href", ""))
                match = re.search(r"/book/(\d+)/?$", href)
                if not match:
                    continue
                book_id = match.group(1)
                if book_id in seen_ids:
                    continue
                seen_ids.add(book_id)

                title = link.get_text(strip=True)
                if not title or len(title) < 2 or "最新章节" in title or "阅读" in title:
                    continue

                prefix = str(int(book_id) // 1000)
                cover_url = f"https://image.uukanshu.cc/{prefix}/{book_id}/{book_id}s.jpg"
                book_url = f"https://uukanshu.cc/book/{book_id}/"

                parent = link.find_parent("div")
                author = ""
                if parent:
                    author_text = parent.get_text()
                    m_auth = re.search(r"作者[：:]\s*([^\s<字]+)", author_text)
                    if m_auth:
                        author = m_auth.group(1).strip()

                results.append(Novel(
                    id=f"uukanshu_{book_id}",
                    title=title,
                    author=author,
                    url=book_url,
                    cover_url=cover_url,
                    source_id=self.SOURCE_ID
                ))
            return results[:20]
        except Exception:
            return []
