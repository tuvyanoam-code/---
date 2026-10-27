package com.tuvyanoam.soundpilot.core.ai

import com.tuvyanoam.soundpilot.core.engine.TimeMath
import com.tuvyanoam.soundpilot.core.model.Action
import com.tuvyanoam.soundpilot.core.model.AudioStream
import com.tuvyanoam.soundpilot.core.model.Condition
import com.tuvyanoam.soundpilot.core.model.DndMode
import com.tuvyanoam.soundpilot.core.model.RingerMode
import com.tuvyanoam.soundpilot.core.model.Rule
import com.tuvyanoam.soundpilot.core.model.RuleOrigin
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The flat rule format Claude reads and writes. It is deliberately simpler than [Rule]: at most one condition of
 * each kind, combined with AND, which covers what people ask for and is easy for a model to produce correctly.
 */
@Serializable
data class RuleSpec(
    val name: String,
    val enabled: Boolean = true,
    val conditions: ConditionSpec = ConditionSpec(),
    val actions: ActionSpec = ActionSpec(),
    val priority: Int = 0,
    @SerialName("restore_on_exit") val restoreOnExit: Boolean = true,
)

@Serializable
data class ConditionSpec(
    @SerialName("place_id") val placeId: String? = null,
    val days: List<String>? = null,
    @SerialName("start_time") val startTime: String? = null,
    @SerialName("end_time") val endTime: String? = null,
    @SerialName("end_days_later") val endDaysLater: Int? = null,
    @SerialName("holy_time") val holyTime: String? = null,
    @SerialName("holy_minutes_before") val holyMinutesBefore: Int? = null,
    @SerialName("holy_minutes_after") val holyMinutesAfter: Int? = null,
    @SerialName("in_calendar_event") val inCalendarEvent: Boolean? = null,
    @SerialName("calendar_keyword") val calendarKeyword: String? = null,
    @SerialName("wifi_ssid") val wifiSsid: String? = null,
    @SerialName("bluetooth_device") val bluetoothDevice: String? = null,
    val charging: Boolean? = null,
    val headphones: Boolean? = null,
    @SerialName("noise_min_db") val noiseMinDb: Double? = null,
    @SerialName("noise_max_db") val noiseMaxDb: Double? = null,
)

@Serializable
data class ActionSpec(
    @SerialName("ringer_mode") val ringerMode: String? = null,
    @SerialName("ring_volume") val ringVolume: Int? = null,
    @SerialName("notification_volume") val notificationVolume: Int? = null,
    @SerialName("media_volume") val mediaVolume: Int? = null,
    @SerialName("alarm_volume") val alarmVolume: Int? = null,
    @SerialName("do_not_disturb") val doNotDisturb: String? = null,
    @SerialName("adaptive_volume") val adaptiveVolume: Boolean? = null,
    val notify: String? = null,
)

object RuleSpecs {
    val DAY_CODES = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")

    /** holy_time value -> (Shabbat, Yom Tov). */
    val HOLY_KINDS: Map<String, Pair<Boolean, Boolean>> = linkedMapOf(
        "SHABBAT_AND_YOM_TOV" to (true to true),
        "SHABBAT" to (true to false),
        "YOM_TOV" to (false to true),
    )

    fun dayCode(isoDay: Int): String = DAY_CODES[(isoDay - 1).coerceIn(0, 6)]

