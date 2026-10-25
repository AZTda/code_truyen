package com.truyencloud.shared.repo

import com.truyencloud.shared.data.SavedWord

/**
 * Phân loại trường nghĩa từ vựng (Vocabulary Domain Lexicons).
 */
object VocabularyDomainClassifier {

    val domainLexicons = mapOf(
        "WORKPLACE_CAREER" to listOf(
            "strategy", "conflict", "battle", "war", "betray", "deceit", "plot", "scheme", "power",
            "throne", "blade", "rival", "defeat", "ruthless", "tactical", "conspiracy", "ambush",
            "career", "business", "negotiate", "vengeance", "mưu", "chiến", "đối đầu", "phản trắc",
            "quyền lực", "sát khí", "tranh đoạt", "kế sách", "uy quyền", "thủ đoạn", "hạ bệ",
            "trừng phạt", "thao túng", "thắng bại", "công sở", "sự nghiệp", "đàm phán"
        ),
        "PSYCHOLOGY_EMOTION" to listOf(
            "affection", "love", "tender", "yearning", "longing", "melancholy", "nostalgia", "sorrow",
            "passion", "cherish", "tears", "parting", "heartbreak", "intimacy", "whisper", "devotion",
            "soul", "emotion", "feeling", "lãng mạn", "tình cảm", "hoài niệm", "vương vấn", "ly biệt",
            "tha thiết", "tương tư", "đau lòng", "nhớ nhung", "ngọt ngào", "khắc cốt", "ghi tâm",
            "ấm áp", "nước mắt", "nội tâm", "tâm lý", "cảm xúc", "buồn", "vui"
        ),
        "FATE_SERENDIPITY" to listOf(
            "ethereal", "mystic", "transcendence", "realm", "spirit", "divine", "immortal", "aura",
            "magic", "celestial", "cosmos", "enchant", "reincarnation", "sorcery", "alchemy",
            "serendipity", "destiny", "fate", "coincidence", "tu chân", "cảnh giới", "tiên đạo",
            "linh khí", "huyền ảo", "thần bí", "thiên địa", "độ kiếp", "hóa thần", "linh hồn",
            "pháp bảo", "bí tịch", "huyền bí", "phi thăng", "duyên phận", "may mắn", "tình cờ", "định mệnh"
        ),
        "TIME_TRANSIENCE" to listOf(
            "ephemeral", "transient", "mortality", "fleeting", "eternity", "decay",
            "passage", "twilight", "oblivion", "impermanence", "time",
            "thời gian", "phù du", "biến thiên", "chớp mắt", "sinh tử", "tang thương",
            "bạc đầu", "ngắn ngủi", "trôi qua", "vô thường", "chốc lát", "năm tháng", "khoảnh khắc"
        ),
        "AESTHETICS_SCENERY" to listOf(
            "etiquette", "demeanor", "banquet", "elegance", "courteous", "diplomacy", "noble", "dialogue",
            "manner", "prestige", "aristocracy", "propriety", "polite", "dignity", "grace",
            "scenery", "landscape", "picturesque", "lễ nghi", "phong thái", "giao tế", "quý phái",
            "thanh tao", "khách sáo", "tao nhã", "xã giao", "khí chất", "điềm đạm", "thượng lưu",
            "phép tắc", "thận trọng", "cảnh sắc", "mỹ cảm", "phong cảnh", "hùng vĩ"
        ),
        "INTELLECT_ACADEMIA" to listOf(
            "wisdom", "philosophy", "intellect", "epiphany", "doctrine", "scholar", "enlightenment",
            "truth", "virtue", "erudite", "insight", "contemplation", "profound", "logic",
            "theory", "academic", "triết lý", "uyên bác", "ngộ ra", "nhân sinh", "tri thức",
            "học vấn", "chân lý", "giác ngộ", "thấu triệt", "chiêm nghiệm", "thâm sâu",
            "nghiền ngẫm", "học thuật", "nghiên cứu"
        )
    )

    private val domainCache = mutableMapOf<String, String>()

    fun getWordDomain(savedWord: SavedWord): String = domainCache.getOrPut(savedWord.id) {
        val corpus = buildString {
            append(savedWord.word); append(' ')
            append(savedWord.contextMeaningVi); append(' ')
            append(savedWord.englishDefinition); append(' ')
            append(savedWord.contextSentence)
        }.lowercase()

        var bestDomain = "INTELLECT_ACADEMIA"
        var maxScore = 0
        for ((domain, keywords) in domainLexicons) {
            var score = 0
            for (keyword in keywords) {
                if (corpus.contains(keyword)) score += if (keyword.length > 5) 2 else 1
            }
            if (score > maxScore) {
                maxScore = score
                bestDomain = domain
            }
        }
        bestDomain
    }
}
