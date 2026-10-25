package com.truyencloud.shared.data

object SinoVietnameseTransliteration {

    private val titleDictionary = mapOf(
        "万古第一神" to "Vạn Cổ Đệ Nhất Thần",
        "斗破苍穹" to "Đấu Phá Thương Khung",
        "凡人修仙传" to "Phàm Nhân Tu Tiên",
        "凡人修仙传之厉飞雨" to "Phàm Nhân Tu Tiên Chi Lệ Phi Vũ",
        "大奉打更人" to "Đại Phụng Đả Canh Nhân",
        "剑来" to "Kiếm Lai",
        "雪中悍刀行" to "Tuyết Trung Hãn Đao Hành",
        "庆余年" to "Khánh Dư Niên",
        "斗罗大陆" to "Đấu La Đại Lục",
        "诡秘之主" to "Quỷ Bí Chi Chủ",
        "宿命之环" to "Túc Mệnh Chi Hoàn",
        "遮天" to "Già Thiên",
        "完美世界" to "Hoàn Mỹ Thế Giới",
        "仙逆" to "Tiên Nghịch",
        "一念永恒" to "Nhất Niệm Vĩnh Hằng",
        "求魔" to "Cầu Ma",
        "我欲封天" to "Ngã Dục Phong Thiên",
        "星辰变" to "Tinh Thần Biến",
        "元尊" to "Nguyên Tôn",
        "武动乾坤" to "Vũ Động Càn Khôn",
        "大主宰" to "Đại Chúa Tể",
        "帝霸" to "Đế Bá",
        "牧神记" to "Mục Thần Ký",
        "全职高手" to "Toàn Chức Cao Thủ",
        "修真聊天群" to "Tu Chân Liêu Thiên Quần",
        "九星霸体诀" to "Cửu Tinh Bá Thể Quyết",
        "万族之劫" to "Vạn Tộc Chi Kiếp",
        "全球高武" to "Toàn Cầu Cao Võ",
        "深空彼岸" to "Thâm Không Bỉ Ngạn",
        "轮回乐园" to "Luân Hồi Nhạc Viện",
        "魔道祖师" to "Ma Đạo Tổ Sư",
        "天官赐福" to "Thiên Quan Tứ Phúc",
        "那么远那么近" to "Na Ma Viễn Na Ma Cận",
        "那么远，那么近" to "Na Ma Viễn, Na Ma Cận",
        "那么远， 那么近" to "Na Ma Viễn, Na Ma Cận",
        "这么远那么近" to "Giá Ma Viễn Na Ma Cận",
        "这么远，那么近" to "Giá Ma Viễn, Na Ma Cận",
        "当爱情遇上科学家" to "Mãi Mãi Là Bao Xa"
    )

    private val authorDictionary = mapOf(
        "如心" to "Như Tâm",
        "风青阳" to "Phong Thanh Dương",
        "卖报小郎君" to "Mại Báo Tiểu Lang Quân",
        "子不语神鬼" to "Tử Bất Ngữ Thần Quỷ",
        "忘语" to "Vong Ngữ",
        "爱潜水的乌贼" to "Ái Tiềm Thủy Đích Ô Tặc",
        "烽火戏诸侯" to "Phong Hỏa Hí Chư Hầu",
        "猫腻" to "Miêu Nị",
        "唐家三少" to "Đường Gia Tam Thiếu",
        "天蚕土豆" to "Thiên Tằm Thổ Đậu",
        "辰东" to "Thần Đông",
        "我吃西红柿" to "Ngã Cật Tây Hồng Thị",
        "耳根" to "Nhĩ Căn",
        "骷髅精灵" to "Khô Lâu Tinh Linh",
        "梦入神机" to "Mộng Nhập Thần Cơ",
        "跃千愁" to "Dược Thiên Sầu",
        "会说话的肘子" to "Hội Thuyết Thoại Đích Trửu Tử",
        "老鹰吃小鸡" to "Lão Ưng Cật Tiểu Kê",
        "言归正传" to "Ngôn Quy Chính Truyện",
        "宅猪" to "Trạch Trư",
        "远瞳" to "Viễn Đồng",
        "莫默" to "Mạc Mặc",
        "傲天无痕" to "Ngạo Thiên Vô Ngân",
        "净无痕" to "Tịnh Vô Ngân",
        "蚕茧里的牛" to "Tằm Kiển Lý Đích Ngưu",
        "血红" to "Huyết Hồng",
        "乱" to "Loạn",
        "蝴蝶蓝" to "Hồ Điệp Lam",
        "愤怒的香蕉" to "Phẫn Nộ Đích Hương Tiêu",
        "志鸟村" to "Chí Điểu Thôn",
        "柳下挥" to "Liễu Hạ Huy",
        "烟雨江南" to "Yên Vũ Giang Nam",
        "墨香铜臭" to "Mặc Hương Đồng Khứu",
        "肉包不吃肉" to "Nhục Bao Bất Cật Nhục",
        "木苏里" to "Mộc Tô Lý",
        "淮上" to "Hoài Thượng",
        "西子绪" to "Tây Tử Tự",
        "巫哲" to "Vu Triết",
        "漫漫何其多" to "Mạn Mạn Hà Kỳ Đa"
    )

