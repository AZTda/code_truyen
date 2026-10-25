package com.truyencloud.shared.tts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NoOpTtsSpeaker : TtsSpeaker {
    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentParagraphIndex = MutableStateFlow(0)
    override val currentParagraphIndex: StateFlow<Int> = _currentParagraphIndex.asStateFlow()

    private val _speed = MutableStateFlow(1.0f)
    override val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _currentVoiceProfile = MutableStateFlow(TtsVoiceCatalog.defaultProfile())
    override val currentVoiceProfile: StateFlow<TtsVoiceProfile> = _currentVoiceProfile.asStateFlow()

    override fun setVoiceProfile(profileId: String) {
        _currentVoiceProfile.value = TtsVoiceResolver.profileFor(profileId)
    }

    override fun setVoiceTone(tone: String) {
        _currentVoiceProfile.value = TtsVoiceResolver.profileFor(TtsVoiceResolver.resolveToneProfileId(tone))
    }

    override fun speak(paragraphs: List<String>, startIndex: Int) {
        _currentParagraphIndex.value = startIndex
        _isPlaying.value = true
    }

    override fun prepare(paragraphs: List<String>, startIndex: Int) {
        if (_isPlaying.value) speak(paragraphs, startIndex) else _currentParagraphIndex.value = startIndex
    }

    override fun pause() {
        _isPlaying.value = false
    }

    override fun resume() {
        _isPlaying.value = true
    }

    override fun stop() {
        _isPlaying.value = false
        _currentParagraphIndex.value = 0
    }

    override fun setSpeed(speed: Float) {
        _speed.value = speed
    }

    override fun nextParagraph() {
        _currentParagraphIndex.value += 1
    }

    override fun prevParagraph() {
        if (_currentParagraphIndex.value > 0) {
            _currentParagraphIndex.value -= 1
        }
    }
}
