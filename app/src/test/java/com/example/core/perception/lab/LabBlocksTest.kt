package com.example.core.perception.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the battery has to get right before it is worth a minute of Joe's evening.
 *
 * The failures worth testing for here are all failures of *aim*, not of arithmetic — a block that
 * runs perfectly and measures the wrong thing costs a sitting and produces a number that looks
 * fine. Three have already happened on this project and each has a test below: stimuli placed at
 * levels the strip does not render, a dither pair that cannot differ, and a control set that cannot
 * tell "saw nothing" from "was not watching".
 */
class LabBlocksTest {

    /** Joe's setup as measured on 2026-09-05: floor at byte 5, 25% brightness, so a 4-byte grid. */
    private val joe = LabContext(floorFirstVisible = 5, floorClearlyOn = 5, brightnessPercent = 25)

    // --- the context ------------------------------------------------------------------------------

    @Test
    fun `grid spacing follows the measured rule`() {
        assertEquals(4, joe.gridSpacing)
        assertEquals(1, LabContext(1, 2, 100).gridSpacing)
        assertEquals(5, LabContext(1, 2, 20).gridSpacing)
    }

    @Test
    fun `emitted level matches the rule the probe confirmed`() {
        // ceil(byte * 25 / 100): bytes 5-8 are all level 2, byte 9 is level 3.
        assertEquals(2, joe.levelForByte(5))
        assertEquals(2, joe.levelForByte(8))
        assertEquals(3, joe.levelForByte(9))
    }

    @Test
    fun `byteForLevel and levelForByte agree`() {
        for (level in 1..60) {
            assertEquals(level, joe.levelForByte(joe.byteForLevel(level)))
        }
    }

    // --- every block, structurally ----------------------------------------------------------------

    private fun allBlocks(): List<Pair<LabBlocks.BlockSpec, List<LabTrial>>> =
        LabBlocks.ALL.map { it to LabBlocks.trialsFor(it, joe, seed = 4L) }

    @Test
    fun `every block produces trials and every trial is answerable`() {
        for ((spec, trials) in allBlocks()) {
            assertTrue("${spec.id} produced nothing", trials.isNotEmpty())
            trials.forEach {
                assertTrue("${spec.id}: no options", it.options.isNotEmpty())
                assertTrue("${spec.id}: no intervals", it.intervals.isNotEmpty())
                assertTrue("${spec.id}: empty question", it.question.isNotBlank())
                it.correctOptionId?.let { correct ->
                    assertTrue(
                        "${spec.id}: correct answer $correct is not an option",
                        it.options.any { o -> o.id == correct }
                    )
                }
            }
        }
    }

    @Test
    fun `every block carries catch trials, and the ones that can read null carry anchors`() {
        for ((spec, trials) in allBlocks()) {
            assertTrue("${spec.id} has no catch trials", trials.any { it.isCatch })
        }
        // A block whose expected result may be "nothing was visible anywhere" needs anchors, or
        // that result is indistinguishable from inattention. The scale block gets its anchors from
        // the top of its own delta ladder rather than from separate trials.
        val floorGrid = LabBlocks.trialsFor(LabBlocks.FLOOR_GRID, joe, seed = 4L)
        assertTrue(floorGrid.any { it.isAnchor })
        val scale = LabBlocks.trialsFor(LabBlocks.SCALE, joe, seed = 4L)
        assertTrue(
            "the scale block's large deltas are its attention check",
            scale.any { it.meta["delta"] == LabBlocks.SCALE_DELTAS.max() }
        )
    }

    @Test
    fun `trial lists are a pure function of context and seed`() {
        for (spec in LabBlocks.ALL) {
            assertEquals(
                LabBlocks.trialsFor(spec, joe, 9L).map { it.meta },
                LabBlocks.trialsFor(spec, joe, 9L).map { it.meta }
            )
        }
        assertNotEquals(
            LabBlocks.trialsFor(LabBlocks.SCALE, joe, 9L).map { it.meta },
            LabBlocks.trialsFor(LabBlocks.SCALE, joe, 10L).map { it.meta }
        )
    }

