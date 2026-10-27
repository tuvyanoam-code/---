package com.tuvyanoam.soundpilot.core.engine

import com.tuvyanoam.soundpilot.core.ctx
import com.tuvyanoam.soundpilot.core.defaultAudio
import com.tuvyanoam.soundpilot.core.model.Action
import com.tuvyanoam.soundpilot.core.model.AudioCommand
import com.tuvyanoam.soundpilot.core.model.AudioStream
import com.tuvyanoam.soundpilot.core.model.Condition
import com.tuvyanoam.soundpilot.core.model.RingerMode
import com.tuvyanoam.soundpilot.core.model.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuleEngineTest {
    private val atWork = Rule(
        id = "work",
        name = "Work",
        conditions = listOf(Condition.AtPlace("p-work")),
        actions = listOf(Action.SetRinger(RingerMode.VIBRATE)),
    )

    @Test
    fun `entering a place applies the rule once and leaving restores`() {
        val enter = RuleEngine.evaluate(listOf(atWork), ctx(places = setOf("p-work")), EngineState(), defaultAudio)
        assertEquals(listOf<AudioCommand>(AudioCommand.Ringer(RingerMode.VIBRATE)), enter.commands)
        assertEquals(listOf("work"), enter.activated.map { it.id })

        // Still at work: nothing to do, even though the user switched back to sound meanwhile.
        val manual = defaultAudio.copy(ringerMode = RingerMode.NORMAL)
        val stay = RuleEngine.evaluate(listOf(atWork), ctx(places = setOf("p-work")), enter.state, manual)
        assertTrue(stay.commands.isEmpty())

        val leave = RuleEngine.evaluate(listOf(atWork), ctx(), stay.state, defaultAudio.copy(ringerMode = RingerMode.VIBRATE))
        assertEquals(listOf<AudioCommand>(AudioCommand.Ringer(RingerMode.NORMAL)), leave.commands)
        assertEquals(listOf("work"), leave.deactivated)
        assertTrue(leave.state.controlled.isEmpty())
        assertTrue(leave.state.baselines.isEmpty())
    }

    @Test
    fun `restoreOnExit false leaves the setting`() {
        val rule = atWork.copy(restoreOnExit = false)
        val enter = RuleEngine.evaluate(listOf(rule), ctx(places = setOf("p-work")), EngineState(), defaultAudio)
        val leave = RuleEngine.evaluate(listOf(rule), ctx(), enter.state, defaultAudio.copy(ringerMode = RingerMode.VIBRATE))
        assertTrue(leave.commands.isEmpty())
    }

    @Test
    fun `higher priority wins and the lower one takes over when it ends`() {
        val meeting = Rule(
            id = "meeting",
            name = "Meeting",
            priority = 10,
            conditions = listOf(Condition.InCalendarEvent()),
            actions = listOf(Action.SetRinger(RingerMode.SILENT)),
        )
        val rules = listOf(atWork, meeting)
        val busy = ctx(places = setOf("p-work")).copy(
            calendarEvents = listOf(com.tuvyanoam.soundpilot.core.model.CalendarEventInfo("Standup", busy = true)),
        )
        val both = RuleEngine.evaluate(rules, busy, EngineState(), defaultAudio)
        assertEquals(listOf<AudioCommand>(AudioCommand.Ringer(RingerMode.SILENT)), both.commands)

        val afterMeeting = RuleEngine.evaluate(rules, ctx(places = setOf("p-work")), both.state, defaultAudio.copy(ringerMode = RingerMode.SILENT))
        assertEquals(listOf<AudioCommand>(AudioCommand.Ringer(RingerMode.VIBRATE)), afterMeeting.commands)

        // The original setting (sound) is still what comes back at the end.
        val home = RuleEngine.evaluate(rules, ctx(), afterMeeting.state, defaultAudio.copy(ringerMode = RingerMode.VIBRATE))
        assertEquals(listOf<AudioCommand>(AudioCommand.Ringer(RingerMode.NORMAL)), home.commands)
    }

    @Test
    fun `volumes restore per stream and commands are ordered`() {
        val loud = Rule(
            id = "car",
            name = "Car",
            conditions = listOf(Condition.AtPlace("p-car")),
            actions = listOf(Action.SetVolume(AudioStream.RING, 100), Action.SetRinger(RingerMode.NORMAL)),
        )
        val enter = RuleEngine.evaluate(listOf(loud), ctx(places = setOf("p-car")), EngineState(), defaultAudio)
        assertEquals(listOf(AudioCommand.Ringer(RingerMode.NORMAL), AudioCommand.Volume(AudioStream.RING, 100)), enter.commands)
        val leave = RuleEngine.evaluate(listOf(loud), ctx(), enter.state, defaultAudio)
        assertTrue(AudioCommand.Volume(AudioStream.RING, 60) in leave.commands)
    }

    @Test
    fun `force reapply writes every controlled setting`() {
        val enter = RuleEngine.evaluate(listOf(atWork), ctx(places = setOf("p-work")), EngineState(), defaultAudio)
        val again = RuleEngine.evaluate(listOf(atWork), ctx(places = setOf("p-work")), enter.state, defaultAudio, forceReapply = true)
        assertEquals(listOf<AudioCommand>(AudioCommand.Ringer(RingerMode.VIBRATE)), again.commands)
    }

    @Test
    fun `disabled rules and rules without conditions never match`() {
        val disabled = atWork.copy(enabled = false)
        val empty = atWork.copy(id = "empty", conditions = emptyList())
        val result = RuleEngine.evaluate(listOf(disabled, empty), ctx(places = setOf("p-work")), EngineState(), defaultAudio)
        assertTrue(result.commands.isEmpty())
        assertFalse(result.changed)
    }

    @Test
    fun `adaptive override and notifications`() {
        val rule = Rule(
            id = "noisy",
            name = "Noisy",
            conditions = listOf(Condition.Noise(minDb = 70.0)),
            actions = listOf(Action.SetAdaptiveVolume(true), Action.Notify("Loud here")),
        )
        val quiet = RuleEngine.evaluate(listOf(rule), ctx(noise = 50.0), EngineState(), defaultAudio)
        assertNull(quiet.adaptiveOverride)
        val loud = RuleEngine.evaluate(listOf(rule), ctx(noise = 80.0), quiet.state, defaultAudio)
        assertEquals(true, loud.adaptiveOverride)
        assertEquals(listOf("Loud here"), loud.notifications)
        assertTrue(loud.commands.isEmpty())
    }

    @Test
    fun `holy time matches by kind and margins`() {
        val period = com.tuvyanoam.soundpilot.core.jewish.HolyPeriod(1_000_000, 2_000_000, shabbat = true, yomTov = false, "שבת", "Shabbat")
        val at = { millis: Long -> ctx().copy(epochMillis = millis, holyPeriods = listOf(period)) }
        val shabbat = Condition.HolyTime(shabbat = true, yomTov = false, minutesBefore = 5)
        val holidaysOnly = Condition.HolyTime(shabbat = false, yomTov = true)
        assertTrue(RuleEngine.matches(shabbat, at(1_000_000 - 4 * 60_000)))
        assertFalse(RuleEngine.matches(shabbat, at(1_000_000 - 6 * 60_000)))
        assertFalse(RuleEngine.matches(shabbat, at(2_000_000)))
        assertFalse(RuleEngine.matches(holidaysOnly, at(1_500_000)))
    }

    @Test
    fun `wifi names match without quotes and case`() {
        val rule = atWork.copy(conditions = listOf(Condition.WifiConnected("Office-5G")))
        assertTrue(RuleEngine.isActive(rule, ctx().copy(wifiSsid = "\"office-5g\"")))
    }
}
