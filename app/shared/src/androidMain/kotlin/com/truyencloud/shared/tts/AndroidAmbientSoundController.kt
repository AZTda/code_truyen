package com.truyencloud.shared.tts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidAmbientSoundController {
    val engine = AmbientSoundEngine()

    private val _ambientSoundType = MutableStateFlow(AmbientSoundType.NONE)
    val ambientSoundType: StateFlow<AmbientSoundType> = _ambientSoundType.asStateFlow()

    private val _ambientSoundVolume = MutableStateFlow(0.25f)
    val ambientSoundVolume: StateFlow<Float> = _ambientSoundVolume.asStateFlow()

    fun setAmbientSound(type: AmbientSoundType, volume: Float, isPlaying: Boolean, multiplier: Float) {
        _ambientSoundType.value = type
        _ambientSoundVolume.value = volume.coerceIn(0f, 1f)
        if (type == AmbientSoundType.NONE) {
            engine.stop()
        } else if (isPlaying) {
            engine.start(type, _ambientSoundVolume.value * multiplier)
        }
    }

    fun setVolume(volume: Float, multiplier: Float) {
        _ambientSoundVolume.value = volume.coerceIn(0f, 1f)
        engine.setVolume(_ambientSoundVolume.value * multiplier)
    }

    /** Áp hệ số giảm âm (hẹn giờ ngủ / nhường tiếng) mà không đổi mức người dùng đã chọn. */
    fun applyMultiplier(multiplier: Float) {
        engine.setVolume(_ambientSoundVolume.value * multiplier)
    }

    fun startIfActive(isPlaying: Boolean, multiplier: Float) {
        if (_ambientSoundType.value != AmbientSoundType.NONE && isPlaying) {
            engine.start(_ambientSoundType.value, _ambientSoundVolume.value * multiplier)
        }
    }

    fun stop() {
        engine.stop()
    }
}