    private val hanziMap = mapOf(
        '凡' to "Phàm", '人' to "Nhân", '修' to "Tu", '仙' to "Tiên", '传' to "Truyền",
        '之' to "Chi", '厉' to "Lệ", '飞' to "Phi", '雨' to "Vũ", '子' to "Tử",
        '不' to "Bất", '语' to "Ngữ", '神' to "Thần", '鬼' to "Quỷ", '大' to "Đại",
        '奉' to "Phụng", '打' to "Đả", '更' to "Canh", '郎' to "Lang", '君' to "Quân",
        '小' to "Tiểu", '卖' to "Mại", '报' to "Báo", '万' to "Vạn", '古' to "Cổ",
        '第' to "Đệ", '一' to "Nhất", '风' to "Phong", '青' to "Thanh", '阳' to "Dương",
        '波' to "Ba", '士' to "Sĩ", '顿' to "Đốn", '爱' to "Ái", '情' to "Tình",
        '故' to "Cố", '事' to "Sự", '如' to "Như", '心' to "Tâm", '剑' to "Kiếm",
        '来' to "Lai", '雪' to "Tuyết", '中' to "Trung", '悍' to "Hãn", '刀' to "Đao",
        '行' to "Hành", '庆' to "Khánh", '余' to "Dư", '年' to "Niên", '斗' to "Đấu",
        '罗' to "La", '陆' to "Lục", '破' to "Phá", '苍' to "Thương", '穹' to "Khung",
        '诡' to "Quỷ", '秘' to "Bí", '主' to "Chủ", '宿' to "Túc", '命' to "Mệnh",
        '环' to "Hoàn", '遮' to "Già", '天' to "Thiên", '完' to "Hoàn", '美' to "Mỹ",
        '世' to "Thế", '界' to "Giới", '逆' to "Nghịch", '念' to "Niệm", '永' to "Vĩnh",
        '恒' to "Hằng", '求' to "Cầu", '魔' to "Ma", '我' to "Ngã", '欲' to "Dục",
        '封' to "Phong", '星' to "Tinh", '辰' to "Thần", '变' to "Biến", '元' to "Nguyên",
        '尊' to "Tôn", '武' to "Vũ", '动' to "Động", '乾' to "Càn", '坤' to "Khôn",
        '宰' to "Tể", '霸' to "Bá", '牧' to "Mục", '记' to "Ký", '职' to "Chức",
        '高' to "Cao", '手' to "Thủ", '真' to "Chân", '聊' to "Liêu", '群' to "Quần",
        '九' to "Cửu", '体' to "Thể", '诀' to "Quyết", '族' to "Tộc", '劫' to "Kiếp",
        '球' to "Cầu", '深' to "Thâm", '空' to "Không", '彼' to "Bỉ", '岸' to "Ngạn",
        '轮' to "Luân", '回' to "Hồi", '乐' to "Nhạc", '园' to "Viên", '道' to "Đạo",
        '祖' to "Tổ", '师' to "Sư", '官' to "Quan", '赐' to "Tứ", '福' to "Phúc",
        '潜' to "Tiềm", '水' to "Thủy", '的' to "Đích", '乌' to "Ô", '贼' to "Tặc",
        '烽' to "Phong", '火' to "Hỏa", '戏' to "Hí", '诸' to "Chư", '侯' to "Hầu",
        '猫' to "Miêu", '腻' to "Nị", '唐' to "Đường", '家' to "Gia", '三' to "Tam",
        '少' to "Thiếu", '蚕' to "Tằm", '土' to "Thổ", '豆' to "Đậu", '辰' to "Thần",
        '东' to "Đông", '吃' to "Cật", '西' to "Tây", '红' to "Hồng", '柿' to "Thị",
        '耳' to "Nhĩ", '根' to "Căn",
        '那' to "Na", '么' to "Ma", '远' to "Viễn", '近' to "Cận", '这' to "Giá",
        '听' to "Thính", '幸' to "Hạnh", '福' to "Phúc", '当' to "Đương", '遇' to "Ngộ",
        '科' to "Khoa", '学' to "Học", '家' to "Gia", '谢' to "Tạ", '阮' to "Nguyễn",
        '安' to "An", '阳' to "Dương", '多' to "Đa", '睡' to "Thụy", '钟' to "Chung",
        '橘' to "Quất", '薯' to "Thự", '片' to "Phiến", '煮' to "Chử", '鱼' to "Ngư",
        '影' to "Ảnh", '梓' to "Tử", '璃' to "Ly", '喻' to "Dụ", '林' to "Lâm",
        '涯' to "Nhai", '钒' to "Phàn", '浅' to "Thiển", '夏' to "Hạ", '浮' to "Phù",
        '瓦' to "Ngõa", '弦' to "Huyền", '清' to "Thanh", '羽' to "Vũ", '衣' to "Y",
        '丁' to "Đinh", '任' to "Nhậm", '甘' to "Cam"
    )

    fun translateTitle(raw: String): String {
        if (raw.isBlank()) return ""
        val clean = raw.trim().removeSurrounding("《", "》").trim()
        if (!hasChinese(clean)) return clean
        if (titleDictionary.containsKey(clean)) return titleDictionary[clean]!!
        return transliterateHanzi(clean)
    }

    fun translateAuthor(raw: String): String {
        if (raw.isBlank()) return "Khuyết Danh"
        val clean = raw.trim().replace("作者：", "").replace("作者:", "").trim()
        if (!hasChinese(clean)) return clean
        if (authorDictionary.containsKey(clean)) return authorDictionary[clean]!!
        return transliterateHanzi(clean)
    }

    fun hasChinese(str: String): Boolean {
        return str.any { it.code in 0x4E00..0x9FFF }
    }

    private fun transliterateHanzi(text: String): String {
        val sb = StringBuilder()
        for (ch in text) {
            val vi = hanziMap[ch]
            if (vi != null) {
                if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
                sb.append(vi)
            } else if (ch.code in 0x4E00..0x9FFF) {
                // If unmapped Hanzi, skip or space
                continue
            } else {
                sb.append(ch)
            }
        }
        val res = sb.toString().trim().replace(Regex("\\s+"), " ")
        return if (res.isNotBlank()) res else text
    }
}
