package com.tuvyanoam.soundpilot.core.engine

import com.tuvyanoam.soundpilot.core.model.Action
import com.tuvyanoam.soundpilot.core.model.AdaptiveVolumeConfig
import com.tuvyanoam.soundpilot.core.model.Condition
import com.tuvyanoam.soundpilot.core.model.RingerMode
import com.tuvyanoam.soundpilot.core.model.Rule
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimeAndNoiseTest {
    private val night = Condition.TimeWindow(Condition.ALL_DAYS, 22 * 60, 7 * 60)
    private val shabbat = Condition.TimeWindow(setOf(5, 6), 16 * 60, 20 * 60)

    @Test
    fun `overnight windows span midnight`() {
        assertTrue(TimeMath.inWindow(1, 23 * 60, night))
        assertTrue(TimeMath.inWindow(2, 6 * 60 + 59, night))
        assertFalse(TimeMath.inWindow(2, 7 * 60, night))
        assertFalse(TimeMath.inWindow(2, 12 * 60, night))
    }

    @Test
    fun `day restricted overnight window uses the start day`() {
        val fridayNight = Condition.TimeWindow(setOf(5), 22 * 60, 2 * 60)
        assertTrue(TimeMath.inWindow(5, 23 * 60, fridayNight))
        assertTrue(TimeMath.inWindow(6, 60, fridayNight))
        assertFalse(TimeMath.inWindow(1, 60, fridayNight))
    }

    @Test
    fun `equal start and end means the whole day`() {
        val allDay = Condition.TimeWindow(setOf(6), 0, 0)
        assertTrue(TimeMath.inWindow(6, 0, allDay))
        assertTrue(TimeMath.inWindow(6, 1439, allDay))
        assertFalse(TimeMath.inWindow(7, 0, allDay))
    }

    @Test
    fun `next boundary finds the closest start or end`() {
        val rules = listOf(Rule("a", "a", conditions = listOf(shabbat), actions = listOf(Action.SetRinger(RingerMode.SILENT))))
        // Thursday 12:00 -> Friday 16:00 is 28 hours away.
        assertEquals(28 * 60, TimeMath.minutesUntilNextBoundary(rules, 4, 12 * 60))
        // Friday 17:00 -> the window closes at 20:00.
        assertEquals(3 * 60, TimeMath.minutesUntilNextBoundary(rules, 5, 17 * 60))
        assertNull(TimeMath.minutesUntilNextBoundary(emptyList(), 1, 0))
    }

    @Test
    fun `multi day windows cover shabbat`() {
        val fullShabbat = Condition.TimeWindow(setOf(5), 16 * 60, 20 * 60, endDayOffset = 1)
        assertFalse(TimeMath.inWindow(5, 15 * 60 + 59, fullShabbat))
        assertTrue(TimeMath.inWindow(5, 16 * 60, fullShabbat))
        assertTrue(TimeMath.inWindow(6, 3 * 60, fullShabbat))
        assertTrue(TimeMath.inWindow(6, 19 * 60 + 59, fullShabbat))
        assertFalse(TimeMath.inWindow(6, 20 * 60, fullShabbat))
        assertFalse(TimeMath.inWindow(7, 10 * 60, fullShabbat))

        val rules = listOf(Rule("s", "s", conditions = listOf(fullShabbat), actions = listOf(Action.SetRinger(RingerMode.SILENT))))
        // Saturday noon -> ends Saturday 20:00.
        assertEquals(8 * 60, TimeMath.minutesUntilNextBoundary(rules, 6, 12 * 60))
        // Saturday 21:00 -> next Friday 16:00.
        assertEquals(5 * 24 * 60 + 19 * 60, TimeMath.minutesUntilNextBoundary(rules, 6, 21 * 60))
    }

    @Test
    fun `parse and format minutes`() {
        assertEquals(7 * 60 + 5, TimeMath.parseMinute("07:05"))
        assertEquals(0, TimeMath.parseMinute("24:00"))
        assertNull(TimeMath.parseMinute("25:00"))
        assertNull(TimeMath.parseMinute("noon"))
        assertEquals("22:30", TimeMath.formatMinute(22 * 60 + 30))
    }

    @Test
    fun `dbfs of silence and of a full scale sine`() {
        assertEquals(NoiseMath.SILENCE_DBFS, NoiseMath.dbfs(ShortArray(1000)))
        val sine = ShortArray(4800) { i -> (32767 * sin(2 * PI * 440 * i / 48000)).toInt().toShort() }
        // A full-scale sine has an RMS of 1/sqrt(2), i.e. about -3 dBFS.
        assertTrue(abs(NoiseMath.dbfs(sine) + 3.01) < 0.1)
    }

    @Test
    fun `robust level ignores a single spike`() {
        assertEquals(40.0, NoiseMath.robustLevel(listOf(40.0, 41.0, 39.0, 95.0, 40.0)))
        assertNull(NoiseMath.robustLevel(emptyList()))
    }

    @Test
    fun `adaptive volume maps noise linearly and only moves on real change`() {
        val config = AdaptiveVolumeConfig(enabled = true, minPercent = 30, maxPercent = 100, quietDb = 40.0, loudDb = 80.0)
        assertEquals(30, AdaptiveVolume.targetPercent(20.0, config))
        assertEquals(65, AdaptiveVolume.targetPercent(60.0, config))
        assertEquals(100, AdaptiveVolume.targetPercent(95.0, config))

        val tracker = AdaptiveVolumeTracker()
        assertEquals(100, tracker.onSample(85.0, config, currentPercent = 30))
        // Small fluctuations do not cause changes.
        assertNull(tracker.onSample(84.0, config, currentPercent = 100))
    }
}
