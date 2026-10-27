package com.tuvyanoam.soundpilot.core.learning

import com.tuvyanoam.soundpilot.core.model.Action
import com.tuvyanoam.soundpilot.core.model.Condition
import com.tuvyanoam.soundpilot.core.model.ContextSnapshot
import com.tuvyanoam.soundpilot.core.model.Place
import com.tuvyanoam.soundpilot.core.model.RingerMode
import com.tuvyanoam.soundpilot.core.model.Rule
import com.tuvyanoam.soundpilot.core.model.RuleOrigin
import com.tuvyanoam.soundpilot.core.model.Suggestion
import com.tuvyanoam.soundpilot.core.model.UserEvent
import com.tuvyanoam.soundpilot.core.storage.newId
import java.time.Instant
import java.time.ZoneId
import kotlin.math.ln
import kotlin.math.roundToInt

/** Localized wording for learned rules. The app supplies one backed by string resources. */
interface LearningPhrases {
    fun placeRuleName(mode: RingerMode, placeName: String): String
    fun wifiRuleName(mode: RingerMode, ssid: String): String
    fun bluetoothRuleName(mode: RingerMode, device: String): String
    fun calendarRuleName(mode: RingerMode): String
    fun timeRuleName(mode: RingerMode, days: Set<Int>, startMinute: Int, endMinute: Int): String
    fun rationale(events: Int, days: Int, confidencePercent: Int): String
}

/**
 * Finds routines in the user's manual ringer changes, entirely on the device. A pattern is reported when the same
 * change happened on several different days and in most of the comparable situations.
 */
