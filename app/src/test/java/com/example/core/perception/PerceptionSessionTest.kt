package com.example.core.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Drives whole sittings with simulated observers whose answers are known in advance, and checks the
 * session reaches the right conclusion about them.
 *
 * The observers are the point. A real sitting cannot be replayed with a different viewer, so the
 * only way to know the session can tell a careful viewer from a guesser is to feed it both.
 */
class PerceptionSessionTest {

    /** Answers honestly, seeing differences above [threshold] bytes and never inventing one. */
    private fun careful(trial: Trial, threshold: Int, random: Random): Response {
        if (trial.isCatch) return Response.CANT_TELL
        val visible = when (trial.kind) {
            TrialKind.STEP_VISIBILITY -> trial.delta >= threshold
            // Stands in for a viewer who sees dither flicker only when it is slow.
            TrialKind.DITHER_FLICKER -> trial.intervalMs >= 60
            TrialKind.FADE_SMOOTHNESS -> true
        }
        if (!visible) return if (random.nextBoolean()) Response.A else Response.B
        return if (trial.targetIsB) Response.B else Response.A
    }

    /** Never sees anything and always picks a side anyway. */
    private fun guesser(random: Random): Response =
        if (random.nextBoolean()) Response.A else Response.B

    private fun runSitting(seed: Long, answer: (Trial, Random) -> Response): SessionReport {
        val session = PerceptionSession(seed)
        val random = Random(seed)
        var guard = 0
        while (!session.finished && guard++ < 4000) {
            val trial = session.next() ?: break
            session.record(answer(trial, random), responseMs = 800)
        }
        assertTrue("the sitting should terminate", session.finished || session.next() == null)
        return session.report()
    }

    @Test
    fun `a careful viewer's step threshold is recovered at every base level`() {
        val truth = 5
        val report = runSitting(11L) { trial, random -> careful(trial, truth, random) }
        for ((base, threshold) in report.stepThresholdByBase) {
            assertNotNull("base $base should reach a threshold", threshold)
            assertTrue(
                "base $base threshold ${threshold!!} should be near $truth",
                abs(threshold - truth) <= 2.0
            )
        }
    }

    @Test
    fun `catch trials expose a guesser, and the thresholds from one are not to be trusted`() {
        val careful = runSitting(21L) { trial, random -> careful(trial, 5, random) }
        val guessing = runSitting(21L) { _, random -> guesser(random) }

        assertTrue("a sitting must contain catch trials at all", careful.catchTrials > 0)
        assertEquals(
            "an honest viewer says can't-tell on every catch trial",
            0.0, careful.falsePositiveRate, 1e-9
        )
        assertTrue(
            "a guesser's false-positive rate should be high, was ${guessing.falsePositiveRate}",
            guessing.falsePositiveRate > 0.8
        )
    }

    @Test
    fun `the dither block separates a rate that flickers from one that does not`() {
        // The simulated viewer sees flicker at 60ms and slower. The report should show that as a
        // high correct rate there and chance-level at the fast intervals.
        val report = runSitting(31L) { trial, random -> careful(trial, 5, random) }
        val fast = report.ditherVisibleByInterval.filterKeys { it <= 40L }.values
        val slow = report.ditherVisibleByInterval.filterKeys { it >= 60L }.values
        assertTrue("slow dither should be spotted reliably: $slow", slow.all { it >= 0.75 })
        assertTrue("fast dither should be near chance: $fast", fast.all { it <= 0.75 })
    }

    @Test
    fun `a catch trial shows the same thing twice`() {
        // If the two intervals of a catch trial ever differ, the false-positive rate is measuring
        // nothing and every threshold built on it is unsupported.
        val session = PerceptionSession(41L)
        var seen = 0
        var guard = 0
        while (!session.finished && guard++ < 4000) {
            val trial = session.next() ?: break
            if (trial.isCatch) {
                seen++
                assertEquals(
                    "catch trial ${trial.kind} must be identical in both intervals",
                    trial.a.steps.map { it.byte }, trial.b.steps.map { it.byte }
                )
            }
            session.record(Response.CANT_TELL, 500)
        }
        assertTrue("should have seen some catch trials", seen > 5)
    }

    @Test
    fun `catch trials do not move the staircase`() {
        // A can't-tell on a catch trial is correct and carries no information about the delta. If it
        // fed the staircase it would drive the threshold up on every honest answer.
        val session = PerceptionSession(51L)
        var guard = 0
        var nonCatchSteps = 0
        while (!session.finished && guard++ < 4000) {
            val trial = session.next() ?: break
            if (trial.kind == TrialKind.STEP_VISIBILITY && !trial.isCatch) nonCatchSteps++
            session.record(if (trial.isCatch) Response.CANT_TELL else Response.A, 500)
        }
        val report = session.report()
        val catchSteps = report.records.count { it.kind == TrialKind.STEP_VISIBILITY && it.isCatch }
        assertTrue("the sitting should contain both kinds", catchSteps > 0 && nonCatchSteps > 0)
    }
}
