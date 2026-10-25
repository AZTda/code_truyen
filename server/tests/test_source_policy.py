"""Nguồn không miễn phí (Tấn Giang) đã được gỡ: không còn trong registry, không cào được, và không lọt qua trình cào tổng quát."""

import pytest

from services.scraper_registry import registry, get_scraper_for_url, get_scraper_by_id
from services.source_policy import BLOCKED_MESSAGE, REMOVED_SOURCE_IDS, is_blocked_url


@pytest.mark.parametrize("url", [
    "https://www.jjwxc.net/onebook.php?novelid=12345",
    "http://jjwxc.net/onebook.php?novelid=1",
    "https://i9-static.jjwxc.net/novelimage.php?novelid=2157106",
])
def test_jjwxc_urls_are_blocked(url):
    assert is_blocked_url(url)
    with pytest.raises(ValueError, match="gỡ"):
        get_scraper_for_url(url)


def test_free_source_urls_are_not_blocked():
    for url in [
        "https://www.69shuba.com/book/1384.htm",
        "https://www.piaotia.com/html/15/15976/index.html",
        "https://uukanshu.cc/book/12528/",
    ]:
        assert not is_blocked_url(url)


def test_jjwxc_scraper_is_not_registered():
    assert "jjwxc" not in [s.SOURCE_ID for s in registry.all_scrapers()]
    assert registry.get_by_source_id("jjwxc") is None
    with pytest.raises(ValueError):
        get_scraper_by_id("jjwxc")


def test_block_message_says_source_was_removed():
    assert "gỡ" in BLOCKED_MESSAGE
    assert "jjwxc" in REMOVED_SOURCE_IDS