class PatternMiner(
    private val minDistinctDays: Int = 3,
    private val minConfidence: Double = 0.7,
    private val lookbackDays: Int = 30,
    private val clusterGapMinutes: Int = 40,
    private val maxSuggestions: Int = 8,
) {

    fun mine(
        events: List<UserEvent>,
        places: List<Place>,
        now: Long,
        zone: ZoneId,
        phrases: LearningPhrases,
    ): List<Suggestion> {
        val since = now - lookbackDays * DAY_MILLIS
        val ringer = events
            .filter { it.kind == UserEvent.Kind.RINGER && it.ringerMode != null && it.epochMillis >= since }
            .sortedBy { it.epochMillis }
        if (ringer.size < minDistinctDays) return emptyList()

        val placeNames = places.associate { it.id to it.name }
        val found = mutableListOf<Scored>()

        found += contextual(ringer, zone, now, { ctx -> ctx.placeIds.filter { it in placeNames } }) { mode, placeId ->
            Condition.AtPlace(placeId) to phrases.placeRuleName(mode, placeNames.getValue(placeId))
        }
        found += contextual(ringer, zone, now, { ctx -> listOfNotNull(ctx.wifiSsid) }) { mode, ssid ->
            Condition.WifiConnected(ssid) to phrases.wifiRuleName(mode, ssid)
        }
        found += contextual(ringer, zone, now, { ctx -> ctx.bluetoothDevices.toList() }) { mode, device ->
            Condition.BluetoothConnected(device) to phrases.bluetoothRuleName(mode, device)
        }
        found += contextual(ringer, zone, now, { ctx -> if (ctx.inBusyEvent) listOf(BUSY) else emptyList() }) { mode, _ ->
            Condition.InCalendarEvent(keyword = null, busyOnly = true) to phrases.calendarRuleName(mode)
        }
        found += timePatterns(ringer, zone, now, phrases)

        return found
            .sortedByDescending { it.score }
            .distinctBy { it.suggestion.rule.conditions to it.suggestion.rule.actions }
            .take(maxSuggestions)
            .map { scored ->
                scored.suggestion.copy(
                    rationale = phrases.rationale(
                        scored.suggestion.evidenceCount,
                        scored.days,
                        (scored.suggestion.confidence * 100).roundToInt(),
                    ),
                )
            }
    }

    private data class Scored(val suggestion: Suggestion, val days: Int) {
        val score: Double get() = suggestion.confidence * ln(1.0 + days)
    }

    private fun contextual(
        events: List<UserEvent>,
        zone: ZoneId,
        now: Long,
        values: (ContextSnapshot) -> List<String>,
        build: (RingerMode, String) -> Pair<Condition, String>,
    ): List<Scored> {
        val byValue = mutableMapOf<String, MutableList<UserEvent>>()
        events.forEach { event -> values(event.context).forEach { byValue.getOrPut(it) { mutableListOf() } += event } }

        return byValue.flatMap { (value, atValue) ->
            atValue.groupBy { it.ringerMode!! }
                // Switching back to normal is what leaving a place does anyway; it is not a routine of its own.
                .filterKeys { it != RingerMode.NORMAL }
                .mapNotNull { (mode, matching) ->
                    val days = distinctDays(matching, zone)
                    val confidence = matching.size.toDouble() / atValue.size
                    if (days < minDistinctDays || confidence < minConfidence) return@mapNotNull null
                    val (condition, name) = build(mode, value)
                    Scored(suggestion(name, listOf(condition), mode, restore = true, confidence, matching.size, now), days)
                }
        }
    }

    private fun timePatterns(events: List<UserEvent>, zone: ZoneId, now: Long, phrases: LearningPhrases): List<Scored> {
        val results = mutableListOf<Scored>()
        val usedEnds = mutableListOf<Int>()
        val modes = listOf(RingerMode.SILENT, RingerMode.VIBRATE, RingerMode.NORMAL)
        for (mode in modes) {
            val ofMode = events.filter { it.ringerMode == mode }
            for (cluster in clusterByMinute(ofMode)) {
                val days = distinctDays(cluster, zone)
                if (days < minDistinctDays) continue
                val start = roundDown5(median(cluster.map { it.context.minuteOfDay }))
                if (mode == RingerMode.NORMAL && usedEnds.any { circularDistance(it, start) <= clusterGapMinutes }) continue

                val weekDays = cluster.map { it.context.dayOfWeek }.toSet().let { if (it.size >= 6) Condition.ALL_DAYS else it }
                // How often, around this time on these days, the user chose this mode rather than another.
                val comparable = events.filter { e ->
                    e.context.dayOfWeek in weekDays && circularDistance(e.context.minuteOfDay, start) <= clusterGapMinutes
                }
                val confidence = cluster.size.toDouble() / comparable.size.coerceAtLeast(1)
                if (confidence < minConfidence) continue

                val end = typicalEnd(cluster, events, mode)
                val (window, restore) = if (end != null) {
                    usedEnds += end
                    Condition.TimeWindow(weekDays, start, end) to true
                } else {
                    // No consistent switch back: act like a trigger at that time and leave the phone as set.
                    Condition.TimeWindow(weekDays, start, (start + 30) % MINUTES_PER_DAY) to false
                }
                val name = phrases.timeRuleName(mode, weekDays, window.startMinute, window.endMinute)
                results += Scored(suggestion(name, listOf(window), mode, restore, confidence, cluster.size, now), days)
            }
        }
        return results
    }

    /** The usual time the user switched away from [mode] after the events in [cluster], if consistent. */
    private fun typicalEnd(cluster: List<UserEvent>, all: List<UserEvent>, mode: RingerMode): Int? {
        val followUps = cluster.mapNotNull { event ->
            all.firstOrNull { it.epochMillis > event.epochMillis && it.ringerMode != mode }
                ?.takeIf { it.epochMillis - event.epochMillis <= MAX_WINDOW_MILLIS }
        }
        if (followUps.size < cluster.size * 0.6) return null
        val biggest = clusterByMinute(followUps).maxByOrNull { it.size } ?: return null
        if (biggest.size < cluster.size * 0.6) return null
        return roundUp5(median(biggest.map { it.context.minuteOfDay }))
    }

    private fun clusterByMinute(events: List<UserEvent>): List<List<UserEvent>> {
        if (events.isEmpty()) return emptyList()
        val sorted = events.sortedBy { it.context.minuteOfDay }
        val clusters = mutableListOf(mutableListOf(sorted.first()))
        for (event in sorted.drop(1)) {
            val last = clusters.last().last()
            if (event.context.minuteOfDay - last.context.minuteOfDay <= clusterGapMinutes) clusters.last() += event
            else clusters += mutableListOf(event)
        }
        // Join the first and last clusters when they meet across midnight.
        if (clusters.size > 1) {
            val first = clusters.first()
            val last = clusters.last()
            val gap = first.first().context.minuteOfDay + MINUTES_PER_DAY - last.last().context.minuteOfDay
            if (gap <= clusterGapMinutes) {
                clusters.removeAt(clusters.lastIndex)
                clusters[0] = (last + first).toMutableList()
            }
        }
        return clusters
    }

    private fun suggestion(
        name: String,
        conditions: List<Condition>,
        mode: RingerMode,
        restore: Boolean,
        confidence: Double,
        evidence: Int,
        now: Long,
    ) = Suggestion(
        id = newId(),
        rule = Rule(
            id = "",
            name = name,
            conditions = conditions,
            actions = listOf(Action.SetRinger(mode)),
            restoreOnExit = restore,
            origin = RuleOrigin.LEARNED,
        ),
        rationale = "",
        source = Suggestion.Source.ON_DEVICE,
        confidence = confidence.coerceIn(0.0, 1.0),
        evidenceCount = evidence,
        createdAt = now,
    )

    private fun distinctDays(events: List<UserEvent>, zone: ZoneId): Int =
        // atZone().toLocalDate() rather than LocalDate.ofInstant, which older Android versions lack.
        events.map { Instant.ofEpochMilli(it.epochMillis).atZone(zone).toLocalDate() }.toSet().size

    companion object {
        private const val BUSY = "busy"
        private const val MINUTES_PER_DAY = 24 * 60
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
        private const val MAX_WINDOW_MILLIS = 16L * 60 * 60 * 1000

        /** Median that respects the clock wrapping at midnight (e.g. 23:50 and 00:10 -> 00:00). */
        internal fun median(minutes: List<Int>): Int {
            if (minutes.isEmpty()) return 0
            val anchor = minutes.first()
            val unwrapped = minutes.map { m ->
                var x = m
                if (x - anchor > MINUTES_PER_DAY / 2) x -= MINUTES_PER_DAY
                if (anchor - x > MINUTES_PER_DAY / 2) x += MINUTES_PER_DAY
                x
            }.sorted()
            val mid = unwrapped[unwrapped.size / 2]
            return ((mid % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        }

        internal fun circularDistance(a: Int, b: Int): Int {
            val d = kotlin.math.abs(a - b) % MINUTES_PER_DAY
            return minOf(d, MINUTES_PER_DAY - d)
        }

        private fun roundDown5(minute: Int) = minute - minute % 5
        private fun roundUp5(minute: Int) = ((minute + 4) / 5 * 5) % MINUTES_PER_DAY
    }
}
