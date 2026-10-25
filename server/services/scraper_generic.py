import re
import urllib.parse
from typing import List, Optional
import httpx
from services.http_shared import get_shared_client
from bs4 import BeautifulSoup
from models.novel import Novel, NovelDetail, ChapterSummary

USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

class GenericScraper:
    """Universal fallback scraper for arbitrary novel websites.
    Extracts metadata, book cover (OpenGraph/Twitter/Schema/HTML), and chapter list.
    """
    SOURCE_ID = "generic"
    NAME = "Web Mở Rộng"

    def supports(self, url: str) -> bool:
        clean = url.strip().lower()
        return clean.startswith("http://") or clean.startswith("https://")

    async def fetch_page(self, url: str) -> tuple[str, bytes]:
        headers = {
            "User-Agent": USER_AGENT,
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language": "zh-CN,zh;q=0.9,vi;q=0.8,en;q=0.7",
        }
        client = get_shared_client()
        resp = await client.get(url, headers=headers, timeout=25.0)
        resp.raise_for_status()
        content = resp.content

        # Detect encoding
        encoding = "utf-8"
        content_type = resp.headers.get("content-type", "").lower()
        if "gbk" in content_type or "gb2312" in content_type:
            encoding = "gbk"
        elif "gb18030" in content_type:
            encoding = "gb18030"
        else:
            # Check meta tag in head
            meta_match = re.search(rb'<meta[^>]+charset=["\']?([\w-]+)', content[:1024], re.IGNORECASE)
            if meta_match:
                meta_enc = meta_match.group(1).decode("ascii", errors="ignore").lower()
                if "gb" in meta_enc:
                    encoding = "gb18030"
                elif "utf-8" in meta_enc:
                    encoding = "utf-8"
            else:
                try:
                    content.decode("utf-8")
                    encoding = "utf-8"
                except UnicodeDecodeError:
                    encoding = "gb18030"

        html = content.decode(encoding, errors="replace")
        return html, content

    async def fetch_novel_info(self, url: str) -> NovelDetail:
        html, _ = await self.fetch_page(url)
        soup = BeautifulSoup(html, "html.parser")

        # 1. Title Extraction
        title = ""
        og_title = soup.select_one('meta[property="og:title"], meta[name="og:title"], meta[property="og:novel:book_name"]')
        if og_title and og_title.get("content"):
            title = og_title.get("content").strip()

        if not title:
            h1 = soup.select_one("h1")
            if h1:
                title = h1.get_text(strip=True)

        if not title and soup.title:
            title = soup.title.get_text(strip=True).split("_")[0].split("-")[0].strip()

        title = re.sub(r"[《》最新章节列表全文阅读]", "", title).strip() or "Truyện Web"

        # 2. Author Extraction
        author = "Unknown"
        og_author = soup.select_one('meta[property="og:novel:author"], meta[name="author"], meta[property="og:author"]')
        if og_author and og_author.get("content"):
            author = og_author.get("content").strip()

        if author == "Unknown":
            author_text = soup.find(string=re.compile(r"作者[：:]\s*([^\s<]+)"))
            if author_text:
                m = re.search(r"作者[：:]\s*([^\s<]+)", str(author_text))
                if m:
                    author = m.group(1).strip()

        # 3. Comprehensive Cover Extraction (OpenGraph, Twitter, Link, Img)
        cover_url = ""
        for selector in [
            'meta[property="og:image"]',
            'meta[property="og:novel:image"]',
            'meta[name="twitter:image"]',
            'link[rel="image_src"]',
            'meta[itemprop="image"]',
        ]:
            meta_tag = soup.select_one(selector)
            if meta_tag and (meta_tag.get("content") or meta_tag.get("href")):
                val = (meta_tag.get("content") or meta_tag.get("href")).strip()
                if val and not val.endswith(".ico"):
                    cover_url = val
                    break

        if not cover_url:
            for selector in [
                '.book-img img', '.bookimg img', '.cover img', '#fmimg img',
                'img[src*="cover"]', 'img[src*="article/image"]', 'img[src*="poster"]',
                '.books-img img', '.pic img', '.thumb img', '.imgbox img',
                '.books img', '.info-holder .books img', '.desc-info img',
                '.cover-wrapper img', 'img[itemprop="image"]', '.novel-cover img'
            ]:
                img_el = soup.select_one(selector)
                if img_el:
                    val = (img_el.get("src") or img_el.get("data-src") or img_el.get("data-original") or "").strip()
                    if val and not val.endswith(".ico") and not val.endswith(".gif"):
                        cover_url = val
                        break

        # Check JSON-LD metadata
        if not cover_url:
            for script_el in soup.find_all("script", type="application/ld+json"):
                try:
                    import json
                    jd = json.loads(script_el.string or "{}")
                    if isinstance(jd, dict):
                        img_val = jd.get("image") or jd.get("thumbnailUrl")
                        if isinstance(img_val, str) and img_val.strip():
                            cover_url = img_val.strip()
                            break
                        elif isinstance(img_val, list) and img_val:
                            cover_url = str(img_val[0]).strip()
                            break
                except Exception:
                    pass

        # Check JieQi CMS pattern if still no cover
        if not cover_url:
            jieqi_match = re.search(r"/(\d+)/(\d+)(?:/|\.html?)?$", url)
            if jieqi_match:
                cat, bid = jieqi_match.groups()
                parsed = urllib.parse.urlparse(url)
                cover_url = f"{parsed.scheme}://{parsed.netloc}/files/article/image/{cat}/{bid}/{bid}s.jpg"

        if cover_url:
            if cover_url.startswith("//"):
                cover_url = "https:" + cover_url
            elif not cover_url.startswith("http"):
                cover_url = urllib.parse.urljoin(url, cover_url)

        # Fallback to intelligent cover resolver by title across web novel databases
        if not cover_url and title and title != "Truyện Web":
            try:
                from services.cover_service import cover_service
                cover_url = await cover_service.find_cover_by_title(title)
            except Exception:
                cover_url = "/static/fallback_cover.jpg"

        # 4. Description Extraction
        desc = ""
        og_desc = soup.select_one('meta[property="og:description"], meta[name="description"]')
        if og_desc and og_desc.get("content"):
            desc = og_desc.get("content").strip()

        # 5. Chapters Extraction
        links = soup.select("#list a, #chapters a, .catalog a, .chapter-list a, .volume-list a, dl dd a")
        if not links:
            # Fallback to any links with .html / .htm / chapter
            links = [a for a in soup.select("a[href]") if re.search(r"(?:chapter|\d+\.html?)", a.get("href", ""), re.I)]

        raw_items = []
        for a in links:
            ch_href = a.get("href", "").strip()
            ch_title = a.get_text(strip=True)
            if not ch_href or not ch_title:
                continue
            raw_items.append({"title": ch_title, "url": ch_href})

        from services.toc_normalizer import normalize_chapters
        chapters = normalize_chapters(raw_items, base_url=url)

        novel_id = hashlib_id(url)

        return NovelDetail(
            id=novel_id,
            title=title,
            author=author,
            source_url=url,
            source_id=self.SOURCE_ID,
            cover_url=cover_url,
            description=desc,
            total_chapters=len(chapters),
            chapters=chapters
        )

    async def fetch_chapter_content(self, chapter_url: str) -> str:
        html, _ = await self.fetch_page(chapter_url)
        soup = BeautifulSoup(html, "html.parser")

        # Decompose script, style, ads
        for noise in soup.select("script, style, noscript, header, footer, nav, .ad, .ads, .readsmall"):
            noise.decompose()

        content_el = soup.select_one("#content, #chaptercontent, #articlecontent, .read-content, .chapter-content, article")
        if not content_el:
            content_el = soup.select_one("body")

        if not content_el:
            raise ValueError(f"Không thể trích xuất nội dung chương tại {chapter_url}")

        raw_text = content_el.get_text(separator="\n")
        lines = [line.strip() for line in raw_text.splitlines() if line.strip()]
        return "\n\n".join(lines)

    async def search(self, query: str) -> List[Novel]:
        return []

def hashlib_id(url: str) -> str:
    import hashlib
    clean = re.sub(r"\D", "", url)
    if len(clean) >= 4:
        return clean[:12]
    return hashlib.md5(url.encode("utf-8")).hexdigest()[:10]
