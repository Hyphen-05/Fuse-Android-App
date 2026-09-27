package com.example.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import android.widget.VideoView
import androidx.core.net.toUri
import kotlinx.coroutines.suspendCancellableCoroutine
import com.example.RgbControllerViewModel
import com.example.core.perception.FloorFinder
import com.example.core.perception.Stimulus
import com.example.core.perception.lab.LabAnalysis
import com.example.core.perception.lab.LabAnswer
import com.example.core.perception.lab.LabBlocks
import com.example.core.perception.lab.LabContext
import com.example.core.perception.lab.LabTrial
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The test battery: several short blocks, run one at a time, in the order that unblocks most.
 *
 * ## What this is for
 *
 * Not "collect more numbers". The point is a **model that predicts Joe's answers**, so that tuning
 * can stop needing his eyes on the strip for every change. The quantisation probe set the standard:
 * a rule that got all 74 individual trials right, rather than an average fitted to a cloud. A block
 * that produces a number nobody can predict the next answer from has not moved that along.
 *
 * ## Why blocks rather than one sitting
 *
 * The first sitting was designed at a median of 195 trials and Joe was asked to sit through it.
 * Every block here is four to six minutes and separately runnable, so stopping after any one leaves
 * something whole. They are ordered, and the order is real: block 1 produces the units the later
 * blocks want their stimuli spaced in, and skipping ahead re-creates the original mistake of
 * spacing stimuli in a currency the strip does not render.
 *
 * ## The context is measured, never assumed
 *
 * [LabContext] carries tonight's floor and tonight's brightness, and every block places its stimuli
 * relative to them. Block 0 measures it and writes it down; the others read it back. Hardcoding
 * base levels is what made most of the first sitting invisible.
 */
@Composable
fun ColumnScope.LabBatteryPanel(
    viewModel: RgbControllerViewModel,
    joeBrightness: Int,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    var labContext by remember { mutableStateOf(readLabContext(context)) }
    var running by remember { mutableStateOf<LabBlocks.BlockSpec?>(null) }
    var floorFor by remember { mutableStateOf<LabBlocks.BlockSpec?>(null) }
    var lastSaved by remember { mutableStateOf<String?>(null) }
    // A sitting is a queue of blocks run back to back. `between` is the block waiting on the pause
    // screen: he starts each one himself, so a sitting never runs on while he is not watching.
    var queue by remember { mutableStateOf<List<LabBlocks.BlockSpec>>(emptyList()) }
    var between by remember { mutableStateOf<LabBlocks.BlockSpec?>(null) }
    var sittingSize by remember { mutableIntStateOf(0) }

    fun start(chosen: LabBlocks.BlockSpec) {
        // Block 0 measures the floor as part of itself; the others need one already on record,
        // because a stimulus placed relative to a guessed floor measures nothing.
        if (chosen.id == LabBlocks.FLOOR_GRID.id || labContext == null) floorFor = chosen
        else running = chosen
    }

    fun blockEnded() {
        running = null
        between = queue.firstOrNull()
        queue = queue.drop(1)
    }

    val spec = running
    val waiting = between
    when {
        waiting != null -> SittingPause(
            next = waiting,
            position = sittingSize - queue.size,
            of = sittingSize,
            onStart = { between = null; start(waiting) },
            onStop = { between = null; queue = emptyList() }
        )

        floorFor != null -> FloorPanel(
            onLevel = { viewModel.holdPerceptionByte(it) },
            onDone = { found ->
                val ctx = LabContext(found.firstVisible, found.clearlyOn, joeBrightness)
                labContext = ctx
                writeLabContext(context, ctx)
                viewModel.holdPerceptionByte(0)
                running = floorFor
                floorFor = null
            }
        )

        spec != null -> {
            val ctx = labContext
            if (ctx == null) {
                Text("No floor measured yet.", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { running = null }) { Text("Back") }
            } else {
                BlockRunner(
                    spec = spec,
                    labContext = ctx,
                    viewModel = viewModel,
                    joeBrightness = joeBrightness,
                    onFinish = { trials, answers ->
                        lastSaved = writeBlockReport(context, spec, ctx, trials, answers)
                        clearBlockProgress(context, spec)
                        viewModel.setPerceptionBrightness(joeBrightness)
                        viewModel.holdPerceptionByte(0)
                        blockEnded()
                    },
                    onAbandon = {
                        viewModel.setPerceptionBrightness(joeBrightness)
                        viewModel.holdPerceptionByte(0)
                        // Leaving a block leaves the sitting. Its answers so far are saved, and the
                        // block resumes where it stopped next time.
                        running = null
                        queue = emptyList()
                    }
                )
            }
        }

        else -> BlockMenu(
            labContext = labContext,
            lastSaved = lastSaved,
            onRun = { chosen ->
                lastSaved = null
                queue = emptyList()
                start(chosen)
            },
            onRunSitting = {
                lastSaved = null
                sittingSize = LabBlocks.SITTING.size
                queue = LabBlocks.SITTING.drop(1)
                start(LabBlocks.SITTING.first())
            },
            onExit = onExit
        )
    }
}

