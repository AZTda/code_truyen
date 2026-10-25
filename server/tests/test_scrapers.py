import pytest
from services.scraper_registry import registry, get_scraper_for_url, get_scraper_by_id

def test_scraper_registry_all_registered():
    scrapers = registry.all_scrapers()
    ids = [s.SOURCE_ID for s in scrapers]
    assert "69shuba" in ids
    assert "piaotia" in ids
    assert "uukanshu" in ids
    assert "jjwxc" not in ids

def test_scraper_url_resolution():
    s1 = get_scraper_for_url("https://www.69shuba.com/book/12345.htm")
    assert s1.SOURCE_ID == "69shuba"

    s2 = get_scraper_for_url("https://www.piaotia.com/html/10/10255/index.html")
    assert s2.SOURCE_ID == "piaotia"

    s3 = get_scraper_for_url("https://uukanshu.cc/book/12345/")
    assert s3.SOURCE_ID == "uukanshu"

    with pytest.raises(ValueError):
        get_scraper_for_url("https://www.jjwxc.net/onebook.php?novelid=12345")

def test_scraper_69shuba_proxy_url():
    scraper = get_scraper_by_id("69shuba")
    original = "https://www.69shuba.com/book/12345.htm"
    proxy = scraper.get_proxy_url(original)
    assert "69shuba-com.translate.goog" in proxy
    # Scraper dung _x_tr_sl=auto (tu nhan dien ngon ngu nguon) va _x_tr_tl=zh-CN
    # lam ngon ngu dich. Test cu khang dinh _x_tr_sl=zh-CN nen luon that bai.
    assert "_x_tr_sl=auto" in proxy
    assert "_x_tr_tl=zh-CN" in proxy

    cleaned = scraper.clean_proxy_url(proxy)
    assert "69shuba.com" in cleaned
    assert "translate.goog" not in cleaned
