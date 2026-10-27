package com.tuvyanoam.soundpilot.core.platform

import com.tuvyanoam.soundpilot.core.learning.LearningPhrases
import com.tuvyanoam.soundpilot.core.model.AudioCommand
import com.tuvyanoam.soundpilot.core.model.AudioState
import com.tuvyanoam.soundpilot.core.model.AudioStream
import com.tuvyanoam.soundpilot.core.model.ContextSnapshot
import com.tuvyanoam.soundpilot.core.model.DndMode
import com.tuvyanoam.soundpilot.core.model.RingerMode
import java.time.ZoneId

/** Reads and changes the phone's sound settings. */
interface AudioGateway {
    suspend fun read(): AudioState

    /** Applies one change. Returns null on success, or a short reason it could not be done. */
    suspend fun apply(command: AudioCommand): String?
}

/** Collects the current context (location, network, calendar, ...). */
interface ContextGateway {
    suspend fun snapshot(): ContextSnapshot

    /** When the next calendar event starts or the current one ends, if known. */
    suspend fun nextCalendarChangeMillis(): Long?
}

/** Wakes the automation at a given time even if the app is not running. */
interface WakeUpScheduler {
    fun schedule(tag: String, atMillis: Long)
    fun cancel(tag: String)
}

interface UserNotifier {
    fun ruleMessage(text: String)
    fun aiAction(summary: String)
    fun suggestionsAvailable(count: Int)
}

data class GeoPoint(val latitude: Double, val longitude: Double, val accuracyMeters: Float? = null)

/** Platform services the assistant's tools need beyond sound control. */
interface PlatformHooks {
    suspend fun currentLocation(): GeoPoint?

    /** Human readable names of permissions or settings the user still needs to grant. */
    fun missingCapabilities(): List<String>

    /** Called after places change so geofences can be re-registered. */
    suspend fun placesChanged()
}

interface Clock {
    fun now(): Long
    fun zone(): ZoneId

    object System : Clock {
        override fun now(): Long = java.lang.System.currentTimeMillis()
        override fun zone(): ZoneId = ZoneId.systemDefault()
    }
}

/** Every user-visible sentence the core produces. The app implements it with localized string resources. */
interface Phrases : LearningPhrases {
    fun ruleOn(name: String): String
    fun ruleOff(name: String): String
    fun userRinger(mode: RingerMode): String
    fun userVolume(stream: AudioStream, percent: Int): String
    fun userDnd(mode: DndMode): String
    fun adaptiveChange(percent: Int, noiseDb: Int): String
    fun temporaryStart(description: String, minutes: Int): String
    fun temporaryEnd(): String
    fun applyFailed(reason: String): String
    fun aiAction(description: String): String

    // Short labels for what the assistant did, shown under its replies.
    fun toolRinger(mode: RingerMode, minutes: Int?): String
    fun toolVolume(stream: AudioStream, percent: Int, minutes: Int?): String
    fun toolDnd(mode: DndMode, minutes: Int?): String
    fun toolRuleCreated(name: String): String
    fun toolRuleUpdated(name: String): String
    fun toolRuleDeleted(name: String): String
    fun toolRuleToggled(name: String, enabled: Boolean): String
    fun toolPlaceSaved(name: String): String
    fun toolAdaptive(enabled: Boolean): String
    fun toolRemembered(note: String): String
    fun toolForgot(): String
    fun toolSuggested(name: String): String
    fun toolDrafted(name: String): String
    fun toolTemporaryCancelled(): String
}

open class EnglishPhrases : Phrases {
    protected fun modeName(mode: RingerMode) = mode.name.lowercase().replaceFirstChar { it.uppercase() }

    override fun placeRuleName(mode: RingerMode, placeName: String) = "${modeName(mode)} at $placeName"
    override fun wifiRuleName(mode: RingerMode, ssid: String) = "${modeName(mode)} on Wi-Fi $ssid"
    override fun bluetoothRuleName(mode: RingerMode, device: String) = "${modeName(mode)} with $device"
    override fun calendarRuleName(mode: RingerMode) = "${modeName(mode)} during meetings"
    override fun timeRuleName(mode: RingerMode, days: Set<Int>, startMinute: Int, endMinute: Int) =
        "${modeName(mode)} at ${"%02d:%02d".format(startMinute / 60, startMinute % 60)}"
    override fun rationale(events: Int, days: Int, confidencePercent: Int) =
        "You did this $events times on $days different days ($confidencePercent% of the time)."

    override fun ruleOn(name: String) = "Rule on: $name"
    override fun ruleOff(name: String) = "Rule off: $name"
    override fun userRinger(mode: RingerMode) = "You switched to ${modeName(mode)}"
    override fun userVolume(stream: AudioStream, percent: Int) = "You set ${stream.name.lowercase()} volume to $percent%"
    override fun userDnd(mode: DndMode) = "You set Do Not Disturb: ${mode.name.lowercase()}"
    override fun adaptiveChange(percent: Int, noiseDb: Int) = "Ring volume $percent% (noise $noiseDb dB)"
    override fun temporaryStart(description: String, minutes: Int) = "$description for $minutes min"
    override fun temporaryEnd() = "Temporary mode ended, settings restored"
    override fun applyFailed(reason: String) = "Could not apply: $reason"
    override fun aiAction(description: String) = "Claude: $description"

    private fun forMinutes(minutes: Int?) = if (minutes != null) " for $minutes min" else ""
    override fun toolRinger(mode: RingerMode, minutes: Int?) = "Ringer: ${modeName(mode)}${forMinutes(minutes)}"
    override fun toolVolume(stream: AudioStream, percent: Int, minutes: Int?) =
        "${stream.name.lowercase().replaceFirstChar { it.uppercase() }} volume $percent%${forMinutes(minutes)}"
    override fun toolDnd(mode: DndMode, minutes: Int?) = "Do Not Disturb: ${mode.name.lowercase()}${forMinutes(minutes)}"
    override fun toolRuleCreated(name: String) = "Rule created: $name"
    override fun toolRuleUpdated(name: String) = "Rule updated: $name"
    override fun toolRuleDeleted(name: String) = "Rule deleted: $name"
    override fun toolRuleToggled(name: String, enabled: Boolean) = "Rule ${if (enabled) "enabled" else "disabled"}: $name"
    override fun toolPlaceSaved(name: String) = "Place saved: $name"
    override fun toolAdaptive(enabled: Boolean) = "Adaptive volume ${if (enabled) "on" else "off"}"
    override fun toolRemembered(note: String) = "Remembered: $note"
    override fun toolForgot() = "Forgot a note"
    override fun toolSuggested(name: String) = "Suggested: $name"
    override fun toolDrafted(name: String) = "Draft: $name"
    override fun toolTemporaryCancelled() = "Temporary mode cancelled"

    companion object Default : EnglishPhrases()
}
