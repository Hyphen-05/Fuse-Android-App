package com.github.hyphen05.fuse.ui.components

import android.content.Context
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val GITHUB_URL = "https://github.com/Hyphen-05/Fuse-Android-App"
const val WEBSITE_URL = "https://comettea.com/fuse/"

/**
 * A one-off "star the repo" bottom sheet. It slides up a few seconds after a strip connects, on
 * the [StarPrompt.SHOW_AFTER_CONNECTS]th launch that reaches a connected strip, and never again
 * once it has been shown, however it was closed.
 */
object StarPrompt {
    const val SHOW_AFTER_CONNECTS = 5
    const val DELAY_AFTER_CONNECT_MS = 3_000L

    private const val PREFS = "star_prompt_prefs"
    private const val KEY_CONNECTS = "connected_launches"
    private const val KEY_DONE = "done"

    // One count per process, however many strips connect or reconnect during it.
    @Volatile private var countedThisProcess = false

    fun shouldShow(connectedLaunches: Int, done: Boolean): Boolean =
        !done && connectedLaunches >= SHOW_AFTER_CONNECTS

    fun recordConnectedLaunch(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getInt(KEY_CONNECTS, 0)
        if (countedThisProcess) return current
        countedThisProcess = true
        val next = current + 1
        prefs.edit().putInt(KEY_CONNECTS, next).apply()
        return next
    }

    fun isDone(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false)

    fun markDone(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).apply()
    }
}

/**
 * Counts this launch once a strip connects. Call it from the screen itself, not from inside a lazy
 * list item: an item only composes when scrolled into view, so the count would depend on scrolling.
 */
@Composable
fun rememberConnectedLaunches(connected: Boolean): Int {
    val context = LocalContext.current
    var connectedLaunches by remember { mutableStateOf(0) }
    LaunchedEffect(connected) {
        if (connected) connectedLaunches = StarPrompt.recordConnectedLaunch(context)
    }
    return connectedLaunches
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StarPromptSheet(connected: Boolean, connectedLaunches: Int) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(connected, connectedLaunches) {
        if (!connected || visible) return@LaunchedEffect
        if (!StarPrompt.shouldShow(connectedLaunches, StarPrompt.isDone(context))) return@LaunchedEffect
        delay(StarPrompt.DELAY_AFTER_CONNECT_MS)
        // Marked on showing rather than on answering, so a swipe away, a tap outside or the app
        // being killed with the sheet up all count as the one time it was asked.
        StarPrompt.markDone(context)
        visible = true
    }

    if (!visible) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun close(then: () -> Unit = {}) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            visible = false
            then()
        }
    }

    ModalBottomSheet(
        onDismissRequest = { visible = false },
        sheetState = sheetState,
        modifier = Modifier.testTag("star_prompt_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp)
            )
            Text(
                text = "Enjoying Fuse?",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center
            )
            Text(
                text = "Fuse is free, open source and made by one person. A star on GitHub helps other people find it.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            val starInteractionSource = remember { MutableInteractionSource() }
            Button(
                onClick = { close { uriHandler.openUri(GITHUB_URL) } },
                interactionSource = starInteractionSource,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .joyfulPress(starInteractionSource)
                    .testTag("star_prompt_star"),
                shape = CircleShape
            ) {
                Text("Star on GitHub", fontWeight = FontWeight.Bold)
            }
            TextButton(
                onClick = { close() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("star_prompt_dismiss")
            ) {
                Text("No thanks")
            }
        }
    }
}
