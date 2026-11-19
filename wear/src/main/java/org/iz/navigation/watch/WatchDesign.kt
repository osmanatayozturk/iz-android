package org.iz.navigation.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import kotlinx.coroutines.launch

internal data class WatchPalette(val accent: Color, val background: Color, val surface: Color, val text: Color) {
    companion object {
        private val indigo = WatchPalette(Color(0xffb8b2ff), Color(0xff22222e), Color(0xff333345), Color(0xfff2eff7))
        private val petrol = WatchPalette(Color(0xff69d4cf), Color(0xff202c30), Color(0xff2b3c3f), Color(0xffedf4f0))
        private val amber = WatchPalette(Color(0xffffbf69), Color(0xff252725), Color(0xff3b3930), Color(0xfff2f1e5))
        fun from(id: String?) = when (id) {
            "petrol" -> petrol
            "graphite_amber" -> amber
            else -> indigo
        }
    }
}

@Composable
internal fun WatchPageLayout(pageId: String, title: String, palette: WatchPalette, tag: String,
    content: ScalingLazyListScope.() -> Unit) {
    val list = rememberScalingLazyListState(initialCenterItemIndex = 0)
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(pageId, focused) { if (focused) focus.requestFocus() }
    MaterialTheme(colors = Colors(primary = palette.accent, onPrimary = palette.background,
        secondary = palette.surface, onSecondary = palette.text, background = palette.background,
        onBackground = palette.text, surface = palette.surface, onSurface = palette.text,
        error = Color(0xffffb4a9), onError = Color(0xff30110e))) {
        Scaffold(modifier = Modifier.fillMaxSize().background(palette.background).testTag(pageId),
            timeText = { TimeText(modifier = Modifier.scrollAway(list)) },
            positionIndicator = { PositionIndicator(scalingLazyListState = list) }) {
            ScalingLazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag(tag)
                .onRotaryScrollEvent { event ->
                    scope.launch { list.scrollBy(event.verticalScrollPixels) }
                    true
                }.focusRequester(focus).focusable(), horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 36.dp),
                autoCentering = null, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item { Text(title, color = palette.accent, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() }) }
                content()
            }
        }
    }
}

@Composable
internal fun WatchValue(text: String) {
    Text(text, color = MaterialTheme.colors.onBackground, fontWeight = FontWeight.Bold,
        fontSize = 24.sp, textAlign = TextAlign.Center)
}

@Composable
internal fun Note(text: String, highlighted: Boolean = false) {
    Text(text, color = if (highlighted) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface,
        fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 2.dp))
}

@Composable
internal fun WatchAction(label: String, onClick: () -> Unit, primary: Boolean = false, enabled: Boolean = true,
    tag: String? = null, modifier: Modifier = Modifier) {
    Chip(label = { Text(label) }, onClick = onClick, enabled = enabled,
        colors = if (primary) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
        modifier = modifier.fillMaxWidth().then(if (tag == null) Modifier else Modifier.testTag(tag)))
}

@Composable
internal fun Measurement(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Note(label)
        WatchValue(value)
    }
}
