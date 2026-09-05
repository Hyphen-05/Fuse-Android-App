package com.example.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.RgbControllerViewModel
import com.example.core.perception.PerceptionSession
import com.example.core.perception.Response
import com.example.core.perception.Trial
import com.example.core.perception.TrialKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Asks Joe what he can actually see, on his own strips.
 *
 * The capture rig measured the strip and never measured the viewer, and that gap is what let a
 * change ship that a simulation scored as an improvement and he described as "everything is steppy
 * now". With the Pixel 9 gone there is no camera any more - which matters less than it sounds,
 * because a camera could not have answered this question either.
 *
 * ## How to read what it does
 *
 * Each trial plays two intervals on the strip, **A** then **B**, and asks which one had the thing.
 * Which interval carries it is decided by a seed, so knowing the design does not help. Roughly one
 * trial in five is a catch trial where both intervals are genuinely identical - there is no right
 * answer, and the rate of confident answers on those is the guessing rate. A threshold with a high
 * guessing rate behind it is not a threshold.
 *
 * **"Can't tell" is a real answer and the most useful one on a catch trial.** Nothing here is
 * looking for a particular result.
 *
 * Results are written to the app's external files directory as JSON, next to the diagnostics logs,
 * so a session can pull them over adb and analyse them without anything being retyped.
 */
@Composable
fun PerceptionLabScreen(
    viewModel: RgbControllerViewModel,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var session by remember { mutableStateOf<PerceptionSession?>(null) }
    var trial by remember { mutableStateOf<Trial?>(null) }
    var playing by remember { mutableStateOf<String?>(null) }
    var awaitingAnswer by remember { mutableStateOf(false) }
    var trialsDone by remember { mutableIntStateOf(0) }
    var savedPath by remember { mutableStateOf<String?>(null) }
    var shownAt by remember { mutableLongStateOf(0L) }

    val targets = remember { viewModel.perceptionTargetCount() }

    suspend fun present(t: Trial) {
        awaitingAnswer = false
        playing = "A"
        viewModel.playPerceptionStimulus(t.a)
        playing = null
        delay(com.example.core.perception.PerceptionTrials.INTER_STIMULUS_MS)
        playing = "B"
        viewModel.playPerceptionStimulus(t.b)
        playing = null
        shownAt = android.os.SystemClock.elapsedRealtime()
        awaitingAnswer = true
    }

    fun advance() {
        val s = session ?: return
        val next = s.next()
        trial = next
        if (next == null) {
            savedPath = writeReport(context, s)
        } else {
            scope.launch { present(next) }
        }
    }

    fun answer(response: Response) {
        val s = session ?: return
        if (!awaitingAnswer) return
        awaitingAnswer = false
        s.record(response, android.os.SystemClock.elapsedRealtime() - shownAt)
        trialsDone++
        advance()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Perception Lab",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            when {
                targets == 0 -> Text(
                    "No devices under Active Control. Turn one on and reopen this screen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )

                session == null -> {
                    Text(
                        "Each trial plays two patterns on the strip, A then B, and asks which one " +
                            "had the difference. Watch the strip, not the phone.\n\n" +
                            "Some trials genuinely have no difference at all — \"Can't tell\" is a " +
                            "real answer and on those it is the right one. Guessing makes the " +
                            "results worse, not better.\n\n" +
                            "Leave the brightness slider where you normally have it. That setting " +
                            "is recorded with the results and is part of what is being measured.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val start = remember { MutableInteractionSource() }
                    Button(
                        onClick = {
                            val s = PerceptionSession(seed = System.currentTimeMillis())
                            session = s
                            val first = s.next()
                            trial = first
                            if (first != null) scope.launch { present(first) }
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp).joyfulPress(start),
                        interactionSource = start,
                        shape = CircleShape
                    ) { Text("Start") }
                }

                savedPath != null -> {
                    val report = session!!.report()
                    Text(
                        "Done — $trialsDone trials.",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Guessing rate on the no-difference trials: " +
                            "${(report.falsePositiveRate * 100).toInt()}% " +
                            "(${report.catchTrials} of them). Lower is better; if this is high the " +
                            "thresholds below should not be trusted.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    report.stepThresholdByBase.forEach { (base, threshold) ->
                        Text(
                            "At byte $base, the smallest change you spotted was " +
                                (threshold?.let { "%.1f bytes".format(it) } ?: "not established"),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Text(
                        "Saved to $savedPath",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> {
                    Text(
                        "Trial ${trialsDone + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = when {
                            playing != null -> "Showing $playing"
                            awaitingAnswer -> questionFor(trial?.kind)
                            else -> "…"
                        },
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.weight(1f))
                    if (awaitingAnswer) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            AnswerButton("A", Modifier.weight(1f)) { answer(Response.A) }
                            AnswerButton("B", Modifier.weight(1f)) { answer(Response.B) }
                        }
                        val cant = remember { MutableInteractionSource() }
                        OutlinedButton(
                            onClick = { answer(Response.CANT_TELL) },
                            modifier = Modifier.fillMaxWidth().height(52.dp).joyfulPress(cant),
                            interactionSource = cant,
                            shape = CircleShape
                        ) { Text("Can't tell") }
                    }
                }
            }
        }
    }
}

private fun questionFor(kind: TrialKind?): String = when (kind) {
    TrialKind.STEP_VISIBILITY -> "Which one changed?"
    TrialKind.DITHER_FLICKER -> "Which one flickered?"
    TrialKind.FADE_SMOOTHNESS -> "Which one was smoother?"
    null -> ""
}

@Composable
private fun AnswerButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        interactionSource = interaction,
        modifier = modifier.height(64.dp).joyfulPress(interaction),
        shape = CircleShape
    ) { Text(label, style = MaterialTheme.typography.titleLarge) }
}

/**
 * Writes the sitting to JSON beside the diagnostics logs.
 *
 * Every trial is written out, not just the summary, so the analysis can be redone differently later
 * without asking Joe to sit through it again - which is the whole reason to record a response time
 * and a catch flag per trial rather than a threshold per level.
 */
private fun writeReport(context: android.content.Context, session: PerceptionSession): String? = try {
    val report = session.report()
    val root = JSONObject()
    root.put("generatedAtMs", System.currentTimeMillis())
    root.put("falsePositiveRate", report.falsePositiveRate)
    root.put("catchTrials", report.catchTrials)
    root.put("stepThresholdByBase", JSONObject().apply {
        report.stepThresholdByBase.forEach { (k, v) -> put(k.toString(), v ?: JSONObject.NULL) }
    })
    root.put("ditherVisibleByInterval", JSONObject().apply {
        report.ditherVisibleByInterval.forEach { (k, v) -> put(k.toString(), v) }
    })
    root.put("fadePreference", JSONObject().apply {
        report.fadePreference.forEach { (k, v) -> put(k, v) }
    })
    root.put("trials", JSONArray().apply {
        report.records.forEach { r ->
            put(JSONObject().apply {
                put("kind", r.kind.name)
                put("baseByte", r.baseByte)
                put("delta", r.delta)
                put("intervalMs", r.intervalMs)
                put("isCatch", r.isCatch)
                put("targetIsB", r.targetIsB)
                put("response", r.response.name)
                put("correct", r.correct)
                put("responseMs", r.responseMs)
            })
        }
    })
    val dir = File(context.getExternalFilesDir(null), "perception").apply { mkdirs() }
    val file = File(dir, "perception_${System.currentTimeMillis()}.json")
    file.writeText(root.toString(2))
    file.absolutePath
} catch (e: Exception) {
    android.util.Log.e("PerceptionLab", "Could not write report", e)
    null
}
