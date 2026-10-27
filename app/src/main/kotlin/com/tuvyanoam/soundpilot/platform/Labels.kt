package com.tuvyanoam.soundpilot.platform

import android.content.Context
import com.tuvyanoam.soundpilot.R
import com.tuvyanoam.soundpilot.core.engine.TimeMath
import com.tuvyanoam.soundpilot.core.jewish.City
import com.tuvyanoam.soundpilot.core.jewish.HolySpan
import com.tuvyanoam.soundpilot.core.model.Action
import com.tuvyanoam.soundpilot.core.model.AudioStream
import com.tuvyanoam.soundpilot.core.model.Condition
import com.tuvyanoam.soundpilot.core.model.DndMode
import com.tuvyanoam.soundpilot.core.model.RingerMode
import com.tuvyanoam.soundpilot.core.platform.Phrases
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** Localized names for everything the user sees about rules and sound settings. */
object Labels {
    fun mode(context: Context, mode: RingerMode): String = context.getString(
        when (mode) {
            RingerMode.NORMAL -> R.string.mode_normal
            RingerMode.VIBRATE -> R.string.mode_vibrate
            RingerMode.SILENT -> R.string.mode_silent
        },
    )

    fun stream(context: Context, stream: AudioStream): String = context.getString(
        when (stream) {
            AudioStream.RING -> R.string.stream_ring
            AudioStream.NOTIFICATION -> R.string.stream_notification
            AudioStream.MEDIA -> R.string.stream_media
            AudioStream.ALARM -> R.string.stream_alarm
        },
    )

    fun dnd(context: Context, mode: DndMode): String = context.getString(
        when (mode) {
            DndMode.OFF -> R.string.dnd_off
            DndMode.PRIORITY -> R.string.dnd_priority
            DndMode.ALARMS -> R.string.dnd_alarms
            DndMode.TOTAL_SILENCE -> R.string.dnd_total
        },
    )

    /** ISO days ordered the way the user's locale starts the week. */
    fun cityName(city: City): String = if (isHebrew()) city.hebrewName else city.englishName

    fun isHebrew(): Boolean = Locale.getDefault().language in setOf("iw", "he")

    fun holyName(span: HolySpan): String = if (isHebrew()) span.hebrewName else span.englishName

    /** e.g. "Fri 17:18" in the user's language. */
    fun dayTime(millis: Long): String = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("EEE HH:mm", Locale.getDefault()))

    fun orderedDays(): List<Int> {
        val first = WeekFields.of(Locale.getDefault()).firstDayOfWeek.value
        return (0..6).map { ((first - 1 + it) % 7) + 1 }
    }

    fun dayShort(day: Int): String = DayOfWeek.of(day).getDisplayName(TextStyle.SHORT, Locale.getDefault())

    fun days(context: Context, days: Set<Int>): String = when {
        days.isEmpty() || days.size == 7 -> context.getString(R.string.days_every)
        else -> orderedDays().filter { it in days }.joinToString(", ") { dayShort(it) }
    }

    fun condition(context: Context, condition: Condition, placeNames: Map<String, String>): String = when (condition) {
        is Condition.AtPlace -> context.getString(R.string.cond_place, placeNames[condition.placeId] ?: "?")
        is Condition.TimeWindow -> when {
            condition.isAllDay -> context.getString(R.string.cond_all_day, days(context, condition.days))
            condition.endDayOffset > 0 -> context.getString(
                R.string.cond_time_span,
                days(context, condition.days),
                TimeMath.formatMinute(condition.startMinute),
                TimeMath.formatMinute(condition.endMinute),
                condition.endDayOffset,
            )
            else -> context.getString(
                R.string.cond_time,
                days(context, condition.days),
                TimeMath.formatMinute(condition.startMinute),
                TimeMath.formatMinute(condition.endMinute),
            )
        }
        is Condition.InCalendarEvent ->
            if (condition.keyword.isNullOrBlank()) context.getString(R.string.cond_calendar)
            else context.getString(R.string.cond_calendar_keyword, condition.keyword)
        is Condition.HolyTime -> {
            val base = context.getString(
                when {
                    condition.shabbat && condition.yomTov -> R.string.cond_holy_both
                    condition.shabbat -> R.string.cond_holy_shabbat
                    else -> R.string.cond_holy_yomtov
                },
            )
            if (condition.minutesBefore == 0 && condition.minutesAfter == 0) base
            else context.getString(R.string.cond_holy_margins, base, condition.minutesBefore, condition.minutesAfter)
        }
        is Condition.WifiConnected -> context.getString(R.string.cond_wifi, condition.ssid)
        is Condition.BluetoothConnected -> context.getString(R.string.cond_bluetooth, condition.deviceName)
        is Condition.Charging -> context.getString(if (condition.charging) R.string.cond_charging else R.string.cond_on_battery)
        is Condition.Headphones -> context.getString(if (condition.connected) R.string.cond_headphones else R.string.cond_no_headphones)
        is Condition.Noise -> when {
            condition.minDb != null && condition.maxDb != null ->
                context.getString(R.string.cond_noise_between, condition.minDb!!.toInt(), condition.maxDb!!.toInt())
            condition.minDb != null -> context.getString(R.string.cond_noise_above, condition.minDb!!.toInt())
            else -> context.getString(R.string.cond_noise_below, (condition.maxDb ?: 0.0).toInt())
        }
    }

    fun action(context: Context, action: Action): String = when (action) {
        is Action.SetRinger -> mode(context, action.mode)
        is Action.SetVolume -> context.getString(R.string.act_volume, stream(context, action.stream), action.percent)
        is Action.SetDnd -> context.getString(R.string.act_dnd, dnd(context, action.mode))
        is Action.SetAdaptiveVolume -> context.getString(if (action.enabled) R.string.act_adaptive_on else R.string.act_adaptive_off)
        is Action.Notify -> context.getString(R.string.act_notify, action.message)
    }

    fun conditions(context: Context, conditions: List<Condition>, placeNames: Map<String, String>): String =
        conditions.joinToString(" · ") { condition(context, it, placeNames) }

    fun actions(context: Context, actions: List<Action>): String = actions.joinToString(" · ") { action(context, it) }
}

