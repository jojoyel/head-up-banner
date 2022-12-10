package com.jojo.head_up_banner

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.zIndex

class HeadUpDisplayerState(
    val headUpBannerHostState: HeadUpBannerHostState
)

@Composable
fun rememberHeadUpDisplayerState(
    headUpBannerHostState: HeadUpBannerHostState = remember { HeadUpBannerHostState() }
): HeadUpDisplayerState = remember {
    HeadUpDisplayerState(headUpBannerHostState)
}

@Composable
fun HeadUpDisplayer(
    modifier: Modifier = Modifier,
    headUpDisplayerState: HeadUpDisplayerState = rememberHeadUpDisplayerState(),
    headUpBannerHost: @Composable (HeadUpBannerHostState) -> Unit = { HeadUpBannerHost(hostState = it) },
    backgroundColor: Color = MaterialTheme.colors.background,
    content: @Composable () -> Unit
) {
    val child = @Composable { childModifier: Modifier ->
        Surface(modifier = childModifier, color = backgroundColor) {
            HeadUpDisplayerLayout(
                content = content,
                headUpBanner = { headUpBannerHost(headUpDisplayerState.headUpBannerHostState) }
            )
        }
    }

    child(modifier)
}

@Composable
fun HeadUpDisplayerLayout(
    content: @Composable () -> Unit,
    headUpBanner: @Composable () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        content()
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.zIndex(99f)) {
            headUpBanner()
        }
    }
}