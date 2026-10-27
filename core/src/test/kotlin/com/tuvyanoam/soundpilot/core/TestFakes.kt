package com.tuvyanoam.soundpilot.core

import com.tuvyanoam.soundpilot.core.model.AudioCommand
import com.tuvyanoam.soundpilot.core.model.AudioState
import com.tuvyanoam.soundpilot.core.model.AudioStream
import com.tuvyanoam.soundpilot.core.model.ContextSnapshot
import com.tuvyanoam.soundpilot.core.model.DndMode
import com.tuvyanoam.soundpilot.core.model.RingerMode
import com.tuvyanoam.soundpilot.core.platform.AudioGateway
import com.tuvyanoam.soundpilot.core.platform.Clock
import com.tuvyanoam.soundpilot.core.platform.ContextGateway
import com.tuvyanoam.soundpilot.core.platform.GeoPoint
import com.tuvyanoam.soundpilot.core.platform.PlatformHooks
import com.tuvyanoam.soundpilot.core.platform.UserNotifier
import com.tuvyanoam.soundpilot.core.platform.WakeUpScheduler
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

val defaultAudio = AudioState(
    ringerMode = RingerMode.NORMAL,
    volumes = mapOf(AudioStream.RING to 60, AudioStream.NOTIFICATION to 60, AudioStream.MEDIA to 40, AudioStream.ALARM to 80),
    dnd = DndMode.OFF,
)

class FakeAudio(var state: AudioState = defaultAudio) : AudioGateway {
    val applied = mutableListOf<AudioCommand>()
    var failure: String? = null

    override suspend fun read(): AudioState = state

    override suspend fun apply(command: AudioCommand): String? {
        failure?.let { return it }
        applied += command
        state = when (command) {
            is AudioCommand.Ringer -> state.copy(ringerMode = command.mode)
            is AudioCommand.Volume -> state.copy(volumes = state.volumes + (command.stream to command.percent))
            is AudioCommand.Dnd -> state.copy(dnd = command.mode)
        }
        return null
    }
}

class MutableClock(var dateTime: LocalDateTime) : Clock {
    override fun now(): Long = dateTime.toInstant(ZoneOffset.UTC).toEpochMilli()
    override fun zone(): ZoneId = ZoneOffset.UTC
}

class FakeContext(private val clock: MutableClock) : ContextGateway {
    var placeIds: Set<String> = emptySet()
    var wifi: String? = null
    var noise: Double? = null

    override suspend fun snapshot(): ContextSnapshot {
        val t = clock.dateTime
        return ContextSnapshot(
            epochMillis = clock.now(),
            dayOfWeek = t.dayOfWeek.value,
            minuteOfDay = t.hour * 60 + t.minute,
            placeIds = placeIds,
            wifiSsid = wifi,
            noiseDb = noise,
        )
    }

    override suspend fun nextCalendarChangeMillis(): Long? = null
}

class FakeScheduler : WakeUpScheduler {
    val scheduled = mutableMapOf<String, Long>()
    override fun schedule(tag: String, atMillis: Long) {
        scheduled[tag] = atMillis
    }
    override fun cancel(tag: String) {
        scheduled.remove(tag)
    }
}

class FakeNotifier : UserNotifier {
    val messages = mutableListOf<String>()
    override fun ruleMessage(text: String) {
        messages += "rule:$text"
    }
    override fun aiAction(summary: String) {
        messages += "ai:$summary"
    }
    override fun suggestionsAvailable(count: Int) {
        messages += "suggestions:$count"
    }
}

class FakePlatform : PlatformHooks {
    var location: GeoPoint? = GeoPoint(32.08, 34.78)
    var placesChangedCount = 0
    override suspend fun currentLocation(): GeoPoint? = location
    override fun missingCapabilities(): List<String> = emptyList()
    override suspend fun placesChanged() {
        placesChangedCount++
    }
}

/** A context at [day] (ISO) and [hour]:[minute]. */
fun ctx(day: Int = 1, hour: Int = 12, minute: Int = 0, places: Set<String> = emptySet(), noise: Double? = null) =
    ContextSnapshot(epochMillis = 0, dayOfWeek = day, minuteOfDay = hour * 60 + minute, placeIds = places, noiseDb = noise)
