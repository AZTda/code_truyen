"""Pronunciation scoring must come from the recognized transcript, never a fixed score."""

import pytest
from fastapi.testclient import TestClient

from server import app
from services.auth_token import issue_token
from services.pronunciation_service import pronunciation_service, NO_SPEECH_MESSAGE

client = TestClient(app, headers={"Authorization": f"Bearer {issue_token('test_pron_stt', 'stt')}"})


@pytest.mark.asyncio
@pytest.mark.parametrize("transcript", ["", "   ", "?!", None])
async def test_missing_transcript_returns_null_score(transcript):
    res = await pronunciation_service.evaluate("ephemeral", spoken_text=transcript)
    assert res["score"] is None
    assert res["overall_score"] is None
    assert res["is_fallback"] is True
    assert res["message"] == NO_SPEECH_MESSAGE
    assert res["phonemes"] == []


@pytest.mark.asyncio
async def test_exact_transcript_ignores_case_and_punctuation():
    res = await pronunciation_service.evaluate("Ephemeral", spoken_text="ephemeral.")
    assert res["score"] == 100 and res["overall_score"] == 100
    assert res["is_fallback"] is False
    assert res["alignment_unit"] == "letter"
    assert [p["symbol"] for p in res["phonemes"]] == list("ephemeral")
    assert all(p["status"] == "correct" for p in res["phonemes"])


@pytest.mark.asyncio
async def test_letter_segments_are_real_mismatches_not_repeated_score():
    res = await pronunciation_service.evaluate("resilience", spoken_text="resilient")
    scores = {p["score"] for p in res["phonemes"]}
    assert scores != {res["score"]}
    flagged = [p["symbol"] for p in res["phonemes"] if p["status"] != "correct"]
    assert flagged == ["c", "e"]


@pytest.mark.asyncio
async def test_homophone_spelling_scores_well_but_different_word_does_not():
    close = await pronunciation_service.evaluate("ephemeral", spoken_text="effemeral")
    far = await pronunciation_service.evaluate("ephemeral", spoken_text="cabbage")
    assert 75 <= close["score"] < 100
    assert far["score"] < 50
    assert far["accuracy_level"] == "needs_work"


@pytest.mark.asyncio
async def test_multi_word_target_uses_word_alignment():
    res = await pronunciation_service.evaluate("take it easy", spoken_text="Take easy")
    assert res["alignment_unit"] == "word"
    by_word = {p["symbol"]: p for p in res["phonemes"]}
    assert by_word["it"]["op"] == "missing"
    assert by_word["take"]["status"] == by_word["easy"]["status"] == "correct"
    assert res["score"] < 75


def test_endpoint_without_speech_returns_fallback():
    res = client.post("/v1/english/pronunciation/evaluate", json={"word": "serendipity", "spoken_text": ""})
    assert res.status_code == 200
    body = res.json()
    assert body["score"] is None and body["is_fallback"] is True
    assert body["word"] == "serendipity"
