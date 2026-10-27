package com.tuvyanoam.soundpilot.ui.screens

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector
import com.tuvyanoam.soundpilot.R
import com.tuvyanoam.soundpilot.core.ai.ActionSpec
import com.tuvyanoam.soundpilot.core.ai.ConditionSpec
import com.tuvyanoam.soundpilot.core.ai.RuleSpec
import java.util.Locale

/** Ready-made starting points; each opens the editor pre-filled. */
data class RuleTemplate(@StringRes val title: Int, val icon: ImageVector, val build: (Context) -> RuleSpec)

private fun isIsraeliWeek(): Boolean = Locale.getDefault().language in setOf("iw", "he") || Locale.getDefault().country == "IL"

val ruleTemplates: List<RuleTemplate> = listOf(
    RuleTemplate(R.string.tpl_night, Icons.Filled.Bedtime) { context ->
        RuleSpec(
            name = context.getString(R.string.tpl_night),
            conditions = ConditionSpec(startTime = "22:30", endTime = "07:00"),
            actions = ActionSpec(ringerMode = "SILENT"),
        )
    },
    RuleTemplate(R.string.tpl_shabbat, Icons.Filled.Star) { context ->
        RuleSpec(
            name = context.getString(R.string.tpl_shabbat),
            conditions = ConditionSpec(days = listOf("FRI"), startTime = "16:00", endTime = "20:00", endDaysLater = 1),
            actions = ActionSpec(ringerMode = "SILENT", mediaVolume = 0),
        )
    },
    RuleTemplate(R.string.tpl_work, Icons.Filled.Work) { context ->
        val days = if (isIsraeliWeek()) listOf("SUN", "MON", "TUE", "WED", "THU") else listOf("MON", "TUE", "WED", "THU", "FRI")
        RuleSpec(
            name = context.getString(R.string.tpl_work),
            conditions = ConditionSpec(days = days, startTime = "09:00", endTime = "17:00"),
            actions = ActionSpec(ringerMode = "VIBRATE"),
        )
    },
    RuleTemplate(R.string.tpl_meetings, Icons.Filled.Event) { context ->
        RuleSpec(
            name = context.getString(R.string.tpl_meetings),
            conditions = ConditionSpec(inCalendarEvent = true),
            actions = ActionSpec(ringerMode = "VIBRATE"),
            priority = 5,
        )
    },
    RuleTemplate(R.string.tpl_car, Icons.Filled.DirectionsCar) { context ->
        RuleSpec(
            name = context.getString(R.string.tpl_car),
            conditions = ConditionSpec(bluetoothDevice = ""),
            actions = ActionSpec(ringerMode = "NORMAL", ringVolume = 100),
            priority = 3,
        )
    },
    RuleTemplate(R.string.tpl_headphones, Icons.Filled.Headphones) { context ->
        RuleSpec(
            name = context.getString(R.string.tpl_headphones),
            conditions = ConditionSpec(headphones = true),
            actions = ActionSpec(mediaVolume = 40),
        )
    },
    RuleTemplate(R.string.tpl_loud, Icons.AutoMirrored.Filled.VolumeUp) { context ->
        RuleSpec(
            name = context.getString(R.string.tpl_loud),
            conditions = ConditionSpec(noiseMinDb = 70.0),
            actions = ActionSpec(ringerMode = "NORMAL", ringVolume = 100),
        )
    },
    RuleTemplate(R.string.tpl_quiet, Icons.AutoMirrored.Filled.VolumeDown) { context ->
        RuleSpec(
            name = context.getString(R.string.tpl_quiet),
            conditions = ConditionSpec(noiseMaxDb = 35.0),
            actions = ActionSpec(ringVolume = 30),
        )
    },
)
