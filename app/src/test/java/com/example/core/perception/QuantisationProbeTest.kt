package com.example.core.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * What the probe has to get right, and the two ways it could quietly lie.
 *
 * The first is measuring the wrong thing: if the walk did not advance one commanded byte per step,
 * the spacings it reports would be some multiple of the grid rather than the grid. The second is
 * being unfalsifiable: a design where "saw nothing anywhere" and "was not paying attention" produce
 * the same record cannot support the conclusion it exists to reach.
 *
 * The simulations here answer the question the probe was built for by putting a *known* grid behind
 * a synthetic viewer and checking the probe recovers it. That is the only check available without
 * Joe in the room, and it is worth having: it says the arithmetic is right, not that the hypothesis
 * is.
 */
class QuantisationProbeTest {

    private val floor = FloorFinder.FloorResult(firstVisible = 4, clearlyOn = 5)

    // --- the plan --------------------------------------------------------------------------------

    @Test
    fun `plan walks up from the clearly-lit byte, not from black`() {
        val plan = QuantisationProbe.planFor(floor)
        assertEquals(5, plan.first { it.label == "low" }.startByte)
    }

    @Test
    fun `plan repeats the low segment at full brightness and leaves the others alone`() {
        val plan = QuantisationProbe.planFor(floor)
        val low = plan.first { it.label == "low" }
        val full = plan.first { it.label == "low_full" }
        assertNull("the segments measuring Joe's own setting must command nothing", low.brightnessPercent)
        assertNull(plan.first { it.label == "high" }.brightnessPercent)
        assertEquals(100, full.brightnessPercent)
        assertEquals(
            "the full-brightness pass must cover the same bytes, or the comparison is between two things",
            low.startByte,
            full.startByte
        )
    }

    @Test
    fun `high segment sits well above the low one so constant spacing can be told from proportional`() {
        val plan = QuantisationProbe.planFor(floor)
        val low = plan.first { it.label == "low" }
        val high = plan.first { it.label == "high" }
        assertTrue(high.startByte >= low.startByte + QuantisationProbe.LOW_STEPS)
    }

    // --- the trial list --------------------------------------------------------------------------

    @Test
    fun `every step advances the walk by exactly one commanded byte`() {
        val plan = QuantisationProbe.planFor(floor)
        val trials = QuantisationProbe.buildTrials(plan, seed = 7L)
        for (segment in plan) {
            val steps = trials.filter {
                it.segment == segment.label && it.kind == QuantisationProbe.ProbeTrialKind.STEP
            }
            assertEquals(segment.steps, steps.size)
            steps.forEachIndexed { i, t ->
                assertEquals(segment.startByte + i, t.fromByte)
                assertEquals(t.fromByte + 1, t.toByte)
            }
        }
    }

    @Test
    fun `consecutive steps share a level, so nothing accumulates across trials`() {
        val plan = QuantisationProbe.planFor(floor)
        val trials = QuantisationProbe.buildTrials(plan, seed = 11L)
            .filter { it.segment == "low" && it.kind == QuantisationProbe.ProbeTrialKind.STEP }
        trials.zipWithNext { a, b -> assertEquals(a.toByte, b.fromByte) }
    }

    @Test
    fun `catch trials carry no change and anchors carry an obvious one`() {
        val trials = QuantisationProbe.buildTrials(QuantisationProbe.planFor(floor), seed = 3L)
        val catches = trials.filter { it.kind == QuantisationProbe.ProbeTrialKind.CATCH }
        val anchors = trials.filter { it.kind == QuantisationProbe.ProbeTrialKind.ANCHOR }
        assertTrue("a probe with no catch trials cannot report a guessing rate", catches.isNotEmpty())
        assertTrue("a probe with no anchors cannot tell nothing-seen from not-watching", anchors.isNotEmpty())
        catches.forEach { assertEquals(it.fromByte, it.toByte) }
        anchors.forEach { assertEquals(QuantisationProbe.ANCHOR_DELTA, it.toByte - it.fromByte) }
    }

    @Test
    fun `the sequence is a pure function of seed and plan`() {
        val plan = QuantisationProbe.planFor(floor)
        assertEquals(
            QuantisationProbe.buildTrials(plan, seed = 42L),
            QuantisationProbe.buildTrials(plan, seed = 42L)
        )
        assertNotEquals(
            QuantisationProbe.buildTrials(plan, seed = 42L),
            QuantisationProbe.buildTrials(plan, seed = 43L)
        )
    }

