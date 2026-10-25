"""Word Relationship Graph Service (Feature #7).

Constructs an interactive semantic network of user's saved vocabulary:
- Nodes: Words with CEFR level, mastery stage, semantic cluster, coordinates
- Edges: Synonyms, Antonyms, Collocations, Shared Domain, Etymological Roots
"""

import math
import logging
from typing import List, Dict, Any, Optional

logger = logging.getLogger("truyencloud.word_graph")

# Curated semantic clusters for common literature & novel vocabulary
SEMANTIC_CLUSTERS = {
    "Time & Transience": [
        "ephemeral", "fleeting", "transient", "momentary", "evanescent", "perpetual", "eternal", "immortal",
        "ancient", "primordial", "millennium", "epoch", "fleeting", "enduring"
    ],
    "Martial & Power": [
        "dauntless", "sovereign", "zenith", "prowess", "vanquish", "subjugate", "annihilate", "ascend",
        "primordial", "blade", "sword", "peerless", "indomitable", "strike"
    ],
    "Emotion & Mind": [
        "serendipity", "melancholy", "petrichor", "nostalgia", "euphoria", "resilience", "equanimity",
        "solitude", "tranquility", "trepidation", "fury", "resolute"
    ],
    "Arcane & Mystery": [
        "arcane", "esoteric", "ethereal", "abyssal", "luminescence", "enigma", "cryptic", "celestial",
        "incantation", "catalyst", "mirage", "phantom"
    ],
    "Nature & Cosmos": [
        "solitude", "wilderness", "chasm", "tempest", "celestial", "stellar", "nebula", "abyss",
        "horizon", "aurora", "firmament", "cascade"
    ]
}

# Known relationships (synonyms, antonyms, roots)
KNOWN_RELATIONS = [
    ("ephemeral", "fleeting", "synonym", 0.95),
    ("ephemeral", "transient", "synonym", 0.90),
    ("ephemeral", "eternal", "antonym", 0.85),
    ("ephemeral", "immortal", "antonym", 0.85),
    ("fleeting", "transient", "synonym", 0.90),
    ("ancient", "primordial", "synonym", 0.88),
    ("sovereign", "peerless", "synonym", 0.80),
    ("dauntless", "indomitable", "synonym", 0.92),
    ("dauntless", "resolute", "synonym", 0.85),
    ("vanquish", "subjugate", "synonym", 0.85),
    ("vanquish", "annihilate", "synonym", 0.90),
    ("equanimity", "tranquility", "synonym", 0.88),
    ("arcane", "esoteric", "synonym", 0.92),
    ("arcane", "cryptic", "synonym", 0.85),
    ("celestial", "stellar", "synonym", 0.80),
    ("abyssal", "chasm", "synonym", 0.75),
]


