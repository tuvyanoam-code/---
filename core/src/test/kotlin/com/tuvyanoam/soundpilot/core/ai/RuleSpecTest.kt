package com.tuvyanoam.soundpilot.core.ai

import com.tuvyanoam.soundpilot.core.model.Action
import com.tuvyanoam.soundpilot.core.model.AudioStream
import com.tuvyanoam.soundpilot.core.model.Condition
import com.tuvyanoam.soundpilot.core.model.DndMode
import com.tuvyanoam.soundpilot.core.model.RingerMode
import com.tuvyanoam.soundpilot.core.model.RuleOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuleSpecTest {
    @Test
    fun `converts a shabbat rule`() {
        val spec = RuleSpec(
            name = "שבת",
            conditions = ConditionSpec(days = listOf("FRI", "sat"), startTime = "16:00", endTime = "20:00"),
            actions = ActionSpec(ringerMode = "silent", doNotDisturb = "PRIORITY", mediaVolume = 0),
        )
        val rule = RuleSpecs.toRule(spec, "r1", RuleOrigin.AI, emptySet()).getOrThrow()
        assertEquals(listOf<Condition>(Condition.TimeWindow(setOf(5, 6), 16 * 60, 20 * 60)), rule.conditions)
        assertEquals(
            listOf(Action.SetRinger(RingerMode.SILENT), Action.SetVolume(AudioStream.MEDIA, 0), Action.SetDnd(DndMode.PRIORITY)),
            rule.actions,
        )
        assertEquals(spec.copy(conditions = spec.conditions.copy(days = listOf("FRI", "SAT")), actions = spec.actions.copy(ringerMode = "SILENT")), RuleSpecs.fromRule(rule))
    }

    @Test
    fun `end_days_later round trips`() {
        val spec = RuleSpec(
            name = "Shabbat",
            conditions = ConditionSpec(days = listOf("FRI"), startTime = "16:00", endTime = "20:00", endDaysLater = 1),
            actions = ActionSpec(ringerMode = "SILENT"),
        )
        val rule = RuleSpecs.toRule(spec, "r", RuleOrigin.AI, emptySet()).getOrThrow()
        assertEquals(listOf<Condition>(Condition.TimeWindow(setOf(5), 960, 1200, 1)), rule.conditions)
        assertEquals(spec, RuleSpecs.fromRule(rule))
    }

    @Test
    fun `reports every problem at once`() {
        val spec = RuleSpec(
            name = "bad",
            conditions = ConditionSpec(placeId = "nope", startTime = "9am", endTime = "10:00"),
            actions = ActionSpec(ringerMode = "LOUD", ringVolume = 150),
        )
        val error = RuleSpecs.toRule(spec, "r", RuleOrigin.AI, setOf("home")).exceptionOrNull()!!.message!!
        assertTrue("unknown place_id" in error)
        assertTrue("HH:mm" in error)
        assertTrue("ringer_mode" in error)
        assertTrue("ring_volume" in error)
    }

    @Test
    fun `requires a condition and a sound action`() {
        val noCondition = RuleSpec(name = "x", actions = ActionSpec(ringerMode = "VIBRATE"))
        assertTrue(RuleSpecs.toRule(noCondition, "r", RuleOrigin.AI, emptySet()).isFailure)
        val onlyNotify = RuleSpec(name = "x", conditions = ConditionSpec(charging = true), actions = ActionSpec(notify = "hi"))
        assertTrue(RuleSpecs.toRule(onlyNotify, "r", RuleOrigin.AI, emptySet()).isFailure)
    }

    @Test
    fun `schema lists required top level fields`() {
        val schema = RuleSpecs.schema.toString()
        assertTrue("\"required\":[\"name\",\"conditions\",\"actions\"]" in schema)
    }
}