// --- the menu ------------------------------------------------------------------------------------

@Composable
private fun ColumnScope.BlockMenu(
    labContext: LabContext?,
    lastSaved: String?,
    onRun: (LabBlocks.BlockSpec) -> Unit,
    onRunSitting: () -> Unit,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Tonight's sitting",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    LabBlocks.SITTING.joinToString(" → ") { "${LabBlocks.ALL.indexOf(it)}. ${it.title}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "About ${LabBlocks.SITTING.sumOf { it.estimateMinutes }} min, one after another. " +
                        "There is a pause between blocks, and you can stop at any pause.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val interaction = remember { MutableInteractionSource() }
                Button(
                    onClick = onRunSitting,
                    modifier = Modifier.fillMaxWidth().joyfulPress(interaction),
                    interactionSource = interaction,
                    shape = CircleShape
                ) { Text("Start the sitting") }
            }
        }

        Text(
            "Short blocks, in the order that unblocks the most. Each is a few minutes and stops " +
                "cleanly. Run them top down — the early ones produce the units the later ones need.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (labContext != null) {
            Text(
                "Measured tonight: floor at byte ${labContext.floorClearlyOn}, brightness " +
                    "${labContext.brightnessPercent}%, one output step every " +
                    "${labContext.gridSpacing} bytes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        lastSaved?.let {
            Text("Saved to $it", style = MaterialTheme.typography.bodySmall)
        }

        LabBlocks.ALL.forEachIndexed { index, spec ->
            val done = hasReport(context, spec)
            val partial = readBlockProgress(context, spec)?.size ?: 0
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "$index. ${spec.title}",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        spec.purpose,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val note = buildString {
                        if (spec.isRetired) append("Retired") else append("~${spec.estimateMinutes} min")
                        if (spec.commandsBrightness) append(" · turns brightness to 100% and puts it back")
                        if (done) append(" · already run")
                        if (partial > 0) append(" · $partial answers saved")
                    }
                    Text(
                        note,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // A retired block is shown and not run. Hiding it would renumber the ones after
                    // it, and running it would spend a sitting collecting answers already known to
                    // mean nothing - which is worse than no answers, because they look like data.
                    spec.retiredBecause?.let { why ->
                        Text(
                            why,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } ?: run {
                        val interaction = remember { MutableInteractionSource() }
                        Button(
                            onClick = { onRun(spec) },
                            modifier = Modifier.fillMaxWidth().joyfulPress(interaction),
                            interactionSource = interaction,
                            shape = CircleShape
                        ) { Text(if (partial > 0) "Carry on" else if (done) "Run again" else "Run") }
                    }
                }
            }
        }

        Text(
            "Still to build, once the blocks above have run:",
            style = MaterialTheme.typography.labelLarge
        )
        LabBlocks.PLANNED.forEach {
            Text("· $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    TextButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) { Text("Back") }
}

// --- between blocks in a sitting -----------------------------------------------------------------

/**
 * The pause between two blocks of a sitting. The next block starts only when he says so, which is
 * both a rest and a guarantee that nothing plays to an empty room.
 */
@Composable
private fun ColumnScope.SittingPause(
    next: LabBlocks.BlockSpec,
    position: Int,
    of: Int,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Block done. Saved.",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Text(
            "Next, $position of $of: ${next.title}",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Text(
            "~${next.estimateMinutes} min. Take a breather if you want one.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        val interaction = remember { MutableInteractionSource() }
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().joyfulPress(interaction),
            interactionSource = interaction,
            shape = CircleShape
        ) { Text("Start") }
        TextButton(onClick = onStop) { Text("Stop for tonight") }
    }
}

// --- the runner ----------------------------------------------------------------------------------

/**
 * Plays one block's trials and collects the answers.
 *
 * Every trial is a pure function of its index, so **Back a trial** is dropping the last answer and
 * resuming is restoring the list — no staircase to unwind, nothing that can desynchronise from what
 * was saved. Progress is written after every answer.
 */
@Composable
private fun ColumnScope.BlockRunner(
    spec: LabBlocks.BlockSpec,
    labContext: LabContext,
    viewModel: RgbControllerViewModel,
    joeBrightness: Int,
    onFinish: (List<LabTrial>, List<LabAnswer>) -> Unit,
    onAbandon: () -> Unit
) {
    val context = LocalContext.current
    val seed = remember(spec.id) { readOrMintSeed(context, spec) }
    val trials = remember(spec.id, seed) { LabBlocks.trialsFor(spec, labContext, seed) }
    var answers by remember(spec.id) { mutableStateOf(readBlockProgress(context, spec).orEmpty()) }
    var phase by remember { mutableStateOf("") }
    var awaiting by remember { mutableStateOf(false) }
    var replay by remember { mutableIntStateOf(0) }
    var shownAt by remember { mutableLongStateOf(0L) }
    var commanded by remember { mutableIntStateOf(joeBrightness) }
    // Read before anything moves, and dismissed by hand. A question explained underneath itself is
    // explained while he is already trying to answer it.
    var briefed by remember(spec.id) { mutableStateOf(spec.briefing.isEmpty()) }
    // Block 7 plays the film the trace was recorded from. Everything else is a light on its own and
    // shows nothing here.
    val usesFilm = remember(spec.id) { trials.any { t -> t.intervals.any { it.clip != null } } }
    var videoView by remember(spec.id) { mutableStateOf<VideoView?>(null) }

    val index = answers.size
    val trial = trials.getOrNull(index)

    LaunchedEffect(spec.id, index, replay, briefed, videoView) {
        if (!briefed) return@LaunchedEffect
        // A film block must not start its first trial before the surface exists, or that trial
        // plays its lights against a black rectangle - which is the exact complaint the film is
        // here to answer, reintroduced on the one trial nobody would think to check.
        if (usesFilm && videoView == null) return@LaunchedEffect
        val t = trials.getOrNull(index) ?: return@LaunchedEffect
        awaiting = false
        val want = t.brightnessPercent ?: joeBrightness
        if (want != commanded) {
            phase = "Setting brightness to $want%"
            viewModel.holdPerceptionByte(0)
            viewModel.setPerceptionBrightness(want)
            delay(900)
            commanded = want
        }
        t.intervals.forEachIndexed { i, stimulus ->
            phase = if (t.intervals.size == 1) "Watch" else if (i == 0) "Showing A" else "Showing B"
            // Prepared first and started immediately before the stimulus, so the picture and the
            // light start together. They stay together on their own after that: the clip was
            // encoded from exactly the frames the trace was computed from, at the trace's own step
            // rate, so there is no drift to correct - only a common start to get right.
            stimulus.clip?.let { clip -> videoView?.playFromStart(context, clip) }
            viewModel.playPerceptionStimulus(stimulus)
            if (i < t.intervals.lastIndex) {
                phase = "…"
                viewModel.holdPerceptionByte(0)
                delay(INTER_STIMULUS_MS)
            }
        }
        phase = ""
        shownAt = android.os.SystemClock.elapsedRealtime()
        awaiting = true
    }

    fun answer(optionId: String) {
        if (!awaiting) return
        awaiting = false
        val next = answers + LabAnswer(optionId, android.os.SystemClock.elapsedRealtime() - shownAt)
        answers = next
        saveBlockProgress(context, spec, seed, next)
        if (next.size >= trials.size) onFinish(trials, next)
    }

    if (!briefed) {
        Text(
            spec.title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Before you start",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        spec.briefing.forEach { paragraph ->
            Text(
                paragraph,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            )
        }
        Spacer(Modifier.weight(1f))
        AnswerButton("Start", Modifier.fillMaxWidth()) { briefed = true }
        TextButton(onClick = onAbandon, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
        return
    }

    val progress = if (trials.isEmpty()) 1.0 else index.toDouble() / trials.size
    Text(
        "${spec.title} · ${index + 1} of ${trials.size}",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    LinearProgressIndicator(progress = { progress.toFloat() }, modifier = Modifier.fillMaxWidth())
    Text(
        text = if (phase.isNotEmpty()) phase else trial?.question ?: "…",
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        text = when {
            awaiting -> trial?.hint.orEmpty()
            usesFilm -> "Watch the film. Let the strip sit at the edge of your vision."
            else -> "Watch the strip, not the phone."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )

    if (usesFilm) {
        Spacer(Modifier.height(12.dp))
        AndroidView(
            factory = { ctx ->
                // No media controller: a scrub bar invites him to drive the clip, and a clip
                // driven by hand is no longer the moment the trace was recorded from.
                VideoView(ctx).also { videoView = it }
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(FILM_ASPECT)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
        )
    }

    Spacer(Modifier.weight(1f))

    if (awaiting && trial != null) {
        // Two options side by side, three stacked - "Can't tell" is a full-width answer of its own
        // rather than a third of a row, because it is a real answer and should not read as the
        // afterthought option.
        val primary = trial.options.filter { it.id != "unsure" }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            primary.forEach { option ->
                AnswerButton(option.label, Modifier.weight(1f)) { answer(option.id) }
            }
        }
        trial.options.firstOrNull { it.id == "unsure" }?.let { option ->
            val interaction = remember { MutableInteractionSource() }
            OutlinedButton(
                onClick = { answer(option.id) },
                modifier = Modifier.fillMaxWidth().height(52.dp).joyfulPress(interaction),
                interactionSource = interaction,
                shape = CircleShape
            ) { Text(option.label) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { replay += 1 }, modifier = Modifier.weight(1f)) { Text("Show again") }
            TextButton(
                onClick = {
                    if (answers.isNotEmpty()) {
                        answers = answers.dropLast(1)
                        saveBlockProgress(context, spec, seed, answers)
                    }
                },
                enabled = answers.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) { Text("Back a trial") }
        }
        TextButton(onClick = onAbandon, modifier = Modifier.fillMaxWidth()) {
            Text("Stop for now (progress is kept)")
        }
    }
}

private const val INTER_STIMULUS_MS = 600L

/** The capture frame the clips are cut at (374x168), which is what ambiance actually samples. */
private const val FILM_ASPECT = 374f / 168f

/**
 * Start [clip] from its first frame and return once it is running.
 *
 * Preparing and starting are separate because a `VideoView` that is merely told to start plays
 * whenever it happens to be ready, and block 7's whole claim is that the picture and the light are
 * the same moment. Waiting for prepared first puts the uncertainty before the stimulus rather than
 * inside it.
 */
private suspend fun VideoView.playFromStart(context: android.content.Context, clip: String) {
    val id = context.resources.getIdentifier(clip, "raw", context.packageName)
    if (id == 0) return
    val uri = "android.resource://${context.packageName}/$id".toUri()
    suspendCancellableCoroutine { cont ->
        setOnPreparedListener { player ->
            player.setVolume(0f, 0f)
            player.isLooping = false
            if (cont.isActive) cont.resume(Unit) {}
        }
        setVideoURI(uri)
    }
    seekTo(0)
    start()
}

// --- persistence ----------------------------------------------------------------------------------

private fun labDir(context: android.content.Context) =
    File(perceptionDir(context), "lab").apply { mkdirs() }

private fun contextFile(context: android.content.Context) = File(labDir(context), "context.json")
private fun progressFile(context: android.content.Context, spec: LabBlocks.BlockSpec) =
    File(labDir(context), "progress_${spec.id}.json")

internal fun writeLabContext(context: android.content.Context, ctx: LabContext) {
    try {
        contextFile(context).writeText(
            JSONObject().apply {
                put("floorFirstVisible", ctx.floorFirstVisible)
                put("floorClearlyOn", ctx.floorClearlyOn)
                put("brightnessPercent", ctx.brightnessPercent)
                put("measuredAtMs", System.currentTimeMillis())
            }.toString()
        )
    } catch (e: Exception) {
        android.util.Log.e("LabBattery", "Could not save context", e)
    }
}

internal fun readLabContext(context: android.content.Context): LabContext? = try {
    val f = contextFile(context)
    if (!f.exists()) null else JSONObject(f.readText()).let {
        LabContext(
            floorFirstVisible = it.getInt("floorFirstVisible"),
            floorClearlyOn = it.getInt("floorClearlyOn"),
            brightnessPercent = it.getInt("brightnessPercent")
        )
    }
} catch (e: Exception) {
    android.util.Log.e("LabBattery", "Could not read context", e)
    null
}

private fun saveBlockProgress(
    context: android.content.Context,
    spec: LabBlocks.BlockSpec,
    seed: Long,
    answers: List<LabAnswer>
) {
    try {
        progressFile(context, spec).writeText(
            JSONObject().apply {
                put("seed", seed)
                put("answers", JSONArray().apply {
                    answers.forEach {
                        put(JSONObject().apply {
                            put("optionId", it.optionId)
                            put("responseMs", it.responseMs)
                        })
                    }
                })
            }.toString()
        )
    } catch (e: Exception) {
        android.util.Log.e("LabBattery", "Could not save progress", e)
    }
}

private fun readBlockProgress(
    context: android.content.Context,
    spec: LabBlocks.BlockSpec
): List<LabAnswer>? = try {
    val f = progressFile(context, spec)
    if (!f.exists()) null else JSONObject(f.readText()).getJSONArray("answers").let { a ->
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            LabAnswer(o.getString("optionId"), o.getLong("responseMs"))
        }
    }
} catch (e: Exception) {
    null
}

/**
 * The seed is minted once per part-finished block and kept.
 *
 * If it were re-minted on resume, the trial list would be regenerated differently and the answers
 * already given would be attached to trials he never saw — silently, and in a file that looks fine.
 */
private fun readOrMintSeed(context: android.content.Context, spec: LabBlocks.BlockSpec): Long = try {
    val f = progressFile(context, spec)
    if (f.exists()) JSONObject(f.readText()).getLong("seed") else System.currentTimeMillis()
} catch (e: Exception) {
    System.currentTimeMillis()
}

private fun clearBlockProgress(context: android.content.Context, spec: LabBlocks.BlockSpec) {
    try {
        progressFile(context, spec).delete()
    } catch (e: Exception) {
        android.util.Log.e("LabBattery", "Could not clear progress", e)
    }
}

private fun hasReport(context: android.content.Context, spec: LabBlocks.BlockSpec): Boolean =
    labDir(context).listFiles()?.any { it.name.startsWith("${spec.id}_") } == true

/**
 * Writes the block out, trial by trial.
 *
 * The per-trial rows are the record and the summary is a convenience: every reading here can be
 * recomputed differently later without asking Joe to sit through it again, which is the whole
 * reason the controls, the metadata and the response time travel with each row.
 */
private fun writeBlockReport(
    context: android.content.Context,
    spec: LabBlocks.BlockSpec,
    labContext: LabContext,
    trials: List<LabTrial>,
    answers: List<LabAnswer>
): String? = try {
    val controls = LabAnalysis.controls(trials, answers)
    val root = JSONObject()
    root.put("kind", "lab_block")
    root.put("block", spec.id)
    root.put("generatedAtMs", System.currentTimeMillis())
    root.put("floorFirstVisible", labContext.floorFirstVisible)
    root.put("floorClearlyOn", labContext.floorClearlyOn)
    root.put("brightnessPercent", labContext.brightnessPercent)
    root.put("gridSpacing", labContext.gridSpacing)
    root.put("catchTrials", controls.catchTrials)
    root.put("catchFalsePositives", controls.catchFalsePositives)
    root.put("anchorTrials", controls.anchorTrials)
    root.put("anchorsMissed", controls.anchorsMissed)
    root.put("trials", JSONArray().apply {
        trials.take(answers.size).forEachIndexed { i, t ->
            put(JSONObject().apply {
                put("kind", t.kind)
                put("question", t.question)
                put("optionIds", JSONArray(t.options.map { it.id }))
                put("correctOptionId", t.correctOptionId ?: JSONObject.NULL)
                put("isPreference", t.isPreference)
                put("brightnessPercent", t.brightnessPercent ?: JSONObject.NULL)
                put("meta", JSONObject().apply { t.meta.forEach { (k, v) -> put(k, v) } })
                put("answer", answers[i].optionId)
                put("responseMs", answers[i].responseMs)
            })
        }
    })
    val file = File(labDir(context), "${spec.id}_${System.currentTimeMillis()}.json")
    file.writeText(root.toString(2))
    file.absolutePath
} catch (e: Exception) {
    android.util.Log.e("LabBattery", "Could not write block report", e)
    null
}
