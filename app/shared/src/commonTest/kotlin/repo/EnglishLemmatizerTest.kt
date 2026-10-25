package com.truyencloud.shared.repo

import kotlin.test.Test
import kotlin.test.assertEquals

class EnglishLemmatizerTest {

    /** Liệt kê TẤT CẢ từ sai (thay vì dừng ở từ đầu tiên) để sửa được cả loạt trong một lần chạy. */
    private fun assertLemmas(vararg pairs: Pair<String, String>) {
        val bad = pairs
            .filter { (word, expected) -> EnglishLemmatizer.lemmatize(word) != expected }
            .map { (word, expected) -> "$word -> ${EnglishLemmatizer.lemmatize(word)} (cần $expected)" }
        assertEquals(emptyList<String>(), bad)
    }

    @Test
    fun cacLoiCuTrongBaoCao() {
        assertLemmas(
            "playing" to "play", "reading" to "read", "sitting" to "sit", "smiled" to "smile",
            "horses" to "horse", "news" to "news", "during" to "during", "evening" to "evening"
        )
    }

    @Test
    fun dangBatQuyTac() {
        assertLemmas(
            "ran" to "run", "went" to "go", "better" to "good", "best" to "good", "worse" to "bad",
            "children" to "child", "men" to "man", "women" to "woman", "feet" to "foot", "people" to "person",
            "was" to "be", "were" to "be", "been" to "be", "is" to "be", "had" to "have", "did" to "do",
            "gave" to "give", "taken" to "take", "saw" to "see", "knew" to "know", "thought" to "think",
            "left" to "leave", "found" to "find", "told" to "tell", "sat" to "sit", "spoke" to "speak",
            "wrote" to "write", "written" to "write", "began" to "begin", "broken" to "break"
        )
    }

    @Test
    fun hauToIngDoiPhuAmVaEcam() {
        assertLemmas(
            "sitting" to "sit", "running" to "run", "swimming" to "swim", "stopping" to "stop",
            "planning" to "plan", "beginning" to "begin", "cutting" to "cut", "putting" to "put",
            "smiling" to "smile", "writing" to "write", "dancing" to "dance", "using" to "use",
            "closing" to "close", "arriving" to "arrive", "changing" to "change", "singing" to "sing",
            "ringing" to "ring", "bringing" to "bring", "helping" to "help", "looking" to "look",
            "studying" to "study", "crying" to "cry", "playing" to "play", "dying" to "die", "lying" to "lie",
            "continuing" to "continue", "feelings" to "feel", "meetings" to "meet"
        )
    }

    @Test
    fun hauToEdDoiPhuAmVaEcam() {
        assertLemmas(
            "smiled" to "smile", "hoped" to "hope", "hopped" to "hop", "stopped" to "stop",
            "planned" to "plan", "preferred" to "prefer", "occurred" to "occur", "added" to "add",
            "nodded" to "nod", "created" to "create", "decided" to "decide", "included" to "include",
            "invited" to "invite", "visited" to "visit", "limited" to "limit", "waited" to "wait",
            "repeated" to "repeat", "agreed" to "agree", "needed" to "need", "loved" to "love",
            "used" to "use", "called" to "call", "controlled" to "control", "cancelled" to "cancel",
            "travelled" to "travel", "changed" to "change", "belonged" to "belong", "managed" to "manage",
            "judged" to "judge", "noticed" to "notice", "raised" to "raise", "pleased" to "please",
            "focused" to "focus", "passed" to "pass", "watched" to "watch", "fixed" to "fix",
            "played" to "play", "studied" to "study", "tried" to "try", "carried" to "carry",
            "opened" to "open", "listened" to "listen", "whispered" to "whisper", "described" to "describe",
            "settled" to "settle", "struggled" to "struggle", "determined" to "determine",
            "prepared" to "prepare", "appeared" to "appear", "required" to "require", "assumed" to "assume",
            "evaluated" to "evaluate", "hesitated" to "hesitate", "walked" to "walk", "looked" to "look"
        )
    }

