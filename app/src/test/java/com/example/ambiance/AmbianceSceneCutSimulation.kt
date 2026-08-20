package com.example.ambiance

import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.random.Random

/**
 * Answers whether ambiance's scene-cut rule is really per-frame rather than per-second, and whether
 * normalising it by frame length is worth shipping. **It is not** — see the verdict below.
 *
 * The claim (handoff item 4) is that a fixed threshold compared against a delta accumulated over
 * the frame interval must be rate-dependent: a late frame has had longer to drift, so jank
 * manufactures cuts, and any change of capture rate silently re-tunes the rule. That is true of the
 * arithmetic in isolation. It is not true of the pipeline, because the EMA the delta is measured
 * from moves faster on a long frame too (`alpha = 1 - exp(-dt/tau)`), and the two effects very
 * nearly cancel.
 *
 * What the tables below show:
 *
 *  - **Rate.** At sensitivity 90, cuts per minute go 29 (10fps) / 32 (20fps) / 27 (40fps) on a
 *    random-walk scene and 6 / 8 / 8 on a panning one. The shipped rule is already rate-stable
 *    within noise. Both candidate normalisations collapse the 10fps figure to 0–3.
 *  - **Jank.** 5% of frames arriving 600ms late produces *fewer* false cuts per minute than steady
 *    capture, not more. There is no jank-invented-cut effect to fix.
 *  - **Cost.** Where the candidate does suppress false cuts it suppresses real ones just as hard
 *    (sensitivity 90, heavy jank: false 19 → 10, real 8 → 5). Less sensitive, not more selective.
 *
 * So nothing changed in production beyond extracting the rule to [AmbianceSceneCut]. The candidate
 * lives in [candidateThreshold] here so that the next person who notices the same asymmetry can
 * read the numbers rather than re-derive the theory. It also settles the "prerequisite for adaptive
 * capture" note in the plan: there is no rate-dependence to fix first.
 *
 * Prints tables; asserts only the properties that must hold whatever the numbers say.
 */
class AmbianceSceneCutSimulation {

    private val expectedIntervalMs = 50           // 20fps, the shipped ambiance capture rate
    private val REFERENCE_INTERVAL_MS = 50.0      // what the candidate normalises against
    private val durationMs = 60_000
    private val tauMs = 150.0                     // smoothness 150ms at responseSpeed 0.5

    /** Built-in preset thresholds, plus the bottom of the slider that a Custom preset can reach. */
    private val sensitivities = listOf(10.0, 30.0, 60.0, 90.0, 110.0, 150.0)

    /** How fast the picture moves between cuts, mean sRGB bytes per second, per channel. */
    private val driftRates = listOf(20.0, 60.0, 150.0)

    /** (label, probability a frame is late, how late) — the moto stalls the main thread ~3.5s. */
    private val jankProfiles = listOf(
        Triple("none", 0.0, 0L),
        Triple("light 1%/200ms", 0.01, 200L),
        Triple("heavy 5%/600ms", 0.05, 600L),
        Triple("moto 0.5%/3.5s", 0.005, 3500L)
    )

    @Test
    fun `how many cuts does jank invent`() {
        for ((label, jankProb, jankMs) in jankProfiles) {
            println("\n=== False cuts per minute — jank $label ===")
            println("(a false cut is one fired on a frame with no real cut in it; 60s of scene)")
            print("%10s".format("drift/s"))
            sensitivities.forEach { print("%14s".format("S=${it.toInt()}")) }
            println()
            for (drift in driftRates) {
                print("%10.0f".format(drift))
                for (s in sensitivities) {
                    val off = run(s, drift, jankProb, jankMs, timeNormalised = false)
                    val on = run(s, drift, jankProb, jankMs, timeNormalised = true)
                    print("%14s".format("${off.falseCuts} → ${on.falseCuts}"))
                }
                println()
            }
        }
    }

    @Test
    fun `does the normalisation cost real cuts`() {
        println("\n=== Real cuts detected, of 12 per minute ===")
        for ((label, jankProb, jankMs) in jankProfiles) {
            print("%18s".format(label))
            for (s in sensitivities) {
                val off = run(s, 60.0, jankProb, jankMs, timeNormalised = false)
                val on = run(s, 60.0, jankProb, jankMs, timeNormalised = true)
                print("%14s".format("${off.realCutsFound} → ${on.realCutsFound}"))
            }
            println()
        }
    }

