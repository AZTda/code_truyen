"""Chấm phát âm không được đóng băng máy chủ: input khổng lồ rẻ, chạy ngoài event loop, điểm không đổi."""

import asyncio
import random
import string
import threading
import time

import pytest

from services import pronunciation_scoring as scoring
from services import pronunciation_service as service_module
from services.pronunciation_service import pronunciation_service

# Điểm tham chiếu do cài đặt TRƯỚC khi tối ưu tính ra (đã so cả dict kết quả với bản mới trên các cặp này).
REFERENCE_SCORES = [
    ("ephemeral", "ephemeral", 100), ("ephemeral", "effemeral", 91), ("ephemeral", "cabbage", 15),
    ("resilience", "resilient", 85), ("serendipity", "serenity", 73), ("equanimity", "equal ability", 67),
    ("hello", "hallo", 92), ("take it easy", "Take easy", 67), ("take it easy", "tape it easy please", 73),
    ("knight", "night", 93), ("phone", "fone", 84), ("through", "threw", 57),
    ("the quick brown fox", "the quick brown box", 93), ("a", "uh", 30), ("go", "go go go", 100),
    ("ice cream", "i scream", 57), ("wednesday", "wens day", 76), ("colonel", "kernel", 60),
    ("Hello, World!", "hello world", 100), ("don't", "dont", 100), ("unbelievable", "un believable", 100),
]


def _random_tokens(count, length, rng):
    return " ".join("".join(rng.choice(string.ascii_lowercase) for _ in range(length)) for _ in range(count))


def test_worst_case_request_is_cheap():
    rng = random.Random(1)
    target = _random_tokens(60, 48, rng)  # ~3 KB mỗi bên: trước đây ~1,7 giây CPU, chặn cả máy chủ
    heard = _random_tokens(60, 48, rng)
    scoring.token_similarity.cache_clear()
    started = time.perf_counter()
    result = scoring.score_transcript(target, heard)
    assert time.perf_counter() - started < 0.5
    assert 0 <= result["score"] <= 100
    assert len(result["segments"]) <= scoring._MAX_TOKENS + 1


def test_caps_are_small_enough_for_real_phrases_only():
    assert scoring._MAX_TOKENS <= 15 and scoring._MAX_TOKEN_CHARS <= 24


@pytest.mark.parametrize("target,heard,expected", REFERENCE_SCORES)
def test_scores_are_unchanged_for_normal_inputs(target, heard, expected):
    assert scoring.score_transcript(target, heard)["score"] == expected


def test_faster_edit_distance_matches_the_alignment_based_definition():
    rng = random.Random(5)
    unit_cost = lambda x, y: 0.0 if x == y else 1.0  # noqa: E731
    for _ in range(500):
        a = "".join(rng.choice("abcde") for _ in range(rng.randint(0, 10)))
        b = "".join(rng.choice("abcde") for _ in range(rng.randint(0, 10)))
        expected, _ = scoring._align(a, b, unit_cost)
        assert scoring._edit_distance(a, b) == expected
        if a or b:
            assert scoring._ratio(a, b) == (0.0 if not (a and b) else max(0.0, 1.0 - expected / max(len(a), len(b))))


def test_token_similarity_is_memoized_and_short_circuits_trivial_pairs():
    scoring.token_similarity.cache_clear()
    assert scoring.token_similarity("hello", "hello") == 1.0
    assert scoring.token_similarity("", "hello") == 0.0 and scoring.token_similarity("hello", "") == 0.0
    first = scoring.token_similarity("kitten", "sitting")
    hits_before = scoring.token_similarity.cache_info().hits
    assert scoring.token_similarity("kitten", "sitting") == first
    assert scoring.token_similarity.cache_info().hits == hits_before + 1


def test_evaluation_runs_off_the_event_loop(monkeypatch):
    seen = {}

    def slow_scoring(word, transcript):
        seen["thread"] = threading.current_thread()
        time.sleep(0.3)  # đại diện cho phép tính CPU nặng
        return {"score": 80, "similarity": 0.8, "heard": transcript, "unit": "word", "segments": []}

    monkeypatch.setattr(service_module, "score_transcript", slow_scoring)

    async def scenario():
        ticks = 0
        stop = asyncio.Event()

        async def ticker():
            nonlocal ticks
            while not stop.is_set():
                ticks += 1
                await asyncio.sleep(0.01)

        task = asyncio.create_task(ticker())
        await pronunciation_service.evaluate("take it easy", spoken_text="take it easy")
        stop.set()
        await task
        return ticks

    ticks = asyncio.run(scenario())
    assert seen["thread"] is not threading.main_thread()
    assert ticks >= 10  # event loop vẫn quay trong lúc chấm; chạy trong loop thì ticks <= 1


def test_oversized_inputs_are_cut_before_normalisation():
    huge = "word " * 200_000
    started = time.perf_counter()
    result = asyncio.run(pronunciation_service.evaluate(huge, spoken_text=huge))
    assert time.perf_counter() - started < 1.0
    assert result["is_fallback"] is False and len(result["word"]) <= service_module._MAX_INPUT_CHARS
