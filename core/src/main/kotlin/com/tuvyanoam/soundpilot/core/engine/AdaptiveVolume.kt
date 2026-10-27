package com.tuvyanoam.soundpilot.core.engine

import com.tuvyanoam.soundpilot.core.model.AdaptiveVolumeConfig
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

object NoiseMath {
    const val SILENCE_DBFS = -120.0

    /** Root-mean-square level of 16-bit PCM samples, in dB relative to full scale (always <= 0). */
    fun dbfs(samples: ShortArray, count: Int = samples.size): Double {
        val n = count.coerceAtMost(samples.size)
        if (n <= 0) return SILENCE_DBFS
        var mean = 0.0
        for (i in 0 until n) mean += samples[i]
        mean /= n
        var sum = 0.0
        for (i in 0 until n) {
            val s = (samples[i] - mean) / 32768.0
            sum += s * s
        }
        val rms = sqrt(sum / n)
        return if (rms <= 1e-9) SILENCE_DBFS else 20 * log10(rms)
    }

    /**
     * The median of per-frame levels. A single door slam or a word spoken into the phone barely moves it,
     * which is what we want from a reading of the room.
     */
    fun robustLevel(frameLevels: List<Double>): Double? {
        if (frameLevels.isEmpty()) return null
        val sorted = frameLevels.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    fun estimateSpl(dbfs: Double, calibrationOffsetDb: Double): Double = (dbfs + calibrationOffsetDb).coerceIn(0.0, 130.0)
}

object AdaptiveVolume {
    /** Linear map from ambient noise to ring volume between the configured quiet and loud levels. */
    fun targetPercent(noiseDb: Double, config: AdaptiveVolumeConfig): Int {
        val low = config.minPercent.coerceIn(0, 100)
        val high = config.maxPercent.coerceIn(low, 100)
        if (config.loudDb <= config.quietDb) return high
        val t = ((noiseDb - config.quietDb) / (config.loudDb - config.quietDb)).coerceIn(0.0, 1.0)
        return (low + t * (high - low)).roundToInt().coerceIn(0, 100)
    }
}

/**
 * Smooths noise readings and decides when a volume change is worth making. Volume rises quickly when the room
 * gets loud (so a call is not missed) and falls slowly when it gets quiet.
 */
class AdaptiveVolumeTracker(
    private val riseAlpha: Double = 0.7,
    private val fallAlpha: Double = 0.3,
    private val minStepPercent: Int = 7,
) {
    var smoothedDb: Double? = null
        private set

    fun reset() {
        smoothedDb = null
    }

    /** Returns the new volume to set, or null when the current one is close enough. */
    fun onSample(noiseDb: Double, config: AdaptiveVolumeConfig, currentPercent: Int): Int? {
        val previous = smoothedDb
        val smoothed = if (previous == null) {
            noiseDb
        } else {
            val alpha = if (noiseDb > previous) riseAlpha else fallAlpha
            previous + alpha * (noiseDb - previous)
        }
        smoothedDb = smoothed
        val target = AdaptiveVolume.targetPercent(smoothed, config)
        return if (abs(target - currentPercent) >= minStepPercent) target else null
    }
}
