from typing import Optional, List
from .scraper_69shuba import Shuba69Scraper
from .scraper_piaotia import PiaotiaScraper
from .scraper_uukanshu import UuKanshuScraper
from .scraper_generic import GenericScraper
from .source_policy import BLOCKED_MESSAGE, is_blocked_url

class ScraperRegistry:
    """Registry coordinating all supported website scrapers (chỉ các nguồn miễn phí)."""
    def __init__(self):
        self.generic = GenericScraper()
        self.scrapers = [
            Shuba69Scraper(),
            PiaotiaScraper(),
            UuKanshuScraper(),
        ]
        self._map = {s.SOURCE_ID: s for s in self.scrapers}
        self._map[self.generic.SOURCE_ID] = self.generic

    def get_by_source_id(self, source_id: str):
        """Scraper của mã nguồn; None nếu nguồn không được hỗ trợ hoặc đã gỡ."""
        return self._map.get(source_id)

    def get_by_url(self, url: str):
        if is_blocked_url(url):
            return None
        for s in self.scrapers:
            if s.supports(url):
                return s
        if self.generic.supports(url):
            return self.generic
        return None

    def all_scrapers(self):
        return self.scrapers

registry = ScraperRegistry()

def get_scraper_for_url(url: str):
    if is_blocked_url(url):
        raise ValueError(BLOCKED_MESSAGE)
    scraper = registry.get_by_url(url)
    if not scraper:
        raise ValueError(f"Không tìm thấy parser phù hợp cho URL: {url}")
    return scraper

def get_scraper_by_id(source_id: str):
    scraper = registry.get_by_source_id(source_id)
    if not scraper:
        raise ValueError(f"Nguồn '{source_id}' không được hỗ trợ.")
    return scraper