class AndroidPhrases(private val context: Context) : Phrases {
    private fun s(id: Int, vararg args: Any): String = context.getString(id, *args)
    private fun mode(mode: RingerMode) = Labels.mode(context, mode)
    private fun minutes(text: String, minutes: Int?) = if (minutes == null) text else s(R.string.for_minutes, text, minutes)

    override fun placeRuleName(mode: RingerMode, placeName: String) = s(R.string.learn_rule_place, mode(mode), placeName)
    override fun wifiRuleName(mode: RingerMode, ssid: String) = s(R.string.learn_rule_wifi, mode(mode), ssid)
    override fun bluetoothRuleName(mode: RingerMode, device: String) = s(R.string.learn_rule_bluetooth, mode(mode), device)
    override fun calendarRuleName(mode: RingerMode) = s(R.string.learn_rule_calendar, mode(mode))
    override fun holyRuleName(mode: RingerMode) = s(R.string.learn_rule_holy, mode(mode))
    override fun timeRuleName(mode: RingerMode, days: Set<Int>, startMinute: Int, endMinute: Int) =
        s(R.string.learn_rule_time, mode(mode), Labels.days(context, days), TimeMath.formatMinute(startMinute))
    override fun rationale(events: Int, days: Int, confidencePercent: Int) = s(R.string.learn_rationale, events, days, confidencePercent)

    override fun ruleOn(name: String) = s(R.string.log_rule_on, name)
    override fun ruleOff(name: String) = s(R.string.log_rule_off, name)
    override fun userRinger(mode: RingerMode) = s(R.string.log_user_ringer, mode(mode))
    override fun userVolume(stream: AudioStream, percent: Int) = s(R.string.log_user_volume, Labels.stream(context, stream), percent)
    override fun userDnd(mode: DndMode) = s(R.string.log_user_dnd, Labels.dnd(context, mode))
    override fun adaptiveChange(percent: Int, noiseDb: Int) = s(R.string.log_adaptive, percent, noiseDb)
    override fun temporaryStart(description: String, minutes: Int) = s(R.string.log_temporary_start, description, minutes)
    override fun temporaryEnd() = s(R.string.log_temporary_end)
    override fun applyFailed(reason: String) = s(R.string.log_apply_failed, reason)
    override fun aiAction(description: String) = s(R.string.log_ai_action, description)

    override fun toolRinger(mode: RingerMode, minutes: Int?) = minutes(mode(mode), minutes)
    override fun toolVolume(stream: AudioStream, percent: Int, minutes: Int?) =
        minutes(s(R.string.act_volume, Labels.stream(context, stream), percent), minutes)
    override fun toolDnd(mode: DndMode, minutes: Int?) = minutes(s(R.string.act_dnd, Labels.dnd(context, mode)), minutes)
    override fun toolRuleCreated(name: String) = s(R.string.tool_rule_created, name)
    override fun toolRuleUpdated(name: String) = s(R.string.tool_rule_updated, name)
    override fun toolRuleDeleted(name: String) = s(R.string.tool_rule_deleted, name)
    override fun toolRuleToggled(name: String, enabled: Boolean) =
        s(if (enabled) R.string.tool_rule_enabled else R.string.tool_rule_disabled, name)
    override fun toolPlaceSaved(name: String) = s(R.string.tool_place_saved, name)
    override fun toolAdaptive(enabled: Boolean) = s(if (enabled) R.string.act_adaptive_on else R.string.act_adaptive_off)
    override fun toolRemembered(note: String) = s(R.string.tool_remembered, note)
    override fun toolForgot() = s(R.string.tool_forgot)
    override fun toolSuggested(name: String) = s(R.string.tool_suggested, name)
    override fun toolDrafted(name: String) = s(R.string.tool_drafted, name)
    override fun toolTemporaryCancelled() = s(R.string.tool_temporary_cancelled)
    override fun toolJewishCalendar(location: String) = s(R.string.tool_jewish_calendar, location)
}
