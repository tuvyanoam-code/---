package com.tuvyanoam.soundpilot.core.model

import kotlinx.serialization.Serializable

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
) {
    val inBusyEvent: Boolean get() = calendarEvents.any { it.busy }

    /** True when something a person would notice changed: place, network, devices or meetings. */
    fun differsMeaningfullyFrom(other: ContextSnapshot?): Boolean {
        if (other == null) return true
        return placeIds != other.placeIds ||
            wifiSsid != other.wifiSsid ||
            bluetoothDevices != other.bluetoothDevices ||
            headphones != other.headphones ||
            inBusyEvent != other.inBusyEvent
    }
}
