package com.example.ambiance

import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Runs the shipped ambiance decision over real film frames and counts what the strip would have
 * emitted.
 *
 * ## Why this exists
 *
 * `AmbianceDarkSceneSimulation` fed synthetic aggregate colours in, which skipped the two stages
 * that look at pixels - the letterbox crop and the 4x4 zone grid - and scored configurations with a
 * metric that counted the *size* of each step and ignored how *often* steps happened. It ranked the
 * 2026-09-04 change as an improvement and Joe's verdict on the hardware was "everything is steppy
 * now". Its own header says not to trust its ranking.
 *
 * This one differs in four ways that matter:
 *
 *  1. **Real content.** Frames from Tears of Steel (Blender Foundation, CC-BY 3.0), letterboxed into
 *     a phone-shaped capture the way a fullscreen video actually reaches MediaProjection.
 *  2. **The whole decision**, ROI crop and grid included, via [AmbianceFrameAnalyser].
 *  3. **Two-sided metric.** Step size *and* transition rate *and* direction reversals, which is the
 *     shimmer half the old metric could not see.
 *  4. **Joe's brightness.** Emitted levels use the confirmed `ceil(byte * brightness / 100)` rule at
 *     22%, not the 100% the LUT was measured at.
 *
 * And it carries the ground-truth gate the old one failed: **dark clips must score worse than the
 * bright clip.** A metric that says otherwise is wrong before it is used to justify anything.
 *
 * ## Running it
 *
 * Frames are not in the repo (about 150MB). Regenerate them with `tools/ambiance-bench/fetch.py`,
 * then point the bench at them:
 *
 * ```
 * ./gradlew :app:testDebugUnitTest --tests '*AmbianceVideoBench*' -Dambiance.frames=<dir>
 * ```
 *
 * Without that property it skips, so the ordinary suite is unaffected.
 */
class AmbianceVideoBench {

    /** Joe runs the strip here, and it is the only place the quantisation rule was confirmed. */
    private val brightnessPercent = 22

    /** Capture cadence: `max(1000/20fps, pacing)` with the shipped 50ms pacing. */
    private val frameMs = 50L

    private fun framesRoot(): File? =
        System.getProperty("ambiance.frames")?.let { File(it) }?.takeIf { it.isDirectory }

    /** The confirmed grid: what the strip can actually emit for a commanded byte at a brightness. */
    private fun emitted(byte: Int): Int =
        ceil(byte * brightnessPercent / 100.0).toInt()

    private data class Trace(
        val name: String,
        val frames: Int,
        /** Emitted levels per frame, one triple per accepted frame. */
        val levels: List<Triple<Int, Int, Int>>,
        val rois: List<AmbianceFrameAnalyser.Roi>
    )

    /** How the letterbox rectangle is chosen. The shipped arm is [PER_FRAME]. */
    private enum class RoiMode {
        /** What ships: re-detected from scratch on every frame, with no memory. */
        PER_FRAME,

        /** Detected once and never revisited. Not a candidate fix - it is the causal control. */
        LOCKED,

        /** A candidate: a new rectangle has to be both a real move and a persistent one. */
        HYSTERESIS
    }

    /** A move smaller than this on every edge is noise, not a new letterbox. */
    private val roiMoveThresholdPx = 8

    /** And it has to hold for this many frames before the crop follows it. */
    private val roiPersistFrames = 3