    @Test
    fun `no stimulus is ever commanded below the measured floor`() {
        // The mistake that made most of the first sitting invisible: levels chosen without asking
        // the strip what it renders. The scale block is exempt, since it runs at 100% brightness
        // where its own floor is byte 1.
        val blocks = listOf(LabBlocks.FLOOR_GRID, LabBlocks.RATE, LabBlocks.DITHER)
        for (spec in blocks) {
            for (trial in LabBlocks.trialsFor(spec, joe, 4L)) {
                for (stimulus in trial.intervals) {
                    for (step in stimulus.steps) {
                        assertTrue(
                            "${spec.id} commands byte ${step.byte}, below the floor of ${joe.floorClearlyOn}",
                            step.byte >= joe.floorClearlyOn || step.byte == 0
                        )
                    }
                }
            }
        }
    }

    // --- block 3, where the last attempt went wrong ------------------------------------------------

    @Test
    fun `every dither pair straddles a grid boundary, so it can actually differ`() {
        // The 2026-09-05 sitting dithered bytes 5 and 6, which at 25% are both emitted level 2. The
        // interval emitted one steady level, so the block measured nothing in either direction.
        for (lower in LabBlocks.ditherBoundaries(joe)) {
            assertNotEquals(
                "bytes $lower and ${lower + 1} emit the same level - this pair cannot be dithered",
                joe.levelForByte(lower),
                joe.levelForByte(lower + 1)
            )
        }
    }

    @Test
    fun `dither boundaries hold at other brightness settings too`() {
        for (percent in listOf(10, 20, 22, 25, 33, 50, 100)) {
            val ctx = LabContext(4, 5, percent)
            for (lower in LabBlocks.ditherBoundaries(ctx)) {
                assertNotEquals(
                    "at $percent%, bytes $lower/${lower + 1} emit the same level",
                    ctx.levelForByte(lower),
                    ctx.levelForByte(lower + 1)
                )
            }
        }
    }

    @Test
    fun `the flicker comparison sends the same writes at the same moments`() {
        // A dithered interval sends ~100 writes and a single held level sends one. Compared against
        // a plain hold, the dithered interval could be picked out by its write pattern rather than
        // by any flicker - a confident answer to the wrong question.
        val flicker = LabBlocks.trialsFor(LabBlocks.DITHER, joe, 4L).filter { it.kind == "flicker" }
        assertTrue(flicker.isNotEmpty())
        flicker.forEach { trial ->
            val (a, b) = trial.intervals
            assertEquals("write counts differ", a.steps.size, b.steps.size)
            assertEquals(
                "write moments differ",
                a.steps.map { it.holdMs },
                b.steps.map { it.holdMs }
            )
        }
    }

    // --- block 2 --------------------------------------------------------------------------------

    @Test
    fun `rate trials differ only in update rate, not in how far the fade travels`() {
        val rate = LabBlocks.trialsFor(LabBlocks.RATE, joe, 4L).filter { it.kind == "rate" }
        assertTrue(rate.isNotEmpty())
        rate.forEach { trial ->
            val (a, b) = trial.intervals
            assertEquals("fades must start together", a.steps.first().byte, b.steps.first().byte)
            assertEquals("fades must end together", a.steps.last().byte, b.steps.last().byte)
            assertEquals("fades must take the same time", a.durationMs, b.durationMs)
            assertNotEquals("but must differ in update count", a.steps.size, b.steps.size)
        }
    }

    // --- block 1 --------------------------------------------------------------------------------

    @Test
    fun `the scale block runs at full brightness, where byte and emitted level coincide`() {
        val scale = LabBlocks.trialsFor(LabBlocks.SCALE, joe, 4L)
        assertTrue(scale.all { it.brightnessPercent == 100 })
        assertEquals(1, LabBlocks.FULL_BRIGHTNESS_CONTEXT.gridSpacing)
    }

