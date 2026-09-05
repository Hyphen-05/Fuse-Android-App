package com.example.core.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Going back a trial, and picking a sitting up after it was interrupted.
 *
 * Both rest on one property — a sitting is entirely determined by its seed, its config and its
 * answers — so both are tested the same way: take a sitting apart and check the rebuilt one is
 * indistinguishable from the original.
 */
class PerceptionResumeTest {

    private fun answersFor(session: PerceptionSession, count: Int, seed: Long): List<Answer> {
        val random = Random(seed)
        val given = mutableListOf<Answer>()
        repeat(count) {
            val trial = session.next() ?: return@repeat
            val response = when {
                trial.isCatch -> Response.CANT_TELL
                random.nextBoolean() -> if (trial.targetIsB) Response.B else Response.A
                else -> Response.CANT_TELL
            }
            session.record(response, responseMs = 700)
            given.add(Answer(response, 700))
        }
        return given
    }

    @Test
    fun `a resumed sitting is identical to the one that was interrupted`() {
        val config = SessionConfig(baseLevels = listOf(6, 12, 30), reversalsToFinish = 6)
        val original = PerceptionSession(seed = 99L, config = config)
        val given = answersFor(original, 25, seed = 5L)

        val resumed = PerceptionSession(seed = 99L, config = config)
        resumed.restore(given)

        assertEquals("the answer count must survive", original.answerCount, resumed.answerCount)
        assertEquals("every recorded trial must come back", original.records, resumed.records)
        assertEquals(
            "and the next question must be the same one he was about to be asked",
            original.next()?.let { it.kind to (it.baseByte to it.delta) },
            resumed.next()?.let { it.kind to (it.baseByte to it.delta) }
        )
        assertEquals(
            "including the staircases behind it",
            original.report().stepThresholdByBase,
            resumed.report().stepThresholdByBase
        )
    }

    @Test
    fun `going back returns the same trial he just answered`() {
        val session = PerceptionSession(seed = 7L, config = SessionConfig(baseLevels = listOf(5, 10, 25)))
        val shown = session.next()!!
        session.record(Response.A, 500)

        val backTo = session.undoLast()
        assertNotNull("there was an answer to take back", backTo)
        assertEquals("the trial must be the one he saw, not a fresh one", shown.kind, backTo!!.kind)
        assertEquals(shown.baseByte, backTo.baseByte)
        assertEquals(shown.delta, backTo.delta)
        assertEquals("including which interval carried it", shown.targetIsB, backTo.targetIsB)
        assertEquals("and the stimuli themselves", shown.a.steps, backTo.a.steps)
        assertEquals(0, session.answerCount)
    }

    @Test
    fun `going back unwinds the staircase, not just the record list`() {
        // The bug this guards: taking back an answer but leaving the staircase carrying it, so the
        // next delta reflects an answer he withdrew.
        val config = SessionConfig(baseLevels = listOf(8), reversalsToFinish = 6)
        val session = PerceptionSession(seed = 13L, config = config)
        answersFor(session, 12, seed = 3L)

        val before = session.next()!!.delta
        val recordsBefore = session.records

        session.record(Response.B, 400)
        session.undoLast()

        assertEquals("the record list must be back where it was", recordsBefore, session.records)
        assertEquals("and so must the delta the staircase is offering", before, session.next()!!.delta)
    }

    @Test
    fun `going back repeatedly walks all the way to the start`() {
        val session = PerceptionSession(seed = 21L, config = SessionConfig(baseLevels = listOf(5, 10)))
        answersFor(session, 10, seed = 9L)
        assertEquals(10, session.answerCount)

        var guard = 0
        while (session.undoLast() != null && guard++ < 50) { /* walk back */ }

        assertEquals("every answer should be recoverable", 0, session.answerCount)
        assertTrue("and no records left behind", session.records.isEmpty())
        assertNull("nothing left to undo", session.undoLast())
    }

    @Test
    fun `answering differently after going back changes what comes next`() {
        // Later trials are adaptive, so an answer he withdrew must stop influencing them. This is
        // the point of rebuilding rather than patching state in place.
        val config = SessionConfig(baseLevels = listOf(9), reversalsToFinish = 6)
        val session = PerceptionSession(seed = 33L, config = config)
        answersFor(session, 6, seed = 4L)

        val trial = session.next()!!
        val rightAnswer = if (trial.targetIsB) Response.B else Response.A
        session.record(rightAnswer, 500)
        val afterCorrect = session.next()!!.delta

        session.undoLast()
        session.record(Response.CANT_TELL, 500)
        val afterWrong = session.next()!!.delta

        assertTrue(
            "a wrong answer should make the next step easier than a right one did " +
                "($afterWrong vs $afterCorrect)",
            afterWrong >= afterCorrect
        )
    }

    @Test
    fun `a restored sitting keeps its config, so it cannot resume into a different design`() {
        val config = SessionConfig(baseLevels = listOf(11, 22, 55), reversalsToFinish = 6)
        val session = PerceptionSession(seed = 77L, config = config)
        answersFor(session, 8, seed = 2L)
        assertEquals(config, session.config)
        assertTrue(
            "every step trial must come from the configured levels",
            session.records.filter { it.kind == TrialKind.STEP_VISIBILITY }
                .all { it.baseByte in config.baseLevels }
        )
    }
}
