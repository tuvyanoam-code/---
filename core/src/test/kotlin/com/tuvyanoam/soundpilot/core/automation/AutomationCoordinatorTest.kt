package com.tuvyanoam.soundpilot.core.automation

import com.tuvyanoam.soundpilot.core.FakeAudio
import com.tuvyanoam.soundpilot.core.FakeContext
import com.tuvyanoam.soundpilot.core.FakeNotifier
import com.tuvyanoam.soundpilot.core.FakeScheduler
import com.tuvyanoam.soundpilot.core.MutableClock
import com.tuvyanoam.soundpilot.core.model.Action
import com.tuvyanoam.soundpilot.core.model.ActivityEntry
import com.tuvyanoam.soundpilot.core.model.AudioCommand
import com.tuvyanoam.soundpilot.core.model.AudioStream
import com.tuvyanoam.soundpilot.core.model.Condition
import com.tuvyanoam.soundpilot.core.model.RingerMode
import com.tuvyanoam.soundpilot.core.model.Rule
import com.tuvyanoam.soundpilot.core.model.UserEvent
import com.tuvyanoam.soundpilot.core.platform.EnglishPhrases
import com.tuvyanoam.soundpilot.core.storage.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutomationCoordinatorTest {
    private val clock = MutableClock(LocalDateTime.of(2026, 10, 5, 12, 0)) // Monday noon
    private val storage = Storage(Files.createTempDirectory("sp").toFile(), clock::now)
    private val audio = FakeAudio()
    private val context = FakeContext(clock)
    private val scheduler = FakeScheduler()
    private val notifier = FakeNotifier()

    private fun coordinator(scope: CoroutineScope) =
        AutomationCoordinator(storage, audio, context, scheduler, notifier, clock, EnglishPhrases, scope)

    private val workRule = Rule(
        id = "work",
        name = "Work",
        conditions = listOf(Condition.AtPlace("p-work")),
        actions = listOf(Action.SetRinger(RingerMode.VIBRATE), Action.Notify("Vibrate at work")),
    )

    @Test
    fun `applies rules on arrival, restores on departure and logs it`() = runTest {
        storage.rules.upsert(workRule)
        val coordinator = coordinator(backgroundScope)

        context.placeIds = setOf("p-work")
        coordinator.evaluate("geofence")
        assertEquals(RingerMode.VIBRATE, audio.state.ringerMode)
        assertEquals(listOf("rule:Vibrate at work"), notifier.messages)

        context.placeIds = emptySet()
        coordinator.evaluate("geofence")
        assertEquals(RingerMode.NORMAL, audio.state.ringerMode)

        val log = storage.events.recentActivity(10).map { it.type }
        assertEquals(listOf(ActivityEntry.Type.RULE_ON, ActivityEntry.Type.RULE_OFF), log)
    }

    @Test
    fun `schedules a wake-up for the next time boundary`() = runTest {
        storage.rules.upsert(
            Rule("night", "Night", conditions = listOf(Condition.TimeWindow(startMinute = 22 * 60, endMinute = 7 * 60)),
                actions = listOf(Action.SetRinger(RingerMode.SILENT))),
        )
        coordinator(backgroundScope).evaluate("test")
        val expected = clock.now() + 10 * 60 * 60_000L + 2_000L
        assertEquals(expected, scheduler.scheduled[AutomationCoordinator.TAG_EVALUATE])
    }

    @Test
    fun `temporary mode pauses rules and restores afterwards`() = runTest {
        storage.rules.upsert(workRule)
        val coordinator = coordinator(backgroundScope)

        coordinator.startTemporary(listOf(AudioCommand.Ringer(RingerMode.SILENT)), 60, "Silent")
        assertEquals(RingerMode.SILENT, audio.state.ringerMode)
        assertNotNull(scheduler.scheduled[AutomationCoordinator.TAG_TEMPORARY])

        // Arriving at work during the temporary mode changes nothing yet.
        context.placeIds = setOf("p-work")
        coordinator.evaluate("geofence")
        assertEquals(RingerMode.SILENT, audio.state.ringerMode)

        clock.dateTime = clock.dateTime.plusMinutes(61)
        coordinator.evaluate("alarm")
        assertNull(storage.automation.get().temporary)
        assertEquals(RingerMode.VIBRATE, audio.state.ringerMode)

        // Leaving work restores the setting from before the temporary mode, not the temporary one.
        context.placeIds = emptySet()
        coordinator.evaluate("geofence")
        assertEquals(RingerMode.NORMAL, audio.state.ringerMode)
    }

    @Test
    fun `adaptive volume follows noise while ringing normally`() = runTest {
        storage.settings.update { it.copy(adaptive = it.adaptive.copy(enabled = true, minPercent = 30, maxPercent = 100)) }
        val coordinator = coordinator(backgroundScope)
        coordinator.evaluate("test")
        assertTrue(coordinator.adaptiveActive.value)

        coordinator.onNoiseSample(85.0)
        assertEquals(100, audio.state.volumes[AudioStream.RING])
        assertEquals(100, audio.state.volumes[AudioStream.NOTIFICATION])

        audio.state = audio.state.copy(ringerMode = RingerMode.VIBRATE)
        val before = audio.applied.size
        coordinator.onNoiseSample(30.0)
        assertEquals(before, audio.applied.size)
    }

    @Test
    fun `manual changes are recorded with their context`() = runTest {
        context.placeIds = setOf("p-home")
        coordinator(backgroundScope).recordUserChange(UserEvent.Kind.RINGER, ringerMode = RingerMode.SILENT)
        val event = storage.events.allUserEvents().single()
        assertEquals(RingerMode.SILENT, event.ringerMode)
        assertEquals(setOf("p-home"), event.context.placeIds)
    }

    @Test
    fun `unhandled context changes reach the listener`() = runTest {
        val coordinator = coordinator(this)
        val seen = mutableListOf<EvaluationReport>()
        coordinator.contextChangeListener = ContextChangeListener { seen += it }
        coordinator.evaluate("first")
        context.wifi = "Cafe"
        coordinator.evaluate("wifi")
        testScheduler.advanceUntilIdle()
        assertEquals(2, seen.size)
        assertEquals("Cafe", seen.last().context.wifiSsid)
    }
}