class WordGraphService:
    """Builds and lays out 2D semantic graphs from vocabulary words."""

    def build_graph(
        self,
        words: Optional[List[Dict[str, Any]]] = None,
        limit: int = 50
    ) -> Dict[str, Any]:
        """Generates graph nodes and edges with calculated 2D positions for canvas rendering."""
        # If no words provided or empty, supply curated demonstrative words
        source_words = words or self._get_default_words()
        source_words = source_words[:limit]

        word_map = {w["word"].lower().strip(): w for w in source_words}
        all_word_keys = list(word_map.keys())

        # Determine clusters
        clusters: Dict[str, List[str]] = {}
        for w_key in all_word_keys:
            cluster_name = self._find_cluster(w_key)
            clusters.setdefault(cluster_name, []).append(w_key)

        # Layout clusters in 2D circular topology
        cluster_names = list(clusters.keys())
        cluster_centers = {}
        num_clusters = max(1, len(cluster_names))
        for i, c_name in enumerate(cluster_names):
            angle = (2 * math.pi * i) / num_clusters
            radius = 0.30  # distance from center
            cx = 0.5 + radius * math.cos(angle)
            cy = 0.5 + radius * math.sin(angle)
            cluster_centers[c_name] = (cx, cy)

        # Position nodes within their cluster circle
        nodes = []
        for c_name, members in clusters.items():
            cx, cy = cluster_centers[c_name]
            num_members = len(members)
            for j, w_key in enumerate(members):
                w_info = word_map[w_key]
                if num_members == 1:
                    nx, ny = cx, cy
                else:
                    member_angle = (2 * math.pi * j) / num_members
                    member_radius = 0.10 + (j % 3) * 0.02
                    nx = cx + member_radius * math.cos(member_angle)
                    ny = cy + member_radius * math.sin(member_angle)

                # Clamp to [0.05, 0.95]
                nx = max(0.08, min(0.92, nx))
                ny = max(0.08, min(0.92, ny))

                cefr = w_info.get("cefr_level", "c1").lower()
                stage = w_info.get("stage", "learning")

                nodes.append({
                    "id": w_key,
                    "word": w_info["word"],
                    "meaning_vi": w_info.get("meaning_vi", ""),
                    "cefr_level": cefr,
                    "cluster": c_name,
                    "mastery_stage": stage,
                    "x": round(nx, 3),
                    "y": round(ny, 3),
                    "size": 24 if cefr in ("c1", "c2") else 20
                })

        # Generate edges
        edges = []
        node_id_set = set(all_word_keys)

        # 1. Known semantic relationships
        for s, t, rel, weight in KNOWN_RELATIONS:
            if s in node_id_set and t in node_id_set:
                edges.append({
                    "source": s,
                    "target": t,
                    "relation": rel,
                    "weight": weight
                })

        # 2. Cluster intra-connections
        for c_name, members in clusters.items():
            for k in range(len(members) - 1):
                s = members[k]
                t = members[k + 1]
                if not any((e["source"] == s and e["target"] == t) for e in edges):
                    edges.append({
                        "source": s,
                        "target": t,
                        "relation": "domain",
                        "weight": 0.65
                    })

        # Cluster summaries with color palettes
        palette = [
            {"name": "Time & Transience", "color": "#D4AF37", "bg": "#252115"},
            {"name": "Martial & Power", "color": "#E57373", "bg": "#2A1818"},
            {"name": "Emotion & Mind", "color": "#81C784", "bg": "#162518"},
            {"name": "Arcane & Mystery", "color": "#BA68C8", "bg": "#251828"},
            {"name": "Nature & Cosmos", "color": "#64B5F6", "bg": "#15222B"},
            {"name": "General Literature", "color": "#B0BEC5", "bg": "#1C2124"}
        ]
        cluster_list = []
        for p in palette:
            count = len(clusters.get(p["name"], []))
            if count > 0:
                cluster_list.append({
                    "name": p["name"],
                    "count": count,
                    "color": p["color"],
                    "bg": p["bg"]
                })

        return {
            "total_nodes": len(nodes),
            "total_edges": len(edges),
            "clusters": cluster_list,
            "nodes": nodes,
            "edges": edges
        }

    def _find_cluster(self, word: str) -> str:
        for c_name, members in SEMANTIC_CLUSTERS.items():
            if word in members:
                return c_name
        return "General Literature"

    def _get_default_words(self) -> List[Dict[str, Any]]:
        return [
            {"word": "ephemeral", "meaning_vi": "phù du, ngắn ngủi", "cefr_level": "c1", "stage": "learning"},
            {"word": "fleeting", "meaning_vi": "thoáng qua, trôi nhanh", "cefr_level": "b2", "stage": "mature"},
            {"word": "transient", "meaning_vi": "nhất thời, tạm thời", "cefr_level": "c1", "stage": "learning"},
            {"word": "eternal", "meaning_vi": "vĩnh hằng, muôn đời", "cefr_level": "b2", "stage": "mature"},
            {"word": "immortal", "meaning_vi": "bất tử, bất diệt", "cefr_level": "b2", "stage": "mature"},
            {"word": "primordial", "meaning_vi": "nguyên thủy, hỗn mang", "cefr_level": "c2", "stage": "learning"},
            {"word": "ancient", "meaning_vi": "cổ xưa", "cefr_level": "b1", "stage": "mastered"},
            {"word": "sovereign", "meaning_vi": "tối cao, quân chủ", "cefr_level": "c1", "stage": "learning"},
            {"word": "peerless", "meaning_vi": "vô song, độc nhất vô nhị", "cefr_level": "c2", "stage": "learning"},
            {"word": "dauntless", "meaning_vi": "bất khuất, không sợ hãi", "cefr_level": "c1", "stage": "learning"},
            {"word": "indomitable", "meaning_vi": "kiên cường, không thể khuất phục", "cefr_level": "c2", "stage": "learning"},
            {"word": "vanquish", "meaning_vi": "chinh phục, đánh bại hoàn toàn", "cefr_level": "c1", "stage": "learning"},
            {"word": "subjugate", "meaning_vi": "khuất phục, bắt quy phục", "cefr_level": "c2", "stage": "learning"},
            {"word": "serendipity", "meaning_vi": "sự tình cờ may mắn", "cefr_level": "c2", "stage": "learning"},
            {"word": "equanimity", "meaning_vi": "sự điềm tĩnh, thản nhiên", "cefr_level": "c2", "stage": "learning"},
            {"word": "tranquility", "meaning_vi": "sự tĩnh lặng, thanh bình", "cefr_level": "c1", "stage": "mature"},
            {"word": "solitude", "meaning_vi": "sự thanh tịnh cô độc", "cefr_level": "b2", "stage": "mature"},
            {"word": "arcane", "meaning_vi": "bí truyền, huyền bí khó hiểu", "cefr_level": "c2", "stage": "learning"},
            {"word": "esoteric", "meaning_vi": "uyên thâm, mật truyền", "cefr_level": "c2", "stage": "learning"},
            {"word": "celestial", "meaning_vi": "thiên giới, thần thánh", "cefr_level": "c1", "stage": "mature"},
            {"word": "stellar", "meaning_vi": "tinh tú, xuất chúng", "cefr_level": "b2", "stage": "mature"},
            {"word": "abyss", "meaning_vi": "vực thẳm sâu thẳm", "cefr_level": "b2", "stage": "learning"}
        ]


word_graph_service = WordGraphService()
