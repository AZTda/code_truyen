"""Tầng 1 tìm kiếm: thư viện local trong bộ nhớ — tìm mờ song ngữ, xếp hạng.

Tách khỏi search_service để giữ mỗi tệp đúng một trách nhiệm: ở đây chỉ có
so khớp/xếp hạng truyện đã có trong thư viện và hòa trộn với bản cache.
"""

from typing import List, Optional, Sequence
from models.novel import Novel
from .cover_service import cover_service
from .hanviet_dict import has_cjk
from .library_index import library_index
from .query_lexicon import _ACCENTED_MAP, _FOLDED_MAP, fuzzy_lookup_latin
from .text_similarity import strip_accents, similarity_vi, similarity_cjk, to_simplified, VI_STOPWORDS

# Ngưỡng điểm để một truyện trong thư viện được coi là khớp mờ với từ khóa
# (0.58: giữ được sai chính tả nhẹ ~0.6+, loại nhiễu bigram tình cờ ~0.5)
LOCAL_FUZZY_THRESHOLD = 0.58
LOCAL_MAX_RESULTS = 30


class LocalSearchMixin:
    """Tìm kiếm trong thư viện local + hòa trộn kết quả local với cache."""

    def search_local_library(
        self, query: str, cjk_terms: Optional[Sequence[str]] = None
    ) -> List[Novel]:
        """Tìm mờ trong thư viện: chịu thiếu dấu, sai chính tả nhẹ, đảo từ, tìm cả trong
        tóm tắt (description), tác giả Hán-Việt và từ khóa tiếng Trung đã dịch (`cjk_terms`)."""
        clean_q = query.strip().lower()
        if not clean_q:
            return []

        q_has_cjk = has_cjk(clean_q)
        clean_q_no_acc = strip_accents(clean_q)
        q_words = clean_q_no_acc.split()
        sig_q = {w for w in q_words if w not in VI_STOPWORDS} if len(q_words) >= 2 else set()
        effective_cjk = list(cjk_terms) if cjk_terms else (
            [] if q_has_cjk else list(
                _ACCENTED_MAP.get(clean_q) or _FOLDED_MAP.get(clean_q_no_acc) or fuzzy_lookup_latin(clean_q_no_acc) or ()
            )
        )

        scored = []
        for e in library_index.get_entries():
            desc_lower = e.get("desc_lower", "")
            desc_no_acc = e.get("desc_no_acc", "")
            desc_words = e.get("desc_word_set") or set()
            if q_has_cjk:
                score = similarity_cjk(clean_q, e["title_lower"])
                q_simp = to_simplified(clean_q)
                if score < LOCAL_FUZZY_THRESHOLD and len(q_simp) >= 2 and desc_lower and q_simp in to_simplified(desc_lower):
                    score = max(score, 0.64)
            else:
                # 1.0 chỉ dành cho BẰNG NHAU tuyệt đối; chuỗi con để similarity_vi
                # tự chấm theo độ dài — tránh mọi tựa "chứa" từ khóa đều đồng hạng.
                exact = (
                    clean_q == e["title_lower"] or clean_q == e["title_vi_lower"]
                    or clean_q_no_acc == e["title_vi_no_acc"]
                    or clean_q_no_acc == e["title_no_acc"] or clean_q == e["id"]
                )
                score = 1.0 if exact else max(
                    similarity_vi(clean_q_no_acc, e["title_vi_no_acc"]),
                    similarity_vi(clean_q_no_acc, e["title_no_acc"]),
                    similarity_vi(clean_q_no_acc, e["author_no_acc"]),
                )
                # Đối chiếu từ khoá tiếng Trung đã dịch với tựa gốc & tóm tắt tiếng Trung trong kho
                for idx, cjk_q in enumerate(effective_cjk[:3]):
                    cjk_norm = to_simplified((cjk_q or "").strip())
                    if cjk_norm and has_cjk(cjk_norm):
                        w = 0.95 if idx == 0 else 0.85
                        score = max(score, similarity_cjk(cjk_norm, e["title_lower"]) * w)
                        if score < LOCAL_FUZZY_THRESHOLD and len(cjk_norm) >= 2 and desc_lower and cjk_norm in to_simplified(desc_lower):
                            score = max(score, 0.64)
                # Luật bao phủ từ trên tiêu đề
                if score < LOCAL_FUZZY_THRESHOLD and sig_q:
                    common = sig_q & e["word_set"]
                    if len(common) >= 3 or (len(common) >= 2 and len(common) / len(sig_q) >= 0.75):
                        score = max(score, LOCAL_FUZZY_THRESHOLD)
                # Tìm sâu trong phần Giới thiệu / Tóm tắt (description) của kho nội bộ 1TB
                if score < LOCAL_FUZZY_THRESHOLD and desc_lower and len(clean_q) >= 4 and clean_q_no_acc not in VI_STOPWORDS:
                    q_tone = clean_q.replace("uỵ", "ụy").replace("uỷ", "ủy").replace("oà", "òa").replace("oá", "óa")
                    d_tone = desc_lower.replace("uỵ", "ụy").replace("uỷ", "ủy").replace("oà", "òa").replace("oá", "óa")
                    if q_tone in d_tone and (f" {q_tone} " in f" {d_tone} " or f"({q_tone}" in d_tone or f"{q_tone})" in d_tone or f"{q_tone}," in d_tone or f"{q_tone}." in d_tone):
                        score = max(score, 0.65)
                    elif clean_q == clean_q_no_acc and len(clean_q_no_acc) >= 6 and f" {clean_q_no_acc} " in f" {desc_no_acc} ":
                        score = max(score, 0.62)

            if score >= LOCAL_FUZZY_THRESHOLD:
                scored.append((score, e))

        # Đồng điểm: tựa ngắn hơn (khớp trọn vẹn hơn) đứng trước
        scored.sort(key=lambda x: (-x[0], len(x[1]["title_vi_no_acc"])))
        results: List[Novel] = []
        for score, e in scored[:LOCAL_MAX_RESULTS]:
            n = e["novel"]
            results.append(Novel(
                id=e["id"],
                title=n.get("title", e["id"]),
                title_vi=e["title_vi"],
                author=n.get("author") or "Nội bộ",
                url=n.get("source_url", f"/v1/novels/{e['id']}"),
                cover_url=n.get("cover_url") or "/static/fallback_cover.jpg",
                source_id="local",
                total_chapters=int(n.get("total_chapters") or 0)
            ))
        return results

    def _build_local_lookup(self, novels: List[Novel]) -> dict:
        lookup = {}
        for n in novels:
            if n.source_id == "local":
                lookup[n.id] = n
                if n.url:
                    lookup[n.url] = n
                norm_t = cover_service.normalize_key(n.title)
                if norm_t:
                    lookup[norm_t] = n
        return lookup

    def _merge_cached_with_local(self, query: str, cached: List[Novel], filter_source: Optional[str]) -> List[Novel]:
        """Đặt kết quả thư viện local mới nhất lên đầu bản cache, khử trùng lặp."""
        if filter_source and filter_source != "local":
            return cached
        local_novels = self.search_local_library(query)
        if not local_novels:
            return cached
        local_ids = {n.id for n in local_novels}
        local_urls = {n.url for n in local_novels if n.url}
        local_titles = {cover_service.normalize_key(n.title) for n in local_novels}
        merged = list(local_novels)
        for n in cached:
            norm_t = cover_service.normalize_key(n.title)
            if n.id in local_ids or (n.url and n.url in local_urls) or (norm_t and norm_t in local_titles):
                continue
            merged.append(n)
        return merged
