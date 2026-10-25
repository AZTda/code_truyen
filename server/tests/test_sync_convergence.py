"""Mô phỏng ngẫu nhiên nhiều thiết bị dùng đúng giao thức của app (đẩy dòng bẩn, kéo `since=<con trỏ>`).

Đồng hồ server giả có thể LẶP LẠI cùng một mili giây (cách duy nhất để lộ lỗi mốc `since` bỏ sót), đồng hồ
từng máy chậm tới 3 giây, mọi giờ sửa đều khác nhau. Sau khi mọi máy đồng bộ xong, chúng phải thấy đúng cùng
một tập dữ liệu với server — kể cả bia mộ (xoá) và sửa sau xoá.
"""

import random
import time

import pytest
from fastapi.testclient import TestClient

from server import app
from services import sync_merge
from services.auth_token import issue_token

client = TestClient(app)
SEEDS = range(40)
STEPS = 120


class FakeServerClock:
    def __init__(self):
        self.t = int(time.time() * 1000)

    def tick(self, rng):
        self.t += rng.choice([0, 0, 0, 0, 0, 0, 1])  # phần lớn bước rơi vào CÙNG mili giây với lượt trước

    def __call__(self):
        return self.t


@pytest.fixture
def server_clock(monkeypatch):
    clock = FakeServerClock()
    for target in ("services.sync_merge", "routes.bookmarks", "routes.vocabulary_sync"):
        monkeypatch.setattr(f"{target}.now_ms", clock)
    monkeypatch.setattr(sync_merge.sync_clock, "_last", 0)  # mốc đơn điệu của server bắt đầu lại cùng đồng hồ giả
    return clock


class Device:
    """Một thiết bị: kho cục bộ + giao thức đẩy/kéo. Hai bộ chuyển đổi (bookmark, từ vựng) khác nhau ở hình dạng JSON."""

    def __init__(self, name, headers, clock, skew, used, adapter):
        self.name, self.headers, self.clock, self.skew, self.used = name, headers, clock, skew, used
        self.adapter, self.rows, self.cursor = adapter, {}, 0

    def local_now(self):
        stamp = self.clock.t + self.skew
        while stamp in self.used:
            stamp += 1
        self.used.add(stamp)
        return stamp

    def edit(self, key, value):
        self.rows[key] = {"value": value, "ts": self.local_now(), "deleted": 0, "dirty": True}

    def delete(self, key):
        row = self.rows.get(key)
        if row and not row["deleted"]:
            stamp = self.local_now()
            row.update(ts=stamp, deleted=stamp, dirty=True)

    def sync(self):
        dirty = {k: dict(v) for k, v in self.rows.items() if v["dirty"]}
        stale_seen = self.adapter.push(client, self.headers, dirty) if dirty else False
        for key, sent in dirty.items():
            if self.rows[key]["ts"] == sent["ts"]:
                self.rows[key]["dirty"] = False
        items, self.cursor = self.adapter.pull(client, self.headers, 0 if stale_seen else self.cursor)
        for key, remote in items:
            local = self.rows.get(key)
            if local is None:
                if not remote["deleted"]:
                    self.rows[key] = dict(remote, dirty=False)
                continue
            if remote["ts"] > local["ts"] or (remote["ts"] == local["ts"] and remote["deleted"] and not local["deleted"]):
                self.rows[key] = dict(remote, dirty=False)

    def view(self):
        return {k: v["value"] for k, v in self.rows.items() if not v["deleted"]}


class BookmarkAdapter:
    def server_view(self, client, headers):
        return {it["id"]: it["note"] for it in client.get("/v1/bookmarks?since=0", headers=headers).json() if not it["deleted_at"]}

    def push(self, client, headers, dirty):
        items = [{"id": k, "novel_id": "n", "chapter_number": 1, "paragraph_index": 0, "created_at": 1, "note": v["value"],
                  "updated_at": v["ts"], "deleted_at": v["deleted"]} for k, v in dirty.items()]
        return bool(client.post("/v1/bookmarks/sync", headers=headers, json={"bookmarks": items}).json()["stale_ids"])

    def pull(self, client, headers, since):
        res = client.get(f"/v1/bookmarks?since={since}", headers=headers)
        rows = [(it["id"], {"value": it["note"], "ts": max(it["updated_at"], it["deleted_at"]), "deleted": it["deleted_at"]})
                for it in res.json()]
        return rows, int(res.headers["X-Server-Time-Ms"])


class WordAdapter:
    def server_view(self, client, headers):
        words = client.get("/v1/vocabulary/sync?since=0", headers=headers).json()["words"]
        return {w["id"]: w["meaning"] for w in words if not w.get("deletedAt")}

    def push(self, client, headers, dirty):
        words = [{"id": k, "word": k, "meaning": v["value"], "updatedAt": v["ts"], "deletedAt": v["deleted"]} for k, v in dirty.items()]
        return bool(client.post("/v1/vocabulary/sync", headers=headers, json={"words": words}).json()["stale_word_ids"])

    def pull(self, client, headers, since):
        data = client.get(f"/v1/vocabulary/sync?since={since}", headers=headers).json()
        rows = [(w["id"], {"value": w["meaning"], "ts": max(w["updatedAt"], w.get("deletedAt", 0)), "deleted": w.get("deletedAt", 0)})
                for w in data["words"]]
        return rows, data["server_time_ms"]


@pytest.mark.parametrize("adapter", [BookmarkAdapter(), WordAdapter()], ids=["bookmarks", "vocabulary"])
@pytest.mark.parametrize("seed", SEEDS)
def test_devices_converge_with_each_other_and_the_server(seed, adapter, server_clock):
    rng = random.Random(seed)
    headers = {"Authorization": f"Bearer {issue_token(f'user_sim_{seed}', 'sim')}"}
    used = set()
    devices = [Device(f"d{i}", headers, server_clock, rng.choice([0, 0, -500, -3000]), used, adapter) for i in range(3)]
    keys = [f"k{i}" for i in range(6)]
    for step in range(STEPS):
        server_clock.tick(rng)
        device = rng.choice(devices)
        roll = rng.random()
        if roll < 0.45:
            device.edit(rng.choice(keys), f"{device.name}-{step}")
        elif roll < 0.6:
            device.delete(rng.choice(keys))
        else:
            device.sync()
    for _ in range(3):  # vài vòng cuối để mọi thay đổi lan hết
        for device in devices:
            server_clock.tick(rng)
            device.sync()
    views = [d.view() for d in devices]
    assert all(view == views[0] for view in views), (seed, views)
    assert views[0] == adapter.server_view(client, headers), seed
