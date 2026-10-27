package com.tuvyanoam.soundpilot.core.model

import com.tuvyanoam.soundpilot.core.jewish.HolyPeriod
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Place(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int = DEFAULT_RADIUS_METERS,
) {
    companion object {
        const val DEFAULT_RADIUS_METERS = 150
        const val MIN_RADIUS_METERS = 50
        const val MAX_RADIUS_METERS = 5000
    }
}

/**
 * A condition that must hold for a rule to be active. All conditions of a rule are combined with AND.
 *
 * Days are ISO day-of-week numbers (1 = Monday ... 7 = Sunday). Times are minutes since midnight.
 */
@Serializable
sealed interface Condition {

    @Serializable
    @SerialName("place")
    data class AtPlace(val placeId: String) : Condition

    /**
     * Starts on each of [days] at [startMinute] and ends at [endMinute], [endDayOffset] days later. With an offset
     * of 0 a window whose end is not after its start spans midnight, and equal start and end mean the whole day.
     * Shabbat, for example, is days = {Friday}, 16:00 to 20:00 with an offset of 1.
     */
    @Serializable
    @SerialName("time")
    data class TimeWindow(
        val days: Set<Int> = ALL_DAYS,
        val startMinute: Int,
        val endMinute: Int,
        val endDayOffset: Int = 0,
    ) : Condition {
        val isAllDay: Boolean get() = endDayOffset == 0 && startMinute == endMinute

        /** Days from the start day to the end day. */
        val effectiveEndOffset: Int get() = when {
            endDayOffset > 0 -> endDayOffset
            endMinute <= startMinute -> 1
            else -> 0
        }

        val lengthMinutes: Int get() = effectiveEndOffset * 24 * 60 + endMinute - startMinute
    }

    @Serializable
    @SerialName("calendar")
    data class InCalendarEvent(
        val keyword: String? = null,
        val busyOnly: Boolean = true,
    ) : Condition

    @Serializable
    @SerialName("wifi")
    data class WifiConnected(val ssid: String) : Condition

    @Serializable
    @SerialName("bluetooth")
    data class BluetoothConnected(val deviceName: String) : Condition

    @Serializable
    @SerialName("charging")
    data class Charging(val charging: Boolean) : Condition

    @Serializable
    @SerialName("headphones")
    data class Headphones(val connected: Boolean) : Condition

    /**
     * Shabbat and/or Yom Tov by the Hebrew calendar, from candle lighting to nightfall at the configured location.
     * The window can be widened, e.g. to silence the phone a few minutes before candle lighting.
     */
    @Serializable
    @SerialName("holy")
    data class HolyTime(
        val shabbat: Boolean = true,
        val yomTov: Boolean = true,
        val minutesBefore: Int = 0,
        val minutesAfter: Int = 0,
    ) : Condition {
        fun covers(period: HolyPeriod): Boolean = (shabbat && period.shabbat) || (yomTov && period.yomTov)

        fun startOf(period: HolyPeriod): Long = period.startMillis - minutesBefore * 60_000L
        fun endOf(period: HolyPeriod): Long = period.endMillis + minutesAfter * 60_000L
    }

    /** Ambient noise in estimated dB SPL. Either bound may be null. */
    @Serializable
    @SerialName("noise")
    data class Noise(val minDb: Double? = null, val maxDb: Double? = null) : Condition

    companion object {
        val ALL_DAYS: Set<Int> = (1..7).toSet()
    }
}

@Serializable
sealed interface Action {

    @Serializable
    @SerialName("ringer")
    data class SetRinger(val mode: RingerMode) : Action

    @Serializable
    @SerialName("volume")
    data class SetVolume(val stream: AudioStream, val percent: Int) : Action

    @Serializable
    @SerialName("dnd")
    data class SetDnd(val mode: DndMode) : Action

    /** Turns noise-adaptive ring volume on or off while the rule is active. */
    @Serializable
    @SerialName("adaptive")
    data class SetAdaptiveVolume(val enabled: Boolean) : Action

    /** Shows a notification when the rule becomes active. */
    @Serializable
    @SerialName("notify")
    data class Notify(val message: String) : Action
}

@Serializable
enum class RuleOrigin { USER, TEMPLATE, AI, LEARNED }

@Serializable
data class Rule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val conditions: List<Condition>,
    val actions: List<Action>,
    /** Higher wins when two active rules set the same thing. */
    val priority: Int = 0,
    /** When the rule stops matching, put back what it changed. */
    val restoreOnExit: Boolean = true,
    val origin: RuleOrigin = RuleOrigin.USER,
    val createdAt: Long = 0,
)
