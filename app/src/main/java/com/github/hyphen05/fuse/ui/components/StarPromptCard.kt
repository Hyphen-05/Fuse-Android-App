package com.github.hyphen05.fuse.ui.components

import android.content.Context
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp

const val GITHUB_URL = "https://github.com/Hyphen-05/Fuse-Android-App"
const val WEBSITE_URL = "https://comettea.com/fuse/"

/**
 * A one-off "star the repo" card on Home. It appears only after the app has reached a connected
 * strip on [StarPrompt.SHOW_AFTER_CONNECTS] separate launches, and once either button is pressed
 * it never comes back.
 */
object StarPrompt {
    const val SHOW_AFTER_CONNECTS = 5

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

/** Shows the card when [connected] and the rule allows it. Renders nothing otherwise. */
@Composable
fun StarPromptCard(connected: Boolean, connectedLaunches: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var done by remember { mutableStateOf(StarPrompt.isDone(context)) }

    if (!connected || !StarPrompt.shouldShow(connectedLaunches, done)) return

    Card(
        modifier = modifier.fillMaxWidth().testTag("star_prompt_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = "Enjoying Fuse?",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Text(
                text = "It's free and open source. A star on GitHub helps other people find it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = {
                        StarPrompt.markDone(context)
                        done = true
                    },
                    modifier = Modifier.testTag("star_prompt_dismiss")
                ) {
                    Text("No thanks")
                }
                Spacer(modifier = Modifier.width(8.dp))
                val starInteractionSource = remember { MutableInteractionSource() }
                Button(
                    onClick = {
                        StarPrompt.markDone(context)
                        done = true
                        uriHandler.openUri(GITHUB_URL)
                    },
                    interactionSource = starInteractionSource,
                    modifier = Modifier
                        .height(44.dp)
                        .joyfulPress(starInteractionSource)
                        .testTag("star_prompt_star"),
                    shape = CircleShape
                ) {
                    Text("Star on GitHub", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
