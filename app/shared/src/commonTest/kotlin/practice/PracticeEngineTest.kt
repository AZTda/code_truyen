package com.truyencloud.shared.practice

import com.truyencloud.shared.data.SavedWord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PracticeEngineTest {

    private val word = SavedWord(
        id = "word_ephemeral",
        word = "ephemeral",
        lemma = "ephemeral",
        contextMeaningVi = "phù du, ngắn ngủi",
        contextSentence = "The ephemeral light faded before she could speak.",
        bookTitle = "Chuyện Tình Boston",
        chapterNumber = 12,
        stability = 5.0
    )

    @Test
    fun taoBaiDienKhuyetTuCauThatTrongTruyen() {
        assertTrue(word.hasBookContext)
        val item = PracticeEngine.build(word, PracticeMode.CLOZE)
        assertNotNull(item)
        assertFalse(
            item.promptText.contains("ephemeral", ignoreCase = true),
            "Từ đích phải bị che"
        )
        assertTrue(item.promptText.contains("faded"), "Phần còn lại của câu phải giữ nguyên")
        assertEquals("ephemeral", item.answer)
    }

    @Test
    fun khongCoCauGocThiKhongTaoDuocDienKhuyet() {
        val noContext = word.copy(contextSentence = "")
        assertFalse(noContext.hasBookContext)
        assertNull(PracticeEngine.build(noContext, PracticeMode.CLOZE))
    }

    @Test
    fun cheTuChiKhopTronRanhGioiTu() {
        // "ephemerality" chứa "ephemeral" nhưng không phải cùng một từ
        assertNull(PracticeEngine.maskWordInSentence("The ephemerality was clear.", "ephemeral"))
        assertNotNull(PracticeEngine.maskWordInSentence("It was ephemeral.", "ephemeral"))
    }

    @Test
    fun chamBaiGoTayChoPhepSaiChinhTaNhe() {
        assertTrue(PracticeEngine.gradeTyped("ephemeral", "ephemeral").isCorrect)
        assertTrue(PracticeEngine.gradeTyped("ephemeral", " Ephemeral ").isCorrect)

        val near = PracticeEngine.gradeTyped("ephemeral", "ephemerel")
        assertTrue(near.isCorrect, "Sai 1 ký tự vẫn là nhớ được — đây là bài kiểm tra trí nhớ, không phải đánh máy")
        assertTrue(near.isNearMiss)
        assertEquals(2, near.suggestedRating)

        val wrong = PracticeEngine.gradeTyped("ephemeral", "beautiful")
        assertFalse(wrong.isCorrect)
        assertEquals(1, wrong.suggestedRating)

        assertFalse(PracticeEngine.gradeTyped("ephemeral", "").isCorrect)
    }

    @Test
    fun chamBaiNgheChepTheoTyLeTuDung() {
        val sentence = "The ephemeral light faded before she could speak."

        val perfect = PracticeEngine.gradeDictation(sentence, sentence)
        assertEquals(4, perfect.suggestedRating)

        val oneWordOff = PracticeEngine.gradeDictation(
            sentence, "The ephemeral light faded before she could talk."
        )
        assertTrue(oneWordOff.isCorrect, "Sai một từ vẫn tính là nghe được")

        val bad = PracticeEngine.gradeDictation(sentence, "I have no idea")
        assertFalse(bad.isCorrect)
    }

    @Test
    fun chonCheDoTheoMucDoThuoc() {
        assertEquals(PracticeMode.RECOGNITION, PracticeEngine.suggestMode(word.copy(stability = 0.0)))
        assertEquals(PracticeMode.CLOZE, PracticeEngine.suggestMode(word.copy(stability = 8.0)))
        assertEquals(PracticeMode.TYPING, PracticeEngine.suggestMode(word.copy(stability = 20.0)))
    }

    // -----------------------------------------------------------------------
    // Chống "chơi lịch" bằng bài luyện tập
    // -----------------------------------------------------------------------

    private val meanings = listOf("xinh đẹp", "con ngựa", "chạy nhanh", "buồn bã")

    @Test
    fun nhanDienKhongHienDapAnTrongDe() {
        val item = PracticeEngine.build(word, PracticeMode.RECOGNITION, meaningDistractors = meanings)
        assertNotNull(item)
        assertEquals("ephemeral", item.promptText, "Đề là từ tiếng Anh cần nhận ra nghĩa")
        assertEquals("phù du, ngắn ngủi", item.answer, "Đáp án là NGHĨA, không phải chính từ đang hiện")
        assertFalse(item.promptText.contains(item.answer), "Đáp án không được nằm trong đề")
        assertTrue(item.options.size >= 3 && item.answer in item.options)
        assertEquals(item.answer, item.options[item.correctOptionIndex])
        assertTrue(item.options.count { it == item.answer } == 1)
    }

    @Test
    fun nhanDienThieuDuLieuThiKhongDungDeGianDon() {
        assertNull(PracticeEngine.build(word, PracticeMode.RECOGNITION, meaningDistractors = listOf("một")))
        assertNull(PracticeEngine.build(word.copy(contextMeaningVi = ""), PracticeMode.RECOGNITION, meaningDistractors = meanings))
    }

    @Test
    fun phatAmKhongTuChoDiemDeVaKhongBaoGioQuaTot() {
        val item = PracticeEngine.build(word, PracticeMode.PRONUNCIATION)
        assertNotNull(item)
        assertTrue(item.options.isEmpty(), "Phát âm không có ô gõ / lựa chọn để gian lận")
        assertEquals(3, PracticeEngine.gradePronunciation("ephemeral", 100).suggestedRating)
        assertEquals(3, PracticeEngine.gradePronunciation("ephemeral", 85).suggestedRating)
        assertEquals(2, PracticeEngine.gradePronunciation("ephemeral", 70).suggestedRating)
        assertEquals(1, PracticeEngine.gradePronunciation("ephemeral", 30).suggestedRating)
        assertFalse(PracticeEngine.gradePronunciation("ephemeral", 30).isCorrect)
    }

    @Test
    fun ngheChepPhaiChuaTuDich() {
        val sentence = "The ephemeral light faded before she could speak."
        // chép đúng 100% trừ chính từ cần học
        val missingTarget = PracticeEngine.gradeDictation(
            sentence, "The light faded before she could speak.", targetWord = "ephemeral"
        )
        assertTrue(missingTarget.suggestedRating <= 2, "Thiếu từ đích thì tối đa Khó: ${missingTarget.suggestedRating}")
        assertTrue(missingTarget.feedback.contains("ephemeral"))

        val withTarget = PracticeEngine.gradeDictation(sentence, sentence, targetWord = "ephemeral")
        assertEquals(4, withTarget.suggestedRating)

        // sai chính tả nhẹ vẫn tính là có từ đích
        val typo = PracticeEngine.gradeDictation(
            sentence, "The ephemerel light faded before she could speak.", targetWord = "ephemeral"
        )
        assertTrue(typo.suggestedRating >= 3)

        // không truyền từ đích -> giữ hành vi cũ
        assertEquals(4, PracticeEngine.gradeDictation(sentence, sentence).suggestedRating)
    }

    @Test
    fun ngheChepTuDichDangChiaVanTinh() {
        val sentence = "She was running through the rain."
        val r = PracticeEngine.gradeDictation(sentence, sentence, targetWord = "run")
        assertEquals(4, r.suggestedRating)
        val missing = PracticeEngine.gradeDictation(sentence, "She was through the rain.", targetWord = "run")
        assertTrue(missing.suggestedRating <= 2)
    }

    @Test
    fun dienKhuyetCheCaDangChia() {
        val masked = PracticeEngine.maskWordInSentence("She was running through the rain.", "run")
        assertNotNull(masked)
        assertTrue(masked.text.contains(PracticeEngine.BLANK))
        assertFalse(masked.text.contains("running"))
        assertEquals("running", masked.answer, "Đáp án là chính chữ trong câu")
        // dạng đúng nguyên văn thì vẫn ưu tiên khớp chính xác
        assertEquals("ephemeral", PracticeEngine.maskWordInSentence("It was ephemeral.", "ephemeral")?.answer)
        assertTrue(PracticeEngine.isClozeReady(word.copy(word = "run", contextSentence = "He ran home.")))
        // Sở hữu cách trên dạng chia không bắt người học gõ cả "'s"
        val possessive = PracticeEngine.maskWordInSentence("The children's laughter echoed.", "child")
        assertNotNull(possessive)
        assertEquals("children", possessive.answer)
        assertTrue(possessive.text.contains("${PracticeEngine.BLANK}'s"))
    }

    @Test
    fun goiYChuCaiDauChoBietDoDai() {
        val hint = PracticeEngine.firstLetterHint("ephemeral")
        assertTrue(hint.startsWith("e "))
        assertTrue(hint.contains("9"), "Phải cho biết từ dài 9 chữ: $hint")
    }

    @Test
    fun khoangCachLevenshtein() {
        assertEquals(0, PracticeEngine.levenshtein("run", "run"))
        assertEquals(1, PracticeEngine.levenshtein("run", "runs"))
        assertEquals(3, PracticeEngine.levenshtein("", "abc"))
    }

    @Test
    fun demSoTheSanSangChoBaiDienKhuyet() {
        val words = listOf(word, word.copy(id = "b", word = "x", contextSentence = ""))
        assertEquals(1, PracticeEngine.clozeReadyCount(words))
    }
}