    /** Validates [spec] and converts it into a rule. Errors are written for Claude, in English. */
    fun toRule(spec: RuleSpec, id: String, origin: RuleOrigin, knownPlaceIds: Set<String>, createdAt: Long = 0): Result<Rule> {
        val problems = mutableListOf<String>()
        val conditions = mutableListOf<Condition>()
        val c = spec.conditions

        if (spec.name.isBlank()) problems += "name must not be empty"

        c.placeId?.let { placeId ->
            if (placeId !in knownPlaceIds) problems += "unknown place_id '$placeId' (call list_places, or create the place first)"
            else conditions += Condition.AtPlace(placeId)
        }

        val hasTime = c.startTime != null || c.endTime != null
        if (hasTime || c.days != null) {
            val days = c.days?.mapNotNull { code ->
                val index = DAY_CODES.indexOf(code.trim().uppercase().take(3))
                if (index < 0) { problems += "invalid day '$code' (use MON..SUN)"; null } else index + 1
            }?.toSet()?.ifEmpty { null } ?: Condition.ALL_DAYS
            if (hasTime) {
                val start = c.startTime?.let(TimeMath::parseMinute)
                val end = c.endTime?.let(TimeMath::parseMinute)
                val offset = c.endDaysLater ?: 0
                if (start == null || end == null) problems += "start_time and end_time must both be given as HH:mm"
                else if (offset !in 0..6) problems += "end_days_later must be 0-6"
                else conditions += Condition.TimeWindow(days, start, end, offset)
            } else {
                conditions += Condition.TimeWindow(days, 0, 0)
            }
        }

        c.holyTime?.let { value ->
            val kind = HOLY_KINDS[value.trim().uppercase()]
            val before = c.holyMinutesBefore ?: 0
            val after = c.holyMinutesAfter ?: 0
            when {
                kind == null -> problems += "holy_time must be one of ${HOLY_KINDS.keys.joinToString()}"
                before !in 0..180 || after !in 0..180 -> problems += "holy_minutes_before/after must be 0-180"
                else -> conditions += Condition.HolyTime(kind.first, kind.second, before, after)
            }
        }
        if (c.inCalendarEvent == true || !c.calendarKeyword.isNullOrBlank()) {
            conditions += Condition.InCalendarEvent(c.calendarKeyword?.trim()?.ifEmpty { null }, busyOnly = true)
        }
        c.wifiSsid?.takeIf { it.isNotBlank() }?.let { conditions += Condition.WifiConnected(it.trim()) }
        c.bluetoothDevice?.takeIf { it.isNotBlank() }?.let { conditions += Condition.BluetoothConnected(it.trim()) }
        c.charging?.let { conditions += Condition.Charging(it) }
        c.headphones?.let { conditions += Condition.Headphones(it) }
        if (c.noiseMinDb != null || c.noiseMaxDb != null) {
            if (c.noiseMinDb != null && c.noiseMaxDb != null && c.noiseMinDb > c.noiseMaxDb) {
                problems += "noise_min_db must not exceed noise_max_db"
            } else {
                conditions += Condition.Noise(c.noiseMinDb, c.noiseMaxDb)
            }
        }
        if (conditions.isEmpty() && problems.isEmpty()) problems += "a rule needs at least one condition"

        val a = spec.actions
        val actions = mutableListOf<Action>()
        a.ringerMode?.let { value ->
            enumOrNull<RingerMode>(value)?.let { actions += Action.SetRinger(it) }
                ?: run { problems += "ringer_mode must be NORMAL, VIBRATE or SILENT" }
        }
        listOf(
            AudioStream.RING to a.ringVolume,
            AudioStream.NOTIFICATION to a.notificationVolume,
            AudioStream.MEDIA to a.mediaVolume,
            AudioStream.ALARM to a.alarmVolume,
        ).forEach { (stream, percent) ->
            if (percent != null) {
                if (percent !in 0..100) problems += "${stream.name.lowercase()}_volume must be 0-100"
                else actions += Action.SetVolume(stream, percent)
            }
        }
        a.doNotDisturb?.let { value ->
            enumOrNull<DndMode>(value)?.let { actions += Action.SetDnd(it) }
                ?: run { problems += "do_not_disturb must be OFF, PRIORITY, ALARMS or TOTAL_SILENCE" }
        }
        a.adaptiveVolume?.let { actions += Action.SetAdaptiveVolume(it) }
        a.notify?.takeIf { it.isNotBlank() }?.let { actions += Action.Notify(it.trim().take(200)) }
        if (actions.none { it !is Action.Notify } && problems.isEmpty()) problems += "a rule needs at least one sound action"

        if (problems.isNotEmpty()) return Result.failure(IllegalArgumentException(problems.joinToString("; ")))
        return Result.success(
            Rule(
                id = id,
                name = spec.name.trim().take(80),
                enabled = spec.enabled,
                conditions = conditions,
                actions = actions,
                priority = spec.priority.coerceIn(-100, 100),
                restoreOnExit = spec.restoreOnExit,
                origin = origin,
                createdAt = createdAt,
            ),
        )
    }

    fun fromRule(rule: Rule): RuleSpec {
        var c = ConditionSpec()
        rule.conditions.forEach { condition ->
            c = when (condition) {
                is Condition.AtPlace -> c.copy(placeId = condition.placeId)
                is Condition.TimeWindow -> {
                    val days = if (condition.days.size == 7) null else condition.days.sorted().map(::dayCode)
                    if (condition.isAllDay) c.copy(days = days ?: DAY_CODES)
                    else c.copy(
                        days = days,
                        startTime = TimeMath.formatMinute(condition.startMinute),
                        endTime = TimeMath.formatMinute(condition.endMinute),
                        endDaysLater = condition.endDayOffset.takeIf { it > 0 },
                    )
                }
                is Condition.HolyTime -> c.copy(
                    holyTime = HOLY_KINDS.entries.firstOrNull { it.value == (condition.shabbat to condition.yomTov) }?.key,
                    holyMinutesBefore = condition.minutesBefore.takeIf { it > 0 },
                    holyMinutesAfter = condition.minutesAfter.takeIf { it > 0 },
                )
                is Condition.InCalendarEvent -> c.copy(inCalendarEvent = true, calendarKeyword = condition.keyword)
                is Condition.WifiConnected -> c.copy(wifiSsid = condition.ssid)
                is Condition.BluetoothConnected -> c.copy(bluetoothDevice = condition.deviceName)
                is Condition.Charging -> c.copy(charging = condition.charging)
                is Condition.Headphones -> c.copy(headphones = condition.connected)
                is Condition.Noise -> c.copy(noiseMinDb = condition.minDb, noiseMaxDb = condition.maxDb)
            }
        }
        var a = ActionSpec()
        rule.actions.forEach { action ->
            a = when (action) {
                is Action.SetRinger -> a.copy(ringerMode = action.mode.name)
                is Action.SetVolume -> when (action.stream) {
                    AudioStream.RING -> a.copy(ringVolume = action.percent)
                    AudioStream.NOTIFICATION -> a.copy(notificationVolume = action.percent)
                    AudioStream.MEDIA -> a.copy(mediaVolume = action.percent)
                    AudioStream.ALARM -> a.copy(alarmVolume = action.percent)
                }
                is Action.SetDnd -> a.copy(doNotDisturb = action.mode.name)
                is Action.SetAdaptiveVolume -> a.copy(adaptiveVolume = action.enabled)
                is Action.Notify -> a.copy(notify = action.message)
            }
        }
        return RuleSpec(rule.name, rule.enabled, c, a, rule.priority, rule.restoreOnExit)
    }

