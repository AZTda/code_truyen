package com.truyencloud.compose.components.icons

import androidx.compose.ui.graphics.vector.ImageVector

/** Sách nói và điều khiển âm thanh. */
interface AudioIcons {
    val PlayArrow: ImageVector get() = AudioHolder.playArrow
    val PlayFilled: ImageVector get() = AudioHolder.playFilled
    val Pause: ImageVector get() = AudioHolder.pause
    val PauseFilled: ImageVector get() = AudioHolder.pauseFilled
    val Stop: ImageVector get() = AudioHolder.stop
    val VolumeUp: ImageVector get() = AudioHolder.volumeUp
    val VolumeMute: ImageVector get() = AudioHolder.volumeMute
    val VolumeOff: ImageVector get() = AudioHolder.volumeOff
    val Shuffle: ImageVector get() = AudioHolder.shuffle
    val Headphones: ImageVector get() = AudioHolder.headphones
    val Tune: ImageVector get() = AudioHolder.tune
    val SkipPrevious: ImageVector get() = AudioHolder.skipPrevious
    val SkipNext: ImageVector get() = AudioHolder.skipNext
    val Timer: ImageVector get() = AudioHolder.timer
    val RecordVoiceOver: ImageVector get() = AudioHolder.recordVoiceOver
    val GraphicEq: ImageVector get() = AudioHolder.graphicEq
}

private const val PLAY = "M7.5 5.2v13.6a.6.6 0 0 0 .9.5l11-6.8a.6.6 0 0 0 0-1l-11-6.8a.6.6 0 0 0-.9.5Z"
private const val PAUSE_L = "M7 5.5h2.5A.5.5 0 0 1 10 6v12a.5.5 0 0 1-.5.5H7a.5.5 0 0 1-.5-.5V6A.5.5 0 0 1 7 5.5Z"
private const val PAUSE_R = "M14.5 5.5H17a.5.5 0 0 1 .5.5v12a.5.5 0 0 1-.5.5h-2.5a.5.5 0 0 1-.5-.5V6a.5.5 0 0 1 .5-.5Z"

private object AudioHolder {
    val playArrow by lazyIcon("PlayArrow", PLAY)
    val playFilled by lazyIcon("PlayFilled", "b:$PLAY")
    val pause by lazyIcon("Pause", PAUSE_L, PAUSE_R)
    val pauseFilled by lazyIcon("PauseFilled", "b:$PAUSE_L", "b:$PAUSE_R")
    val stop by lazyIcon("Stop", "M7 5.5h10A1.5 1.5 0 0 1 18.5 7v10a1.5 1.5 0 0 1-1.5 1.5H7A1.5 1.5 0 0 1 5.5 17V7A1.5 1.5 0 0 1 7 5.5Z")
    val volumeUp by lazyIcon("VolumeUp", SPEAKER, "M15.5 9a4.2 4.2 0 0 1 0 6", "M18.5 6a8.5 8.5 0 0 1 0 12")
    val volumeMute by lazyIcon("VolumeMute", SPEAKER)
    val volumeOff by lazyIcon("VolumeOff", SPEAKER, "M16 9.5l5 5 M21 9.5l-5 5")
    val shuffle by lazyIcon(
        "Shuffle",
        "M2 18h1.4c1.3 0 2.5-.6 3.3-1.7l6.1-8.6c.7-1.1 2-1.7 3.3-1.7H22", "m18 2 4 4-4 4",
        "M2 6h1.9c1.5 0 2.9.9 3.6 2.2", "M22 18h-5.9c-1.3 0-2.6-.7-3.3-1.8l-.5-.8", "m18 14 4 4-4 4"
    )
    val headphones by lazyIcon(
        "Headphones",
        "M4 15v-3a8 8 0 0 1 16 0v3",
        "M4 15h2.5a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1H6a2 2 0 0 1-2-2Z",
        "M20 15h-2.5a1 1 0 0 0-1 1v3a1 1 0 0 0 1 1H18a2 2 0 0 0 2-2Z"
    )
    val tune by lazyIcon(
        "Tune",
        "M4 7h9 M17 7h3 M4 17h3 M11 17h9",
        "M15 9.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z", "M9 19.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z"
    )
    val skipPrevious by lazyIcon("SkipPrevious", "M6.5 5.5v13", "M18.5 6.2v11.6a.5.5 0 0 1-.8.4l-8-5.8a.5.5 0 0 1 0-.8l8-5.8a.5.5 0 0 1 .8.4Z")
    val skipNext by lazyIcon("SkipNext", "M17.5 5.5v13", "M5.5 6.2v11.6a.5.5 0 0 0 .8.4l8-5.8a.5.5 0 0 0 0-.8l-8-5.8a.5.5 0 0 0-.8.4Z")
    val timer by lazyIcon("Timer", "M12 21a8 8 0 1 0 0-16 8 8 0 0 0 0 16Z", "M12 9v4l2.5 1.5", "M10 2.5h4", "M18.5 6.5L20 5")
    val recordVoiceOver by lazyIcon(
        "RecordVoiceOver",
        "M9 12a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Z",
        "M3 20c.5-3.2 3-5.2 6-5.2s5.5 2 6 5.2",
        "M16.5 7.5a3.5 3.5 0 0 1 0 5",
        "M19 5a7 7 0 0 1 0 10"
    )
    val graphicEq by lazyIcon(
        "GraphicEq",
        "M4 10v4", "M8 7v10", "M12 4v16", "M16 7v10", "M20 10v4"
    )
}
