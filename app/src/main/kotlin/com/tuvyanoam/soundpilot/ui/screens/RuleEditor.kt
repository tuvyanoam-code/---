@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.tuvyanoam.soundpilot.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tuvyanoam.soundpilot.R
import com.tuvyanoam.soundpilot.core.ai.ConditionSpec
import com.tuvyanoam.soundpilot.core.ai.RuleSpecs
import com.tuvyanoam.soundpilot.core.engine.TimeMath
import com.tuvyanoam.soundpilot.core.model.AudioStream
import com.tuvyanoam.soundpilot.core.model.DndMode
import com.tuvyanoam.soundpilot.core.model.RingerMode
import com.tuvyanoam.soundpilot.platform.Labels
import com.tuvyanoam.soundpilot.ui.components.AppTopBar
import com.tuvyanoam.soundpilot.ui.components.ConfirmDialog
import com.tuvyanoam.soundpilot.ui.components.Dropdown
import com.tuvyanoam.soundpilot.ui.components.SectionCard
import com.tuvyanoam.soundpilot.ui.components.Segmented
import com.tuvyanoam.soundpilot.ui.components.SwitchRow
import com.tuvyanoam.soundpilot.ui.components.TimeButton

@Composable
fun RuleEditor(vm: RulesViewModel, form: RuleForm) {
    val context = LocalContext.current
    val places by vm.places.collectAsStateWithLifecycle()
    val report by vm.report.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    val spec = form.spec
    val c = spec.conditions
    val a = spec.actions

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.rule_delete_title),
            text = stringResource(R.string.rule_delete_text, spec.name),
            confirm = stringResource(R.string.delete),
            onConfirm = vm::delete,
            onDismiss = { confirmDelete = false },
        )
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(if (form.isNew) R.string.rule_new else R.string.rule_edit),
                onBack = vm::close,
                actions = {
                    if (!form.isNew) {
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
                    }
                    TextButton(onClick = vm::save) { Text(stringResource(R.string.save)) }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            form.explanation?.let { explanation ->
                item {
                    SectionCard(stringResource(R.string.rule_from_claude), Icons.Filled.AutoAwesome) { Text(explanation) }
                }
            }
            vm.formError?.let { error ->
                item { Text(error, color = MaterialTheme.colorScheme.error) }
            }
            item {
                OutlinedTextField(
                    value = spec.name,
                    onValueChange = { name -> vm.updateSpec { it.copy(name = name) } },
                    label = { Text(stringResource(R.string.rule_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                SwitchRow(stringResource(R.string.rule_enabled), form.enabled, { enabled -> vm.update { it.copy(enabled = enabled) } })
            }

            // ---- When ----
            item {
                SectionCard(stringResource(R.string.rule_section_when), Icons.Filled.Schedule) {
                    Text(stringResource(R.string.rule_when_hint), style = MaterialTheme.typography.bodySmall)

                    SwitchRow(stringResource(R.string.cond_title_place), c.placeId != null, { on ->
                        vm.updateConditions { it.copy(placeId = if (on) places.firstOrNull()?.id ?: "" else null) }
                    })
                    if (c.placeId != null) {
                        if (places.isEmpty()) {
                            Text(stringResource(R.string.rule_no_places), color = MaterialTheme.colorScheme.error)
                        } else {
                            Dropdown(
                                options = places,
                                selected = places.firstOrNull { it.id == c.placeId } ?: places.first(),
                                label = { it.name },
                                onSelect = { place -> vm.updateConditions { it.copy(placeId = place.id) } },
                            )
                        }
                    }

                    SwitchRow(stringResource(R.string.cond_title_time), c.startTime != null, { on ->
                        vm.updateConditions {
                            if (on) it.copy(startTime = "22:00", endTime = "07:00") else it.copy(startTime = null, endTime = null, days = null, endDaysLater = null)
                        }
                    })
                    if (c.startTime != null) TimeEditor(c) { transform -> vm.updateConditions(transform) }

                    SwitchRow(stringResource(R.string.cond_title_holy), c.holyTime != null, { on ->
                        vm.updateConditions {
                            if (on) it.copy(holyTime = "SHABBAT_AND_YOM_TOV")
                            else it.copy(holyTime = null, holyMinutesBefore = null, holyMinutesAfter = null)
                        }
                    })
                    if (c.holyTime != null) HolyEditor(c) { transform -> vm.updateConditions(transform) }

                    SwitchRow(stringResource(R.string.cond_title_calendar), c.inCalendarEvent == true || c.calendarKeyword != null, { on ->
                        vm.updateConditions { it.copy(inCalendarEvent = if (on) true else null, calendarKeyword = null) }
                    })
                    if (c.inCalendarEvent == true || c.calendarKeyword != null) {
                        OutlinedTextField(
                            value = c.calendarKeyword.orEmpty(),
                            onValueChange = { text -> vm.updateConditions { it.copy(calendarKeyword = text.ifEmpty { null }) } },
                            label = { Text(stringResource(R.string.cond_calendar_keyword_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }

                    SwitchRow(stringResource(R.string.cond_title_wifi), c.wifiSsid != null, { on ->
                        vm.updateConditions { it.copy(wifiSsid = if (on) report?.context?.wifiSsid.orEmpty() else null) }
                    })
                    if (c.wifiSsid != null) {
                        OutlinedTextField(
                            value = c.wifiSsid.orEmpty(),
                            onValueChange = { text -> vm.updateConditions { it.copy(wifiSsid = text) } },
                            label = { Text(stringResource(R.string.cond_wifi_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }

                    SwitchRow(stringResource(R.string.cond_title_bluetooth), c.bluetoothDevice != null, { on ->
                        vm.updateConditions { it.copy(bluetoothDevice = if (on) report?.context?.bluetoothDevices?.firstOrNull().orEmpty() else null) }
                    })
                    if (c.bluetoothDevice != null) {
                        OutlinedTextField(
                            value = c.bluetoothDevice.orEmpty(),
                            onValueChange = { text -> vm.updateConditions { it.copy(bluetoothDevice = text) } },
                            label = { Text(stringResource(R.string.cond_bluetooth_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        val connected = report?.context?.bluetoothDevices.orEmpty()
                        if (connected.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                connected.forEach { name ->
                                    AssistChip(onClick = { vm.updateConditions { it.copy(bluetoothDevice = name) } }, label = { Text(name) })
                                }
                            }
                        }
                    }

                    SwitchRow(stringResource(R.string.cond_title_charging), c.charging != null, { on ->
                        vm.updateConditions { it.copy(charging = if (on) true else null) }
                    })
                    if (c.charging != null) {
                        Segmented(
                            labels = listOf(stringResource(R.string.cond_charging), stringResource(R.string.cond_on_battery)),
                            selected = if (c.charging == true) 0 else 1,
                            onSelect = { index -> vm.updateConditions { it.copy(charging = index == 0) } },
                        )
                    }

                    SwitchRow(stringResource(R.string.cond_title_headphones), c.headphones != null, { on ->
                        vm.updateConditions { it.copy(headphones = if (on) true else null) }
                    })
                    if (c.headphones != null) {
                        Segmented(
                            labels = listOf(stringResource(R.string.cond_headphones), stringResource(R.string.cond_no_headphones)),
                            selected = if (c.headphones == true) 0 else 1,
                            onSelect = { index -> vm.updateConditions { it.copy(headphones = index == 0) } },
                        )
                    }

                    val hasNoise = c.noiseMinDb != null || c.noiseMaxDb != null
                    SwitchRow(stringResource(R.string.cond_title_noise), hasNoise, { on ->
                        vm.updateConditions { if (on) it.copy(noiseMinDb = 65.0, noiseMaxDb = null) else it.copy(noiseMinDb = null, noiseMaxDb = null) }
                    })
                    if (hasNoise) NoiseEditor(c) { transform -> vm.updateConditions(transform) }
                }
            }

            // ---- Then ----
            item {
                SectionCard(stringResource(R.string.rule_section_then), Icons.Filled.PlayArrow) {
                    Text(stringResource(R.string.act_ringer_title), style = MaterialTheme.typography.labelLarge)
                    val modes = listOf(null, RingerMode.NORMAL, RingerMode.VIBRATE, RingerMode.SILENT)
                    Segmented(
                        labels = modes.map { mode -> mode?.let { Labels.mode(context, it) } ?: stringResource(R.string.no_change) },
                        selected = modes.indexOfFirst { it?.name == a.ringerMode?.uppercase() }.coerceAtLeast(0),
                        onSelect = { index -> vm.updateActions { it.copy(ringerMode = modes[index]?.name) } },
                    )

                    AudioStream.entries.forEach { stream ->
                        val value = when (stream) {
                            AudioStream.RING -> a.ringVolume
                            AudioStream.NOTIFICATION -> a.notificationVolume
                            AudioStream.MEDIA -> a.mediaVolume
                            AudioStream.ALARM -> a.alarmVolume
                        }
                        val set: (Int?) -> Unit = { percent ->
                            vm.updateActions {
                                when (stream) {
                                    AudioStream.RING -> it.copy(ringVolume = percent)
                                    AudioStream.NOTIFICATION -> it.copy(notificationVolume = percent)
                                    AudioStream.MEDIA -> it.copy(mediaVolume = percent)
                                    AudioStream.ALARM -> it.copy(alarmVolume = percent)
                                }
                            }
                        }
                        SwitchRow(
                            title = stringResource(R.string.act_volume_title, Labels.stream(context, stream)),
                            checked = value != null,
                            onChange = { on -> set(if (on) 70 else null) },
                            subtitle = value?.let { "$it%" },
                        )
                        if (value != null) {
                            Slider(value = value / 100f, onValueChange = { set((it * 100).toInt()) }, steps = 19)
                        }
                    }

                    Text(stringResource(R.string.act_dnd_title), style = MaterialTheme.typography.labelLarge)
                    val dndOptions = listOf<DndMode?>(null) + DndMode.entries
                    Dropdown(
                        options = dndOptions,
                        selected = dndOptions.firstOrNull { it?.name == a.doNotDisturb?.uppercase() },
                        label = { mode -> mode?.let { Labels.dnd(context, it) } ?: context.getString(R.string.no_change) },
                        onSelect = { mode -> vm.updateActions { it.copy(doNotDisturb = mode?.name) } },
                    )

                    Text(stringResource(R.string.act_adaptive_title), style = MaterialTheme.typography.labelLarge)
                    Segmented(
                        labels = listOf(stringResource(R.string.no_change), stringResource(R.string.on), stringResource(R.string.off)),
                        selected = when (a.adaptiveVolume) {
                            null -> 0
                            true -> 1
                            false -> 2
                        },
                        onSelect = { index -> vm.updateActions { it.copy(adaptiveVolume = listOf(null, true, false)[index]) } },
                    )

                    OutlinedTextField(
                        value = a.notify.orEmpty(),
                        onValueChange = { text -> vm.updateActions { it.copy(notify = text.ifEmpty { null }) } },
                        label = { Text(stringResource(R.string.act_notify_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // ---- Options ----
            item {
                SectionCard(stringResource(R.string.rule_section_options)) {
                    SwitchRow(
                        title = stringResource(R.string.rule_restore),
                        subtitle = stringResource(R.string.rule_restore_desc),
                        checked = spec.restoreOnExit,
                        onChange = { on -> vm.updateSpec { it.copy(restoreOnExit = on) } },
                    )
                    Text(stringResource(R.string.rule_priority, spec.priority), style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = spec.priority.toFloat(),
                        onValueChange = { value -> vm.updateSpec { it.copy(priority = value.toInt()) } },
                        valueRange = -10f..10f,
                        steps = 19,
                    )
                    Text(stringResource(R.string.rule_priority_desc), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun TimeEditor(c: ConditionSpec, update: ((ConditionSpec) -> ConditionSpec) -> Unit) {
    val context = LocalContext.current
    val selectedDays = c.days?.map { RuleSpecs.DAY_CODES.indexOf(it.uppercase()) + 1 }?.toSet() ?: (1..7).toSet()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Labels.orderedDays().forEach { day ->
            FilterChip(
                selected = day in selectedDays,
                onClick = {
                    val next = if (day in selectedDays) selectedDays - day else selectedDays + day
                    update { it.copy(days = if (next.size == 7 || next.isEmpty()) null else next.sorted().map(RuleSpecs::dayCode)) }
                },
                label = { Text(Labels.dayShort(day)) },
            )
        }
    }
    val start = c.startTime?.let(TimeMath::parseMinute) ?: 0
    val end = c.endTime?.let(TimeMath::parseMinute) ?: 0
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.time_from))
        Spacer(Modifier.width(8.dp))
        TimeButton(start, { minute -> update { it.copy(startTime = TimeMath.formatMinute(minute)) } })
        Spacer(Modifier.width(16.dp))
        Text(stringResource(R.string.time_to))
        Spacer(Modifier.width(8.dp))
        TimeButton(end, { minute -> update { it.copy(endTime = TimeMath.formatMinute(minute)) } })
    }
    val offsets = listOf(0, 1, 2, 3)
    Dropdown(
        options = offsets,
        selected = c.endDaysLater ?: 0,
        label = { offset ->
            when {
                offset == 1 -> context.getString(R.string.ends_next_day)
                offset > 1 -> context.getString(R.string.ends_days_later, offset)
                end < start -> context.getString(R.string.ends_next_morning)
                else -> context.getString(R.string.ends_same_day)
            }
        },
        onSelect = { offset -> update { it.copy(endDaysLater = offset.takeIf { o -> o > 0 }) } },
    )
    if (start == end && (c.endDaysLater ?: 0) == 0) Text(stringResource(R.string.time_all_day_note), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun HolyEditor(c: ConditionSpec, update: ((ConditionSpec) -> ConditionSpec) -> Unit) {
    val kinds = RuleSpecs.HOLY_KINDS.keys.toList()
    Segmented(
        labels = listOf(
            stringResource(R.string.holy_kind_both),
            stringResource(R.string.holy_kind_shabbat),
            stringResource(R.string.holy_kind_yomtov),
        ),
        selected = kinds.indexOf(c.holyTime?.uppercase()).coerceAtLeast(0),
        onSelect = { index -> update { it.copy(holyTime = kinds[index]) } },
    )
    val before = c.holyMinutesBefore ?: 0
    val after = c.holyMinutesAfter ?: 0
    Text(stringResource(R.string.cond_holy_before, before), style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = before.toFloat(),
        onValueChange = { v -> update { it.copy(holyMinutesBefore = (v.toInt() / 5 * 5).takeIf { m -> m > 0 }) } },
        valueRange = 0f..120f,
    )
    Text(stringResource(R.string.cond_holy_after, after), style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = after.toFloat(),
        onValueChange = { v -> update { it.copy(holyMinutesAfter = (v.toInt() / 5 * 5).takeIf { m -> m > 0 }) } },
        valueRange = 0f..120f,
    )
    Text(stringResource(R.string.cond_holy_hint), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun NoiseEditor(c: ConditionSpec, update: ((ConditionSpec) -> ConditionSpec) -> Unit) {
    val min = (c.noiseMinDb ?: 20.0).toFloat()
    val max = (c.noiseMaxDb ?: 100.0).toFloat()
    Text(stringResource(R.string.cond_noise_range, min.toInt(), max.toInt()), style = MaterialTheme.typography.bodyMedium)
    RangeSlider(
        value = min..max,
        onValueChange = { range ->
            update {
                it.copy(
                    noiseMinDb = range.start.toDouble().takeIf { v -> v > 20.5 },
                    noiseMaxDb = range.endInclusive.toDouble().takeIf { v -> v < 99.5 },
                ).let { spec -> if (spec.noiseMinDb == null && spec.noiseMaxDb == null) spec.copy(noiseMinDb = 21.0) else spec }
            }
        },
        valueRange = 20f..100f,
        steps = 15,
    )
    Text(stringResource(R.string.cond_noise_scale), style = MaterialTheme.typography.bodySmall)
}
