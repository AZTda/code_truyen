"""Tests for Progressive English Reading (Feature #5 - Diglot Weave)."""

import pytest
from fastapi.testclient import TestClient

from server import app
from services.progressive_english import progressive_service
from services.auth_token import issue_token

client = TestClient(app, headers={"Authorization": f"Bearer {issue_token('test_progressive', 'test_progressive')}"})


def test_progressive_service_replacement():
    """Verify Diglot Weave word replacement and glossary."""
    paragraphs = [
        "Hắn vung thanh kiếm sắc bén chém đứt ngọn núi cổ xưa.",
        "Ánh kiếm rực rỡ như tia sét xé toạc màn đêm."
    ]
    mature_words = [
        {"word": "sword", "meaning_vi": "thanh kiếm, kiếm"},
        {"word": "ancient", "meaning_vi": "cổ xưa, lâu đời"},
        {"word": "lightning", "meaning_vi": "tia sét"}
    ]

    result = progressive_service.weave_chapter(
        paragraphs=paragraphs,
        mature_words=mature_words,
        target_ratio=1.0
    )

    assert result["replaced_count"] >= 3
    assert len(result["glossary"]) >= 2
    # Verify that 'sword' or 'ancient' appears in the transformed text
    combined = " ".join(result["paragraphs"])
    assert "sword" in combined.lower()
    assert "ancient" in combined.lower()


def test_progressive_service_case_preservation():
    """Verify capitalization preservation."""
    paragraphs = ["Thanh kiếm này là của ai?"]
    mature_words = [{"word": "sword", "meaning_vi": "thanh kiếm"}]

    result = progressive_service.weave_chapter(
        paragraphs=paragraphs,
        mature_words=mature_words,
        target_ratio=1.0
    )
    # At start of sentence, should become 'Sword' (capitalized)
    assert "Sword" in result["paragraphs"][0]


def test_progressive_endpoint():
    """Test /v1/english/progressive-chapter endpoint."""
    res = client.post(
        "/v1/english/progressive-chapter",
        json={
            "paragraphs": [
                "Bên trong ngôi bảo tháp cổ xưa, hắn tìm thấy một thanh kiếm thần bí."
            ],
            "mature_words": [
                {"word": "ancient", "meaning_vi": "cổ xưa"},
                {"word": "sword", "meaning_vi": "thanh kiếm"}
            ],
            "target_ratio": 1.0
        }
    )
    assert res.status_code == 200
    data = res.json()
    assert data["replaced_count"] >= 2
    assert len(data["glossary"]) == 2
