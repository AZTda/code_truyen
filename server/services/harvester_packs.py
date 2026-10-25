"""Danh mục Gói Chủ Đề (`TOPIC_PACKS`) cho Bộ Thu Thập Truyện Tự Động (`LibraryHarvester`)."""

from typing import Any, Dict, List, Set, Tuple

TOPIC_PACKS: Dict[str, Tuple[str, Tuple[str, ...]]] = {
    "study_abroad": (
        "Du học sinh · Lưu học · Hải quy · Đại học Thế giới (MIT, Harvard, Uppsala, Thụy Điển, Oxford...)",
        (
            "留学生", "留学", "海归", "交换生", "公派留学", "乌普萨拉", "瑞典", "北欧",
            "皇家理工", "卡罗林斯卡", "麻省", "哈佛", "斯坦福", "牛津", "剑桥", "普林斯顿",
            "耶鲁", "加州理工", "哥伦比亚", "伯克利", "东京大学", "东大", "早稻田",
            "首尔大学", "帝国理工", "常春藤", "访问学者",
        ),
    ),
    "phd": (
        "Nghiên cứu sinh Tiến sĩ · Đọc Bác · Luận văn · Viện sĩ · Học thuật",
        (
            "博士", "研究生", "院士", "科研", "科学家", "教授", "导师", "论文",
            "实验室", "诺贝尔", "麻省", "哈佛", "斯坦福", "学霸", "黑科技", "读博",
            "数学", "物理", "材料", "芯片", "光刻机", "航天", "量子", "可控核聚变", "学术",
        ),
    ),
    "academic": (
        "Công nghệ cao · Quân công · Đại quốc Trọng công · AI & Silicon",
        (
            "大国重工", "军工", "工业", "重工", "航空", "造船", "发动机", "机甲",
            "人工智能", "算法", "程序员", "极客", "硅谷", "互联网", "科技帝国", "首席科学家",
        ),
    ),
    "urban": (
        "Đô thị · Bác sĩ · Luật sư · Đạo diễn · Tài chính · Thần hào",
        (
            "医生", "外科医生", "神医", "中医", "急诊", "神豪", "首富", "文豪",
            "导演", "影帝", "娱乐", "警察", "刑警", "法医", "律师", "检察官",
            "金融", "华尔街", "财阀", "投资", "重生", "奶爸", "美食", "厨神",
        ),
    ),
    "xianxia": (
        "Tu tiên · Trường sinh · Gia tộc · Hồng Hoang · Cẩu đạo",
        (
            "修仙", "长生", "高武", "剑仙", "凡人", "洪荒", "家族修仙", "宗门",
            "苟道", "稳健", "炼丹", "炼器", "阵法", "符箓", "御兽", "飞升", "大帝", "圣人",
        ),
    ),
    "system": (
        "Hệ thống · Bảng thuộc tính · Mô phỏng · Từ điều · Tình báo",
        (
            "开局签到", "面板", "模拟器", "熟练度", "词条", "加点", "属性", "肝经验",
            "简化", "合成", "挂机", "暴击", "返还", "每日情报", "情报", "天道酬勤",
        ),
    ),
    "horror": (
        "Linh dị · Quỷ dị · Cthulhu · Quy tắc quái đàm · Vô hạn lưu",
        (
            "诡异", "恐怖屋", "神秘复苏", "克苏鲁", "精神病院", "旧日", "斩神", "民俗",
            "规则怪谈", "怪谈", "无限流", "惊悚", "密室", "末日", "丧尸", "废土", "序列",
        ),
    ),
    "history": (
        "Lịch sử · Đại Minh · Đại Đường · Tam Quốc · Khoa cử · Hoàng triều",
        (
            "大明", "大唐", "大秦", "三国", "大宋", "贞观", "锦衣卫", "科举",
            "状元", "宰相", "世家", "寒门", "太子", "藩王", "晚明", "崇祯",
        ),
    ),
    "scifi_game": (
        "Tinh tế · Lãnh chúa · Vu sư · Đệ tứ thiên tai · Võng du",
        (
            "星际", "星空", "文明", "舰队", "领主", "种田", "第四天灾", "玩家",
            "网游", "NPC", "BOSS", "全民", "转职", "巫师", "魔药", "赛博朋克", "蒸汽朋克",
        ),
    ),
    "fandom": (
        "Đồng nhân Điện ảnh & Anime (Hogwarts, Marvel, Naruto, One Piece...)",
        (
            "霍格沃茨", "漫威", "美漫", "火影", "木叶", "海贼", "宝可梦", "柯南",
            "高达", "死神", "龙珠", "咒术", "奥特曼", "巫师3", "艾泽拉斯",
        ),
    ),
}


def list_topic_packs() -> List[Dict[str, Any]]:
    seen: Set[str] = set()
    all_kws: List[str] = []
    for _, kws in TOPIC_PACKS.values():
        for kw in kws:
            if kw not in seen:
                seen.add(kw)
                all_kws.append(kw)
    out = [
        {
            "id": "all",
            "name": f"🔥 Toàn bộ {len(TOPIC_PACKS)} Gói Chủ Đề ({len(all_kws)} từ khoá mọi thể loại)",
            "keywords": all_kws,
        }
    ]
    for pid, (label, kws) in TOPIC_PACKS.items():
        out.append({"id": pid, "name": label, "keywords": list(kws)})
    return out
