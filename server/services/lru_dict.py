"""Dict có chặn trên số mục, bỏ mục ít dùng nhất (LRU) — cho các cache trong bộ nhớ."""

from collections import OrderedDict
from typing import Any, Hashable, Optional


class LruDict(OrderedDict):
    def __init__(self, max_items: int = 2000):
        super().__init__()
        self.max_items = max_items

    def __getitem__(self, key: Hashable) -> Any:
        value = super().__getitem__(key)
        self.move_to_end(key)
        return value

    def get(self, key: Hashable, default: Optional[Any] = None) -> Any:
        if key in self:
            return self[key]
        return default

    def __setitem__(self, key: Hashable, value: Any) -> None:
        super().__setitem__(key, value)
        self.move_to_end(key)
        while len(self) > self.max_items:
            self.popitem(last=False)
