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
import com.example.core.perception.Answer
import com.example.core.perception.FloorFinder
import com.example.core.perception.PerceptionSession
import com.example.core.perception.PerceptionTrials
import com.example.core.perception.QuantisationProbe
import com.example.core.perception.Response
import com.example.core.perception.SessionConfig
import com.example.core.perception.Stimulus
import com.example.core.perception.StimulusStep
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
 * ## A sitting has two parts
 *
 * First a **floor calibration**: the strip walks up from black and he says where he first sees
 * light and where it is clearly lit. Everything after is placed relative to those two bytes.
 *
 * The first version skipped this and hardcoded its levels at bytes 4 to 100, which against the
 * measured curve is nearly nothing even at full firmware brightness - and Joe runs at 22%, where
 * how byte and brightness compose has never been measured. He ran it on 2026-09-05 and reported
 * "for lots of the tests one or both is just leds off". Defining stimuli in commanded bytes removed
 * the need to know *how much* light comes out; it did not remove the need for some to come out.
 *
 * Then the **trials**. Each plays two intervals, **A** then **B**, and asks which had the thing.
 * Which interval carries it is decided by a seed, so knowing the design does not help. Roughly one
 * trial in five is a catch trial where both intervals are genuinely identical - there is no right
 * answer, and the rate of confident answers on those is the guessing rate. A threshold with a high
 * guessing rate behind it is not a threshold.
 *
 * **"Can't tell" is a real answer and the right one on a catch trial.**
 *
 * ## Nothing is lost by stopping
 *
 * Every answer is written to disk as it is given, and a sitting is fully reconstructible from its
 * seed, its config and its answers (see [PerceptionSession]), so closing the screen, backgrounding
 * the app or losing the process costs at most the trial in progress. Reopening offers to resume.
 * **Back a trial** takes one back and re-shows it, repeatedly if he likes; **Show again** replays
 * the current trial without answering it.
 */
