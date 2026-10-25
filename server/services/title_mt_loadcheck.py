"""Deploy check: does Hy-MT2 (truyencloud-mt) slow down VieNeu-TTS? Measures TTS RTF idle vs while MT is busy.

Run inside the backend container (same cgroup / cpu_shares as the server's own TTS thread):
    docker exec truyencloud-backend python -m services.title_mt_loadcheck --rounds 3
Loads a second VieNeu engine in this process (~600 MB, freed on exit); the MT load is two concurrent title
translations (the server's two llama.cpp slots), i.e. worse than the real refiner (one request at a time).
"""

import argparse
import asyncio
import json
import statistics
import sys
import threading
import time
from typing import Dict, List

TEXT = ("Lâm Động bước ra khỏi động phủ, ánh mắt sáng như đuốc. Con đường tu luyện phía trước còn rất dài, "
        "nhưng hắn biết mình đã không còn là thiếu niên yếu ớt của năm năm trước.")
LOAD_TITLES = ["国师的建议", "出乎意料的事实", "我在大学当校长", "张磊的悲伤", "来自于月球的天人", "断绝的超凡之路"]


def _rtf(text: str, voice: str, rounds: int) -> List[float]:
    from services.tts_local import local_tts, lower_thread_priority
    lower_thread_priority()  # như luồng dựng giọng thật của máy chủ (nice TTS_LOCAL_NICE)
    out = []
    for _ in range(rounds):
        started = time.perf_counter()
        pcm, rate = local_tts.synth_blocking(text, voice)
        out.append((time.perf_counter() - started) / max(len(pcm) / rate, 1e-6))
    return out


async def _mt_load(stop: threading.Event, done: Dict[str, int]) -> None:
    from services.title_mt_client import title_mt

    async def worker(offset: int) -> None:
        i = offset
        while not stop.is_set():
            await title_mt.zh_to_vi(LOAD_TITLES[i % len(LOAD_TITLES)] + "！" * (i // len(LOAD_TITLES) % 3), timeout=60)
            done["n"] += 1
            i += 2
    await asyncio.gather(worker(0), worker(1))


async def run(rounds: int, text: str) -> Dict[str, object]:
    from services.tts_local import _warmup_voice, local_tts
    from services.title_mt_client import title_mt
    local_tts.start_warmup()
    deadline = time.monotonic() + 300
    while not local_tts.is_ready() and time.monotonic() < deadline:
        await asyncio.sleep(0.5)
    if not local_tts.is_ready():
        return {"error": f"giọng local chưa sẵn sàng: {local_tts.status()}"}
    voice = _warmup_voice(local_tts._engine)
    await asyncio.to_thread(_rtf, text[:40], voice, 1)  # làm nóng
    idle = await asyncio.to_thread(_rtf, text, voice, rounds)
    report: Dict[str, object] = {"rtf_idle": [round(x, 2) for x in idle]}
    if not await title_mt.healthy(max_age=0):
        report["mt"] = "không truy cập được truyencloud-mt: chỉ đo được khi rảnh"
        return report
    stop, done = threading.Event(), {"n": 0}
    load = asyncio.create_task(_mt_load(stop, done))
    await asyncio.sleep(3)  # để llama.cpp vào trạng thái sinh token
    started = time.perf_counter()
    busy = await asyncio.to_thread(_rtf, text, voice, rounds)
    elapsed = time.perf_counter() - started
    stop.set()
    await load
    report.update({
        "rtf_with_mt": [round(x, 2) for x in busy],
        "slowdown_pct": round((statistics.median(busy) / statistics.median(idle) - 1) * 100, 1),
        "mt_titles_per_min_during_tts": round(done["n"] / max(elapsed + 3, 1e-6) * 60, 1),
        "verdict": "OK" if statistics.median(busy) < 1.0 else "CHẬM: TTS không còn nhanh hơn thời gian thực",
    })
    await title_mt.aclose()
    return report


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--rounds", type=int, default=3)
    ap.add_argument("--text", default=TEXT)
    args = ap.parse_args(argv)
    report = asyncio.run(run(max(1, args.rounds), args.text))
    print(json.dumps(report, ensure_ascii=False, indent=1))
    return 0 if "error" not in report else 1


if __name__ == "__main__":
    sys.exit(main())
