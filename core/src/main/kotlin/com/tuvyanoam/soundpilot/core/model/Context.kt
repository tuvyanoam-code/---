package com.tuvyanoam.soundpilot.core.model

import com.tuvyanoam.soundpilot.core.jewish.HolyPeriod
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class CalendarEventInfo(
    val title: String,
    val busy: Boolean,
    val endMillis: Long = 0,
)

/** Everything the automation knows about the phone's surroundings at one moment. */
@Serializable
data class ContextSnapshot(
    val epochMillis: Long,
    /** ISO day of week, 1 = Monday ... 7 = Sunday. */
    val dayOfWeek: Int,
    val minuteOfDay: Int,
    val placeIds: Set<String> = emptySet(),
    val wifiSsid: String? = null,
    val bluetoothDevices: Set<String> = emptySet(),
    val charging: Boolean = false,
    val headphones: Boolean = false,
    val noiseDb: Double? = null,
    val calendarEvents: List<CalendarEventInfo> = emptyList(),
    /** Shabbat and Yom Tov periods around now. Recomputed on every evaluation, so not stored. */
    @Transient val holyPeriods: List<HolyPeriod> = emptyList(),
    val shabbatNow: Boolean = false,
    val yomTovNow: Boolean = false,
    /** Minutes until the next Shabbat or Yom Tov starts, when that is within a few hours. */
    val minutesToHolyStart: Int? = null,
) {
    val inBusyEvent: Boolean get() = calendarEvents.any { it.busy }
    val inHolyTime: Boolean get() = shabbatNow || yomTovNow

    /** True when something a person would notice changed: place, network, devices or meetings. */
    fun differsMeaningfullyFrom(other: ContextSnapshot?): Boolean {
        if (other == null) return true
        return placeIds != other.placeIds ||
            wifiSsid != other.wifiSsid ||
            bluetoothDevices != other.bluetoothDevices ||
            headphones != other.headphones ||
            inBusyEvent != other.inBusyEvent ||
            inHolyTime != other.inHolyTime
    }
}
