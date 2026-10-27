package com.tuvyanoam.soundpilot.core.engine

import com.tuvyanoam.soundpilot.core.model.Condition
import com.tuvyanoam.soundpilot.core.model.Rule

object TimeMath {
    const val MINUTES_PER_DAY = 24 * 60

    fun previousDay(day: Int): Int = if (day == 1) 7 else day - 1

    fun nextDay(day: Int): Int = if (day == 7) 1 else day + 1

    fun inWindow(day: Int, minuteOfDay: Int, window: Condition.TimeWindow): Boolean {
        val days = window.days.ifEmpty { Condition.ALL_DAYS }
        if (window.isAllDay) return day in days
        val length = window.lengthMinutes
        // Look back over the start days that could still have a window open now.
        for (back in 0..window.effectiveEndOffset) {
            val startDay = shift(day, -back)
            if (startDay !in days) continue
            val elapsed = back * MINUTES_PER_DAY + minuteOfDay - window.startMinute
            if (elapsed in 0 until length) return true
        }
        return false
    }

    fun shift(day: Int, by: Int): Int = (((day - 1 + by) % 7) + 7) % 7 + 1

    /**
     * Minutes from now until the next moment any enabled time window opens or closes, or null when no rule
     * depends on time. Used to schedule a wake-up so time rules fire even when nothing else happens.
     */
    fun minutesUntilNextBoundary(rules: List<Rule>, day: Int, minuteOfDay: Int): Int? {
        var best: Int? = null
        fun consider(delta: Int) {
            if (delta > 0 && (best == null || delta < best!!)) best = delta
        }
        rules.asSequence()
            .filter { it.enabled }
            .flatMap { it.conditions.asSequence() }
            .filterIsInstance<Condition.TimeWindow>()
            .forEach { window ->
                val days = window.days.ifEmpty { Condition.ALL_DAYS }
                for (ahead in 0..14) {
                    val d = shift(day, ahead)
                    val base = ahead * MINUTES_PER_DAY - minuteOfDay
                    if (window.isAllDay) {
                        if (d in days) consider(base)
                        if (shift(d, -1) in days) consider(base)
                        continue
                    }
                    if (d in days) consider(base + window.startMinute)
                    if (shift(d, -window.effectiveEndOffset) in days) consider(base + window.endMinute)
                }
            }
        return best
    }

    fun formatMinute(minuteOfDay: Int): String {
        val m = ((minuteOfDay % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        return "%02d:%02d".format(m / 60, m % 60)
    }

    /** Parses "HH:mm" (or "H:mm") into minutes since midnight. */
    fun parseMinute(text: String): Int? {
        val parts = text.trim().split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..24 || m !in 0..59 || (h == 24 && m != 0)) return null
        return (h * 60 + m) % MINUTES_PER_DAY
    }
}