    /**
     * The one that matters for what this unblocks: run the *same* scene at three capture rates and
     * see whether "sensitivity 110" means the same thing at each. If the cut count moves with the
     * rate, adaptive capture cannot be built on top of it.
     */
    @Test
    fun `is the rule rate-independent`() {
        for (drift in Drift.values()) {
            println("\n=== Cuts per minute at each capture rate, drift 150/s, $drift ===")
            println("(shipped / scale^0.5 / scale^1.0; 20fps is what the app runs at, so it is the")
            println(" row the other two should look like if sensitivity means one thing at any rate)")
            print("%8s".format("fps"))
            sensitivities.forEach { print("%18s".format("S=${it.toInt()}")) }
            println()
            for ((fps, interval) in listOf(10 to 100, 20 to 50, 40 to 25)) {
                print("%8d".format(fps))
                for (s in sensitivities) {
                    val off = run(s, 150.0, 0.0, 0L, false, interval, drift = drift)
                    val half = run(s, 150.0, 0.0, 0L, true, interval, 0.5, drift)
                    val full = run(s, 150.0, 0.0, 0L, true, interval, 1.0, drift)
                    print("%18s".format(
                        "${off.cutFrames.size}/${half.cutFrames.size}/${full.cutFrames.size}"
                    ))
                }
                println()
            }
        }
    }

    /**
     * The jank case at the two candidate exponents, so the exponent is not chosen on the rate table
     * alone: false cuts invented by lateness, against real cuts lost to the relaxed threshold.
     */
    @Test
    fun `which exponent survives jank`() {
        println("\n=== Heavy jank (5% of frames 600ms late), drift 150/s ===")
        println("%8s %26s %26s".format("", "false cuts/min", "real cuts found of 12"))
        println("%8s %8s %8s %8s %8s %8s %8s".format(
            "S", "shipped", "^0.5", "^1.0", "shipped", "^0.5", "^1.0"))
        for (s in sensitivities) {
            val off = run(s, 150.0, 0.05, 600L, false)
            val half = run(s, 150.0, 0.05, 600L, true, exponent = 0.5)
            val full = run(s, 150.0, 0.05, 600L, true, exponent = 1.0)
            println("%8.0f %8d %8d %8d %8d %8d %8d".format(
                s, off.falseCuts, half.falseCuts, full.falseCuts,
                off.realCutsFound, half.realCutsFound, full.realCutsFound))
        }
    }

    /** At the shipped rate with no jank every frame scales by 1.0, so the candidate is a no-op. */
    @Test
    fun `the candidate changes nothing at the shipped rate`() {
        for (s in sensitivities) {
            for (drift in driftRates) {
                val off = run(s, drift, jankProb = 0.0, jankMs = 0L, timeNormalised = false)
                val on = run(s, drift, jankProb = 0.0, jankMs = 0L, timeNormalised = true)
                assert(off.cutFrames == on.cutFrames) {
                    "S=$s drift=$drift: on-time frames must decide identically, " +
                        "got ${off.cutFrames} vs ${on.cutFrames}"
                }
            }
        }
    }

    /** A late frame may only ever make the candidate *harder* to fire, never easier. */
    @Test
    fun `the candidate never invents a cut the shipped rule would not fire`() {
        for (s in sensitivities) {
            for ((_, jankProb, jankMs) in jankProfiles) {
                val off = run(s, 60.0, jankProb, jankMs, timeNormalised = false)
                val on = run(s, 60.0, jankProb, jankMs, timeNormalised = true)
                assert(on.cutFrames.size <= off.cutFrames.size) {
                    "S=$s jank=$jankMs: normalised fired ${on.cutFrames.size} cuts, " +
                        "shipped fired ${off.cutFrames.size}"
                }
            }
        }
    }

    /**
     * The rejected candidate, kept here rather than in production code: scale the threshold by how
     * long the frame took, against the 50ms the app actually captures at, so the rule asks "how
     * fast is it moving" instead of "how far has it moved". Never scales below 1.0 — a short frame
     * is not evidence of a cut — and is capped just under the largest delta possible (255) so a
     * total change still registers however late the frame is.
     */
    private fun candidateThreshold(sensitivity: Double, frameIntervalMs: Long, exponent: Double) =
        (sensitivity * (frameIntervalMs / REFERENCE_INTERVAL_MS).coerceAtLeast(1.0).pow(exponent))
            .coerceAtMost(250.0)

    /** How the picture moves between cuts. */
    enum class Drift { RANDOM_WALK, RAMP }

    private data class Result(
        val cutFrames: List<Int>,
        val falseCuts: Int,
        val realCutsFound: Int
    )