    @Test
    fun soNhieuVaNgoiThuBa() {
        assertLemmas(
            "horses" to "horse", "houses" to "house", "uses" to "use", "causes" to "cause", "buses" to "bus",
            "viruses" to "virus", "boxes" to "box", "churches" to "church", "wishes" to "wish",
            "classes" to "class", "glasses" to "glass", "heroes" to "hero", "shoes" to "shoe",
            "potatoes" to "potato", "movies" to "movie", "cookies" to "cookie", "cities" to "city",
            "studies" to "study", "flies" to "fly", "babies" to "baby", "stories" to "story",
            "leaves" to "leave", "lives" to "live", "knives" to "knife", "wolves" to "wolf",
            "headaches" to "headache", "days" to "day", "keys" to "key", "hands" to "hand",
            "courses" to "course", "senses" to "sense", "cases" to "case", "phrases" to "phrase",
            "changes" to "change", "faces" to "face", "thoughts" to "think"
        )
    }

    @Test
    fun tuVonLaNguyenTheGiuNguyen() {
        assertLemmas(
            "news" to "news", "series" to "series", "species" to "species", "physics" to "physics",
            "politics" to "politics", "always" to "always", "perhaps" to "perhaps", "during" to "during",
            "evening" to "evening", "morning" to "morning", "nothing" to "nothing", "something" to "something",
            "ceiling" to "ceiling", "wedding" to "wedding", "thing" to "thing", "string" to "string",
            "spring" to "spring", "king" to "king", "bus" to "bus", "glass" to "glass", "class" to "class",
            "focus" to "focus", "analysis" to "analysis", "bias" to "bias", "lens" to "lens",
            "hundred" to "hundred", "sacred" to "sacred", "naked" to "naked", "indeed" to "indeed",
            "speed" to "speed", "feed" to "feed", "need" to "need", "beautiful" to "beautiful",
            "happiness" to "happiness", "quickly" to "quickly"
        )
    }

    @Test
    fun chuHoaDauCauVaDauCau() {
        assertEquals("smile", EnglishLemmatizer.lemmatize("Smiled"))
        assertEquals("run", EnglishLemmatizer.lemmatize("\"Running,\""))
        assertEquals("john", EnglishLemmatizer.lemmatize("John's"))
        assertEquals("", EnglishLemmatizer.lemmatize("   "))
        // tiếng Việt / số / từ ghép giữ nguyên
        assertEquals("xin chào", EnglishLemmatizer.lemmatize("xin chào"))
        assertEquals("well-known", EnglishLemmatizer.lemmatize("well-known"))
    }

    @Test
    fun cungMotLemmaChoMoiDangChia() {
        val forms = listOf("run", "runs", "ran", "running")
        assertEquals(setOf("run"), forms.map { EnglishLemmatizer.lemmatize(it) }.toSet())
        val smile = listOf("smile", "smiles", "smiled", "smiling")
        assertEquals(setOf("smile"), smile.map { EnglishLemmatizer.lemmatize(it) }.toSet())
    }

    @Test
    fun daiTuSoHuuTrangTuNoiChonVaTenBenhKhongPhaiSoNhieu() {
        assertLemmas(
            "yours" to "yours", "ours" to "ours", "hers" to "hers", "theirs" to "theirs",
            "upstairs" to "upstairs", "downstairs" to "downstairs", "indoors" to "indoors", "outdoors" to "outdoors",
            "diabetes" to "diabetes", "measles" to "measles", "rabies" to "rabies"
        )
    }

    @Test
    fun chuTanCungBangTzesBoEsKhiGocLaZ() {
        assertLemmas(
            "waltzes" to "waltz", "blitzes" to "blitz", "quartzes" to "quartz",
            // cùng một thẻ cho mọi dạng chia của waltz
            "waltzed" to "waltz", "waltzing" to "waltz", "blitzed" to "blitz", "blitzing" to "blitz",
            // chữ z đơn vẫn giữ "e" của gốc
            "sizes" to "size", "prizes" to "prize", "gazes" to "gaze", "buzzes" to "buzz", "quizzes" to "quiz"
        )
    }

    @Test
    fun cacDangMoiSuaVanLaIdempotent() {
        val words = listOf("yours", "ours", "hers", "theirs", "upstairs", "downstairs", "indoors", "outdoors",
            "diabetes", "measles", "rabies", "waltzes", "blitzes")
        for (w in words) {
            val once = EnglishLemmatizer.lemmatize(w)
            assertEquals(once, EnglishLemmatizer.lemmatize(once), "lemmatize không idempotent với $w")
        }
    }
}