    private fun run(
        clipDir: File,
        ease: Boolean,
        roiMode: RoiMode,
        ablation: AmbianceAblation = AmbianceAblation()
    ): Trace {
        val analyser = AmbianceFrameAnalyser()
        val tuning = AmbianceTuning()
        val files = clipDir.listFiles { f -> f.extension.lowercase() == "png" }!!.sortedBy { it.name }

        var curLinR = 0.0; var curLinG = 0.0; var curLinB = 0.0
        var hasTarget = false
        val levels = ArrayList<Triple<Int, Int, Int>>(files.size)
        val rois = ArrayList<AmbianceFrameAnalyser.Roi>(files.size)

        var heldRoi: AmbianceFrameAnalyser.Roi? = null
        var candidate: AmbianceFrameAnalyser.Roi? = null
        var candidateAge = 0

        for (f in files) {
            val img = ImageIO.read(f)
            val w = img.width
            val h = img.height
            val pixel = { x: Int, y: Int -> img.getRGB(x, y) and 0xFFFFFF }

            val override: AmbianceFrameAnalyser.Roi? = when (roiMode) {
                RoiMode.PER_FRAME -> null
                RoiMode.LOCKED -> heldRoi ?: analyser.detectRoi(w, h, pixel).also { heldRoi = it }
                RoiMode.HYSTERESIS -> {
                    val detected = analyser.detectRoi(w, h, pixel)
                    val held = heldRoi
                    if (held == null) {
                        heldRoi = detected
                    } else {
                        val move = maxOf(
                            abs(held.left - detected.left), abs(held.top - detected.top),
                            abs(held.right - detected.right), abs(held.bottom - detected.bottom)
                        )
                        if (move < roiMoveThresholdPx) {
                            candidate = null
                            candidateAge = 0
                        } else {
                            val c = candidate
                            val nearSame = c != null && maxOf(
                                abs(c.left - detected.left), abs(c.top - detected.top),
                                abs(c.right - detected.right), abs(c.bottom - detected.bottom)
                            ) < roiMoveThresholdPx
                            if (nearSame) {
                                candidateAge++
                            } else {
                                candidate = detected
                                candidateAge = 1
                            }
                            if (candidateAge >= roiPersistFrames) {
                                heldRoi = candidate
                                candidate = null
                                candidateAge = 0
                            }
                        }
                    }
                    heldRoi
                }
            }

            val result = analyser.analyse(w, h, frameMs, tuning, override, ablation, pixel)
            rois.add(result.roi)

            val (r, g, b) = result.color
            val tR = com.example.core.color.ColorConverter.srgbToLinear(r)
            val tG = com.example.core.color.ColorConverter.srgbToLinear(g)
            val tB = com.example.core.color.ColorConverter.srgbToLinear(b)

            if (!hasTarget || !ease) {
                curLinR = tR; curLinG = tG; curLinB = tB
                hasTarget = true
            } else {
                // The interpolator ticks at the pacing and closes easeAlpha of the distance. One
                // tick per captured frame here: pacing 50ms and capture 50ms are the same number.
                val a = AmbianceOutputRules.easeAlpha(frameMs)
                curLinR += a * (tR - curLinR)
                curLinG += a * (tG - curLinG)
                curLinB += a * (tB - curLinB)
            }

            levels.add(
                Triple(
                    emitted(com.example.core.color.ColorConverter.linearToSrgb(curLinR)),
                    emitted(com.example.core.color.ColorConverter.linearToSrgb(curLinG)),
                    emitted(com.example.core.color.ColorConverter.linearToSrgb(curLinB))
                )
            )
        }
        return Trace(clipDir.name, files.size, levels, rois)
    }

    private data class Score(
        /** Frames per second on which any channel changed emitted level. */
        val transitionsPerSec: Double,
        /** Direction changes per second, summed over channels: the shimmer term. */
        val reversalsPerSec: Double,
        /** Largest single-frame emitted-level jump on any channel. */
        val maxJump: Int,
        /** Share of transitions bigger than one level: the lurch term. */
        val bigStepShare: Double,
        /** ROI rectangle changes per second. */
        val roiChangesPerSec: Double,
        /** Largest ROI edge move in pixels. */
        val maxRoiMove: Int
    )

