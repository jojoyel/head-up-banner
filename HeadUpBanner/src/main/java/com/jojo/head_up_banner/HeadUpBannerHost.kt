package com.jojo.head_up_banner

import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Surface
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
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
        clickable: Boolean = false,
        icon: (@Composable () -> Unit)? = null,
        duration: HeadUpBannerDuration = HeadUpBannerDuration.Normal
    ): HeadUpBannerResult = mutex.withLock {
        try {
            return suspendCancellableCoroutine { continuation ->
                currentHeadUpBannerData = HeadUpDataImpl(
                    content = content,
                    closeable = if (closeable) {
                        {
                            currentHeadUpBannerData?.dismiss()
                        }
                    } else null,
                    clickable = if (clickable) {
                        {
                            currentHeadUpBannerData?.onClick()
                        }
                    } else null,
                    icon = icon,
                    continuation = continuation,
                    duration = duration
                )
            }
        } finally {
            currentHeadUpBannerData = null
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private class HeadUpDataImpl(
        override val content: @Composable () -> Unit,
        override val icon: (@Composable () -> Unit)?,
        override val closeable: (() -> Unit)?,
        override val clickable: (() -> Unit)? = null,
        override val duration: HeadUpBannerDuration = HeadUpBannerDuration.Normal,
        private val continuation: CancellableContinuation<HeadUpBannerResult>
    ) : HeadUpBannerData {
        override fun onClick() {
            if (clickable != null && continuation.isActive) continuation.resume(HeadUpBannerResult.Clicked) {}
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
            onClick = it.clickable,
            onClose = it.closeable,
            content = it.content
        )
    }
) {
    val currentHeadUpBannerData = hostState.currentHeadUpBannerData
    val accessibilityManager = LocalAccessibilityManager.current

    LaunchedEffect(currentHeadUpBannerData) {
        currentHeadUpBannerData?.let {
            val duration = currentHeadUpBannerData.duration.toMillis(
                currentHeadUpBannerData.closeable != null,
                accessibilityManager
            )
            delay(duration)
            currentHeadUpBannerData.dismiss()
        }
    }
    SlideInSlideOut(
        current = hostState.currentHeadUpBannerData,
        modifier = modifier,
        content = headUpBanner
    )
}

interface HeadUpBannerData {
    val content: @Composable () -> Unit
    val closeable: (() -> Unit)?
    val clickable: (() -> Unit)?
    val icon: (@Composable () -> Unit)?
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
        HeadUpBannerDuration.Normal -> 4000L
        HeadUpBannerDuration.Long -> 7000L
    } + HeadUpBannerInBetweenDelayMillis
    if (accessibilityManager == null)
        return original

    return accessibilityManager.calculateRecommendedTimeoutMillis(
        original, containsIcons = true,
        containsText = true,
        containsControls = hasAction
    )
}

@Composable
fun SlideInSlideOut(
    current: HeadUpBannerData?,
    modifier: Modifier = Modifier,
    content: @Composable (HeadUpBannerData) -> Unit
) {
    val state = remember { SlideInSlideOutState<HeadUpBannerData?>() }

    if (current != state.current) {
        state.current = current
        val keys = state.items.map { it.key }.toMutableList()
        if (!keys.contains(current)) keys.add(current)
        state.items.clear()
        keys.filterNotNull().mapTo(state.items) { key ->
            SlideInSlideOutAnimationItem(key) { children ->
                val isVisible = key == current
                val duration =
                    if (isVisible) HeadUpBannerSlideInMillis else HeadUpBannerSlideOutMillis
                val delay = HeadUpBannerSlideOutMillis + HeadUpBannerInBetweenDelayMillis
                val animationDelay = if (isVisible && keys.filterNotNull().size != 1) delay else 0
                val opacity = animatedOpacity(animation = tween(
                    easing = EaseOut,
                    delayMillis = animationDelay,
                    durationMillis = duration
                ),
                    visible = isVisible,
                    onAnimationFinish = {
                        if (key != state.current) {
                            state.items.removeAll { it.key == key }
                            state.scope?.invalidate()
                        }
                    })
                val translation = animatedTranslation(
                    animation = tween(
                        easing = EaseOut,
                        delayMillis = animationDelay,
                        durationMillis = duration
                    ),
                    visible = isVisible
                )
                Box(
                    modifier = Modifier.graphicsLayer(
                        translationY = translation.value,
                        alpha = opacity.value
                    )
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

private class SlideInSlideOutState<T> {
    var current: Any? = Any()
    var items = mutableListOf<SlideInSlideOutAnimationItem<T>>()
    var scope: RecomposeScope? = null
}

private data class SlideInSlideOutAnimationItem<T>(
    val key: T,
    val transition: SlideInSlideOutTransition
)

private typealias SlideInSlideOutTransition = @Composable (content: @Composable () -> Unit) -> Unit

@Composable
private fun animatedTranslation(animation: AnimationSpec<Float>, visible: Boolean): State<Float> {
    val outside = with(LocalDensity.current) { 60.dp.toPx() }

    val translation = remember { Animatable(if (!visible) 0f else -outside) }
    LaunchedEffect(visible) {
        translation.animateTo(
            if (visible) 0f else -outside,
            animationSpec = animation
        )
    }
    return translation.asState()
}

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

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun HeadUpBanner(
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }

    Surface(
        shape = RoundedCornerShape(8.dp),
        elevation = 2.dp,
        onClick = {
            onClick?.invoke()
        },
        enabled = onClick != null,
        modifier = Modifier
            .padding(8.dp)
            .offset { IntOffset(offsetX.value.roundToInt(), 0) }
            .condition(!isSystemInDarkTheme(), { shadow(4.dp, RoundedCornerShape(8.dp)) })
            .clip(RoundedCornerShape(8.dp))
            .fillMaxWidth()
            .draggable(
                enabled = onClose != null,
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    onClose.let {
                        coroutineScope.launch {
                            offsetX.snapTo(offsetX.value + delta)
                        }
                    }
                },
                onDragStopped = {
                    if (offsetX.value > 300f) {
                        coroutineScope.launch {
                            val offsetResult = offsetX.animateTo(
                                Float.MAX_VALUE,
                                animationSpec = tween(durationMillis = 500)
                            )
                            if (offsetResult.endReason == AnimationEndReason.Finished) {
                                onClose?.invoke()
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
    ) {
        Row(
            modifier = Modifier
                .height(60.dp)
                .padding(vertical = 8.dp, horizontal = 12.dp)
                .then(modifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            icon?.let {
                it()
            }
            Row(Modifier.weight(1f)) {
                content()
            }
            onClose?.let {
                IconButton(onClick = {
                    onClose()
                }, modifier = Modifier.weight(.1f)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(id = R.string.action_close)
                    )
                }
            }
        }
    }
}

fun Modifier.condition(
    condition: Boolean,
    ifTrue: Modifier.() -> Modifier,
    ifFalse: (Modifier.() -> Modifier)? = null
): Modifier = if (condition) {
    then(ifTrue(Modifier))
} else if (ifFalse != null) {
    then(ifFalse(Modifier))
} else {
    this
}

private const val HeadUpBannerSlideInMillis = 200
private const val HeadUpBannerSlideOutMillis = 100
private const val HeadUpBannerInBetweenDelayMillis = 500