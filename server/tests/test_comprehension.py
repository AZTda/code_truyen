"""Unit tests for Reading Comprehension Questions (Feature #6)."""

import pytest
from unittest.mock import patch
from fastapi.testclient import TestClient

from server import app
from services.comprehension_generator import comprehension_generator
from services.auth_token import issue_token

client = TestClient(app, headers={"Authorization": f"Bearer {issue_token('test_comprehension', 'test_comprehension')}"})


@pytest.mark.asyncio
async def test_comprehension_generator():
    """Verify comprehension generator creates questions with 4 options and valid correct index."""
    title = "Chapter 1: The Nine Abyss"
    paragraphs = [
        "Li Qiye walked calmly through the desolate mountain pass.",
        "His immortal blade resonated softly as spiritual energy condensed around him.",
        "The ancient sects had forgotten his legend, but he returned to reclaim his heritage."
    ]
    with patch.object(comprehension_generator, "_generate_with_ai", return_value=[]):
        res = await comprehension_generator.generate_questions(
            title=title,
            paragraphs=paragraphs,
            novel_id="test-novel-1",
            chapter_number=1
        )
    assert res["chapter_title"] == title
    assert len(res["questions"]) >= 2
    for q in res["questions"]:
        assert "question" in q
        assert len(q["options"]) == 4
        assert 0 <= q["correct_index"] < 4
        assert "explanation" in q


def test_comprehension_endpoint():
    """Test /v1/english/comprehension-questions endpoint."""
    with patch.object(comprehension_generator, "_generate_with_ai", return_value=[]):
        res = client.post(
            "/v1/english/comprehension-questions",
            json={
                "title": "Chương 1: Tiên Ma Động",
                "paragraphs": [
                    "Trong bóng tối vô tận, tiếng gầm rú của cổ ma vang vọng.",
                    "Thanh niên trẻ tuổi đứng sừng sững, ánh mắt như hai ngôi sao sáng."
                ],
                "novel_id": "van-co-de-nhat-than-test",
                "chapter_number": 1
            }
        )
    assert res.status_code == 200
    data = res.json()
    assert len(data["questions"]) >= 2
    assert "options" in data["questions"][0]
