package com.truyencloud.compose.screens.vocabulary

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.truyencloud.compose.theme.CardShape
import com.truyencloud.compose.theme.LocalCustomColors
import com.truyencloud.shared.api.CollocationItem
import com.truyencloud.shared.api.FlashcardArtResponse
import com.truyencloud.shared.data.SavedWord
import com.truyencloud.shared.srs.FsrsScheduler.Rating

@Composable
internal fun FlashcardInteractiveCard(
    modifier: Modifier = Modifier,
    word: SavedWord,
    rotation: Float,
    /** Chỉ khi thẻ đã lật (đã thấy đáp án) thì vuốt mới chấm điểm được. */
    canRate: Boolean,
    isClozeRevealed: Boolean,
    isGeneratingArt: Boolean,
    isReverseCard: Boolean = false,
    cachedArt: FlashcardArtResponse?,
    collocations: List<CollocationItem>,
    onFlip: () -> Unit,
    onReview: (Int) -> Unit,
    onSpeak: (String) -> Unit,
    onToggleClozeReveal: () -> Unit,
    onGenerateArt: (SavedWord) -> Unit
) {
    val c = LocalCustomColors.current
    val hapticFeedback = LocalHapticFeedback.current
    var dragOffsetX by remember { mutableStateOf(0f) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    var hasTriggeredHaptic by remember { mutableStateOf(false) }

    val animatedOffsetX by animateFloatAsState(targetValue = dragOffsetX, label = "dragX")
    val animatedOffsetY by animateFloatAsState(targetValue = dragOffsetY, label = "dragY")
    val tiltZ = (animatedOffsetX / 40f).coerceIn(-12f, 12f)

    // Viền chuyển xanh / đỏ khi kéo đủ xa để báo "Tốt" / "Quên"; bình thường chỉ là đường kẻ mảnh
    val swipeGood = canRate && animatedOffsetX > 40f
    val swipeAgain = canRate && animatedOffsetX < -40f
    val borderColor = when {
        swipeGood -> c.success
        swipeAgain -> c.error
        else -> c.hairline
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationX = animatedOffsetX
                translationY = animatedOffsetY
                rotationZ = tiltZ
                rotationY = rotation
                cameraDistance = 14f * density
            }
            .clip(CardShape)
            .background(c.card)
            .border(if (swipeGood || swipeAgain) 2.dp else 1.dp, borderColor, CardShape)
            .pointerInput(word.id, canRate) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragOffsetX += dragAmount.x
                        dragOffsetY += dragAmount.y
                        val absX = kotlin.math.abs(dragOffsetX)
                        if (canRate && absX >= 110f && !hasTriggeredHaptic) {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            hasTriggeredHaptic = true
                        } else if (absX < 70f) {
                            hasTriggeredHaptic = false
                        }
                    },
                    onDragEnd = {
                        // Vuốt chỉ chấm điểm SAU khi lật thẻ: phải = Tốt (không phải Dễ), trái = Quên.
                        // Chưa lật mà vuốt ngang thì thẻ chỉ bật về chỗ cũ, không ghi gì vào lịch ôn.
                        if (canRate && dragOffsetX > 130f) {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            onReview(Rating.GOOD.value)
                        } else if (canRate && dragOffsetX < -130f) {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                            onReview(Rating.AGAIN.value)
                        } else if (dragOffsetY < -110f) {
                            onFlip()
                        }
                        dragOffsetX = 0f
                        dragOffsetY = 0f
                    },
                    onDragCancel = {
                        dragOffsetX = 0f
                        dragOffsetY = 0f
                    }
                )
            }
            .clickable(role = Role.Button, onClickLabel = "Lật thẻ") { onFlip() }
            .padding(16.dp)
    ) {
        if (rotation <= 90f) {
            FlashcardFront(
                word = word,
                cachedArt = cachedArt,
                isClozeRevealed = isClozeRevealed,
                isGeneratingArt = isGeneratingArt,
                isReverseCard = isReverseCard,
                onSpeak = { onSpeak(word.word) },
                onToggleClozeReveal = onToggleClozeReveal,
                onGenerateArt = { onGenerateArt(word) }
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { rotationY = 180f }
            ) {
                FlashcardBack(
                    word = word,
                    cachedArt = cachedArt,
                    collocations = collocations,
                    onSpeak = { onSpeak(word.word) }
                )
            }
        }
    }
}
