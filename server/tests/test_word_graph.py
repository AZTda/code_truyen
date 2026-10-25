"""Unit tests for Word Relationship Graph Service (Feature #7)."""

from fastapi.testclient import TestClient
from server import app
from services.word_graph_service import word_graph_service
from services.auth_token import issue_token

client = TestClient(app, headers={"Authorization": f"Bearer {issue_token('test_word_graph', 'test_word_graph')}"})


def test_word_graph_service_default():
    """Verify default graph contains nodes, edges, clusters, and valid coordinates."""
    graph = word_graph_service.build_graph(limit=30)
    assert graph["total_nodes"] > 0
    assert graph["total_edges"] > 0
    assert len(graph["clusters"]) > 0

    # Verify node structure
    for node in graph["nodes"]:
        assert "id" in node
        assert "word" in node
        assert "x" in node
        assert "y" in node
        assert 0.0 <= node["x"] <= 1.0
        assert 0.0 <= node["y"] <= 1.0
        assert "cluster" in node

    # Verify edge structure
    for edge in graph["edges"]:
        assert "source" in edge
        assert "target" in edge
        assert "relation" in edge


def test_word_graph_endpoints():
    """Test both GET and POST /v1/english/word-graph endpoints."""
    # 1. GET
    res_get = client.get("/v1/english/word-graph?limit=25")
    assert res_get.status_code == 200
    data_get = res_get.json()
    assert "nodes" in data_get
    assert "edges" in data_get

    # 2. POST with custom words
    res_post = client.post(
        "/v1/english/word-graph",
        json={
            "words": [
                {"word": "ephemeral", "meaning_vi": "phù du", "cefr_level": "c1"},
                {"word": "fleeting", "meaning_vi": "thoáng qua", "cefr_level": "b2"},
                {"word": "eternal", "meaning_vi": "vĩnh hằng", "cefr_level": "b2"}
            ],
            "limit": 10
        }
    )
    assert res_post.status_code == 200
    data_post = res_post.json()
    assert data_post["total_nodes"] == 3
    assert len(data_post["nodes"]) == 3
