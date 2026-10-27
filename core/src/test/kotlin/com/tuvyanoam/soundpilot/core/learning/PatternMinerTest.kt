package com.tuvyanoam.soundpilot.core.learning

import com.tuvyanoam.soundpilot.core.model.Action
import com.tuvyanoam.soundpilot.core.model.Condition
import com.tuvyanoam.soundpilot.core.model.ContextSnapshot
import com.tuvyanoam.soundpilot.core.model.Place
import com.tuvyanoam.soundpilot.core.model.RingerMode
import com.tuvyanoam.soundpilot.core.model.UserEvent
import com.tuvyanoam.soundpilot.core.platform.EnglishPhrases
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PatternMinerTest {
    private val zone = ZoneOffset.UTC
    private val base = LocalDateTime.of(2026, 10, 5, 0, 0) // a Monday
    private val now = base.plusDays(20).toInstant(zone).toEpochMilli()
    private val places = listOf(Place("p-shul", "Synagogue", 32.0, 34.0))

    private fun event(dayOffset: Long, hour: Int, minute: Int, mode: RingerMode, placeIds: Set<String> = emptySet()): UserEvent {
        val t = base.plusDays(dayOffset).withHour(hour).withMinute(minute)
        return UserEvent(
            epochMillis = t.toInstant(zone).toEpochMilli(),
            kind = UserEvent.Kind.RINGER,
            ringerMode = mode,
            context = ContextSnapshot(t.toInstant(zone).toEpochMilli(), t.dayOfWeek.value, hour * 60 + minute, placeIds),
        )
    }

    @Test
    fun `finds a place routine`() {
        val events = (0L..3L).map { event(it * 2, 9, 0, RingerMode.SILENT, setOf("p-shul")) }
        val suggestions = PatternMiner().mine(events, places, now, zone, EnglishPhrases)
        val place = suggestions.single { s -> s.rule.conditions.any { it is Condition.AtPlace } }
        assertEquals(listOf(Condition.AtPlace("p-shul")), place.rule.conditions)
        assertEquals(listOf<Action>(Action.SetRinger(RingerMode.SILENT)), place.rule.actions)
        assertEquals("Silent at Synagogue", place.rule.name)
        assertTrue(place.rule.restoreOnExit)
    }

    @Test
    fun `finds a nightly window with its usual end`() {
        val events = (0L..4L).flatMap { d ->
            listOf(event(d, 23, 0 + d.toInt(), RingerMode.SILENT), event(d + 1, 7, 0, RingerMode.NORMAL))
        }
        val suggestions = PatternMiner().mine(events, places, now, zone, EnglishPhrases)
        val night = suggestions.first { s -> (s.rule.actions.first() as Action.SetRinger).mode == RingerMode.SILENT }
        val window = night.rule.conditions.single() as Condition.TimeWindow
        assertEquals(23 * 60, window.startMinute)
        assertEquals(7 * 60, window.endMinute)
        assertTrue(night.rule.restoreOnExit)
        // The morning switch back is the end of that window, not a routine of its own.
        assertTrue(suggestions.none { s -> (s.rule.actions.first() as Action.SetRinger).mode == RingerMode.NORMAL })
    }

    @Test
    fun `needs several different days`() {
        val sameDay = (0..5).map { event(0, 9, it, RingerMode.VIBRATE, setOf("p-shul")) }
        assertTrue(PatternMiner().mine(sameDay, places, now, zone, EnglishPhrases).isEmpty())
    }

    @Test
    fun `inconsistent behavior is not a routine`() {
        val mixed = (0L..5L).map { d ->
            event(d, 9, 0, if (d % 2 == 0L) RingerMode.SILENT else RingerMode.VIBRATE, setOf("p-shul"))
        }
        val suggestions = PatternMiner().mine(mixed, places, now, zone, EnglishPhrases)
        assertTrue(suggestions.none { s -> s.rule.conditions.any { it is Condition.AtPlace } })
    }

    @Test
    fun `circular median handles midnight`() {
        assertEquals(0, PatternMiner.median(listOf(23 * 60 + 50, 10, 0)))
        assertEquals(20, PatternMiner.circularDistance(23 * 60 + 50, 10))
    }
}
