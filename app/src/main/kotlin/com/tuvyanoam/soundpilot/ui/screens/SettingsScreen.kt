package com.tuvyanoam.soundpilot.ui.screens

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tuvyanoam.soundpilot.BuildConfig
import com.tuvyanoam.soundpilot.R
import com.tuvyanoam.soundpilot.container
import com.tuvyanoam.soundpilot.core.ai.AiFailure
import com.tuvyanoam.soundpilot.core.jewish.Cities
import com.tuvyanoam.soundpilot.core.jewish.HavdalahMode
import com.tuvyanoam.soundpilot.core.jewish.JewishCalendarService
import com.tuvyanoam.soundpilot.core.jewish.JewishCalendarSettings
import com.tuvyanoam.soundpilot.core.model.AiSettings
import com.tuvyanoam.soundpilot.core.model.AppSettings
import com.tuvyanoam.soundpilot.platform.Capability
import com.tuvyanoam.soundpilot.platform.Labels
import com.tuvyanoam.soundpilot.platform.Permissions
import com.tuvyanoam.soundpilot.service.AutomationService
import com.tuvyanoam.soundpilot.ui.components.AppTopBar
import com.tuvyanoam.soundpilot.ui.components.CapabilityRow
import com.tuvyanoam.soundpilot.ui.components.CommitRangeSlider
import com.tuvyanoam.soundpilot.ui.components.CommitSlider
import com.tuvyanoam.soundpilot.ui.components.ConfirmDialog
import com.tuvyanoam.soundpilot.ui.components.Dropdown
import com.tuvyanoam.soundpilot.ui.components.SectionCard
import com.tuvyanoam.soundpilot.ui.components.Segmented
import com.tuvyanoam.soundpilot.ui.components.SwitchRow
import com.tuvyanoam.soundpilot.ui.components.rememberCapabilityRequester
import com.tuvyanoam.soundpilot.ui.components.rememberMissingCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    val settings = c.storage.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val noise = c.signals.noise

    var keyInput by mutableStateOf("")
    var keySaved by mutableStateOf(c.keyStore.get() != null)
        private set
    var verifying by mutableStateOf(false)
        private set
    var keyMessage by mutableStateOf<String?>(null)
        private set
    var calibrating by mutableStateOf(false)
        private set
    var locating by mutableStateOf(false)
        private set
    var calendarMessage by mutableStateOf<String?>(null)
        private set

    /** The next Shabbat or holiday under the current settings, so the effect of a change is visible right away. */
    val calendarPreview = c.storage.settings.settings
        .map { s -> runCatching { JewishCalendarService.overview(c.clock.now(), s.jewish, days = 9) }.getOrNull() }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun updateJewish(transform: (JewishCalendarSettings) -> JewishCalendarSettings) = update { it.copy(jewish = transform(it.jewish)) }

    fun setCity(id: String) {
        val city = Cities.byId(id) ?: return
        updateJewish { city.toSettings(it) }
    }

    fun useLocationForCalendar() {
        locating = true
        calendarMessage = null
        viewModelScope.launch {
            val here = c.location.currentLocation()
            locating = false
            val app = getApplication<Application>()
            if (here == null) {
                calendarMessage = app.getString(R.string.place_no_location)
                return@launch
            }
            updateJewish {
                it.copy(
                    cityId = Cities.CUSTOM,
                    locationName = app.getString(R.string.jewish_my_location),
                    latitude = here.latitude,
                    longitude = here.longitude,
                    timeZone = ZoneId.systemDefault().id,
                    inIsrael = Cities.looksLikeIsrael(here.latitude, here.longitude),
                )
            }
        }
    }

    fun saveKey() {
        val key = keyInput.trim()
        if (key.isEmpty()) return
        verifying = true
        keyMessage = null
        viewModelScope.launch {
            val model = c.storage.settings.get().ai.model
            val failure = c.gateway.verify(key, model)
            verifying = false
            val app = getApplication<Application>()
            when (failure) {
                null -> {
                    c.keyStore.set(key)
                    keyInput = ""
                    keySaved = true
                    keyMessage = app.getString(R.string.key_verified)
                }
                // Could not reach the API: keep the key, it may well be fine.
                AiFailure.NETWORK, AiFailure.OVERLOADED, AiFailure.RATE_LIMITED -> {
                    c.keyStore.set(key)
                    keyInput = ""
                    keySaved = true
                    keyMessage = app.getString(R.string.key_saved_unverified)
                }
                else -> keyMessage = describeFailure(app, failure)
            }
        }
    }

    fun removeKey() {
        c.keyStore.set(null)
        keySaved = false
        keyMessage = null
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            c.storage.settings.update(transform)
            c.coordinator.evaluate("settings_changed")
        }
    }

    fun setAutomation(enabled: Boolean) {
        update { it.copy(automationEnabled = enabled) }
        if (enabled) AutomationService.start(getApplication(), AutomationService.ACTION_REFRESH) else AutomationService.stop(getApplication())
    }

    /** Treats the current room as quiet (about 35 dB) and sets the microphone offset from it. */
    fun calibrateQuiet() {
        calibrating = true
        viewModelScope.launch {
            val dbfs = c.noiseMonitor.measureDbfs()
            calibrating = false
            if (dbfs != null) {
                val offset = (QUIET_ROOM_DB - dbfs).coerceIn(40.0, 130.0)
                update { it.copy(adaptive = it.adaptive.copy(calibrationOffsetDb = offset)) }
                c.signals.reportNoise(QUIET_ROOM_DB, c.clock.now())
            }
        }
    }

    fun clearLearning() {
        viewModelScope.launch { c.storage.events.clear() }
    }

    companion object {
        private const val QUIET_ROOM_DB = 35.0
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val noise by vm.noise.collectAsStateWithLifecycle()
    val missing = rememberMissingCapabilities()
    val requester = rememberCapabilityRequester()
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    val s = settings ?: return

    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.settings_clear_title),
            text = stringResource(R.string.settings_clear_text),
            confirm = stringResource(R.string.delete),
            onConfirm = vm::clearLearning,
            onDismiss = { confirmClear = false },
        )
    }

    Scaffold(
        topBar = { AppTopBar(stringResource(R.string.settings), onBack = onBack) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard(stringResource(R.string.settings_claude), Icons.Filled.AutoAwesome) {
                    Text(
                        stringResource(if (vm.keySaved) R.string.key_status_saved else R.string.key_status_missing),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = vm.keyInput,
                        onValueChange = { vm.keyInput = it },
                        label = { Text(stringResource(R.string.key_field)) },
                        placeholder = { Text("sk-ant-…") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::saveKey, enabled = vm.keyInput.isNotBlank() && !vm.verifying) {
                            Text(stringResource(R.string.key_save))
                        }
                        if (vm.keySaved) OutlinedButton(onClick = vm::removeKey) { Text(stringResource(R.string.key_remove)) }
                        if (vm.verifying) CircularProgressIndicator(Modifier.size(24.dp))
                    }
                    vm.keyMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.anthropic.com/settings/keys")))
                    }) { Text(stringResource(R.string.key_get)) }

                    Text(stringResource(R.string.settings_model), style = MaterialTheme.typography.labelLarge)
                    Dropdown(
                        options = AiSettings.AVAILABLE_MODELS,
                        selected = s.ai.model,
                        label = { modelLabel(context, it) },
                        onSelect = { model -> vm.update { it.copy(ai = it.ai.copy(model = model)) } },
                    )
                    Text(stringResource(R.string.settings_model_desc), style = MaterialTheme.typography.bodySmall)

                    SwitchRow(
                        title = stringResource(R.string.settings_smart_mode),
                        subtitle = stringResource(R.string.settings_smart_mode_desc),
                        checked = s.ai.smartMode,
                        onChange = { on -> vm.update { it.copy(ai = it.ai.copy(smartMode = on)) } },
                    )
                    if (s.ai.smartMode) {
                        CommitSlider(
                            value = s.ai.smartModeMaxPerHour.toFloat(),
                            onCommit = { v -> vm.update { it.copy(ai = it.ai.copy(smartModeMaxPerHour = v.toInt())) } },
                            valueRange = 1f..10f,
                            steps = 8,
                            label = { v -> Text(stringResource(R.string.settings_smart_limit, v.toInt()), style = MaterialTheme.typography.bodyMedium) },
                        )
                    }
                    SwitchRow(
                        title = stringResource(R.string.settings_daily_analysis),
                        subtitle = stringResource(R.string.settings_daily_analysis_desc),
                        checked = s.ai.dailyAnalysis,
                        onChange = { on -> vm.update { it.copy(ai = it.ai.copy(dailyAnalysis = on)) } },
                    )
                }
            }

            item {
                val a = s.adaptive
                SectionCard(stringResource(R.string.settings_adaptive), Icons.Filled.GraphicEq) {
                    SwitchRow(
                        title = stringResource(R.string.home_adaptive),
                        subtitle = stringResource(R.string.home_adaptive_desc),
                        checked = a.enabled,
                        onChange = { on ->
                            if (on && Capability.MICROPHONE in missing) requester(Capability.MICROPHONE)
                            vm.update { it.copy(adaptive = it.adaptive.copy(enabled = on)) }
                            AutomationService.start(context, AutomationService.ACTION_REFRESH)
                        },
                    )
                    CommitRangeSlider(
                        value = a.minPercent.toFloat()..a.maxPercent.toFloat(),
                        onCommit = { r ->
                            vm.update { it.copy(adaptive = it.adaptive.copy(minPercent = r.start.toInt(), maxPercent = r.endInclusive.toInt())) }
                        },
                        valueRange = 0f..100f,
                        label = { r ->
                            Text(
                                stringResource(R.string.settings_adaptive_volume, r.start.toInt(), r.endInclusive.toInt()),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                    )
                    CommitRangeSlider(
                        value = a.quietDb.toFloat()..a.loudDb.toFloat(),
                        onCommit = { r ->
                            vm.update { it.copy(adaptive = it.adaptive.copy(quietDb = r.start.toDouble(), loudDb = r.endInclusive.toDouble())) }
                        },
                        valueRange = 20f..100f,
                        label = { r ->
                            Text(
                                stringResource(R.string.settings_adaptive_noise, r.start.toInt(), r.endInclusive.toInt()),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                    )
                    Text(stringResource(R.string.settings_adaptive_interval), style = MaterialTheme.typography.bodyMedium)
                    Dropdown(
                        options = listOf(60, 120, 300, 600),
                        selected = a.sampleIntervalSeconds,
                        label = { seconds -> context.getString(R.string.minutes_short, seconds / 60) },
                        onSelect = { seconds -> vm.update { it.copy(adaptive = it.adaptive.copy(sampleIntervalSeconds = seconds)) } },
                    )
                    SwitchRow(
                        title = stringResource(R.string.settings_adaptive_notifications),
                        checked = a.includeNotifications,
                        onChange = { on -> vm.update { it.copy(adaptive = it.adaptive.copy(includeNotifications = on)) } },
                    )
                    CommitSlider(
                        value = a.calibrationOffsetDb.toFloat(),
                        onCommit = { v -> vm.update { it.copy(adaptive = it.adaptive.copy(calibrationOffsetDb = v.toDouble())) } },
                        valueRange = 60f..120f,
                        label = { v -> Text(stringResource(R.string.settings_calibration, v.toInt()), style = MaterialTheme.typography.bodyMedium) },
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = vm::calibrateQuiet, enabled = !vm.calibrating && Permissions.hasMicrophone(context)) {
                            Text(stringResource(R.string.settings_calibrate))
                        }
                        Spacer(Modifier.width(12.dp))
                        if (vm.calibrating) CircularProgressIndicator(Modifier.size(24.dp))
                        noise?.let { Text(stringResource(R.string.db_value, it.db.toInt())) }
                    }
                    Text(stringResource(R.string.settings_mic_privacy), style = MaterialTheme.typography.bodySmall)
                }
            }

            item {
                val j = s.jewish
                val preview by vm.calendarPreview.collectAsStateWithLifecycle()
                SectionCard(stringResource(R.string.settings_jewish), Icons.Filled.CalendarMonth) {
                    Text(stringResource(R.string.jewish_city), style = MaterialTheme.typography.labelLarge)
                    val ids = Cities.ISRAEL.map { it.id } + if (j.cityId == Cities.CUSTOM) listOf(Cities.CUSTOM) else emptyList()
                    Dropdown(
                        options = ids,
                        selected = j.cityId,
                        label = { id -> Cities.byId(id)?.let(Labels::cityName) ?: j.locationName },
                        onSelect = vm::setCity,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = vm::useLocationForCalendar, enabled = !vm.locating) {
                            Text(stringResource(R.string.jewish_use_location))
                        }
                        Spacer(Modifier.width(12.dp))
                        if (vm.locating) CircularProgressIndicator(Modifier.size(24.dp))
                    }
                    vm.calendarMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    SwitchRow(
                        title = stringResource(R.string.jewish_in_israel),
                        subtitle = stringResource(R.string.jewish_in_israel_desc),
                        checked = j.inIsrael,
                        onChange = { on -> vm.updateJewish { it.copy(inIsrael = on) } },
                    )
                    CommitSlider(
                        value = j.candleLightingMinutes.toFloat(),
                        onCommit = { v -> vm.updateJewish { it.copy(candleLightingMinutes = v.toInt()) } },
                        valueRange = 0f..60f,
                        steps = 11,
                        label = { v -> Text(stringResource(R.string.jewish_candles, v.toInt()), style = MaterialTheme.typography.bodyMedium) },
                    )
                    Text(stringResource(R.string.jewish_havdalah), style = MaterialTheme.typography.labelLarge)
                    Segmented(
                        labels = listOf(stringResource(R.string.jewish_havdalah_nightfall), stringResource(R.string.jewish_havdalah_minutes_mode)),
                        selected = if (j.havdalahMode == HavdalahMode.NIGHTFALL) 0 else 1,
                        onSelect = { index ->
                            vm.updateJewish { it.copy(havdalahMode = if (index == 0) HavdalahMode.NIGHTFALL else HavdalahMode.MINUTES_AFTER_SUNSET) }
                        },
                    )
                    if (j.havdalahMode == HavdalahMode.MINUTES_AFTER_SUNSET) {
                        CommitSlider(
                            value = j.havdalahMinutes.toFloat(),
                            onCommit = { v -> vm.updateJewish { it.copy(havdalahMinutes = v.toInt()) } },
                            valueRange = 20f..90f,
                            steps = 13,
                            label = { v -> Text(stringResource(R.string.jewish_havdalah_minutes, v.toInt()), style = MaterialTheme.typography.bodyMedium) },
                        )
                    }
                    preview?.upcoming?.firstOrNull()?.let { span ->
                        Text(
                            stringResource(R.string.jewish_preview, Labels.holyName(span), Labels.dayTime(span.startMillis), Labels.dayTime(span.endMillis)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(stringResource(R.string.jewish_custom_note), style = MaterialTheme.typography.bodySmall)
                }
            }

            item {
                SectionCard(stringResource(R.string.settings_automation), Icons.Filled.Tune) {
                    SwitchRow(stringResource(R.string.home_automation_switch), s.automationEnabled, vm::setAutomation)
                    SwitchRow(
                        title = stringResource(R.string.settings_learning),
                        subtitle = stringResource(R.string.settings_learning_desc),
                        checked = s.learningEnabled,
                        onChange = { on -> vm.update { it.copy(learningEnabled = on) } },
                    )
                    OutlinedButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.settings_clear)) }
                }
            }

            item {
                SectionCard(stringResource(R.string.settings_permissions), Icons.Filled.Security) {
                    Capability.entries.forEach { capability ->
                        CapabilityRow(capability, granted = capability !in missing, onGrant = requester)
                    }
                }
            }

            item {
                SectionCard(stringResource(R.string.settings_about), Icons.Filled.Info) {
                    Text(stringResource(R.string.settings_privacy), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun modelLabel(context: android.content.Context, model: String): String = when (model) {
    "claude-opus-5-5" -> context.getString(R.string.model_opus)
    "claude-sonnet-5-5" -> context.getString(R.string.model_sonnet)
    "claude-haiku-5-5" -> context.getString(R.string.model_haiku)
    else -> model
}
