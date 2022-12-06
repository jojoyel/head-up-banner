package com.jojo.head_up_banner

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

class HeadUpBannerHostState {

    private val mutex = Mutex()

    var currentHeadUpBannerData by mutableStateOf<HeadUpBannerData?>(null)
        private set

    suspend fun showHeadUpBanner(
        content: @Composable () -> Unit,
        closeable: Boolean = false,
        icon: @Composable () -> Unit = {}
    ): HeadUpBannerResult = mutex.withLock {
        try {
            return suspendCancellableCoroutine { continuation ->
                currentHeadUpBannerData = HeadUpDataImpl(content, closeable, icon, continuation)
            }
        } finally {
            currentHeadUpBannerData = null
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private class HeadUpDataImpl(
        override val content: @Composable () -> Unit,
        override val closeable: Boolean,
        override val icon: @Composable () -> Unit,
        private val continuation: CancellableContinuation<HeadUpBannerResult>,
        override val duration: HeadUpBannerDuration = HeadUpBannerDuration.Normal,
        override val clickable: Boolean = false
    ) : HeadUpBannerData {
        override fun onClick() {
            if (clickable && continuation.isActive) continuation.resume(HeadUpBannerResult.Clicked) {}
        }

        override fun dismiss() {
            if (continuation.isActive) continuation.resume(HeadUpBannerResult.Closed) {}
        }
    }
}

@Composable
fun HeadUpBannerHost(
    hostState: HeadUpBannerHostState,
    modifier: Modifier = Modifier,
    headUpBanner: @Composable (HeadUpBannerData) -> Unit = {
        HeadUpBanner(
            modifier = modifier,
            icon = it.icon,
            onClick = {},
            canClose = it.closeable,
            content = it.content
        )
    }
) {
    val currentHeadUpBannerDataList = hostState.currentHeadUpBannerData
    val accessibilityManager = LocalAccessibilityManager.current

    LaunchedEffect(currentHeadUpBannerDataList) {
        if (currentHeadUpBannerDataList != null) {
            val duration = currentHeadUpBannerDataList.duration.toMillis(
                currentHeadUpBannerDataList.closeable,
                accessibilityManager
            )
            delay(duration)
            currentHeadUpBannerDataList.dismiss()
        }
    }
    FadeInFadeOutWithScale(
        current = hostState.currentHeadUpBannerData,
        modifier = modifier,
        content = headUpBanner
    )
}

interface HeadUpBannerData {
    val content: @Composable () -> Unit
    val closeable: Boolean
    val clickable: Boolean
    val icon: @Composable () -> Unit
    val duration: HeadUpBannerDuration

    fun onClick()

    fun dismiss()
}

enum class HeadUpBannerResult {
    Closed,
    Clicked
}

enum class HeadUpBannerDuration {
    Normal,
    Long
}

internal fun HeadUpBannerDuration.toMillis(
    hasAction: Boolean,
    accessibilityManager: AccessibilityManager?
): Long {
    val original = when (this) {
        HeadUpBannerDuration.Normal -> 3000L
        HeadUpBannerDuration.Long -> 7000L
    }
    if (accessibilityManager == null)
        return original

    return accessibilityManager.calculateRecommendedTimeoutMillis(
        original, containsIcons = true,
        containsText = true,
        containsControls = hasAction
    )
}

@Composable
fun FadeInFadeOutWithScale(
    current: HeadUpBannerData?,
    modifier: Modifier = Modifier,
    content: @Composable (HeadUpBannerData) -> Unit
) {
    val state = remember { FadeInFadeOutState<HeadUpBannerData?>() }
    if (current != state.current) {
        state.current = current
        val keys = state.items.map { it.key }.toMutableList()
        if (!keys.contains(current)) {
            keys.add(current)
        }
        state.items.clear()
        keys.filterNotNull().mapTo(state.items) { key ->
            FadeInFadeOutAnimationItem(key) { children ->
                val isVisible = key == current
                val duration =
                    if (isVisible) HeadUpBannerFadeInMillis else HeadUpBannerFadeOutMillis
                val delay = HeadUpBannerFadeOutMillis + HeadUpBannerInBetweenDelayMillis
                val animationDelay = if (isVisible && keys.filterNotNull().size != 1) delay else 0
                val opacity = animatedOpacity(
                    animation = tween(
                        easing = LinearEasing,
                        delayMillis = animationDelay,
                        durationMillis = duration
                    ),
                    visible = isVisible,
                    onAnimationFinish = {
                        if (key != state.current) {
                            state.items.removeAll { it.key == key }
                            state.scope?.invalidate()
                        }
                    }
                )
                val scale = animatedScale(
                    animation = tween(
                        easing = FastOutSlowInEasing,
                        delayMillis = animationDelay,
                        durationMillis = duration
                    ),
                    visible = isVisible
                )
                Box(
                    Modifier.graphicsLayer(
                        scaleX = scale.value,
                        scaleY = scale.value,
                        alpha = opacity.value
                    ).semantics {
                        liveRegion = LiveRegionMode.Polite
                        dismiss {
                            key.dismiss()
                            true
                        }
                    }
                ) {
                    children()
                }
            }
        }
    }
    Box(modifier) {
        state.scope = currentRecomposeScope
        state.items.forEach { (item, opacity) ->
            key(item) {
                opacity {
                    content(item!!)
                }
            }
        }
    }
}

private class FadeInFadeOutState<T> {
    // we use Any here as something which will not be equals to the real initial value
    var current: Any? = Any()
    var items = mutableListOf<FadeInFadeOutAnimationItem<T>>()
    var scope: RecomposeScope? = null
}

private data class FadeInFadeOutAnimationItem<T>(
    val key: T,
    val transition: FadeInFadeOutTransition
)

private typealias FadeInFadeOutTransition = @Composable (content: @Composable () -> Unit) -> Unit

@Composable
private fun animatedOpacity(
    animation: AnimationSpec<Float>,
    visible: Boolean,
    onAnimationFinish: () -> Unit = {}
): State<Float> {
    val alpha = remember { Animatable(if (!visible) 1f else 0f) }
    LaunchedEffect(visible) {
        alpha.animateTo(
            if (visible) 1f else 0f,
            animationSpec = animation
        )
        onAnimationFinish()
    }
    return alpha.asState()
}

@Composable
private fun animatedScale(animation: AnimationSpec<Float>, visible: Boolean): State<Float> {
    val scale = remember { Animatable(if (!visible) 1f else 0.8f) }
    LaunchedEffect(visible) {
        scale.animateTo(
            if (visible) 1f else 0.8f,
            animationSpec = animation
        )
    }
    return scale.asState()
}

@Composable
fun HeadUpBanner(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit = {},
    onClick: (() -> Unit)? = null,
    canClose: Boolean = false,
    content: @Composable () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(true) }
    val offsetX = remember { Animatable(0f) }

    if (visible)
        Row(
            modifier = Modifier
                .padding(8.dp)
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        if (canClose)
                            coroutineScope.launch {
                                offsetX.snapTo(offsetX.value + delta)
                            }
                    },
                    onDragStopped = {
                        if (offsetX.value > 500f) {
                            coroutineScope.launch {
                                val offsetResult = offsetX.animateTo(
                                    1000f,
                                    animationSpec = tween(durationMillis = 500)
                                )
                                if (offsetResult.endReason == AnimationEndReason.Finished) {
                                    visible = false
                                }
                            }
                        } else
                            coroutineScope.launch {
                                offsetX.animateTo(
                                    targetValue = 0f,
                                    animationSpec = tween(durationMillis = 500)
                                )
                            }
                    }
                )
                .height(60.dp)
                .fillMaxWidth()
                .shadow(4.dp, RoundedCornerShape(8.dp))
                .background(MaterialTheme.colors.surface, RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
                .apply {
                    onClick?.let {
                        clickable { it() }
                    }
                }
                .padding(8.dp)
                .then(modifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            icon()
            Row(Modifier.weight(.8f)) {
                content()
            }
            if (canClose)
                IconButton(onClick = { visible = false }, modifier = Modifier.weight(.1f)) {
                    Icon(Icons.Default.Close, contentDescription = "")
                }
        }
}

private const val HeadUpBannerFadeInMillis = 200
private const val HeadUpBannerFadeOutMillis = 100
private const val HeadUpBannerInBetweenDelayMillis = 0