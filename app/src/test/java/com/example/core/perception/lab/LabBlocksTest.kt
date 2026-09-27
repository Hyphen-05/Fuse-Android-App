package com.example.core.perception.lab

import com.example.core.perception.Stimulus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
        val measurementBlocks = listOf(LabBlocks.FLOOR_GRID, LabBlocks.SCALE, LabBlocks.RATE, LabBlocks.DITHER)
        val all = measurementBlocks.flatMap { LabBlocks.trialsFor(it, joe, 4L) }
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

    // --- the visible-step scale --------------------------------------------------------------------

    private val scale = VisibleScale.MEASURED_2026_09_06

    @Test
    fun `the scale never claims a change finer than the hardware can make`() {
        // One output level is the smallest step the strip has. A scale that interpolated below that
        // would space stimuli the strip cannot render - the 2026-09-05 mistake, one level up.
        for (level in 1..255) {
            assertTrue("threshold at $level is below one level", scale.thresholdAt(level) >= 1.0)
        }
    }

    @Test
    fun `the scale reproduces the anchors it was measured at`() {
        assertEquals(1.0, scale.thresholdAt(4), 0.001)
        assertEquals(1.0, scale.thresholdAt(32), 0.001)
        assertEquals(4.0, scale.thresholdAt(64), 0.001)
        assertEquals(8.0, scale.thresholdAt(128), 0.001)
        // Above the top anchor it extends the Weber fraction rather than holding flat.
        assertEquals(16.0, scale.thresholdAt(256), 0.5)
    }

    @Test
    fun `a jump of the same visible size is a different number of levels at each anchor`() {
        // This is the whole reason the taste blocks are spaced in visible steps rather than bytes:
        // eight steps near the floor is eight levels, and eight steps up at 64 is far more.
        val darkTo = scale.levelAfterVisibleSteps(2, 8.0, maxLevel = 255)
        val brightTo = scale.levelAfterVisibleSteps(64, 8.0, maxLevel = 255)
        assertEquals(8.0, scale.stepsBetween(2, darkTo), 0.6)
        assertEquals(8.0, scale.stepsBetween(64, brightTo), 0.6)
        assertTrue(
            "the bright jump must span more levels for the same visible size",
            brightTo - 64 > darkTo - 2
        )
    }

    @Test
    fun `running out of range returns the ceiling rather than an unreachable level`() {
        // At 25% the strip stops at level 64, and a 32-step jump from the floor does not fit. The
        // caller has to be able to see that it did not get what it asked for.
        val capped = scale.levelAfterVisibleSteps(2, 200.0, maxLevel = 64)
        assertEquals(64, capped)
        assertTrue(scale.stepsBetween(2, capped) < 200.0)
    }

    // --- blocks 4-6, the taste blocks ---------------------------------------------------------------

    private fun tasteBlocks() = listOf(LabBlocks.SMOOTHING, LabBlocks.JUMPS, LabBlocks.NEAR_BLACK)

    @Test
    fun `every taste trial is a preference with its arms recorded`() {
        for (spec in tasteBlocks()) {
            val trials = LabBlocks.trialsFor(spec, joe, 4L)
            assertTrue("${spec.id} produced nothing", trials.isNotEmpty())
            for (t in trials.filter { !it.isCatch }) {
                assertTrue("${spec.id}: ${t.kind} is not a preference", t.isPreference)
                assertNull("${spec.id}: a taste trial cannot have a right answer", t.correctOptionId)
                // The options are "a" and "b", which say nothing once the order is shuffled. Without
                // the arm identities travelling with the trial the answers are unreadable.
                assertTrue("${spec.id}: no armFirst", t.meta.containsKey("armFirst"))
                assertTrue("${spec.id}: no armSecond", t.meta.containsKey("armSecond"))
                assertNotEquals(
                    "${spec.id}: a non-catch trial compared an arm with itself",
                    t.meta["armFirst"], t.meta["armSecond"]
                )
                assertEquals("${spec.id}: a comparison needs two intervals", 2, t.intervals.size)
            }
        }
    }

    @Test
    fun `every taste block asks some pair twice, or its votes cannot be trusted`() {
        // Consistency is not a nicety here. Without a repeat there is no way to tell a preference
        // from a coin, and the reading has no way to say so.
        for (spec in listOf(LabBlocks.SMOOTHING, LabBlocks.NEAR_BLACK)) {
            val trials = LabBlocks.trialsFor(spec, joe, 4L).filter { !it.isCatch }
            val pairs = trials.map { pairKey(it) }
            assertTrue("${spec.id} repeats no pair", pairs.size > pairs.distinct().size)
        }
        // The jumps block repeats every pair by construction: two arms, one ladder rung each.
        val jumps = LabBlocks.trialsFor(LabBlocks.JUMPS, joe, 4L).filter { !it.isCatch }
        assertEquals(LabBlocks.JUMP_REPEATS, jumps.count { it.meta["requestedSteps"] == 2 })
    }

    private fun pairKey(t: LabTrial): String {
        val a = t.meta["armFirst"] ?: 0
        val b = t.meta["armSecond"] ?: 0
        // The scope is part of the identity, and `anchorLevel` alone is not the scope: every rung
        // of the jumps ladder starts from the floor, so keying on it alone collates five different
        // questions into one. `LabAnalysis.PairKey` keys on both for the same reason.
        val anchor = t.meta["anchorLevel"] ?: 0
        val to = t.meta["toLevel"] ?: 0
        return "${t.kind}:$anchor->$to:${minOf(a, b)}-${maxOf(a, b)}"
    }

    @Test
    fun `a taste block's catch trials compare something with itself`() {
        for (spec in tasteBlocks()) {
            val catches = LabBlocks.trialsFor(spec, joe, 4L).filter { it.isCatch }
            assertTrue("${spec.id} has no catch trials", catches.isNotEmpty())
            catches.forEach {
                assertEquals("${spec.id}: a catch must be scorable", "unsure", it.correctOptionId)
                assertEquals(it.intervals[0].steps, it.intervals[1].steps)
            }
        }
    }

    @Test
    fun `settling-speed arms differ only in how they travel, not where they start or finish`() {
        // The confound `fadeAt` was written to remove in block 2, applied to an eased move: two
        // half-lives that ended on different bytes would be compared on final level as much as on
        // the journey, and the answer would look like a preference about smoothness.
        val trials = LabBlocks.trialsFor(LabBlocks.SMOOTHING, joe, 4L).filter { it.kind == "smoothing" }
        assertTrue(trials.isNotEmpty())
        trials.forEach { t ->
            val (a, b) = t.intervals
            assertEquals("must start together", a.steps.first().byte, b.steps.first().byte)
            assertEquals("must finish together", a.steps.last().byte, b.steps.last().byte)
            assertEquals("must take the same time", a.durationMs, b.durationMs)
        }
    }

    @Test
    fun `a slower half-life really is further from settled partway through`() {
        // Guards the stimulus itself rather than the block: if `easedMove` collapsed to the same
        // shape at every half-life, every trial above would still pass and the block would be
        // comparing two identical things.
        val fast = LabBlocks.easedMove(from = 4, to = 100, halfLifeMs = 25L)
        val slow = LabBlocks.easedMove(from = 4, to = 100, halfLifeMs = 300L)
        val at = { s: com.example.core.perception.Stimulus -> s.steps[6].byte }
        assertTrue("the slow arm must lag the fast one", at(slow) < at(fast))
        assertEquals(100, fast.steps.last().byte)
        assertEquals(100, slow.steps.last().byte)
    }

    @Test
    fun `a cut is one write and an ease is many, over the same window`() {
        val trials = LabBlocks.trialsFor(LabBlocks.JUMPS, joe, 4L).filter { it.kind == "cut_or_ease" }
        assertTrue(trials.isNotEmpty())
        trials.forEach { t ->
            val sizes = t.intervals.map { s -> s.steps.count { it.holdMs <= LabBlocks.TICK_MS } }
            assertNotEquals("a cut and an ease must differ in update count", sizes[0], sizes[1])
            assertEquals(
                "but must land on the same level",
                t.intervals[0].steps.last().byte,
                t.intervals[1].steps.last().byte
            )
        }
    }

    @Test
    fun `the lift arms hold the scene's shape and move only its height`() {
        val ctx = LabBlocks.FULL_BRIGHTNESS_CONTEXT
        val low = LabBlocks.dimWalkTopping(20, ctx)
        val high = LabBlocks.dimWalkTopping(96, ctx)
        assertEquals("the two arms must be the same scene", low.steps.size, high.steps.size)
        assertEquals(low.durationMs, high.durationMs)
        assertTrue(high.steps.maxOf { it.byte } > low.steps.maxOf { it.byte } * 3)
    }

    @Test
    fun `the near-black block runs where the smooth region is reachable`() {
        // Its lifted arm sits above emitted level 64, which at Joe's 25% is the top of the range -
        // the comparison could not be played at all at his own brightness.
        assertTrue(LabBlocks.NEAR_BLACK.commandsBrightness)
        assertTrue(LabBlocks.LIFT_TOPS.max() > 64)
        assertTrue(LabBlocks.LIFT_TOPS.max() <= LabBlocks.maxLevel(LabBlocks.FULL_BRIGHTNESS_CONTEXT))
    }

    @Test
    fun `a block that builds stimuli at 100 percent declares it on every trial`() {
        // The bug that spoiled block 6's first sitting on 2026-09-06. Its stimuli were built in a
        // 100% context, where byte and emitted level are the same number, but every trial carried a
        // null brightness - and the runner reads brightness per trial. So it played at Joe's 25%,
        // dividing every emitted level by four: the lifted arm was meant to sit above level 64 and
        // arrived at 24, still deep inside the steppy region. The block ran perfectly and never
        // asked its question.
        for (spec in LabBlocks.ALL.filter { it.commandsBrightness }) {
            val trials = LabBlocks.trialsFor(spec, joe, 4L)
            assertTrue("${spec.id} produced nothing", trials.isNotEmpty())
            assertTrue(
                "${spec.id} commands brightness but leaves trials at the viewer's setting",
                trials.all { it.brightnessPercent == 100 }
            )
        }
        // And the converse: a block that does not command brightness must not claim to.
        for (spec in LabBlocks.ALL.filter { !it.commandsBrightness && !it.isRetired }) {
            assertTrue(
                "${spec.id} does not command brightness but pins one",
                LabBlocks.trialsFor(spec, joe, 4L).all { it.brightnessPercent == null }
            )
        }
    }

    @Test
    fun `taste stimuli stay on or above the floor`() {
        for (spec in listOf(LabBlocks.SMOOTHING, LabBlocks.JUMPS)) {
            LabBlocks.trialsFor(spec, joe, 4L).forEach { t ->
                t.intervals.forEach { s ->
                    s.steps.forEach {
                        assertTrue(
                            "${spec.id} commands byte ${it.byte}, below the floor ${joe.floorClearlyOn}",
                            it.byte >= joe.floorClearlyOn
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `a retired block is kept in place and cannot be run`() {
        // Position 2 is block 2 in every results file and every doc. Dropping it would renumber
        // block 3's data; leaving it runnable would spend a sitting on answers already known to
        // mean nothing.
        assertEquals(LabBlocks.RATE, LabBlocks.ALL[2])
        assertTrue(LabBlocks.RATE.isRetired)
        // Block 8 joined it on 2026-09-27: its steady arm was steady in the wrong unit.
        assertEquals(LabBlocks.HUE_MOTION, LabBlocks.ALL[8])
        assertTrue(LabBlocks.HUE_MOTION.isRetired)
        val retired = setOf(LabBlocks.RATE, LabBlocks.HUE_MOTION)
        assertTrue(LabBlocks.ALL.filter { it !in retired }.none { it.isRetired })
    }

    @Test
    fun `no block runs longer than the menu says it will`() {
        // The original lab design was a median of 195 trials and 25-35 minutes, and Joe was asked to
        // sit through it once. Short separately-runnable blocks are what replaced it, and a ladder
        // quietly doubling in a later edit is how that gets undone.
        for (spec in LabBlocks.ALL) {
            if (spec.isRetired) continue
            val trials = LabBlocks.trialsFor(spec, joe, 4L)
            val playMs = trials.sumOf { t -> t.intervals.sumOf { it.durationMs } }
            // Two seconds per trial for reading the question and answering it.
            val minutes = (playMs + trials.size * 2000L) / 60_000.0
            assertTrue(
                "${spec.id} is ${"%.1f".format(minutes)} min against an advertised ${spec.estimateMinutes}",
                minutes <= spec.estimateMinutes
            )
        }
    }

    // --- the preference reading ----------------------------------------------------------------------

    /** Answers a taste block as someone who consistently prefers the arm with the lower number. */
    private fun prefersLower(trials: List<LabTrial>, kind: String): List<LabAnswer> =
        trials.map { t ->
            if (t.kind != kind || t.isCatch) LabAnswer("unsure", 400)
            else {
                val first = t.meta.getValue("armFirst")
                val second = t.meta.getValue("armSecond")
                LabAnswer(if (first < second) "a" else "b", 400)
            }
        }

    @Test
    fun `a consistent viewer produces a clean ranking`() {
        val trials = LabBlocks.trialsFor(LabBlocks.SMOOTHING, joe, 4L)
        val reading = LabAnalysis.preferenceReading(trials, prefersLower(trials, "smoothing"), "smoothing")
        assertEquals(LabBlocks.HALF_LIVES_MS.map { it.toInt() }, reading.ranking)
        assertEquals(0, reading.transitivityViolations)
        assertEquals(1.0, reading.consistency!!, 0.0001)
        assertTrue(reading.trustworthy)
    }

    @Test
    fun `a viewer who cannot separate the arms is not read as having a preference`() {
        // Block 2's failure: 10 "can't tell" out of 15 read naively as "one rate is as good as
        // another", when what it meant was that none of them were any good.
        val trials = LabBlocks.trialsFor(LabBlocks.SMOOTHING, joe, 4L)
        val reading = LabAnalysis.preferenceReading(
            trials, trials.map { LabAnswer("unsure", 400) }, "smoothing"
        )
        assertEquals(1.0, reading.unsureRate, 0.0001)
        assertTrue("all-unsure must not read as trustworthy", !reading.trustworthy)
    }

    @Test
    fun `a cyclic preference is reported as a confound rather than ranked`() {
        // A over B, B over C, C over A means the arms are not on one axis. A vote count would still
        // produce a tidy-looking ordering out of it.
        val trials = LabBlocks.trialsFor(LabBlocks.NEAR_BLACK, joe, 4L)
        val cycle = listOf(20, 44, 96)
        val answers = trials.map { t ->
            if (t.kind != "lift" || t.isCatch) LabAnswer("unsure", 400)
            else {
                val first = t.meta.getValue("armFirst")
                val second = t.meta.getValue("armSecond")
                // Each arm beats the next one round the cycle.
                val firstWins = cycle[(cycle.indexOf(first) + 1) % cycle.size] == second
                LabAnswer(if (firstWins) "a" else "b", 400)
            }
        }
        val reading = LabAnalysis.preferenceReading(trials, answers, "lift")
        assertTrue("a cycle must be counted", reading.transitivityViolations > 0)
        assertTrue(!reading.trustworthy)
    }

    @Test
    fun `the same two arms at two anchors are two questions, not a contradiction`() {
        // Block 4 asks every pair at a dark anchor and a bright one. Pooling them turns "prefers
        // fast when dark, indifferent when bright" - which is the finding the two anchors exist to
        // produce - into a pile of self-contradictions.
        val trials = LabBlocks.trialsFor(LabBlocks.SMOOTHING, joe, 4L)
        val answers = trials.map { t ->
            if (t.kind != "smoothing" || t.isCatch) LabAnswer("unsure", 400)
            else {
                val first = t.meta.getValue("armFirst")
                val second = t.meta.getValue("armSecond")
                // Opposite preferences at the two anchors, consistently held at each.
                val wantLower = t.meta.getValue("anchorLevel") < 20
                val lowerIsFirst = first < second
                LabAnswer(if (wantLower == lowerIsFirst) "a" else "b", 400)
            }
        }
        val pooled = LabAnalysis.preferenceReading(trials, answers, "smoothing")
        assertEquals("opposite-but-consistent anchors must not read as flips", 0, pooled.hardFlips)
        val byAnchor = LabAnalysis.preferenceReadingsBy(trials, answers, "smoothing", "anchorLevel")
        assertEquals(2, byAnchor.size)
        val anchors = byAnchor.keys.sorted()
        assertEquals(
            "each anchor must produce its own ranking",
            byAnchor.getValue(anchors.first()).ranking,
            byAnchor.getValue(anchors.last()).ranking.reversed()
        )
    }

    @Test
    fun `an unsure answer beside a decisive one is not counted as a contradiction`() {
        // "Can't tell once, picked A the other time" is one answer at the edge of visibility. Only
        // naming opposite winners twice says the vote was a coin, and only that should fail a block.
        val trials = LabBlocks.trialsFor(LabBlocks.SMOOTHING, joe, 4L)
        var seen = mutableSetOf<String>()
        val answers = trials.map { t ->
            if (t.kind != "smoothing" || t.isCatch) LabAnswer("unsure", 400)
            else {
                val key = pairKey(t)
                // Decisive the first time a pair is asked, unsure on its repeat.
                if (seen.add(key)) LabAnswer("a", 400) else LabAnswer("unsure", 400)
            }
        }
        val reading = LabAnalysis.preferenceReading(trials, answers, "smoothing")
        assertTrue(reading.repeatedPairs > 0)
        assertTrue("consistency counts it as a disagreement", reading.consistency!! < 1.0)
        assertEquals("but it is not a hard flip", 0, reading.hardFlips)
        assertEquals(0.0, reading.hardFlipRate!!, 0.0001)
    }

    @Test
    fun `every consistency repeat is the same comparison with the intervals swapped`() {
        // Re-randomising a repeat leaves about half of them in the original order, which controls
        // for nothing. Mirroring makes each repeat separate a preference from an order effect.
        for (spec in tasteBlocks()) {
            val trials = LabBlocks.trialsFor(spec, joe, 4L).filter { !it.isCatch }
            val byPair = trials.groupBy { pairKey(it) }.filterValues { it.size > 1 }
            assertTrue("${spec.id} repeats no pair", byPair.isNotEmpty())
            for ((key, group) in byPair) {
                val orders = group.map { it.meta.getValue("armFirst") }.distinct()
                assertTrue(
                    "${spec.id}: $key was only ever asked one way round",
                    orders.size > 1
                )
                // Mirroring must swap the intervals and nothing else.
                val a = group.first()
                val b = group.first { it.meta.getValue("armFirst") != a.meta.getValue("armFirst") }
                assertEquals(a.intervals.map { it.steps }, b.intervals.reversed().map { it.steps })
            }
        }
    }

    @Test
    fun `an answer that follows the interval is told apart from one that follows the stimulus`() {
        // Six of six decisive answers on catch trials across 2026-09-06/07 named the second
        // interval, while comparisons with a real difference tracked the arm across a swap six
        // times out of seven. He breaks ties by saying "the second one" rather than "can't tell",
        // and a catch trial cannot tell that apart from inattention. The order flip can.
        val trials = LabBlocks.trialsFor(LabBlocks.SMOOTHING, joe, 4L)

        val alwaysSecond = trials.map { LabAnswer("b", 400) }
        val bias = LabAnalysis.preferenceReading(trials, alwaysSecond, "smoothing")
        assertTrue("there must be swapped pairs to read", bias.orderFlipPairs > 0)
        assertEquals("picking the second every time is never about the arm", 0, bias.orderFlipSameArm)
        assertEquals(bias.orderFlipPairs, bias.orderFlipSameLetter)
        assertEquals(0.0, bias.orderRobustness!!, 0.0001)
        assertTrue("a pure order effect must not read as trustworthy", !bias.trustworthy)

        val byArm = trials.map { t ->
            if (t.isCatch) LabAnswer("unsure", 400)
            else LabAnswer(
                if (t.meta.getValue("armFirst") < t.meta.getValue("armSecond")) "a" else "b", 400
            )
        }
        val real = LabAnalysis.preferenceReading(trials, byArm, "smoothing")
        assertEquals(1.0, real.orderRobustness!!, 0.0001)
        assertEquals(0, real.orderFlipSameLetter)
        assertTrue(real.trustworthy)
    }

    @Test
    fun `disagreeing with yourself on a repeated pair shows up as low consistency`() {
        val trials = LabBlocks.trialsFor(LabBlocks.SMOOTHING, joe, 4L)
        var flip = false
        val answers = trials.map { t ->
            if (t.kind != "smoothing" || t.isCatch) LabAnswer("unsure", 400)
            else {
                flip = !flip
                LabAnswer(if (flip) "a" else "b", 400)
            }
        }
        val reading = LabAnalysis.preferenceReading(trials, answers, "smoothing")
        assertTrue(reading.repeatedPairs > 0)
        assertTrue("alternating answers must not read as consistent", reading.consistency!! < 1.0)
    }

    // --- block 7: the ambiance recordings -----------------------------------------------------

    private fun ambiance() = LabBlocks.trialsFor(LabBlocks.AMBIANCE_FALL, joe, seed = 11L)

    @Test
    fun `the two ambiance arms send the same writes at the same moments`() {
        // The point of precomputing both arms from the same frames. If they differed in write count
        // or timing, the steadier one could be picked out by its cadence rather than by looking - the
        // same confound the dither block had to design around.
        AmbianceTraces.ALL.forEach { pair ->
            assertEquals(pair.shipped.size, pair.symmetricFall.size)
        }
        ambiance().filter { it.kind == "fall" }.forEach { trial ->
            val (first, second) = trial.intervals
            assertEquals(first.steps.size, second.steps.size)
            assertEquals(first.steps.map { it.holdMs }, second.steps.map { it.holdMs })
            assertEquals(first.durationMs, second.durationMs)
        }
    }

    @Test
    fun `the two ambiance arms are actually different light`() {
        // A generated file that silently emitted the same trace twice would produce a block of
        // catch trials wearing a preference label, and the reading would call it "no preference".
        ambiance().filter { it.kind == "fall" }.forEach { trial ->
            val (first, second) = trial.intervals
            assertNotEquals(
                "the arms of a preference pair must differ",
                first.steps.map { it.commanded },
                second.steps.map { it.commanded }
            )
        }
    }

    @Test
    fun `ambiance recordings play at his own brightness, not at full`() {
        // The traces are commanded bytes computed with no brightness assumption, and the complaint
        // is about his normal viewing - so this block must pin nothing. The mirror of block 6's bug,
        // which pinned nothing when it needed 100.
        assertTrue(ambiance().all { it.brightnessPercent == null })
        assertTrue(!LabBlocks.AMBIANCE_FALL.commandsBrightness)
    }

    @Test
    fun `ambiance stimuli command colour rather than grey`() {
        // A dark scene wobbles in hue as well as level, and grey would throw away half of what is
        // being judged. The grey `byte` still carries the brightest channel so the floor guard reads
        // something sensible.
        ambiance().flatMap { it.intervals }.forEach { stimulus ->
            assertTrue(stimulus.steps.all { it.rgb != null })
            assertTrue(stimulus.steps.all { step ->
                val (r, g, b) = step.commanded
                step.byte == maxOf(r, g, b)
            })
        }
    }

    @Test
    fun `the ambiance anchor holds one arm perfectly still`() {
        // Without an unmissable trial, a run of "can't tell" cannot be told from having stopped
        // watching - and "can't tell" is a plausible honest answer to every real pair in this block.
        val anchor = ambiance().single { it.isAnchor }
        assertTrue(anchor.isScorable)
        val still = anchor.intervals.single { s -> s.steps.map { it.commanded }.distinct().size == 1 }
        val moving = anchor.intervals.single { it !== still }
        assertTrue(moving.steps.map { it.commanded }.distinct().size > 1)
        assertEquals(still.steps.size, moving.steps.size)
        val stillIsFirst = anchor.intervals.first() === still
        assertEquals(if (stillIsFirst) "a" else "b", anchor.correctOptionId)
    }

    @Test
    fun `every ambiance pair is asked in both orders`() {
        // Block 6's one decisive disagreement came from a pair asked twice the same way round, so
        // "no preference" and "named the second one twice" were indistinguishable.
        val byClip = ambiance().filter { it.kind == "fall" && !it.meta.containsKey("repeat") }
            .groupBy { it.meta.getValue("clip") }
        assertTrue(byClip.isNotEmpty())
        byClip.forEach { (_, trials) ->
            val orders = trials.map { it.meta.getValue("armFirst") }.toSet()
            assertEquals("both orders present for every clip", 2, orders.size)
        }
    }

    // --- block 8: hue motion ------------------------------------------------------------------

    private fun hueLights(stimulus: Stimulus): List<Double> =
        stimulus.steps.map { LabBlocks.totalLight(it.commanded) }

    @Test
    fun `the raw hue sweep pulses and the flattened one does not`() {
        // The measured fact the block exists to put to him: a fixed-value rotation lights one
        // channel at a primary and two between primaries, which on the measured curve is 2x.
        val raw = hueLights(LabBlocks.hueSweep(flat = false))
        val flat = hueLights(LabBlocks.hueSweep(flat = true))
        val rawRatio = raw.max() / raw.min()
        val flatRatio = flat.max() / flat.min()
        println("hue sweep light swing: raw %.2fx, flattened %.2fx".format(rawRatio, flatRatio))
        assertTrue("the raw arm is expected to swing about 2x", rawRatio > 1.8)
        assertTrue("the flattened arm has to be visibly flatter to be worth asking about", flatRatio < 1.1)
    }

    @Test
    fun `both hue arms emit the same mean light`() {
        // The control that makes the pair a question about movement rather than about brightness.
        // Flattening to the dim end would have made the flat arm simply dimmer, and "which looks
        // better" would have collected a preference for more light.
        val raw = hueLights(LabBlocks.hueSweep(flat = false)).average()
        val flat = hueLights(LabBlocks.hueSweep(flat = true)).average()
        assertEquals(raw, flat, raw * 0.02)
    }

    @Test
    fun `the flattened arm reaches its target at every hue, including the primaries`() {
        // The primaries are where flattening has to push *up*, and where it would run out of range
        // if the base value sat too high. That constraint is what picks HUE_BASE_VALUE, so it is
        // pinned rather than left as a comment.
        val target = hueLights(LabBlocks.hueSweep(flat = false)).average()
        hueLights(LabBlocks.hueSweep(flat = true)).forEach { light ->
            assertTrue(
                "flattening must reach the mean at every hue, not clip near it",
                kotlin.math.abs(light - target) < target * 0.05
            )
        }
    }

    @Test
    fun `both hue arms move through the same hues at the same rate`() {
        val raw = LabBlocks.hueSweep(flat = false)
        val flat = LabBlocks.hueSweep(flat = true)
        assertEquals(raw.steps.size, flat.steps.size)
        assertEquals(raw.steps.map { it.holdMs }, flat.steps.map { it.holdMs })
        // Same hue at each step, checked as the dominant channel rather than as a full ordering:
        // at a pure primary the two dark channels are tied at zero and sort arbitrarily.
        raw.steps.zip(flat.steps).forEach { (a, b) ->
            assertEquals(dominantChannel(a.commanded), dominantChannel(b.commanded))
        }

        // The arms are not bit-identical in hue, and the reason is worth printing rather than
        // asserting away: near a primary the minor channel is small, and the cubic in
        // ColorConverter.hsvToRgb crushes small values to zero. The raw arm sits lower on the value
        // axis, so it loses the minor channel where the brighter flattened arm keeps it. That is
        // the same dead zone HuePathAnalysis measures on the brightness axis, showing up as hue
        // coarseness - and it is an argument for replacing the cubic, not against this block.
        val differing = raw.steps.zip(flat.steps).count { (a, b) ->
            litChannels(a.commanded) != litChannels(b.commanded)
        }
        println("hue arms differ in which channels are lit on $differing of ${raw.steps.size} steps")
        assertTrue("the arms must still be the same sweep, not two different colour paths", differing < raw.steps.size / 4)
    }

    private fun dominantChannel(rgb: Triple<Int, Int, Int>): Int =
        listOf(rgb.first, rgb.second, rgb.third).withIndex().maxBy { it.value }.index

    private fun litChannels(rgb: Triple<Int, Int, Int>): Set<Int> =
        listOf(rgb.first, rgb.second, rgb.third)
            .withIndex().filter { it.value > 0 }.map { it.index }.toSet()

    @Test
    fun `hue motion asks one question, and it is a measurement rather than a taste`() {
        // The preference question was dropped after 2026-09-11: it collected position, not taste,
        // and there is nothing to prefer between two motions until one of them is known to be
        // visible. If the swing turns out to be invisible the whole idea dies here, which is
        // cheaper than asking both.
        val trials = LabBlocks.trialsFor(LabBlocks.HUE_MOTION, joe, seed = 5L)
        assertTrue("no taste question until the swing is known to be visible", trials.none { it.isPreference })
        val pump = trials.filter { it.kind == "pump" }
        assertEquals(LabBlocks.HUE_REPEATS, pump.size)
        assertTrue("whether he can see the swing is measured, never scored", pump.none { it.isScorable })
        assertTrue("hue motion plays at his own brightness", trials.all { it.brightnessPercent == null })
        assertTrue("the null answer must read as 'neither', not as defeat",
            trials.all { t -> t.options.first { it.id == "unsure" }.label == "Neither" })
    }

    @Test
    fun `the two interval orders are balanced rather than drawn`() {
        // He breaks ties by naming the second interval. With the orders balanced that habit scores
        // an even split and reads as "nothing seen", which is the truth; drawn at random it can
        // produce a lopsided result that looks like a finding.
        for (seed in listOf(1L, 2L, 3L, 9L)) {
            val pump = LabBlocks.trialsFor(LabBlocks.HUE_MOTION, joe, seed).filter { it.kind == "pump" }
            val rawFirst = pump.count { it.meta["armFirst"] == LabBlocks.ARM_RAW.toInt() }
            assertEquals("seed $seed: orders not balanced", pump.size - rawFirst, rawFirst)
        }
    }

    @Test
    fun `the hue anchor is asked in the block's own words`() {
        // Block 8's 2026-09-11 anchor inherited block 7's "which one was steadier?" into a block
        // that asks about pulsing either side of it, and Joe named the moving arm in 835ms - the
        // right answer to the question he had just been asked three times. An anchor is the block's
        // own question with an unmissable answer, never a different question borrowed from a
        // neighbour.
        val trials = LabBlocks.trialsFor(LabBlocks.HUE_MOTION, joe, seed = 5L)
        val asked = trials.filter { it.kind == "pump" }.map { it.question }.distinct()
        assertEquals(1, asked.size)
        val anchor = trials.single { it.kind == LabTrial.KIND_ANCHOR }
        assertEquals("the anchor asks something else", asked.single(), anchor.question)
        assertNotNull("an anchor has a right answer", anchor.correctOptionId)

        // And it has to be unmissable: far more swing than the 2x under measurement, so a run of
        // "neither" can be told apart from having stopped watching.
        val pumped = LabBlocks.hueSweepPumped()
        val light = pumped.steps.map { LabBlocks.totalLight(it.commanded) }
        assertTrue(
            "the anchor swings ${light.max() / light.min()}x, which is not unmissable",
            light.max() / light.min() > 2.5
        )
        val flat = LabBlocks.hueSweep(flat = true).steps.map { LabBlocks.totalLight(it.commanded) }
        assertTrue("the flat arm should not move", flat.max() / flat.min() < 1.1)
    }

    @Test
    fun `the hue catch compares the steady sweep with itself`() {
        // A catch built on the raw sweep would show him the thing under measurement twice and call a
        // decisive answer a false positive. The honest null answer has to be the true one.
        val catch = LabBlocks.trialsFor(LabBlocks.HUE_MOTION, joe, seed = 5L)
            .single { it.kind == LabTrial.KIND_CATCH }
        assertEquals("unsure", catch.correctOptionId)
        assertEquals(catch.intervals[0].label, catch.intervals[1].label)
        assertEquals("hue_flat", catch.intervals[0].label)
    }

    @Test
    fun `every ambiance trial plays the film, and the same film in both intervals`() {
        // Joe's objection to the 2026-09-11 sitting: with a blank screen he was judging the lights
        // with no context, which is not a situation ambiance is ever in. The film is the fix - and
        // it has to be the SAME film in both intervals, or it becomes a way to tell the arms apart
        // that has nothing to do with the rule under test.
        val trials = LabBlocks.trialsFor(LabBlocks.AMBIANCE_FALL, joe, seed = 5L)
        assertTrue(trials.isNotEmpty())
        for (t in trials) {
            val clips = t.intervals.map { it.clip }
            assertTrue("${t.kind}: an ambiance interval with no film", clips.none { it == null })
            assertEquals("${t.kind}: the two intervals play different films", 1, clips.distinct().size)
        }
    }

    @Test
    fun `every film a trial names is actually shipped`() {
        // A trace id that has no clip cut for it fails silently at runtime - `getIdentifier`
        // returns 0 and the block plays its lights against a black rectangle, which is the state
        // the film exists to replace. Regenerate with `tools/ambiance-bench/cut.py`.
        val named = LabBlocks.ALL
            .filterNot { it.isRetired }
            .flatMap { LabBlocks.trialsFor(it, joe, 5L) }
            .flatMap { t -> t.intervals.mapNotNull { it.clip } }
            .distinct()
        assertTrue("no block names a film at all", named.isNotEmpty())
        for (clip in named) {
            val f = java.io.File("src/main/res/raw/$clip.mp4")
            assertTrue("res/raw/$clip.mp4 is missing - run tools/ambiance-bench/cut.py", f.isFile)
        }
    }

    @Test
    fun `the film's licence is attributed where it is seen`() {
        // Tears of Steel is CC-BY, which is what makes shipping it in the APK fine - and CC-BY
        // wants the attribution to reach whoever sees the clip, not just the repo.
        assertTrue(
            "block 7 shows a CC-BY film and must credit it",
            LabBlocks.AMBIANCE_FALL.briefing.any {
                it.contains("Tears of Steel") && it.contains("Blender Foundation")
            }
        )
    }

    @Test
    fun `a block whose question needs explaining carries a briefing`() {
        // One line under the question is read while he is already trying to answer it. Block 8 is
        // the case: on 2026-09-11 it asked about pulsing, about steadiness and about preference in
        // one sitting, and the answers say the question was never separated from the colour motion.
        assertTrue(LabBlocks.HUE_MOTION.briefing.size >= 3)
        assertTrue(
            "the briefing must say what the question is not about",
            LabBlocks.HUE_MOTION.briefing.any { it.contains("colour", ignoreCase = true) }
        )
        assertTrue(
            "the briefing must license the null answer",
            LabBlocks.HUE_MOTION.briefing.any { it.contains("Neither") }
        )
    }

    @Test
    fun `the recorded arm order is the order that plays`() {
        // An answer names an interval letter and nothing else, so `armFirst`/`armSecond` are the
        // only route back to which arm he pointed at. Block 8's pump trials once shuffled their
        // intervals and wrote the arms as a constant pair, and three answers from the 2026-09-11
        // sitting are unreadable because of it.
        //
        // The invariant is checked without knowing what any arm means: within one block and kind,
        // an arm id must always name the same stimulus. The bug breaks that - arm 0 came back as
        // both `hue_raw` and `hue_flat`.
        for (spec in LabBlocks.ALL) {
            for (seed in listOf(3L, 4L, 5L)) {
                val named = mutableMapOf<String, String>()
                for (t in LabBlocks.trialsFor(spec, joe, seed)) {
                    if (t.intervals.size != 2) continue
                    val first = t.meta["armFirst"] ?: continue
                    val second = t.meta["armSecond"] ?: continue
                    for ((arm, stimulus) in listOf(first to t.intervals[0], second to t.intervals[1])) {
                        // Scoped the way `LabAnalysis.PairKey` is scoped: the same arm asked at a
                        // different anchor, rung or clip is a different question and plays a
                        // different stimulus. Block 4's arms are ease durations and each one runs
                        // at both anchors, so an unscoped key would fail it for being correct.
                        val scope = listOf("anchorLevel", "toLevel", "clip")
                            .joinToString("/") { t.meta[it]?.toString() ?: "-" }
                        val key = "${spec.id}/${t.kind}/$scope/$arm"
                        val seen = named.putIfAbsent(key, stimulus.label)
                        if (seen != null) {
                            assertEquals(
                                "$key names two different stimuli",
                                seen, stimulus.label
                            )
                        }
                    }
                }
            }
        }
    }
}
