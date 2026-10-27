package com.tuvyanoam.soundpilot.core.jewish

import com.kosherjava.zmanim.ZmanimCalendar
import com.kosherjava.zmanim.hebrewcalendar.HebrewDateFormatter
import com.kosherjava.zmanim.hebrewcalendar.JewishCalendar
import com.kosherjava.zmanim.hebrewcalendar.JewishDate
import com.kosherjava.zmanim.util.GeoLocation
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.TimeZone

@Serializable
enum class HavdalahMode {
    /** Nightfall when the sun is 8.5° below the horizon (three small stars), the common Israeli practice. */
    NIGHTFALL,

    /** A fixed number of minutes after sunset, e.g. 72 for Rabbeinu Tam. */
    MINUTES_AFTER_SUNSET,
}

/** Where and by which customs Shabbat and holiday times are computed. */
@Serializable
data class JewishCalendarSettings(
    val cityId: String = "jerusalem",
    val locationName: String = "ירושלים",
    val latitude: Double = 31.7683,
    val longitude: Double = 35.2137,
    val timeZone: String = "Asia/Jerusalem",
    /** Israel keeps one day of Yom Tov where the diaspora keeps two. */
    val inIsrael: Boolean = true,
    val candleLightingMinutes: Int = 40,
    val havdalahMode: HavdalahMode = HavdalahMode.NIGHTFALL,
    val havdalahMinutes: Int = 40,
)

data class City(
    val id: String,
    val hebrewName: String,
    val englishName: String,
    val latitude: Double,
    val longitude: Double,
    /** Minutes before sunset by local custom. Jerusalem 40, Haifa and Petah Tikva 30, most places 20. */
    val candleLightingMinutes: Int,
) {
    fun toSettings(current: JewishCalendarSettings) = current.copy(
        cityId = id,
        locationName = hebrewName,
        latitude = latitude,
        longitude = longitude,
        timeZone = "Asia/Jerusalem",
        inIsrael = true,
        candleLightingMinutes = candleLightingMinutes,
    )
}

object Cities {
    const val CUSTOM = "custom"

    val ISRAEL = listOf(
        City("jerusalem", "ירושלים", "Jerusalem", 31.7683, 35.2137, 40),
        City("tel_aviv", "תל אביב", "Tel Aviv", 32.0853, 34.7818, 20),
        City("haifa", "חיפה", "Haifa", 32.7940, 34.9896, 30),
        City("beer_sheva", "באר שבע", "Beer Sheva", 31.2518, 34.7913, 20),
        City("bnei_brak", "בני ברק", "Bnei Brak", 32.0807, 34.8338, 20),
        City("petah_tikva", "פתח תקווה", "Petah Tikva", 32.0840, 34.8878, 30),
        City("rishon", "ראשון לציון", "Rishon LeZion", 31.9730, 34.7925, 20),
        City("netanya", "נתניה", "Netanya", 32.3215, 34.8532, 20),
        City("ashdod", "אשדוד", "Ashdod", 31.8044, 34.6553, 20),
        City("modiin", "מודיעין", "Modiin", 31.8969, 35.0095, 20),
        City("beit_shemesh", "בית שמש", "Beit Shemesh", 31.7470, 34.9881, 20),
        City("tzfat", "צפת", "Safed", 32.9646, 35.4960, 20),
        City("tiberias", "טבריה", "Tiberias", 32.7922, 35.5312, 20),
        City("eilat", "אילת", "Eilat", 29.5577, 34.9519, 20),
    )

    fun byId(id: String): City? = ISRAEL.firstOrNull { it.id == id }

    /** Rough test for coordinates inside Israel, used when the location comes from the phone. */
    fun looksLikeIsrael(latitude: Double, longitude: Double): Boolean =
        latitude in 29.4..33.4 && longitude in 34.2..35.95
}

/** One day on which work is forbidden (Shabbat or Yom Tov), from its start in the evening to nightfall. */
@Serializable
data class HolyPeriod(
    val startMillis: Long,
    val endMillis: Long,
    val shabbat: Boolean,
    val yomTov: Boolean,
    val hebrewName: String,
    val englishName: String,
) {
    fun contains(millis: Long) = millis in startMillis until endMillis
}

/** Consecutive holy days shown as one span, e.g. Rosh Hashana followed by Shabbat. */
data class HolySpan(val startMillis: Long, val endMillis: Long, val hebrewName: String, val englishName: String)

data class CalendarOverview(
    val hebrewDate: String,
    val englishHebrewDate: String,
    val current: HolySpan?,
    val upcoming: List<HolySpan>,
)

/**
 * Hebrew calendar and Shabbat/holiday times, computed with KosherJava. Each holy day starts at candle lighting
 * (sunset minus the local custom) or, when it follows another holy day, where that one ended; it ends at nightfall.
 */
object JewishCalendarService {
    private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

    @Volatile
    private var cache: Triple<JewishCalendarSettings, Pair<Long, Long>, List<HolyPeriod>>? = null

    fun zoneId(settings: JewishCalendarSettings): ZoneId =
        runCatching { ZoneId.of(settings.timeZone) }.getOrDefault(ZoneId.systemDefault())