@Composable
fun PerceptionLabScreen(
    viewModel: RgbControllerViewModel,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var floor by remember { mutableStateOf<FloorFinder.FloorResult?>(null) }
    var session by remember { mutableStateOf<PerceptionSession?>(null) }
    var trial by remember { mutableStateOf<Trial?>(null) }
    var playing by remember { mutableStateOf<String?>(null) }
    var awaitingAnswer by remember { mutableStateOf(false) }
    var answered by remember { mutableIntStateOf(0) }
    var savedPath by remember { mutableStateOf<String?>(null) }
    var shownAt by remember { mutableLongStateOf(0L) }
    var inFloorPass by remember { mutableStateOf(false) }
    var resumable by remember { mutableStateOf(readInProgress(context)) }
    // Which of the two things the floor pass is calibrating for. The floor is needed either way —
    // a probe that walks from a byte the strip cannot render measures nothing, the same way the
    // first sitting did.
    var afterFloor by remember { mutableStateOf(LabRun.SITTING) }
    var probe by remember { mutableStateOf<ProbeRun?>(null) }
    var probeResumable by remember { mutableStateOf(readProbeInProgress(context)) }

    val targets = remember { viewModel.perceptionTargetCount() }
    // Captured once, on entry, so the number put back at the end is the one he had before the probe
    // borrowed it — not whatever the last segment happened to command.
    val joeBrightness = remember { viewModel.perceptionBrightnessPercent() }

    suspend fun present(t: Trial) {
        awaitingAnswer = false
        playing = "A"
        viewModel.playPerceptionStimulus(t.a)
        playing = null
        delay(PerceptionTrials.INTER_STIMULUS_MS)
        playing = "B"
        viewModel.playPerceptionStimulus(t.b)
        playing = null
        viewModel.holdPerceptionByte(0)
        shownAt = android.os.SystemClock.elapsedRealtime()
        awaitingAnswer = true
    }

    fun finish(s: PerceptionSession) {
        savedPath = writeReport(context, s, floor)
        clearInProgress(context)
        viewModel.holdPerceptionByte(0)
    }

    fun advance() {
        val s = session ?: return
        val next = s.next()
        trial = next
        if (next == null) finish(s) else scope.launch { present(next) }
    }

    fun startSession(config: SessionConfig, seed: Long, restore: List<Answer>?) {
        val s = PerceptionSession(seed = seed, config = config)
        restore?.let { s.restore(it) }
        session = s
        answered = s.answerCount
        val first = s.next()
        trial = first
        if (first == null) finish(s) else scope.launch { present(first) }
    }

    fun answer(response: Response) {
        val s = session ?: return
        if (!awaitingAnswer) return
        awaitingAnswer = false
        s.record(response, android.os.SystemClock.elapsedRealtime() - shownAt)
        answered = s.answerCount
        // Saved before the next trial starts, so the answer just given survives whatever happens
        // during the next one.
        saveInProgress(context, s, floor)
        advance()
    }

    fun goBack() {
        val s = session ?: return
        if (playing != null) return
        val previous = s.undoLast() ?: return
        answered = s.answerCount
        saveInProgress(context, s, floor)
        trial = previous
        scope.launch { present(previous) }
    }

    fun startProbe(floorResult: FloorFinder.FloorResult, seed: Long, restore: List<Boolean>?) {
        val plan = QuantisationProbe.planFor(floorResult)
        probe = ProbeRun(
            seed = seed,
            plan = plan,
            trials = QuantisationProbe.buildTrials(plan, seed),
            answers = restore.orEmpty()
        )
    }

    /**
     * Puts the strip and Joe's brightness back, whatever the screen was in the middle of.
     *
     * The probe is the only thing here that borrows a setting of his, so this is the one exit path
     * that has to run on every way out — finishing, closing, or backing out mid-run.
     */
    fun restoreStrip() {
        viewModel.setPerceptionBrightness(joeBrightness)
        viewModel.holdPerceptionByte(0)
    }

    // The Close button is not the only way off this screen — tapping a nav-bar tab drops it without
    // calling onClose. Left to that, a probe abandoned during its full-brightness segment would
    // hand Joe's strips back at 100%. Restoring on disposal covers every exit, and is idempotent.
    DisposableEffect(Unit) { onDispose { restoreStrip() } }

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
                IconButton(onClick = {
                    restoreStrip()
                    onClose()
                }) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            when {
                targets == 0 -> Text(
                    "No strip is connected and under Active Control. Connect one, turn " +
                        "Active Control on for it, and reopen this screen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )

                savedPath != null -> FinishedPanel(
                    session = session,
                    probe = probe,
                    floor = floor,
                    answered = answered,
                    savedPath = savedPath!!
                )

                probe != null -> ProbePanel(
                    run = probe!!,
                    joeBrightness = joeBrightness,
                    onPlay = { stimulus -> viewModel.playPerceptionStimulus(stimulus) },
                    onBrightness = { viewModel.setPerceptionBrightness(it) },
                    onDark = { viewModel.holdPerceptionByte(0) },
                    onAnswers = { answers ->
                        probe = probe!!.copy(answers = answers)
                        saveProbeInProgress(context, probe!!, floor)
                    },
                    onFinish = {
                        savedPath = writeProbeReport(context, probe!!, floor, joeBrightness)
                        clearProbeInProgress(context)
                        restoreStrip()
                    }
                )

                session != null -> TrialPanel(
                    trial = trial,
                    playing = playing,
                    awaitingAnswer = awaitingAnswer,
                    trialNumber = answered + 1,
                    progress = session!!.progressFraction(),
                    canGoBack = answered > 0,
                    onAnswer = { answer(it) },
                    onBack = { goBack() },
                    onReplay = { trial?.let { t -> scope.launch { present(t) } } }
                )

                inFloorPass -> FloorPanel(
                    onLevel = { viewModel.holdPerceptionByte(it) },
                    onDone = { found ->
                        floor = found
                        inFloorPass = false
                        viewModel.holdPerceptionByte(0)
                        if (afterFloor == LabRun.PROBE) {
                            startProbe(found, System.currentTimeMillis(), null)
                        } else {
                            startSession(
                                FloorFinder.sessionConfigFor(found),
                                System.currentTimeMillis(),
                                null
                            )
                        }
                    }
                )

                else -> IntroPanel(
                    resumable = resumable,
                    probeResumable = probeResumable,
                    onResume = {
                        val r = resumable
                        if (r != null) {
                            floor = r.floor
                            resumable = null
                            startSession(r.config, r.seed, r.answers)
                        }
                    },
                    onResumeProbe = {
                        val r = probeResumable
                        if (r?.floor != null) {
                            floor = r.floor
                            probeResumable = null
                            startProbe(r.floor, r.seed, r.answers)
                        }
                    },
                    onDiscard = {
                        clearInProgress(context)
                        clearProbeInProgress(context)
                        resumable = null
                        probeResumable = null
                    },
                    onStart = {
                        afterFloor = LabRun.SITTING
                        inFloorPass = true
                    },
                    onStartProbe = {
                        afterFloor = LabRun.PROBE
                        inFloorPass = true
                    }
                )
            }
        }
    }
}

// --- intro -------------------------------------------------------------------------------------

/** Which of the two runs the lab offers. Both start with the same floor calibration. */
private enum class LabRun { SITTING, PROBE }