    /** JSON schema of [RuleSpec], used in tool definitions. */
    val schema: JsonObject by lazy {
        Schema.obj(
            "An automation rule. All given conditions must hold at once (AND). Omit what you do not need.",
            mapOf(
                "name" to Schema.string("Short name in the user's language, e.g. 'שקט בעבודה'"),
                "enabled" to Schema.boolean("Whether the rule is active. Default true."),
                "conditions" to Schema.obj(
                    "When the rule applies. At least one condition is required.",
                    mapOf(
                        "place_id" to Schema.string("Id of a saved place (from list_places). Active while the phone is inside it."),
                        "days" to Schema.array(Schema.string("Day", DAY_CODES), "Days of the week. Omit for every day."),
                        "start_time" to Schema.string("Window start, 24h HH:mm local time."),
                        "end_time" to Schema.string("Window end, HH:mm. May be earlier than start_time to span midnight."),
                        "end_days_later" to Schema.integer(
                            "Days after the start day on which the window ends, for windows longer than overnight. " +
                                "Shabbat: days [FRI], start_time 16:00, end_time 20:00, end_days_later 1. Omit otherwise.",
                            0, 6,
                        ),
                        "holy_time" to Schema.string(
                            "Hebrew calendar: active on Shabbat and/or Yom Tov (days when work is forbidden), from candle " +
                                "lighting to nightfall at the user's configured city. Prefer this over fixed times for Shabbat and holidays.",
                            HOLY_KINDS.keys.toList(),
                        ),
                        "holy_minutes_before" to Schema.integer("Start this many minutes before candle lighting (0-180).", 0, 180),
                        "holy_minutes_after" to Schema.integer("End this many minutes after nightfall (0-180).", 0, 180),
                        "in_calendar_event" to Schema.boolean("True to apply during busy events in the phone's calendar app."),
                        "calendar_keyword" to Schema.string("Only events whose title contains this text."),
                        "wifi_ssid" to Schema.string("Name of a Wi-Fi network the phone is connected to."),
                        "bluetooth_device" to Schema.string("Name of a connected Bluetooth device, e.g. the car."),
                        "charging" to Schema.boolean("True while charging, false while on battery."),
                        "headphones" to Schema.boolean("True while headphones are connected."),
                        "noise_min_db" to Schema.number("Ambient noise at least this many dB (30 quiet room - 85 very loud)."),
                        "noise_max_db" to Schema.number("Ambient noise at most this many dB."),
                    ),
                ),
                "actions" to Schema.obj(
                    "What to set while the rule applies. At least one sound setting is required.",
                    mapOf(
                        "ringer_mode" to Schema.string("Ringer mode", listOf("NORMAL", "VIBRATE", "SILENT")),
                        "ring_volume" to Schema.integer("Ringtone volume percent", 0, 100),
                        "notification_volume" to Schema.integer("Notification volume percent", 0, 100),
                        "media_volume" to Schema.integer("Media volume percent", 0, 100),
                        "alarm_volume" to Schema.integer("Alarm volume percent", 0, 100),
                        "do_not_disturb" to Schema.string("Do Not Disturb mode", listOf("OFF", "PRIORITY", "ALARMS", "TOTAL_SILENCE")),
                        "adaptive_volume" to Schema.boolean("Turn noise-adaptive ring volume on or off while active."),
                        "notify" to Schema.string("Optional notification text shown when the rule starts."),
                    ),
                ),
                "priority" to Schema.integer("Higher wins when rules conflict. Default 0.", -100, 100),
                "restore_on_exit" to Schema.boolean("Restore previous settings when the rule stops applying. Default true."),
            ),
            required = listOf("name", "conditions", "actions"),
        )
    }

    private inline fun <reified E : Enum<E>> enumOrNull(value: String): E? =
        enumValues<E>().firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
}