    @Test
    fun `the scale block covers every anchor at every delta`() {
        val jnd = LabBlocks.trialsFor(LabBlocks.SCALE, joe, 4L).filter { it.kind == "jnd" }
        for (anchor in LabBlocks.SCALE_ANCHORS) {
            val deltas = jnd.filter { it.meta["anchor"] == anchor }.mapNotNull { it.meta["delta"] }
            assertEquals(LabBlocks.SCALE_DELTAS.toSet(), deltas.toSet())
        }
    }

    // --- the readings ----------------------------------------------------------------------------

    /** Answers a whole block as a viewer who sees a change iff the emitted level moves. */
    private fun gridViewer(trials: List<LabTrial>, ctx: LabContext): List<LabAnswer> =
        trials.map { t ->
            val from = t.meta["fromByte"]
            val to = t.meta["toByte"]
            val changed = from != null && to != null && ctx.levelForByte(from) != ctx.levelForByte(to)
            LabAnswer(if (changed) "yes" else "no", 500)
        }

    @Test
    fun `the grid reading recovers the spacing and agrees with the rule`() {
        val trials = LabBlocks.trialsFor(LabBlocks.FLOOR_GRID, joe, 4L)
        val reading = LabAnalysis.gridReading(trials, gridViewer(trials, joe), joe)
        assertEquals(4.0, reading.meanSpacing!!, 0.01)
        assertTrue(reading.agreesWithRule)
    }

    @Test
    fun `a perfect viewer trips no controls`() {
        val trials = LabBlocks.trialsFor(LabBlocks.FLOOR_GRID, joe, 4L)
        val controls = LabAnalysis.controls(trials, gridViewer(trials, joe))
        assertEquals(0, controls.catchFalsePositives)
        assertEquals(0, controls.anchorsMissed)
        assertTrue(controls.trustworthy)
    }

    @Test
    fun `an inattentive viewer trips the anchors and is not read as seeing nothing`() {
        val trials = LabBlocks.trialsFor(LabBlocks.FLOOR_GRID, joe, 4L)
        val controls = LabAnalysis.controls(trials, trials.map { LabAnswer("no", 400) })
        assertEquals(1.0, controls.anchorMissRate, 0.0001)
        assertTrue("silence with anchors missed must not read as trustworthy", !controls.trustworthy)
    }

    @Test
    fun `a yes-to-everything viewer trips the catch trials`() {
        val trials = LabBlocks.trialsFor(LabBlocks.FLOOR_GRID, joe, 4L)
        val controls = LabAnalysis.controls(trials, trials.map { LabAnswer("yes", 400) })
        assertEquals(1.0, controls.falsePositiveRate, 0.0001)
        assertTrue(!controls.trustworthy)
    }

    @Test
    fun `the scale reading recovers a Weber threshold and reports it as proportional`() {
        val trials = LabBlocks.trialsFor(LabBlocks.SCALE, joe, 4L)
        // A synthetic eye with a 10% Weber fraction: visible when delta >= anchor / 10, floored at
        // one byte since the strip cannot do less.
        val answers = trials.map { t ->
            val anchor = t.meta["anchor"] ?: 0
            val delta = t.meta["delta"] ?: 0
            val threshold = maxOf(1, (anchor + 7) / 8)
            LabAnswer(if (delta >= threshold) "yes" else "no", 500)
        }
        val reading = LabAnalysis.scaleReading(trials, answers)
        assertEquals(1, reading.thresholdByAnchor[4])
        assertEquals(1, reading.thresholdByAnchor[8])
        assertEquals(2, reading.thresholdByAnchor[16])
        assertEquals(4, reading.thresholdByAnchor[32])
        assertEquals(8, reading.thresholdByAnchor[64])
        assertEquals(16, reading.thresholdByAnchor[128])
        assertTrue("a Weber eye must not read as flat", !reading.looksFlat)
        assertTrue(reading.inconsistentAnchors.isEmpty())
    }

