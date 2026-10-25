"""Trang /admin: không còn chỗ chèn mã từ display_name / avatar_url / username, và có CSP chặn script không mang nonce."""

import json
import re
import shutil
import subprocess
from html.parser import HTMLParser
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from server import app

PAGE = Path(__file__).resolve().parent.parent / "static" / "admin.html"
HARNESS = Path(__file__).resolve().parent / "_admin_page_harness.js"
client = TestClient(app)
needs_node = pytest.mark.skipif(shutil.which("node") is None, reason="cần Node để chạy script của trang")

ATTACK_USERS = [
    {"username": 'evil" onclick="alert(1)', "display_name": 'x" onload="alert(document.domain)" y="',
     "email": "<script>alert(1)</script>", "vip_tier": "<img src=x onerror=alert(1)>", "avatar_url": "javascript:alert(1)"},
    {"username": "a'b", "display_name": "'); alert(1); //", "email": "e@x", "vip_tier": "VIP",
     "avatar_url": 'https://ok.example/a.png" onerror="alert(1)'},
    {"username": "</div><img src=x onerror=alert(1)>", "display_name": "`${alert(1)}`", "email": "e@x", "vip_tier": "",
     "avatar_url": "data:text/html,<script>alert(1)</script>"},
]


class Tags(HTMLParser):
    def __init__(self):
        super().__init__()
        self.tags = []

    def handle_starttag(self, tag, attrs):
        self.tags.append((tag, dict(attrs)))


def run_page(users, avatars=()):
    proc = subprocess.run(["node", str(HARNESS)], input=json.dumps({"html": PAGE.read_text(encoding="utf-8"),
                          "users": users, "avatars": list(avatars)}), capture_output=True, text=True, timeout=60)
    assert proc.returncode == 0, proc.stderr
    return json.loads(proc.stdout)


# ------------------------------------------------------------------ CSP

def test_admin_page_is_served_with_a_nonce_based_content_security_policy():
    first, second = client.get("/admin"), client.get("/admin")
    assert first.status_code == 200 and "TruyenCloud Admin" in first.text
    policy = first.headers["content-security-policy"]
    nonce = re.search(r"script-src 'nonce-([^']+)'", policy).group(1)
    assert "unsafe-inline" not in policy.split("script-src")[1].split(";")[0]  # thuộc tính onerror=... bị chặn
    assert f'<script nonce="{nonce}">' in first.text and "__CSP_NONCE__" not in first.text
    assert nonce not in second.headers["content-security-policy"]  # nonce đổi theo từng lượt
    for directive in ("default-src 'none'", "frame-ancestors 'none'", "base-uri 'none'", "form-action 'none'"):
        assert directive in policy
    assert first.headers["x-content-type-options"] == "nosniff" and first.headers["x-frame-options"] == "DENY"
    assert first.headers["cache-control"] == "no-store"


# ------------------------------------------------------------------ mã nguồn trang

def test_page_has_no_inline_event_handler_attributes():
    text = PAGE.read_text(encoding="utf-8")
    assert re.findall(r"\son[a-z]+\s*=", text) == []


def test_every_value_interpolated_into_the_user_card_is_escaped():
    text = PAGE.read_text(encoding="utf-8")
    start = text.index("function renderUserList")
    template = text[start:text.index("// Uỷ quyền sự kiện", start)]
    allowed_plain = {"streak", "hours", "chapters"}  # số đã ép bằng Number()
    for expression in re.findall(r"\$\{([^}]*)\}", template.split("return `", 1)[1]):
        assert expression in allowed_plain or expression.startswith("escapeHtml("), expression


@needs_node
def test_attack_payloads_cannot_break_out_of_attributes_or_inject_handlers():
    rendered = run_page(ATTACK_USERS)["rendered"]
    parser = Tags()
    parser.feed(rendered)
    assert {tag for tag, _ in parser.tags} <= {"div", "img", "span", "button"}  # không có <script>/<img> thừa từ payload
    for tag, attrs in parser.tags:
        assert not [name for name in attrs if name.startswith("on")], (tag, attrs)
    images = [attrs for tag, attrs in parser.tags if tag == "img"]
    assert len(images) == len(ATTACK_USERS)
    assert images[0]["src"].startswith("https://ui-avatars.com/api/")  # javascript: bị thay bằng ảnh chữ cái
    assert images[1]["src"] == 'https://ok.example/a.png" onerror="alert(1)'  # nguyên văn trong một giá trị thuộc tính
    assert images[2]["src"].startswith("https://ui-avatars.com/api/")  # data: bị thay
    usernames = [attrs["data-username"] for tag, attrs in parser.tags if tag == "div" and "data-username" in attrs]
    assert usernames == [user["username"] for user in ATTACK_USERS]  # giải mã đúng nguyên văn, không bị cắt


@needs_node
def test_action_buttons_pass_the_exact_username_without_executing_it():
    out = run_page(ATTACK_USERS[:1])
    assert out["calls"] == [["openChangePasswordModal", ATTACK_USERS[0]["username"]],
                            ["deleteUserPrompt", ATTACK_USERS[0]["username"]]]


@needs_node
@pytest.mark.parametrize("url,expected", [
    ("https://truyen.achatvn.com/static/user_avatar.jpg", True), ("http://127.0.0.1:8000/static/a.png", True),
    ("/static/user_avatar.jpg", True), ("static/user_avatar.jpg", True),
    ("javascript:alert(1)", False), ("JaVaScRiPt:alert(1)", False), (" javascript:alert(1)", False),
    ("java\tscript:alert(1)", False), ("data:image/png;base64,AAAA", False), ("vbscript:x", False),
    ("blob:https://x/1", False), ("//evil.example/a.png", False), ("/\\evil.example/a.png", False),
    ("", False), (None, False),
])
def test_avatar_url_scheme_is_validated(url, expected):
    safe = run_page([], [url])["avatars"][0]
    assert bool(safe) is expected
    if expected:
        assert safe == url.strip()