    /** Holy periods overlapping [fromMillis, toMillis). */
    fun periods(fromMillis: Long, toMillis: Long, settings: JewishCalendarSettings): List<HolyPeriod> {
        cache?.let { (s, range, list) -> if (s == settings && range == fromMillis to toMillis) return list }
        val zone = zoneId(settings)
        val firstDay = Instant.ofEpochMilli(fromMillis).atZone(zone).toLocalDate()
        val lastDay = Instant.ofEpochMilli(toMillis).atZone(zone).toLocalDate().plusDays(1)

        val result = mutableListOf<HolyPeriod>()
        var day = firstDay.minusDays(1)
        var previousEnd: Long? = if (isHoly(day.minusDays(1), settings)) havdalah(day.minusDays(1), settings) else null
        while (!day.isAfter(lastDay)) {
            val calendar = jewishCalendar(day, settings)
            val shabbat = day.dayOfWeek == DayOfWeek.SATURDAY
            val yomTov = calendar.isYomTovAssurBemelacha()
            if (shabbat || yomTov) {
                val start = previousEnd ?: candleLighting(day.minusDays(1), settings)
                val end = havdalah(day, settings)
                if (start != null && end != null && end > start) {
                    val (hebrew, english) = names(calendar, shabbat, yomTov)
                    result += HolyPeriod(start, end, shabbat, yomTov, hebrew, english)
                }
                previousEnd = end
            } else {
                previousEnd = null
            }
            day = day.plusDays(1)
        }
        val list = result.filter { it.endMillis > fromMillis && it.startMillis < toMillis }
        cache = Triple(settings, fromMillis to toMillis, list)
        return list
    }

    fun isHoly(day: LocalDate, settings: JewishCalendarSettings): Boolean =
        day.dayOfWeek == DayOfWeek.SATURDAY || jewishCalendar(day, settings).isYomTovAssurBemelacha()

    /** Joins periods that follow each other without a gap, e.g. two days of Yom Tov followed by Shabbat. */
    fun spans(periods: List<HolyPeriod>): List<HolySpan> {
        val groups = mutableListOf<MutableList<HolyPeriod>>()
        for (p in periods.sortedBy { it.startMillis }) {
            val last = groups.lastOrNull()
            if (last != null && p.startMillis <= last.last().endMillis) last += p else groups += mutableListOf(p)
        }
        return groups.map { group ->
            HolySpan(
                startMillis = group.first().startMillis,
                endMillis = group.maxOf { it.endMillis },
                hebrewName = spanName(group, hebrew = true),
                englishName = spanName(group, hebrew = false),
            )
        }
    }

    private fun spanName(group: List<HolyPeriod>, hebrew: Boolean): String {
        val shabbatWord = if (hebrew) "שבת" else "Shabbat"
        val name = { p: HolyPeriod -> if (hebrew) p.hebrewName else p.englishName }
        val holidays = group.filter { it.yomTov }.map { name(it).removePrefix("$shabbatWord · ") }.distinct()
        if (holidays.isEmpty()) return name(group.first())
        return holidays.joinToString(" + ") + if (group.any { it.shabbat }) " · $shabbatWord" else ""
    }

    fun overview(now: Long, settings: JewishCalendarSettings, days: Int = 21): CalendarOverview {
        val spans = spans(periods(now - 2 * DAY_MILLIS, now + days * DAY_MILLIS, settings))
        return CalendarOverview(
            hebrewDate = hebrewDate(now, settings, hebrew = true),
            englishHebrewDate = hebrewDate(now, settings, hebrew = false),
            current = spans.firstOrNull { now >= it.startMillis && now < it.endMillis },
            upcoming = spans.filter { it.startMillis > now },
        )
    }

    /** The Hebrew date, which changes at nightfall rather than at midnight. */
    fun hebrewDate(now: Long, settings: JewishCalendarSettings, hebrew: Boolean): String {
        val zone = zoneId(settings)
        var day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val nightfall = zmanim(day, settings).tzais?.time
        if (nightfall != null && now >= nightfall) day = day.plusDays(1)
        val formatter = HebrewDateFormatter().apply { setHebrewFormat(hebrew) }
        return formatter.format(JewishDate(day))
    }

    fun candleLighting(day: LocalDate, settings: JewishCalendarSettings): Long? = zmanim(day, settings).candleLighting?.time

    fun havdalah(day: LocalDate, settings: JewishCalendarSettings): Long? {
        val zmanim = zmanim(day, settings)
        return when (settings.havdalahMode) {
            HavdalahMode.NIGHTFALL -> zmanim.tzais?.time
            HavdalahMode.MINUTES_AFTER_SUNSET -> zmanim.sunset?.time?.plus(settings.havdalahMinutes * 60_000L)
        }
    }

    private fun jewishCalendar(day: LocalDate, settings: JewishCalendarSettings): JewishCalendar =
        JewishCalendar(day).apply { setInIsrael(settings.inIsrael) }

    private fun zmanim(day: LocalDate, settings: JewishCalendarSettings): ZmanimCalendar {
        val timeZone = TimeZone.getTimeZone(zoneId(settings))
        val calendar = ZmanimCalendar(GeoLocation(settings.locationName, settings.latitude, settings.longitude, timeZone))
        calendar.setCandleLightingOffset(settings.candleLightingMinutes.toDouble())
        val date = Calendar.getInstance(timeZone).apply {
            clear()
            set(day.year, day.monthValue - 1, day.dayOfMonth, 12, 0, 0)
        }
        calendar.setCalendar(date)
        return calendar
    }

    private fun names(calendar: JewishCalendar, shabbat: Boolean, yomTov: Boolean): Pair<String, String> {
        val hebrew = HebrewDateFormatter().apply { setHebrewFormat(true) }
        val english = HebrewDateFormatter()
        return if (yomTov) {
            val he = hebrew.formatYomTov(calendar)
            val en = english.formatYomTov(calendar)
            if (shabbat) "שבת · $he" to "Shabbat · $en" else he to en
        } else {
            val he = hebrew.formatParsha(calendar)
            val en = english.formatParsha(calendar)
            (if (he.isBlank()) "שבת" else "שבת $he") to (if (en.isBlank()) "Shabbat" else "Shabbat $en")
        }
    }
}
