package com.example.core.calibration

import kotlin.math.abs

object CalibrationMatrixSolver {
    /**
     * Compute a 3x3 linear color correction matrix from a set of target and measured RGB samples.
     * Implements a least-squares fit.
     */
    fun fit3x3Matrix(
        targets: List<IntArray>,
        measured: List<IntArray>
    ): FloatArray {
        val n = targets.size
        if (n < 3) {
            return floatArrayOf(
                1f, 0f, 0f,
                0f, 1f, 0f,
                0f, 0f, 1f
            )
        }

        var c11 = 0.0
        var c12 = 0.0
        var c13 = 0.0
        var c22 = 0.0
        var c23 = 0.0
        var c33 = 0.0

        for (i in 0 until n) {
            val m = measured[i]
            val rm = m[0].toDouble()
            val gm = m[1].toDouble()
            val bm = m[2].toDouble()

            c11 += rm * rm
            c12 += rm * gm
            c13 += rm * bm
            c22 += gm * gm
            c23 += gm * bm
            c33 += bm * bm
        }

        val c21 = c12
        val c31 = c13
        val c32 = c23

        val det = c11 * (c22 * c33 - c23 * c32) -
                  c12 * (c21 * c33 - c23 * c31) +
                  c13 * (c21 * c32 - c22 * c31)

        if (abs(det) < 1e-7) {
            return floatArrayOf(
                1f, 0f, 0f,
                0f, 1f, 0f,
                0f, 0f, 1f
            )
        }

        val inv11 = (c22 * c33 - c23 * c32) / det
        val inv12 = -(c12 * c33 - c13 * c32) / det
        val inv13 = (c12 * c23 - c13 * c22) / det

        val inv21 = -(c21 * c33 - c23 * c31) / det
        val inv22 = (c11 * c33 - c13 * c31) / det
        val inv23 = -(c11 * c23 - c13 * c21) / det

        val inv31 = (c21 * c32 - c22 * c31) / det
        val inv32 = -(c11 * c32 - c12 * c31) / det
        val inv33 = (c11 * c22 - c12 * c21) / det

        val matrix = FloatArray(9)

        for (k in 0..2) {
            var v1 = 0.0
            var v2 = 0.0
            var v3 = 0.0

            for (i in 0 until n) {
                val m = measured[i]
                val rm = m[0].toDouble()
                val gm = m[1].toDouble()
                val bm = m[2].toDouble()

                val y = targets[i][k].toDouble()

                v1 += rm * y
                v2 += gm * y
                v3 += bm * y
            }

            val w1 = inv11 * v1 + inv12 * v2 + inv13 * v3
            val w2 = inv21 * v1 + inv22 * v2 + inv23 * v3
            val w3 = inv31 * v1 + inv32 * v2 + inv33 * v3

            matrix[k * 3 + 0] = w1.toFloat()
            matrix[k * 3 + 1] = w2.toFloat()
            matrix[k * 3 + 2] = w3.toFloat()
        }

        return matrix
    }
}
