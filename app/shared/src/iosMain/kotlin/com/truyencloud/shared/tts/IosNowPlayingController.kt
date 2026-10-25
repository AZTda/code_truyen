@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.truyencloud.shared.tts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.MediaPlayer.MPMediaItemArtwork
import platform.MediaPlayer.MPMediaItemPropertyAlbumTitle
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyArtwork
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyDefaultPlaybackRate
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackQueueCount
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackQueueIndex
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPNowPlayingPlaybackStatePaused
import platform.MediaPlayer.MPNowPlayingPlaybackStatePlaying
import platform.MediaPlayer.MPRemoteCommand
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import platform.UIKit.UIImage
import kotlin.time.DurationUnit
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Màn hình khoá / Control Center / tai nghe: nút phát-dừng-đoạn trước-đoạn sau
 * (MPRemoteCommandCenter) và thông tin đang phát (MPNowPlayingInfoCenter).
 */
internal class IosNowPlayingController(
    private val scope: CoroutineScope,
    private val onPlay: () -> Unit,
    private val onPause: () -> Unit,
    private val onToggle: () -> Unit,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit
) {
    private val bound = mutableListOf<Pair<MPRemoteCommand, Any>>()
    private var artwork: MPMediaItemArtwork? = null
    private var artworkUrl: String? = null
    private var artworkJob: Job? = null

    // "Thời gian đã nghe" của phiên: cộng dồn khi đang phát, hệ thống tự ngoại suy theo rate
    private var listenedSeconds = 0.0
    private var playingSince: TimeMark? = null

    val isActive: Boolean get() = bound.isNotEmpty()

    fun activate() {
        if (bound.isNotEmpty()) return
        val center = MPRemoteCommandCenter.sharedCommandCenter()
        bind(center.playCommand, onPlay)
        bind(center.pauseCommand, onPause)
        bind(center.togglePlayPauseCommand, onToggle)
        bind(center.nextTrackCommand, onNext)
        bind(center.previousTrackCommand, onPrevious)
    }

    private fun bind(command: MPRemoteCommand, action: () -> Unit) {
        command.enabled = true
        val token = command.addTargetWithHandler { _ ->
            action()
            MPRemoteCommandHandlerStatusSuccess
        }
        bound += command to token
    }

    fun resetElapsed() {
        listenedSeconds = 0.0
        playingSince = null
    }

    fun update(chapterTitle: String, novelTitle: String, playing: Boolean, number: Int, total: Int) {
        val since = playingSince
        if (since != null) listenedSeconds += since.elapsedNow().toDouble(DurationUnit.SECONDS)
        playingSince = if (playing) TimeSource.Monotonic.markNow() else null

        val info = mutableMapOf<Any?, Any?>(
            MPMediaItemPropertyTitle to chapterTitle,
            MPMediaItemPropertyArtist to novelTitle,
            MPMediaItemPropertyAlbumTitle to novelTitle,
            MPNowPlayingInfoPropertyElapsedPlaybackTime to listenedSeconds,
            MPNowPlayingInfoPropertyPlaybackRate to (if (playing) 1.0 else 0.0),
            MPNowPlayingInfoPropertyDefaultPlaybackRate to 1.0
        )
        if (total > 0) {
            info[MPNowPlayingInfoPropertyPlaybackQueueIndex] = (number - 1).coerceAtLeast(0)
            info[MPNowPlayingInfoPropertyPlaybackQueueCount] = total
        }
        artwork?.let { info[MPMediaItemPropertyArtwork] = it }
        val center = MPNowPlayingInfoCenter.defaultCenter()
        center.nowPlayingInfo = info
        center.playbackState = if (playing) MPNowPlayingPlaybackStatePlaying else MPNowPlayingPlaybackStatePaused
    }

    fun setArtwork(url: String?, onLoaded: () -> Unit) {
        if (url == artworkUrl) return
        artworkUrl = url
        artworkJob?.cancel()
        artwork = null
        if (url.isNullOrBlank()) return
        artworkJob = scope.launch {
            val data = withContext(Dispatchers.Default) { loadData(url) }
            val image = data?.let { UIImage.imageWithData(it) }
            if (image != null && artworkUrl == url) {
                artwork = MPMediaItemArtwork(image = image)
                onLoaded()
            }
        }
    }

    private fun loadData(url: String): NSData? = try {
        NSURL.URLWithString(url)?.let { NSData.dataWithContentsOfURL(it) }
    } catch (_: Exception) {
        null
    }

    fun deactivate() {
        bound.forEach { (command, token) ->
            command.removeTarget(token)
            command.enabled = false
        }
        bound.clear()
        artworkJob?.cancel()
        val center = MPNowPlayingInfoCenter.defaultCenter()
        center.nowPlayingInfo = null
    }
}