    private fun score(t: Trace): Score {
        val secs = t.frames * frameMs / 1000.0
        var transitions = 0
        var big = 0
        var maxJump = 0
        var reversals = 0
        val lastDir = intArrayOf(0, 0, 0)

        for (i in 1 until t.levels.size) {
            val prev = t.levels[i - 1]
            val cur = t.levels[i]
            val d = intArrayOf(cur.first - prev.first, cur.second - prev.second, cur.third - prev.third)
            if (d.any { it != 0 }) transitions++
            val step = d.maxOf { abs(it) }
            if (step > maxJump) maxJump = step
            if (step > 1) big++
            for (c in 0 until 3) {
                if (d[c] == 0) continue
                val dir = if (d[c] > 0) 1 else -1
                if (lastDir[c] != 0 && dir != lastDir[c]) reversals++
                lastDir[c] = dir
            }
        }

        var roiChanges = 0
        var maxRoiMove = 0
        for (i in 1 until t.rois.size) {
            val a = t.rois[i - 1]; val b = t.rois[i]
            if (a != b) roiChanges++
            val move = maxOf(
                abs(a.left - b.left), abs(a.top - b.top),
                abs(a.right - b.right), abs(a.bottom - b.bottom)
            )
            if (move > maxRoiMove) maxRoiMove = move
        }

        return Score(
            transitionsPerSec = transitions / secs,
            reversalsPerSec = reversals / secs,
            maxJump = maxJump,
            bigStepShare = if (transitions == 0) 0.0 else big.toDouble() / transitions,
            roiChangesPerSec = roiChanges / secs,
            maxRoiMove = maxRoiMove
        )
    }

    private data class Arm(
        val label: String,
        val roi: RoiMode = RoiMode.PER_FRAME,
        val ablation: AmbianceAblation = AmbianceAblation()
    )

    private val arms = listOf(
        Arm("shipped"),
        Arm("roi hysteresis", roi = RoiMode.HYSTERESIS),
        Arm("roi locked", roi = RoiMode.LOCKED),
        Arm("no deadband", ablation = AmbianceAblation(deadband = false)),
        Arm("symmetric fall", ablation = AmbianceAblation(asymmetricFall = false)),
        Arm("no sat boost", ablation = AmbianceAblation(saturationBoost = false)),
        Arm("no floor", ablation = AmbianceAblation(floor = false)),
        // The content control: no smoothing at all. Whatever shimmer survives here is in the film,
        // not in the app, and is the floor no amount of rule-fixing can go below without lagging.
        Arm("no ema (content)", ablation = AmbianceAblation(ema = false, deadband = false))
    )

    @Test
    fun `attribute dark-scene shimmer to a stage`() {
        val root = framesRoot()
        Assume.assumeTrue("set -Dambiance.frames=<dir> to run the video bench", root != null)

        val clips = root!!.listFiles { f -> f.isDirectory }!!.sortedBy { it.name }
        println(
            "clip".padEnd(9) + "arm".padEnd(18) + "trans/s".padStart(9) + "rev/s".padStart(8) +
                "maxJump".padStart(9) + "big%".padStart(7) + "lvl range".padStart(11)
        )
        val shipped = mutableMapOf<String, Score>()
        for (clip in clips) {
            for (arm in arms) {
                val trace = run(clip, ease = true, roiMode = arm.roi, ablation = arm.ablation)
                val s = score(trace)
                if (arm.label == "shipped") shipped[clip.name] = s
                val flat = trace.levels.flatMap { listOf(it.first, it.second, it.third) }
                println(
                    clip.name.padEnd(9) + arm.label.padEnd(18) +
                        "%.2f".format(s.transitionsPerSec).padStart(9) +
                        "%.2f".format(s.reversalsPerSec).padStart(8) +
                        s.maxJump.toString().padStart(9) +
                        "%.0f".format(s.bigStepShare * 100).padStart(7) +
                        "${flat.min()}-${flat.max()}".padStart(11)
                )
            }
            println()
        }
        println("brightness $brightnessPercent%, capture ${frameMs}ms, levels are emitted not commanded")

        // The gate the old simulation failed: the metric has to agree with the one thing that is
        // known from the wall, which is that dark scenes misbehave and bright ones do not.
        val bright = shipped["bright"]
        val darks = shipped.filterKeys { it.startsWith("dark") }.values
        if (bright != null && darks.isNotEmpty()) {
            assertTrue(
                "metric disagrees with ground truth: dark scenes must shimmer more than bright ones",
                darks.all { it.reversalsPerSec > bright.reversalsPerSec }
            )
        }
    }
}
