package com.example

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  // Pure Kotlin implementation of the least squares matrix fitter to test the math in isolation
  private fun testFit3x3Matrix(
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

  @Test
  fun testMatrixFitting_identity() {
      // Setup identical targets and measured values (identity relation)
      val targets = listOf(
          intArrayOf(255, 0, 0),
          intArrayOf(0, 255, 0),
          intArrayOf(0, 0, 255),
          intArrayOf(255, 255, 255)
      )
      val measured = targets

      val matrix = testFit3x3Matrix(targets, measured)

      // Expecting identity matrix [1, 0, 0,  0, 1, 0,  0, 0, 1]
      val epsilon = 0.01f
      assertTrue(abs(matrix[0] - 1.0f) < epsilon)
      assertTrue(abs(matrix[1] - 0.0f) < epsilon)
      assertTrue(abs(matrix[2] - 0.0f) < epsilon)
      
      assertTrue(abs(matrix[3] - 0.0f) < epsilon)
      assertTrue(abs(matrix[4] - 1.0f) < epsilon)
      assertTrue(abs(matrix[5] - 0.0f) < epsilon)
      
      assertTrue(abs(matrix[6] - 0.0f) < epsilon)
      assertTrue(abs(matrix[7] - 0.0f) < epsilon)
      assertTrue(abs(matrix[8] - 1.0f) < epsilon)
  }

  @Test
  fun testMatrixFitting_scaled() {
      // Under-emitting green by 50% (measured green is 0.5 * target green)
      val targets = listOf(
          intArrayOf(255, 0, 0),
          intArrayOf(0, 255, 0),
          intArrayOf(0, 0, 255),
          intArrayOf(255, 255, 255)
      )
      val measured = listOf(
          intArrayOf(255, 0, 0),
          intArrayOf(0, 128, 0),
          intArrayOf(0, 0, 255),
          intArrayOf(255, 128, 255)
      )

      val matrix = testFit3x3Matrix(targets, measured)

      // Correction matrix should scale green by 2.0 to compensate: m22 ≈ 2.0
      val epsilon = 0.1f
      assertTrue(abs(matrix[0] - 1.0f) < epsilon)
      assertTrue(abs(matrix[4] - 2.0f) < epsilon)
      assertTrue(abs(matrix[8] - 1.0f) < epsilon)
  }

  @Test
  fun testHueWraparound() {
      // Test averaging of 350 degrees and 10 degrees.
      // Expected result: ~0 degrees (or 360).
      val h1 = 350f
      val h2 = 10f
      
      val rad1 = Math.toRadians(h1.toDouble())
      val rad2 = Math.toRadians(h2.toDouble())
      
      val sCos = Math.cos(rad1) + Math.cos(rad2)
      val sSin = Math.sin(rad1) + Math.sin(rad2)
      
      var avgHue = Math.toDegrees(Math.atan2(sSin, sCos)).toFloat()
      if (avgHue < 0) avgHue += 360f
      
      // Expected: 0.0 (or 360) within 0.1 deg
      assertTrue(abs(avgHue - 0f) < 0.1f || abs(avgHue - 360f) < 0.1f)
  }

  @Test
  fun testLowSaturationWeighting() {
      // Highly saturated zone at 120 degrees (Green) with S = 1.0
      // Near-white zone at 240 degrees (Blue) with S = 0.05
      val h1 = 120f
      val s1 = 1.0f
      
      val h2 = 240f
      val s2 = 0.05f
      
      val rad1 = Math.toRadians(h1.toDouble())
      val rad2 = Math.toRadians(h2.toDouble())
      
      // Weighting by saturation
      val sCos = Math.cos(rad1) * s1 + Math.cos(rad2) * s2
      val sSin = Math.sin(rad1) * s1 + Math.sin(rad2) * s2
      
      var avgHue = Math.toDegrees(Math.atan2(sSin, sCos)).toFloat()
      if (avgHue < 0) avgHue += 360f
      
      // Since zone 1 is highly saturated and zone 2 is near-white,
      // the avgHue should be very close to 120 degrees, not (120+240)/2 = 180 degrees.
      assertTrue(abs(avgHue - 120f) < 10f)
  }

  @Test
  fun testSceneCutDetection() {
      val leftDistances = java.util.ArrayDeque<Float>()
      val getRollingAverage = { q: java.util.ArrayDeque<Float>, defaultVal: Float ->
          if (q.isEmpty()) defaultVal else q.average().toFloat()
      }
      val updateRollingDistances = { q: java.util.ArrayDeque<Float>, d: Float ->
          q.addLast(d)
          if (q.size > 10) {
              q.removeFirst()
          }
      }
      
      // Seed stable baseline of small frame-to-frame distances (avg ~5.0)
      for (i in 1..10) {
          updateRollingDistances(leftDistances, 5.0f)
      }
      
      val avg = getRollingAverage(leftDistances, 15f)
      assertEquals(5.0f, avg, 0.01f)
      
      val thresh = Math.max(3.5f * avg, 35f)
      
      // A large synthetic jump (e.g., 180.0f)
      val largeJump = 180.0f
      assertTrue("Large jump should exceed adaptive threshold", largeJump > thresh)
      
      // A small frame-to-frame change on a gradual ramp of total size 180 (e.g. 15.0f per frame over 12 frames)
      val smallRampStep = 15.0f
      assertFalse("Ramp step should not exceed adaptive threshold", smallRampStep > thresh)
  }

  @Test
  fun testMedianFilterOutlierRejection() {
      // Buffer has 4 stable values and 1 extreme outlier
      val values = intArrayOf(10, 11, 255, 12, 10) // 255 is an outlier
      val sorted = values.sorted()
      
      val median = sorted[values.size / 2]
      assertEquals(11, median) // 255 is completely rejected, median is 11 (one of the stable values)
  }

  @Test
  fun testAudioSmoothingAttackDecay() {
      // Run 1: Fast/Instant settings (similar to Punchy or Laser Sharp)
      val run1Attack = 0.95f
      val run1Decay = 0.05f
      val run1FlashStrength = 0.6f
      
      // Run 2: Slow/Heavy settings (similar to Ambient Chill)
      val run2Attack = 0.4f
      val run2Decay = 0.4f
      val run2FlashStrength = 0.0f // Ambient Chill has 0.0f flash strength

      val inputs = listOf(5.0f, 5.0f, 80.0f, 80.0f, 5.0f, 5.0f, 80.0f, 80.0f, 5.0f, 5.0f)
      
      println("=== RUN 1: FAST (Attack=$run1Attack, Decay=$run1Decay, Flash=$run1FlashStrength) ===")
      val run1Outputs = simulateSmoothingWithBeat(inputs, run1Attack, run1Decay, run1FlashStrength)
      
      println("=== RUN 2: SLOW (Attack=$run2Attack, Decay=$run2Decay, Flash=$run2FlashStrength) ===")
      val run2Outputs = simulateSmoothingWithBeat(inputs, run2Attack, run2Decay, run2FlashStrength)
      
      // Print comparisons and assert that the outputs differ significantly
      var identicalCount = 0
      for (i in inputs.indices) {
          val diff = abs(run1Outputs[i] - run2Outputs[i])
          if (diff < 1e-4f) {
              identicalCount++
          }
      }
      
      println("Identical output count: $identicalCount out of ${inputs.size}")
      assertTrue("Smoothing attack/decay parameters MUST change the final outputs", identicalCount < inputs.size)
  }

  private fun simulateSmoothingWithBeat(inputs: List<Float>, attack: Float, decay: Float, flashStrength: Float): List<Float> {
      var smoothedBass = 0.0f
      var maxObservedBass = 10.0f
      val bassHistory = FloatArray(8) { 5.0f }
      var bassHistoryIdx = 0
      var bassHistorySum = 40.0f
      val noiseGateThreshold = 1.0f
      var lastBeatTime = 0L
      
      val outputs = mutableListOf<Float>()
      
      for (tick in inputs.indices) {
          val bassVal = inputs[tick]
          val now = tick * 100L // 100ms per tick
          
          // Apply Dynamic Exponential Moving Average (EMA) Temporal Smoothing
          val bassAlpha = if (bassVal > smoothedBass) attack else decay
          smoothedBass = bassAlpha * bassVal + (1f - bassAlpha) * smoothedBass
          
          // Track rolling average of bass energy
          val oldBass = bassHistory[bassHistoryIdx]
          bassHistory[bassHistoryIdx] = bassVal
          bassHistorySum = bassHistorySum - oldBass + bassVal
          bassHistoryIdx = (bassHistoryIdx + 1) % 8
          val avgBass = bassHistorySum / 8.0f
          
          // Auto-gain emulation
          val decayFactorBass = if (bassVal < avgBass) 0.97f else 0.992f
          maxObservedBass = maxOf(maxObservedBass * decayFactorBass, bassVal, 10.0f)
          
          val bassFloor = 0.8f * avgBass
          val rangeBass = maxOf(maxObservedBass - bassFloor, 5.0f)
          
          var valBase = ((smoothedBass - bassFloor) / rangeBass).coerceIn(0.0f, 1.0f)
          if (smoothedBass < noiseGateThreshold) {
              valBase = 0.0f
          } else {
              valBase = Math.pow(valBase.toDouble(), 2.0).toFloat()
          }
          
          // Beat pulse emulation
          var beatPulse = 0.0f
          val isBeat = bassVal > avgBass * 1.3f && bassVal > noiseGateThreshold && (now - lastBeatTime > 250L)
          if (isBeat) {
              lastBeatTime = now
              beatPulse = 1.0f
          } else {
              val elapsedMs = now - lastBeatTime
              val t = elapsedMs.toFloat() / 150.0f
              beatPulse = maxOf(1.0f - (t * t), 0.0f)
          }
          
          // Apply fix: scale beatPulse by flashStrength
          val combinedEnergy = maxOf(valBase, beatPulse * flashStrength)
          
          var baseValVal = (combinedEnergy * 1.0f).coerceIn(0.0f, 1.0f)
          baseValVal = Math.pow(baseValVal.toDouble(), 0.45).toFloat()
          
          val elapsedMs = now - lastBeatTime
          val t = elapsedMs.toFloat() / 100.0f
          val flashValue = maxOf(1.0f - (t * t), 0.0f)
          val value = (baseValVal + flashValue * flashStrength).coerceIn(0.0f, 1.0f)
          
          outputs.add(value)
          println("Tick $tick: Input=${String.format("%.1f", bassVal)} | SmoothedBass=${String.format("%.2f", smoothedBass)} | BeatPulse=${String.format("%.2f", beatPulse)} | Value=${String.format("%.4f", value)}")
      }
      return outputs
  }
}
