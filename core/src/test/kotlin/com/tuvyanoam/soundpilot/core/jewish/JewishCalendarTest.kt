package com.tuvyanoam.soundpilot.core.jewish

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JewishCalendarTest {
    private val jerusalem = JewishCalendarSettings()
    private val zone = ZoneId.of("Asia/Jerusalem")

    private fun millis(y: Int, m: Int, d: Int, h: Int = 0, min: Int = 0) =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    private fun local(millis: Long) = java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime()

    @Test
    fun `a regular shabbat runs from candle lighting to nightfall`() {
        // Shabbat Lech Lecha 5787: Friday 23 Oct 2026 - Saturday 24 Oct 2026 (still summer time).
        val periods = JewishCalendarService.periods(millis(2026, 10, 22), millis(2026, 10, 26), jerusalem)
        val shabbat = periods.single()
        assertTrue(shabbat.shabbat && !shabbat.yomTov)
        val start = local(shabbat.startMillis)
        val end = local(shabbat.endMillis)
        assertEquals(LocalDate.of(2026, 10, 23), start.toLocalDate())
        assertEquals(LocalDate.of(2026, 10, 24), end.toLocalDate())
        // Sunset in Jerusalem is about 17:58 (summer time): candles 40 minutes earlier, nightfall about half an hour after.
        assertTrue(start.hour == 17 && start.minute in 10..25, "start $start")
        assertTrue(end.hour == 18 && end.minute in 20..45, "end $end")
        assertTrue(shabbat.hebrewName.startsWith("שבת"), shabbat.hebrewName)
    }

    @Test
    fun `candle lighting follows the city custom`() {
        val telAviv = Cities.byId("tel_aviv")!!.toSettings(jerusalem)
        val jlm = JewishCalendarService.periods(millis(2026, 10, 22), millis(2026, 10, 26), jerusalem).single()
        val ta = JewishCalendarService.periods(millis(2026, 10, 22), millis(2026, 10, 26), telAviv).single()
        // 40 vs 20 minutes before a sunset that is a couple of minutes later on the coast.
        val diffMinutes = (ta.startMillis - jlm.startMillis) / 60_000
        assertTrue(diffMinutes in 19..26, "diff $diffMinutes")
    }

    @Test
    fun `rosh hashana on shabbat forms one continuous span`() {
        // Rosh Hashana 5787: Saturday 12 and Sunday 13 September 2026.
        val periods = JewishCalendarService.periods(millis(2026, 9, 10), millis(2026, 9, 15), jerusalem)
        assertEquals(2, periods.size, periods.toString())
        assertTrue(periods[0].shabbat && periods[0].yomTov)
        assertTrue(!periods[1].shabbat && periods[1].yomTov)
        assertEquals(periods[0].endMillis, periods[1].startMillis)
        assertEquals(LocalDate.of(2026, 9, 11), local(periods[0].startMillis).toLocalDate())
        assertEquals(LocalDate.of(2026, 9, 13), local(periods[1].endMillis).toLocalDate())
        val span = JewishCalendarService.spans(periods).single()
        assertEquals(periods[0].startMillis, span.startMillis)
        assertEquals(periods[1].endMillis, span.endMillis)
        assertEquals("ראש השנה · שבת", span.hebrewName)
    }

    @Test
    fun `shavuot is one day in israel and two abroad`() {
        // Shavuot 5786: Friday 22 May 2026, followed by Shabbat.
        val israel = JewishCalendarService.periods(millis(2026, 5, 20), millis(2026, 5, 25), jerusalem)
        val abroad = JewishCalendarService.periods(millis(2026, 5, 20), millis(2026, 5, 25), jerusalem.copy(inIsrael = false))
        assertEquals(listOf(false, true), israel.map { it.shabbat })
        assertTrue(israel[0].yomTov && !israel[1].yomTov)
        // Abroad the second day of Shavuot coincides with Shabbat.
        assertTrue(abroad[1].yomTov && abroad[1].shabbat)
    }

    @Test
    fun `havdalah can be a fixed time after sunset`() {
        val fixed = jerusalem.copy(havdalahMode = HavdalahMode.MINUTES_AFTER_SUNSET, havdalahMinutes = 72)
        val normal = JewishCalendarService.periods(millis(2026, 10, 22), millis(2026, 10, 26), jerusalem).single()
        val tam = JewishCalendarService.periods(millis(2026, 10, 22), millis(2026, 10, 26), fixed).single()
        assertTrue(tam.endMillis - normal.endMillis > 30 * 60_000)
    }

    @Test
    fun `overview and hebrew date`() {
        val now = millis(2026, 10, 23, 19, 0)
        val overview = JewishCalendarService.overview(now, jerusalem)
        assertNotNull(overview.current)
        assertTrue(overview.upcoming.isNotEmpty())
        // After nightfall on Friday the Hebrew date is already Shabbat's (13 Cheshvan 5787).
        assertTrue(overview.englishHebrewDate.contains("13"), overview.englishHebrewDate)
        val midweek = JewishCalendarService.overview(millis(2026, 10, 20, 12, 0), jerusalem)
        assertNull(midweek.current)
    }
}