    @Test
    fun `a flat threshold is reported as flat, because last time that meant a grid`() {
        val trials = LabBlocks.trialsFor(LabBlocks.SCALE, joe, 4L)
        val answers = trials.map { t ->
            LabAnswer(if ((t.meta["delta"] ?: 0) >= 2) "yes" else "no", 500)
        }
        val reading = LabAnalysis.scaleReading(trials, answers)
        assertTrue(reading.looksFlat)
    }

    @Test
    fun `an impossible answer pattern is flagged rather than averaged in`() {
        val trials = LabBlocks.trialsFor(LabBlocks.SCALE, joe, 4L)
        // Saw a 2-byte step at anchor 8 but missed the 16-byte one. Not physically possible on a
        // monotonic response, so it is a lapse and the anchor should be flagged.
        val answers = trials.map { t ->
            val anchor = t.meta["anchor"] ?: 0
            val delta = t.meta["delta"] ?: 0
            val yes = if (anchor == 8) delta == 2 else delta >= 1
            LabAnswer(if (yes) "yes" else "no", 500)
        }
        assertTrue(LabAnalysis.scaleReading(trials, answers).inconsistentAnchors.contains(8))
    }

    @Test
    fun `the rate reading names the lowest rate that was not told apart`() {
        val trials = LabBlocks.trialsFor(LabBlocks.RATE, joe, 4L)
        // A viewer who can spot the slow one below 30Hz and cannot at or above it.
        val answers = trials.mapIndexed { i, t ->
            if (t.kind != "rate") LabAnswer("unsure", 400)
            else {
                val rate = t.meta["rateHz"] ?: 0
                LabAnswer(if (rate < 30) t.correctOptionId!! else "unsure", 400)
            }
        }
        assertEquals(30, LabAnalysis.rateReading(trials, answers).sufficientRate())
    }

    @Test
    fun `the dither reading separates lands-between from flickers`() {
        val trials = LabBlocks.trialsFor(LabBlocks.DITHER, joe, 4L)
        // Dithering works and is invisible as flicker: every brightness comparison right, every
        // flicker comparison answered "can't tell".
        val answers = trials.map { t ->
            when (t.kind) {
                "between" -> LabAnswer(t.correctOptionId!!, 500)
                else -> LabAnswer("unsure", 500)
            }
        }
        val reading = LabAnalysis.ditherReading(trials, answers)
        assertEquals(1.0, reading.landsBetween, 0.0001)
        assertTrue(reading.betweenTrials > 0)
        assertTrue(reading.flickerVisibleByHold.values.all { it == 0.0 })
    }

    @Test
    fun `preference trials are typed as having no right answer`() {
        // Blocks 4-8 are preferences. The type has to keep that separate from discrimination, or a
        // vote count gets reported as a score - which is exactly what the first sitting's fade
        // block did.
        val all = LabBlocks.ALL.flatMap { LabBlocks.trialsFor(it, joe, 4L) }
        assertTrue("nothing in blocks 0-3 is a taste question", all.none { it.isPreference })
        // Every control is scorable, and every trial measuring an unknown is not - those are the
        // two things that must not be confused with each other or with taste.
        assertTrue(all.filter { it.isCatch || it.isAnchor }.all { it.isScorable })
        val steps = all.filter { it.kind == "step" || it.kind == "jnd" }
        assertTrue(steps.isNotEmpty())
        assertTrue("a step's visibility is the measurement, not a score", steps.none { it.isScorable })
        steps.forEach { assertNull(it.correctOptionId) }

        val preference = LabTrial(
            block = "x", kind = "pref", intervals = emptyList(), question = "?", hint = "",
            options = LabOptions.A_B_UNSURE, truth = LabTruth.PREFERENCE, correctOptionId = null,
            meta = emptyMap(), brightnessPercent = null
        )
        assertTrue(preference.isPreference)
        assertTrue(!preference.isScorable)
    }
}