@Composable
private fun ColumnScope.IntroPanel(
    resumable: InProgress?,
    probeResumable: ProbeInProgress?,
    onResume: () -> Unit,
    onResumeProbe: () -> Unit,
    onDiscard: () -> Unit,
    onStart: () -> Unit,
    onStartProbe: () -> Unit
) {
    if (probeResumable != null && probeResumable.floor != null) {
        Text(
            "There is a quantisation probe in progress with ${probeResumable.answers.size} " +
                "answers already given. Carrying on picks up at the same byte, at the same floor.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val resumeProbe = remember { MutableInteractionSource() }
        Button(
            onClick = onResumeProbe,
            modifier = Modifier.fillMaxWidth().height(52.dp).joyfulPress(resumeProbe),
            interactionSource = resumeProbe,
            shape = CircleShape
        ) { Text("Carry on the probe (${probeResumable.answers.size} done)") }
        TextButton(onClick = onDiscard, modifier = Modifier.fillMaxWidth()) {
            Text("Throw it away and start fresh")
        }
        return
    }

    if (resumable != null) {
        Text(
            "There is a sitting in progress with ${resumable.answers.size} answers already given. " +
                "Carrying on picks up exactly where it stopped, at the same floor calibration.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val resume = remember { MutableInteractionSource() }
        Button(
            onClick = onResume,
            modifier = Modifier.fillMaxWidth().height(52.dp).joyfulPress(resume),
            interactionSource = resume,
            shape = CircleShape
        ) { Text("Carry on (${resumable.answers.size} done)") }
        TextButton(onClick = onDiscard, modifier = Modifier.fillMaxWidth()) {
            Text("Throw it away and start fresh")
        }
        return
    }

    Text(
        "First the strip walks up from black and you say where you can see it. That is a few taps, " +
            "and it means no trial afterwards runs at a level your strips cannot show at the " +
            "brightness you have set.\n\n" +
            "Then each trial plays two patterns, A then B, and asks a question about them. Watch " +
            "the strip, not the phone.\n\n" +
            "Some trials genuinely have no difference at all — \"Can't tell\" is a real answer " +
            "and on those it is the right one. Guessing makes the results worse, not better.\n\n" +
            "Leave the brightness slider where you normally have it. It is part of what is being " +
            "measured.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    val start = remember { MutableInteractionSource() }
    Button(
        onClick = onStart,
        modifier = Modifier.fillMaxWidth().height(52.dp).joyfulPress(start),
        interactionSource = start,
        shape = CircleShape
    ) { Text("Full sitting (about 11 minutes)") }

    Text(
        "Or the quantisation probe — your own suggestion, and the shorter one. It walks the level " +
            "up one byte at a time and only ever asks whether anything changed. If the strip can " +
            "only render every few bytes, the gaps between your yes answers say how many. Near " +
            "the end it turns the brightness up to full by itself for a minute, to see whether " +
            "those gaps close, and puts your setting back afterwards.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    val probe = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onStartProbe,
        modifier = Modifier.fillMaxWidth().height(52.dp).joyfulPress(probe),
        interactionSource = probe,
        shape = CircleShape
    ) { Text("Quantisation probe (about 6 minutes)") }
}

// --- floor calibration -------------------------------------------------------------------------

private enum class FloorStage { ASCENDING_TO_VISIBLE, ASCENDING_TO_CLEAR, DESCENDING }

/**
 * The floor pass: coarse up the ladder, then one byte at a time back down.
 *
 * Two crossings rather than one because an ascending run reads high (he is waiting for it to
 * appear) and a descending run reads low (he knows where it was); [FloorFinder.combineCrossings]
 * takes the midpoint. The descending pass also pins the answer to a byte, which the coarse ladder
 * on its own cannot do.
 */
@Composable
private fun ColumnScope.FloorPanel(
    onLevel: (Int) -> Unit,
    onDone: (FloorFinder.FloorResult) -> Unit
) {
    var stage by remember { mutableStateOf(FloorStage.ASCENDING_TO_VISIBLE) }
    var rung by remember { mutableIntStateOf(0) }
    var seenAt by remember { mutableIntStateOf(0) }
    var clearAt by remember { mutableIntStateOf(0) }
    var descending by remember { mutableIntStateOf(0) }

    val level = if (stage == FloorStage.DESCENDING) {
        descending
    } else {
        FloorFinder.LADDER[rung.coerceIn(0, FloorFinder.LADDER.lastIndex)]
    }

    LaunchedEffect(level, stage) { onLevel(level) }

    fun finishDescending(darkAt: Int) {
        val firstVisible = FloorFinder.combineCrossings(seenAt, darkAt)
        onDone(
            FloorFinder.FloorResult(
                firstVisible = firstVisible,
                clearlyOn = maxOf(clearAt, firstVisible)
            )
        )
    }

    Text(
        text = when (stage) {
            FloorStage.ASCENDING_TO_VISIBLE -> "Can you see any light at all?"
            FloorStage.ASCENDING_TO_CLEAR -> "Is it clearly lit yet?"
            FloorStage.DESCENDING -> "Can you still see it?"
        },
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        text = when (stage) {
            FloorStage.ASCENDING_TO_VISIBLE ->
                "The faintest glow counts. It starts below what a strip can show at all, so a few " +
                    "taps of nothing at the start is normal."
            FloorStage.ASCENDING_TO_CLEAR ->
                "Not \"can I see it\" now, but \"that is properly on\". This is the level the " +
                    "trials will sit at."
            FloorStage.DESCENDING ->
                "Coming back down a byte at a time, to pin down where it disappears."
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        "byte $level",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.weight(1f))

    val yes = remember { MutableInteractionSource() }
    Button(
        onClick = {
            when (stage) {
                FloorStage.ASCENDING_TO_VISIBLE -> {
                    seenAt = level
                    stage = FloorStage.ASCENDING_TO_CLEAR
                }
                FloorStage.ASCENDING_TO_CLEAR -> {
                    clearAt = level
                    if (seenAt <= 1) {
                        // Nothing below it to walk back down through.
                        onDone(FloorFinder.FloorResult(firstVisible = 1, clearlyOn = maxOf(level, 1)))
                    } else {
                        descending = seenAt - 1
                        stage = FloorStage.DESCENDING
                    }
                }
                FloorStage.DESCENDING -> {
                    if (descending <= 1) finishDescending(0) else descending -= 1
                }
            }
        },
        modifier = Modifier.fillMaxWidth().height(64.dp).joyfulPress(yes),
        interactionSource = yes,
        shape = CircleShape
    ) {
        Text(
            when (stage) {
                FloorStage.ASCENDING_TO_VISIBLE -> "Yes, I can just see it"
                FloorStage.ASCENDING_TO_CLEAR -> "Yes, that is clearly lit"
                FloorStage.DESCENDING -> "Yes, still there"
            },
            style = MaterialTheme.typography.titleMedium
        )
    }
    OutlinedButton(
        onClick = {
            if (stage == FloorStage.DESCENDING) {
                finishDescending(descending)
            } else if (rung < FloorFinder.LADDER.lastIndex) {
                rung += 1
            } else {
                // Top of the ladder with no mark given. Nothing below byte 100 is visible on this
                // setup, which is itself the finding; take the top rather than looping forever.
                val top = FloorFinder.LADDER.last()
                onDone(FloorFinder.FloorResult(firstVisible = top, clearlyOn = top))
            }
        },
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = CircleShape
    ) {
        Text(
            when (stage) {
                FloorStage.ASCENDING_TO_VISIBLE -> "Nothing yet"
                FloorStage.ASCENDING_TO_CLEAR -> "Not quite yet"
                FloorStage.DESCENDING -> "No, it has gone dark"
            }
        )
    }
}

// --- trials ------------------------------------------------------------------------------------

@Composable
private fun ColumnScope.TrialPanel(
    trial: Trial?,
    playing: String?,
    awaitingAnswer: Boolean,
    trialNumber: Int,
    progress: Double,
    canGoBack: Boolean,
    onAnswer: (Response) -> Unit,
    onBack: () -> Unit,
    onReplay: () -> Unit
) {
    Text(
        "Trial $trialNumber · roughly ${(progress * 100).toInt()}% through",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    LinearProgressIndicator(
        progress = { progress.toFloat() },
        modifier = Modifier.fillMaxWidth()
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
    if (awaitingAnswer) {
        Text(
            text = hintFor(trial?.kind),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }

    Spacer(Modifier.weight(1f))

    if (awaitingAnswer) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AnswerButton("A", Modifier.weight(1f)) { onAnswer(Response.A) }
            AnswerButton("B", Modifier.weight(1f)) { onAnswer(Response.B) }
        }
        val cant = remember { MutableInteractionSource() }
        OutlinedButton(
            onClick = { onAnswer(Response.CANT_TELL) },
            modifier = Modifier.fillMaxWidth().height(52.dp).joyfulPress(cant),
            interactionSource = cant,
            shape = CircleShape
        ) { Text("Can't tell") }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            TextButton(onClick = onReplay, modifier = Modifier.weight(1f)) { Text("Show again") }
            TextButton(
                onClick = onBack,
                enabled = canGoBack,
                modifier = Modifier.weight(1f)
            ) { Text("Back a trial") }
        }
    }
}

/**
 * The question, per block.
 *
 * "Which one changed?" was the first wording and Joe reported it as ambiguous, correctly: the two
 * intervals differ from each other, so "changed" reads as a comparison between them when what is
 * being asked about is movement *inside* one of them. Each of these now names the thing to look for
 * instead of assuming the design is in the reader's head.
 */
private fun questionFor(kind: TrialKind?): String = when (kind) {
    TrialKind.STEP_VISIBILITY -> "Which one brightened, then came back?"
    TrialKind.DITHER_FLICKER -> "Which one flickered?"
    TrialKind.FADE_SMOOTHNESS -> "Which one faded more smoothly?"
    null -> ""
}

private fun hintFor(kind: TrialKind?): String = when (kind) {
    TrialKind.STEP_VISIBILITY ->
        "One held steady the whole time. The other lifted a little partway through and dropped back."
    TrialKind.DITHER_FLICKER ->
        "One is a steady level. The other alternates between two levels to sit between them."
    TrialKind.FADE_SMOOTHNESS ->
        "Both climb the same amount over the same time. One may step; one may glide."
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

// --- quantisation probe --------------------------------------------------------------------------

/**
 * A probe run: the plan, the trials it expands to, and the answers so far.
 *
 * Nothing here adapts to an answer, so the trial list is a pure function of `(seed, plan)` and an
 * answer is an index into it. Going back a trial is dropping the last answer, and resuming is
 * restoring the list — neither needs the sequence to be stored or replayed.
 */
private data class ProbeRun(
    val seed: Long,
    val plan: List<QuantisationProbe.Segment>,
    val trials: List<QuantisationProbe.ProbeTrial>,
    val answers: List<Boolean>
) {
    val index: Int get() = answers.size
    val current: QuantisationProbe.ProbeTrial? get() = trials.getOrNull(index)
}

/** Held at the starting level before the watch window opens, so entering it is not the answer. */
private const val PROBE_SETTLE_MS = 700L

/** The watch window: the starting level, then the level one byte up. */
private const val PROBE_PRE_MS = 1300L
private const val PROBE_POST_MS = 1500L

/** After a brightness change, before anything is judged against it. */
private const val PROBE_BRIGHTNESS_SETTLE_MS = 900L

/**
 * The probe itself: one commanded byte at a time, and only ever "did anything change".
 *
 * ## The walk is continuous on purpose
 *
 * Consecutive step trials share a level — trial *i* runs `b+i` to `b+i+1` and trial *i+1* starts at
 * `b+i+1` — so the strip climbs steadily through the run rather than being reset between questions.
 * Each trial's transition is then the only change inside its own window, and a change too small to
 * see is never accumulated across trials into one that is. That is what separates a grid from a
 * threshold: a grid puts a whole emitted level into one of these increments and nothing into the
 * rest, while a threshold above one byte makes every one of them invisible.
 *
 * The controls interrupt the walk (an anchor jumps twelve bytes and comes back), so every trial
 * opens with a settling hold before the watch window, and the screen says which is which. The entry
 * transient is therefore outside the window Joe is judging — and the catch trials measure whatever
 * of it leaks in anyway.
 */
@Composable
private fun ColumnScope.ProbePanel(
    run: ProbeRun,
    joeBrightness: Int,
    onPlay: suspend (Stimulus) -> Unit,
    onBrightness: (Int) -> Unit,
    onDark: () -> Unit,
    onAnswers: (List<Boolean>) -> Unit,
    onFinish: () -> Unit
) {
    var phase by remember { mutableStateOf("") }
    var awaiting by remember { mutableStateOf(false) }
    var replay by remember { mutableIntStateOf(0) }
    var commanded by remember { mutableIntStateOf(joeBrightness) }

    val trial = run.current

    LaunchedEffect(run.index, replay) {
        val t = run.trials.getOrNull(run.index) ?: return@LaunchedEffect
        awaiting = false
        val want = t.brightnessPercent ?: joeBrightness
        if (want != commanded) {
            phase = "Turning the brightness to $want%"
            onDark()
            onBrightness(want)
            delay(PROBE_BRIGHTNESS_SETTLE_MS)
            commanded = want
        }
        phase = "Settling"
        onPlay(Stimulus("probe_settle", listOf(StimulusStep(t.fromByte, PROBE_SETTLE_MS))))
        phase = "Watch"
        onPlay(
            Stimulus(
                "probe_${t.fromByte}_${t.toByte}",
                listOf(
                    StimulusStep(t.fromByte, PROBE_PRE_MS),
                    StimulusStep(t.toByte, PROBE_POST_MS)
                )
            )
        )
        phase = ""
        awaiting = true
    }

    fun answer(changed: Boolean) {
        if (!awaiting) return
        awaiting = false
        val next = run.answers + changed
        onAnswers(next)
        if (next.size >= run.trials.size) onFinish()
    }

    val progress = if (run.trials.isEmpty()) 1.0 else run.index.toDouble() / run.trials.size
    Text(
        "Probe · ${run.index + 1} of ${run.trials.size}" +
            (trial?.let { " · ${segmentLabel(it.segment)}" } ?: ""),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    LinearProgressIndicator(
        progress = { progress.toFloat() },
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        text = when {
            phase.isNotEmpty() -> phase
            awaiting -> "Did anything change?"
            else -> "…"
        },
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        text = if (awaiting) {
            "Only the change matters, not how big it was. Most of these will be nothing at all — " +
                "\"no change\" is the expected answer and not a failure to notice."
        } else {
            "The strip climbs one step at a time. Watch it, not the phone."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(Modifier.weight(1f))

    if (awaiting) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AnswerButton("It changed", Modifier.weight(1f)) { answer(true) }
            AnswerButton("No change", Modifier.weight(1f)) { answer(false) }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            TextButton(onClick = { replay += 1 }, modifier = Modifier.weight(1f)) {
                Text("Show again")
            }
            TextButton(
                onClick = { if (run.answers.isNotEmpty()) onAnswers(run.answers.dropLast(1)) },
                enabled = run.answers.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) { Text("Back a trial") }
        }
    }
}

private fun segmentLabel(segment: String): String = when (segment) {
    "low" -> "low levels"
    "high" -> "higher levels"
    "low_full" -> "low levels, full brightness"
    else -> segment
}

// --- finished ----------------------------------------------------------------------------------

@Composable
private fun ColumnScope.FinishedPanel(
    session: PerceptionSession?,
    probe: ProbeRun?,
    floor: FloorFinder.FloorResult?,
    answered: Int,
    savedPath: String
) {
    if (probe != null) {
        ProbeFinishedPanel(probe, floor, savedPath)
        return
    }
    if (session == null) return
    val report = session.report()
    Text("Done — $answered trials.", style = MaterialTheme.typography.titleMedium)
    if (floor != null) {
        Text(
            "Your floor today: first visible at byte ${floor.firstVisible}, clearly lit at byte " +
                "${floor.clearlyOn}.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Text(
        "Guessing rate on the no-difference trials: " +
            "${(report.falsePositiveRate * 100).toInt()}% (${report.catchTrials} of them). Lower " +
            "is better; if this is high the thresholds below should not be trusted.",
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

/**
 * What the probe found, phrased as what it does and does not settle.
 *
 * Deliberately reports the two shapes separately rather than announcing a conclusion: a mean
 * spacing near one byte and a segment that saw nothing at all are opposite findings, and which one
 * turned up is the whole result. The controls are shown first because neither reading means
 * anything without them.
 */
@Composable
private fun ColumnScope.ProbeFinishedPanel(
    probe: ProbeRun,
    floor: FloorFinder.FloorResult?,
    savedPath: String
) {
    val report = QuantisationProbe.report(probe.plan, probe.trials, probe.answers)
    Text("Probe done — ${probe.answers.size} answers.", style = MaterialTheme.typography.titleMedium)
    if (floor != null) {
        Text(
            "Walked up from byte ${floor.clearlyOn}, where you called it clearly lit.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Text(
        "Said something changed on ${report.catchFalsePositives} of ${report.catchTrials} " +
            "no-change trials, and missed ${report.anchorsMissed} of ${report.anchorTrials} " +
            "obvious ones. Both should be near zero for the rest to mean anything.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    report.segments.forEach { s ->
        Text(
            text = buildString {
                append(segmentLabel(s.label).replaceFirstChar { it.uppercase() })
                append(": ")
                when {
                    s.stepTrials == 0 -> append("not reached.")
                    s.sawNothing -> append(
                        "no change seen at any single byte across ${s.stepTrials} steps."
                    )
                    else -> {
                        append("changed at ${s.changeAtBytes.size} of ${s.stepTrials} steps")
                        s.meanSpacing?.let { append(", about %.1f bytes apart".format(it)) }
                        append(".")
                    }
                }
            },
            style = MaterialTheme.typography.bodySmall
        )
    }
    Text(
        "Your brightness has been put back to where it was.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        "Saved to $savedPath",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

// --- persistence -------------------------------------------------------------------------------

/** A part-finished sitting, as read back off disk. */
private data class InProgress(
    val seed: Long,
    val floor: FloorFinder.FloorResult?,
    val config: SessionConfig,
    val answers: List<Answer>
)

private fun perceptionDir(context: android.content.Context) =
    File(context.getExternalFilesDir(null), "perception").apply { mkdirs() }

private fun inProgressFile(context: android.content.Context) =
    File(perceptionDir(context), "in-progress.json")

/**
 * Writes the sitting's resumable form after every answer.
 *
 * Only the seed, the config, the floor and the answers — the session regenerates everything else
 * from those, so this file cannot describe a state the code could not itself reach. It is small
 * enough that writing it on every answer costs nothing worth measuring.
 */
private fun saveInProgress(
    context: android.content.Context,
    session: PerceptionSession,
    floor: FloorFinder.FloorResult?
) {
    try {
        val root = JSONObject()
        root.put("seed", session.seed)
        root.put("savedAtMs", System.currentTimeMillis())
        floor?.let {
            root.put(
                "floor",
                JSONObject().apply {
                    put("firstVisible", it.firstVisible)
                    put("clearlyOn", it.clearlyOn)
                }
            )
        }
        root.put(
            "config",
            JSONObject().apply {
                put("baseLevels", JSONArray(session.config.baseLevels))
                put("ditherIntervals", JSONArray(session.config.ditherIntervals))
                put("ditherBase", session.config.ditherBase)
                put("fadeFrom", session.config.fadeFrom)
                put("fadeSpan", session.config.fadeSpan)
                put("fadeRepeats", session.config.fadeRepeats)
                put("reversalsToFinish", session.config.reversalsToFinish)
            }
        )
        root.put(
            "answers",
            JSONArray().apply {
                session.answersSoFar().forEach {
                    put(
                        JSONObject().apply {
                            put("response", it.response.name)
                            put("responseMs", it.responseMs)
                        }
                    )
                }
            }
        )
        inProgressFile(context).writeText(root.toString())
    } catch (e: Exception) {
        android.util.Log.e("PerceptionLab", "Could not save progress", e)
    }
}

private fun readInProgress(context: android.content.Context): InProgress? = try {
    val file = inProgressFile(context)
    if (!file.exists()) {
        null
    } else {
        val root = JSONObject(file.readText())
        val cfg = root.getJSONObject("config")
        val bases = cfg.getJSONArray("baseLevels").let { a -> (0 until a.length()).map { a.getInt(it) } }
        val intervals = cfg.getJSONArray("ditherIntervals")
            .let { a -> (0 until a.length()).map { a.getLong(it) } }
        val answers = root.getJSONArray("answers").let { a ->
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Answer(Response.valueOf(o.getString("response")), o.getLong("responseMs"))
            }
        }
        val floor = root.optJSONObject("floor")?.let {
            FloorFinder.FloorResult(it.getInt("firstVisible"), it.getInt("clearlyOn"))
        }
        // An empty sitting is not worth offering to resume: starting fresh costs the same and does
        // not make him choose.
        if (answers.isEmpty()) {
            null
        } else {
            InProgress(
                seed = root.getLong("seed"),
                floor = floor,
                config = SessionConfig(
                    baseLevels = bases,
                    ditherIntervals = intervals,
                    ditherBase = cfg.getInt("ditherBase"),
                    fadeFrom = cfg.getInt("fadeFrom"),
                    fadeSpan = cfg.getInt("fadeSpan"),
                    fadeRepeats = cfg.getInt("fadeRepeats"),
                    reversalsToFinish = cfg.getInt("reversalsToFinish")
                ),
                answers = answers
            )
        }
    }
} catch (e: Exception) {
    android.util.Log.e("PerceptionLab", "Could not read saved progress", e)
    null
}

private fun clearInProgress(context: android.content.Context) {
    try {
        inProgressFile(context).delete()
    } catch (e: Exception) {
        android.util.Log.e("PerceptionLab", "Could not clear saved progress", e)
    }
}

/**
 * Writes the finished sitting to JSON beside the diagnostics logs.
 *
 * Every trial is written out, not just the summary, so the analysis can be redone differently later
 * without asking Joe to sit through it again - which is the whole reason to record a response time
 * and a catch flag per trial rather than a threshold per level. The floor is written with it,
 * because a threshold in bytes means nothing without the level it was measured from.
 */
private fun writeReport(
    context: android.content.Context,
    session: PerceptionSession,
    floor: FloorFinder.FloorResult?
): String? = try {
    val report = session.report()
    val root = JSONObject()
    root.put("generatedAtMs", System.currentTimeMillis())
    root.put("seed", session.seed)
    floor?.let {
        root.put(
            "floor",
            JSONObject().apply {
                put("firstVisible", it.firstVisible)
                put("clearlyOn", it.clearlyOn)
            }
        )
    }
    root.put("baseLevels", JSONArray(session.config.baseLevels))
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
    val file = File(perceptionDir(context), "perception_${System.currentTimeMillis()}.json")
    file.writeText(root.toString(2))
    file.absolutePath
} catch (e: Exception) {
    android.util.Log.e("PerceptionLab", "Could not write report", e)
    null
}

// --- probe persistence ---------------------------------------------------------------------------

/** A part-finished probe, as read back off disk. */
private data class ProbeInProgress(
    val seed: Long,
    val floor: FloorFinder.FloorResult?,
    val answers: List<Boolean>
)

private fun probeInProgressFile(context: android.content.Context) =
    File(perceptionDir(context), "probe-in-progress.json")

/**
 * Writes the probe's resumable form after every answer.
 *
 * Only the seed, the floor and the answers: the plan is derived from the floor and the trials from
 * the plan and the seed, so there is nothing else that could drift. Kept in its own file rather
 * than sharing the sitting's, so having one of each part-finished does not make them fight over it.
 */
private fun saveProbeInProgress(
    context: android.content.Context,
    run: ProbeRun,
    floor: FloorFinder.FloorResult?
) {
    try {
        val root = JSONObject()
        root.put("seed", run.seed)
        root.put("savedAtMs", System.currentTimeMillis())
        floor?.let {
            root.put(
                "floor",
                JSONObject().apply {
                    put("firstVisible", it.firstVisible)
                    put("clearlyOn", it.clearlyOn)
                }
            )
        }
        root.put("answers", JSONArray().apply { run.answers.forEach { put(it) } })
        probeInProgressFile(context).writeText(root.toString())
    } catch (e: Exception) {
        android.util.Log.e("PerceptionLab", "Could not save probe progress", e)
    }
}

private fun readProbeInProgress(context: android.content.Context): ProbeInProgress? = try {
    val file = probeInProgressFile(context)
    if (!file.exists()) {
        null
    } else {
        val root = JSONObject(file.readText())
        val answers = root.getJSONArray("answers").let { a ->
            (0 until a.length()).map { a.getBoolean(it) }
        }
        val floor = root.optJSONObject("floor")?.let {
            FloorFinder.FloorResult(it.getInt("firstVisible"), it.getInt("clearlyOn"))
        }
        // Without a floor there is no plan to rebuild, and an empty run costs nothing to restart.
        if (answers.isEmpty() || floor == null) {
            null
        } else {
            ProbeInProgress(seed = root.getLong("seed"), floor = floor, answers = answers)
        }
    }
} catch (e: Exception) {
    android.util.Log.e("PerceptionLab", "Could not read saved probe", e)
    null
}

private fun clearProbeInProgress(context: android.content.Context) {
    try {
        probeInProgressFile(context).delete()
    } catch (e: Exception) {
        android.util.Log.e("PerceptionLab", "Could not clear saved probe", e)
    }
}

/**
 * Writes the finished probe to JSON beside the sitting reports.
 *
 * Every trial is written with the two bytes it compared and the answer given, so the spacings can
 * be recomputed differently later — the derived figures here are a convenience, not the record.
 * [joeBrightness] is written because the whole result is a statement about one brightness setting
 * and is meaningless pooled across two.
 */
private fun writeProbeReport(
    context: android.content.Context,
    run: ProbeRun,
    floor: FloorFinder.FloorResult?,
    joeBrightness: Int
): String? = try {
    val report = QuantisationProbe.report(run.plan, run.trials, run.answers)
    val root = JSONObject()
    root.put("kind", "quantisation_probe")
    root.put("generatedAtMs", System.currentTimeMillis())
    root.put("seed", run.seed)
    root.put("brightnessPercent", joeBrightness)
    floor?.let {
        root.put(
            "floor",
            JSONObject().apply {
                put("firstVisible", it.firstVisible)
                put("clearlyOn", it.clearlyOn)
            }
        )
    }
    root.put("catchTrials", report.catchTrials)
    root.put("catchFalsePositives", report.catchFalsePositives)
    root.put("anchorTrials", report.anchorTrials)
    root.put("anchorsMissed", report.anchorsMissed)
    root.put("segments", JSONArray().apply {
        report.segments.forEach { s ->
            put(JSONObject().apply {
                put("label", s.label)
                put("commandedBrightness", s.brightnessPercent ?: JSONObject.NULL)
                put("stepTrials", s.stepTrials)
                put("changeAtBytes", JSONArray(s.changeAtBytes))
                put("spacings", JSONArray(s.spacings))
                put("meanSpacing", s.meanSpacing ?: JSONObject.NULL)
            })
        }
    })
    root.put("trials", JSONArray().apply {
        run.trials.take(run.answers.size).forEachIndexed { i, t ->
            put(JSONObject().apply {
                put("segment", t.segment)
                put("kind", t.kind.name)
                put("fromByte", t.fromByte)
                put("toByte", t.toByte)
                put("commandedBrightness", t.brightnessPercent ?: JSONObject.NULL)
                put("walkIndex", t.walkIndex)
                put("changed", run.answers[i])
            })
        }
    })
    val file = File(perceptionDir(context), "probe_${System.currentTimeMillis()}.json")
    file.writeText(root.toString(2))
    file.absolutePath
} catch (e: Exception) {
    android.util.Log.e("PerceptionLab", "Could not write probe report", e)
    null
}