    // --- reading the answers back ----------------------------------------------------------------

    /**
     * A viewer who sees a change exactly when the emitted level crosses a boundary.
     *
     * `gridBytes` is how many commanded bytes one emitted level is worth — 1.0 is a strip with no
     * grid at all. The floor of `byte / gridBytes` is the emitted level, and a change is visible
     * when two levels differ.
     */
    private fun gridViewer(gridBytes: Double): (QuantisationProbe.ProbeTrial) -> Boolean = { t ->
        val from = (t.fromByte / gridBytes).toInt()
        val to = (t.toByte / gridBytes).toInt()
        from != to
    }

    @Test
    fun `recovers a known grid from a synthetic viewer`() {
        val plan = QuantisationProbe.planFor(floor)
        val trials = QuantisationProbe.buildTrials(plan, seed = 5L)
        // 1 / 0.22, the spacing the integer-multiply hypothesis predicts at Joe's brightness.
        val viewer = gridViewer(4.545)
        val report = QuantisationProbe.report(plan, trials, trials.map(viewer))

        val low = report.segments.first { it.label == "low" }
        assertEquals(4.5, low.meanSpacing!!, 0.6)
        assertEquals("a perfect viewer reports no false positives", 0, report.catchFalsePositives)
        assertEquals("an anchor of twelve bytes crosses this grid every time", 0, report.anchorsMissed)
    }

    @Test
    fun `a grid that is constant in commanded bytes reads the same high up as low down`() {
        val plan = QuantisationProbe.planFor(floor)
        val trials = QuantisationProbe.buildTrials(plan, seed = 5L)
        val report = QuantisationProbe.report(plan, trials, trials.map(gridViewer(4.545)))
        val low = report.segments.first { it.label == "low" }.meanSpacing!!
        val high = report.segments.first { it.label == "high" }.meanSpacing!!
        // The distinguishing prediction of an integer multiply: spacing does not grow with level.
        assertEquals(low.roundToInt(), high.roundToInt())
    }

    @Test
    fun `a viewer with a real threshold and no grid reads as nothing seen anywhere`() {
        val plan = QuantisationProbe.planFor(floor)
        val trials = QuantisationProbe.buildTrials(plan, seed = 9L)
        // No grid; a genuine three-byte threshold. Every step is one byte, so every step is
        // subthreshold — and the anchors, at twelve, are not.
        val answers = trials.map { (it.toByte - it.fromByte) >= 3 }
        val report = QuantisationProbe.report(plan, trials, answers)

        report.segments.forEach {
            assertTrue("segment ${it.label} should have seen nothing", it.sawNothing)
            assertNull(it.meanSpacing)
        }
        assertEquals(0, report.catchFalsePositives)
        assertEquals(
            "the anchors are what separate this from a viewer who stopped watching",
            0,
            report.anchorsMissed
        )
    }

    @Test
    fun `an inattentive viewer is not mistaken for one who saw nothing`() {
        val plan = QuantisationProbe.planFor(floor)
        val trials = QuantisationProbe.buildTrials(plan, seed = 9L)
        val report = QuantisationProbe.report(plan, trials, trials.map { false })

        assertTrue(report.segments.all { it.sawNothing })
        assertEquals(
            "every anchor missed is what tells the two apart",
            1.0,
            report.anchorMissRate,
            0.0001
        )
    }

    @Test
    fun `a viewer who says yes to everything shows up in the catch trials`() {
        val plan = QuantisationProbe.planFor(floor)
        val trials = QuantisationProbe.buildTrials(plan, seed = 9L)
        val report = QuantisationProbe.report(plan, trials, trials.map { true })
        assertEquals(1.0, report.falsePositiveRate, 0.0001)
    }

    @Test
    fun `a part-finished run reports on what it has`() {
        val plan = QuantisationProbe.planFor(floor)
        val trials = QuantisationProbe.buildTrials(plan, seed = 5L)
        val partial = trials.take(10).map(gridViewer(4.545))
        val report = QuantisationProbe.report(plan, trials, partial)
        assertEquals(0, report.segments.first { it.label == "high" }.stepTrials)
        assertTrue(report.segments.first { it.label == "low" }.stepTrials in 1..10)
    }
}
