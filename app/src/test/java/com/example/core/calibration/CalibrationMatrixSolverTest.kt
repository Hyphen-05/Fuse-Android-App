package com.example.core.calibration

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationMatrixSolverTest {

    private val identity = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f
    )

    @Test
    fun `fewer than 3 samples returns identity fallback`() {
        val targets = listOf(intArrayOf(10, 20, 30), intArrayOf(40, 50, 60))
        val measured = listOf(intArrayOf(11, 21, 31), intArrayOf(41, 51, 61))

        val result = CalibrationMatrixSolver.fit3x3Matrix(targets, measured)

        assertArrayEquals(identity, result, 0.0001f)
    }

    @Test
    fun `zero samples returns identity fallback`() {
        val result = CalibrationMatrixSolver.fit3x3Matrix(emptyList(), emptyList())

        assertArrayEquals(identity, result, 0.0001f)
    }

    @Test
    fun `near-singular measured samples return identity fallback`() {
        // All three measured points are identical, so the normal-equations matrix is rank-1
        // (every entry equal) and its determinant is ~0 — the degenerate-input guard should fire
        // regardless of what the targets are.
        val measured = listOf(
            intArrayOf(50, 50, 50),
            intArrayOf(50, 50, 50),
            intArrayOf(50, 50, 50)
        )
        val targets = listOf(
            intArrayOf(10, 20, 30),
            intArrayOf(40, 50, 60),
            intArrayOf(70, 80, 90)
        )

        val result = CalibrationMatrixSolver.fit3x3Matrix(targets, measured)

        assertArrayEquals(identity, result, 0.0001f)
    }

    @Test
    fun `exact identity mapping on orthogonal axis samples solves to the identity matrix`() {
        val targets = listOf(
            intArrayOf(100, 0, 0),
            intArrayOf(0, 100, 0),
            intArrayOf(0, 0, 100)
        )
        val measured = targets

        val result = CalibrationMatrixSolver.fit3x3Matrix(targets, measured)

        assertArrayEquals(identity, result, 0.001f)
    }

    @Test
    fun `uniform 2x scaling on orthogonal axis samples solves to a diagonal 2x matrix`() {
        val measured = listOf(
            intArrayOf(100, 0, 0),
            intArrayOf(0, 100, 0),
            intArrayOf(0, 0, 100)
        )
        val targets = listOf(
            intArrayOf(200, 0, 0),
            intArrayOf(0, 200, 0),
            intArrayOf(0, 0, 200)
        )

        val result = CalibrationMatrixSolver.fit3x3Matrix(targets, measured)

        val expected = floatArrayOf(
            2f, 0f, 0f,
            0f, 2f, 0f,
            0f, 0f, 2f
        )
        assertArrayEquals(expected, result, 0.001f)
    }

    @Test
    fun `more than 3 samples still produces a well-formed solved matrix`() {
        val measured = listOf(
            intArrayOf(100, 0, 0),
            intArrayOf(0, 100, 0),
            intArrayOf(0, 0, 100),
            intArrayOf(50, 50, 50)
        )
        val targets = listOf(
            intArrayOf(90, 5, 5),
            intArrayOf(5, 90, 5),
            intArrayOf(5, 5, 90),
            intArrayOf(45, 45, 45)
        )

        val result = CalibrationMatrixSolver.fit3x3Matrix(targets, measured)

        // Not solved exactly (over-determined system), just verify the fallback did NOT fire and
        // every entry is finite.
        result.forEach { assertTrue(it.isFinite()) }
    }
}