    /**
     * One 60-second capture session. The scene is a grey level doing a random walk at [driftPerSec]
     * bytes/s with a real cut every five seconds; frames arrive every [expectedIntervalMs] except
     * for the janked ones. The EMA update mirrors [AmbianceProcessor.processFrame] — snap on a cut,
     * otherwise ease with `alpha = 1 - exp(-dt/tau)`.
     */
    private fun run(
        sensitivity: Double,
        driftPerSec: Double,
        jankProb: Double,
        jankMs: Long,
        timeNormalised: Boolean,
        captureIntervalMs: Int = expectedIntervalMs,
        exponent: Double = 1.0,
        drift: Drift = Drift.RANDOM_WALK
    ): Result {
        val random = Random(
            sensitivity.toInt() * 7919 + driftPerSec.toInt() * 31 + jankMs.toInt() + captureIntervalMs
        )
        var scene = 128.0
        var ema = 128.0
        var t = 0L
        var lastFrameT = 0L
        val cutFrames = mutableListOf<Int>()
        var falseCuts = 0
        val realCutsFound = mutableSetOf<Int>()
        var frameIndex = 0
        var panDirection = 1.0
        var pendingRealCut = -1
        var pendingUntilFrame = -1
        var nextRealCutMs = 5_000L
        val realCutTimes = mutableListOf<Long>()

        while (t < durationMs) {
            val late = random.nextDouble() < jankProb
            val step = captureIntervalMs + if (late) jankMs else 0L
            val prevT = t
            t += step

            // Advance the scene over the whole interval, so a longer gap really does drift further.
            val seconds = step / 1000.0
            // A random walk grows as sqrt(t); a pan or a fade grows as t. Which one video looks
            // like decides whether a fixed threshold is rate-dependent at all, so both are run.
            val moved = when (drift) {
                Drift.RANDOM_WALK -> random.nextGaussian() * driftPerSec * Math.sqrt(seconds)
                Drift.RAMP -> panDirection * driftPerSec * seconds
            }
            if (drift == Drift.RAMP && random.nextDouble() < 0.02 * seconds * 20) {
                panDirection = -panDirection
            }
            scene = (scene + moved).coerceIn(0.0, 255.0)
            var realCutInThisFrame = -1
            while (nextRealCutMs in (prevT + 1)..t) {
                scene = (scene + (if (random.nextBoolean()) 1 else -1) * (80 + random.nextInt(70)))
                    .coerceIn(0.0, 255.0)
                realCutTimes.add(nextRealCutMs)
                realCutInThisFrame = realCutTimes.size - 1
                nextRealCutMs += 5_000L
            }

            val dt = (t - lastFrameT).coerceAtLeast(1L).coerceAtMost(500L)
            lastFrameT = t
            val raw = scene.toInt()
            val emaByte = ema.toInt()
            val delta = AmbianceSceneCut.delta(raw, raw, raw, emaByte, emaByte, emaByte)
            val isCut = if (timeNormalised) {
                delta > candidateThreshold(sensitivity, dt, exponent)
            } else {
                AmbianceSceneCut.isCut(delta, sensitivity)
            }

            // A cut is credited to a real one if it fires on that frame or on either of the two
            // after it — the shipped rule often needs a frame or two of easing before the delta
            // clears the threshold, and calling that a miss *and* a false positive would double-
            // count one event.
            if (realCutInThisFrame >= 0) {
                pendingRealCut = realCutInThisFrame
                pendingUntilFrame = frameIndex + 2
            }
            if (isCut) {
                cutFrames.add(frameIndex)
                if (pendingRealCut >= 0 && frameIndex <= pendingUntilFrame) {
                    realCutsFound.add(pendingRealCut)
                    pendingRealCut = -1
                } else {
                    falseCuts++
                }
                ema = scene
            } else {
                val alpha = (1.0 - exp(-dt / tauMs)).coerceIn(0.01, 1.0)
                if (abs(scene - ema) > 5.0) ema += alpha * (scene - ema)
            }
            frameIndex++
        }
        return Result(cutFrames, falseCuts, realCutsFound.size)
    }

    /** Box-Muller, since kotlin.random has no Gaussian. */
    private fun Random.nextGaussian(): Double {
        var u = nextDouble(); var v = nextDouble()
        if (u < 1e-9) u = 1e-9
        if (v < 1e-9) v = 1e-9
        return Math.sqrt(-2.0 * Math.log(u)) * Math.cos(2.0 * Math.PI * v)
    }
}
