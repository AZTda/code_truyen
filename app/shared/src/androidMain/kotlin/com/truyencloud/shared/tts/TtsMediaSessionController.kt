package com.truyencloud.shared.tts

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState

/**
 * MediaSession cho màn hình khoá, tai nghe, Bluetooth, Android Auto.
 *
 * onPlay / onPause là HAI lệnh riêng: trước đây cả hai đều "đảo trạng thái", nên
 * thiết bị gửi Pause khi đang dừng sẵn lại làm truyện tự phát.
 */
internal class TtsMediaSessionController(context: Context) {

    private val callback = object : MediaSession.Callback() {
        override fun onPlay() { TtsServiceBridge.callback?.onRemotePlay() }
        override fun onPause() { TtsServiceBridge.callback?.onRemotePause() }
        override fun onSkipToNext() { TtsServiceBridge.callback?.onRemoteNext() }
        override fun onSkipToPrevious() { TtsServiceBridge.callback?.onRemotePrev() }
        override fun onStop() { TtsServiceBridge.callback?.onRemoteStop() }
    }

    val session: MediaSession? = try {
        MediaSession(context, "TruyenCloudTtsSession").apply {
            setCallback(callback)
            isActive = true
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }

    fun update() {
        val s = session ?: return
        val b = TtsServiceBridge
        try {
            val playing = b.isPlaying
            val actions = (if (playing) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY) or
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_STOP
            s.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(actions)
                    // Không có mốc thời gian thật (mỗi đoạn là một luồng riêng) -> báo UNKNOWN
                    .setState(
                        if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        if (playing) b.playbackSpeed else 0f
                    )
                    .build()
            )

            val novel = b.novelTitle.ifBlank { "TruyenCloud" }
            val chapter = b.chapterTitle.ifBlank { "Chương đọc" }
            val meta = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, chapter)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, chapter)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, novel)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, novel)
                .putString(
                    MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                    if (b.paragraphInfo.isNotBlank()) "$novel • ${b.paragraphInfo}" else novel
                )
            if (b.paragraphTotal > 0) {
                meta.putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, b.paragraphNumber.toLong())
                meta.putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, b.paragraphTotal.toLong())
            }
            b.artwork?.let {
                meta.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
                meta.putBitmap(MediaMetadata.METADATA_KEY_ART, it)
            }
            s.setMetadata(meta.build())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun release() {
        try {
            session?.isActive = false
            session?.release()
        } catch (_: Exception) {}
    }
}
