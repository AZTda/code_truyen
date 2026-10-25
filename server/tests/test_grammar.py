"""Tests for Grammar Pattern Recognition (Feature #4)."""

import pytest
from fastapi.testclient import TestClient

from server import app
from services.grammar_extractor import grammar_service, GRAMMAR_RULES
from services.auth_token import issue_token

client = TestClient(app, headers={"Authorization": f"Bearer {issue_token('test_grammar', 'test_grammar')}"})


def test_grammar_rules_catalog():
    """Verify predefined grammar rules catalog."""
    assert len(GRAMMAR_RULES) >= 8
    for rule in GRAMMAR_RULES:
        assert "id" in rule
        assert "pattern" in rule
        assert "pattern_name" in rule
        assert "category" in rule
        assert "regex" in rule


def test_scan_sentence_inversion():
    """Test negative inversion detection."""
    sentence = "Hardly had the swordmaster drawn his blade when the dark sky split open."
    matches = grammar_service.scan_sentence(sentence, novel_id="test_nov", chapter_number=10)
    assert len(matches) > 0
    matched_ids = [m["pattern_id"] for m in matches]
    assert "negative_inversion" in matched_ids


def test_scan_sentence_cleft():
    """Test it-cleft detection."""
    sentence = "It was the ancient dragon that guarded the jade gate."
    matches = grammar_service.scan_sentence(sentence)
    assert len(matches) > 0
    matched_ids = [m["pattern_id"] for m in matches]
    assert "cleft_sentence_it" in matched_ids


def test_scan_sentence_participle():
    """Test perfect participle clause detection."""
    sentence = "Having refined the nine-star pill, Li Qiye calmly closed his eyes."
    matches = grammar_service.scan_sentence(sentence)
    assert len(matches) > 0
    matched_ids = [m["pattern_id"] for m in matches]
    assert "participle_clause_having" in matched_ids


def test_extract_and_stats_api(tmp_path, monkeypatch):
    """Test /v1/english/grammar-patterns and /v1/english/grammar-stats endpoints."""
    # Bảng tạm: bản cũ ghi vào truyencloud.db thật rồi `DELETE FROM grammar_patterns` xoá sạch bảng thật.
    from database import SqliteBackedTable
    monkeypatch.setattr(grammar_service, "_get_table",
                        lambda: SqliteBackedTable("grammar_patterns", db_path=str(tmp_path / "g.db")))
    text = (
        "Not only did he survive the heavenly tribulation, but his meridians also expanded tenfold. "
        "It was this very technique that altered his fate forever."
    )
    post_res = client.post(
        "/v1/english/grammar-patterns",
        json={
            "text": text,
            "novel_id": "test_novel_api",
            "chapter_number": 5,
            "novel_title": "Emperor's Domination",
            "auto_record": True,
        },
    )
    assert post_res.status_code == 200
    data = post_res.json()
    assert data["detected_count"] >= 2
    assert len(data["patterns"]) >= 2

    # Get stats
    stats_res = client.get("/v1/english/grammar-stats?limit=10")
    assert stats_res.status_code == 200
    stats = stats_res.json()
    assert stats["total_patterns_cataloged"] >= 8
    assert stats["total_occurrences"] >= 2
    assert len(stats["patterns"]) > 0

