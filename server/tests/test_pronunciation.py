"""Unit tests for Pronunciation Evaluation Service (Feature #8)."""

import pytest
from fastapi.testclient import TestClient
from server import app
from services.pronunciation_service import pronunciation_service
from services.auth_token import issue_token

client = TestClient(app, headers={"Authorization": f"Bearer {issue_token('test_pronunciation', 'test_pronunciation')}"})


@pytest.mark.asyncio
async def test_pronunciation_service_curated():
    """Verify pronunciation evaluation on curated word (e.g. ephemeral)."""
    res = await pronunciation_service.evaluate("ephemeral", spoken_text="ephemeral")
    assert res["word"] == "ephemeral"
    assert res["overall_score"] >= 90
    assert res["accuracy_level"] == "perfect"
    assert len(res["phonemes"]) > 0
    assert "feedback_vi" in res


@pytest.mark.asyncio
async def test_pronunciation_service_imperfect():
    """Verify imperfect speech produces lower score and tips."""
    res = await pronunciation_service.evaluate("ephemeral", spoken_text="effemeral")
    assert res["word"] == "ephemeral"
    assert res["overall_score"] < 95
    assert len(res["phonemes"]) > 0


def test_pronunciation_endpoint():
    """Verify POST /v1/english/pronunciation/evaluate."""
    res = client.post(
        "/v1/english/pronunciation/evaluate",
        json={
            "word": "serendipity",
            "target_ipa": "/ˌser.ənˈdɪp.ə.ti/",
            "spoken_text": "serendipity"
        }
    )
    assert res.status_code == 200
    data = res.json()
    assert data["word"] == "serendipity"
    assert "phonemes" in data
    assert "overall_score" in data
    assert len(data["common_mistakes"]) > 0
