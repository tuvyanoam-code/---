@file:OptIn(ExperimentalLayoutApi::class)

package com.tuvyanoam.soundpilot.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tuvyanoam.soundpilot.R
import com.tuvyanoam.soundpilot.container
import com.tuvyanoam.soundpilot.core.jewish.CalendarOverview
import com.tuvyanoam.soundpilot.core.jewish.JewishCalendarService
import com.tuvyanoam.soundpilot.core.model.AudioCommand
import com.tuvyanoam.soundpilot.core.model.AudioState
import com.tuvyanoam.soundpilot.core.model.AudioStream
import com.tuvyanoam.soundpilot.core.model.RingerMode
import com.tuvyanoam.soundpilot.core.model.UserEvent
import com.tuvyanoam.soundpilot.platform.Capability
import com.tuvyanoam.soundpilot.platform.Labels
import com.tuvyanoam.soundpilot.service.AutomationService
import com.tuvyanoam.soundpilot.ui.MainActivity
import com.tuvyanoam.soundpilot.ui.components.AppTopBar
import com.tuvyanoam.soundpilot.ui.components.InfoRow
import com.tuvyanoam.soundpilot.ui.components.PermissionsCard
import com.tuvyanoam.soundpilot.ui.components.SectionCard
import com.tuvyanoam.soundpilot.ui.components.Segmented
import com.tuvyanoam.soundpilot.ui.components.SwitchRow
import com.tuvyanoam.soundpilot.ui.components.rememberCapabilityRequester
import com.tuvyanoam.soundpilot.ui.components.rememberMissingCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.math.roundToInt
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val _audio = MutableStateFlow<AudioState?>(null)
    val audio: StateFlow<AudioState?> = _audio.asStateFlow()
    val settings = c.storage.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val automation = c.storage.automation.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val report = c.coordinator.lastReport
    val placeNames = c.storage.places.places.map { list -> list.associate { it.id to it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    val noise = c.signals.noise
    val adaptiveRunning = c.coordinator.adaptiveActive
    val serviceRunning = AutomationService.running

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    private val _calendar = MutableStateFlow<CalendarOverview?>(null)
    val calendar: StateFlow<CalendarOverview?> = _calendar.asStateFlow()

    suspend fun refreshCalendar() {
        val settings = c.storage.settings.get().jewish
        _calendar.value = withContext(Dispatchers.Default) {
            runCatching { JewishCalendarService.overview(c.clock.now(), settings, days = 30) }.getOrNull()
        }
    }

    fun levels(stream: AudioStream): Int = c.audio.levels(stream)

    suspend fun refresh() {
        _audio.value = c.audio.read()
    }

    private fun act(description: String, commands: List<AudioCommand>, record: suspend () -> Unit) {
        viewModelScope.launch {
            val errors = c.coordinator.applyNow(commands, description, fromAi = false)
            if (errors.isNotEmpty()) _messages.tryEmit(errors.first()) else record()
            refresh()
        }
    }

    fun setRinger(mode: RingerMode) = act(Labels.mode(getApplication(), mode), listOf(AudioCommand.Ringer(mode))) {
        c.coordinator.recordUserChange(UserEvent.Kind.RINGER, ringerMode = mode)
    }

    fun setVolume(stream: AudioStream, percent: Int) = act(stream.name, listOf(AudioCommand.Volume(stream, percent))) {
        c.coordinator.recordUserChange(UserEvent.Kind.VOLUME, stream = stream, percent = percent)
    }

    fun quickMute(mode: RingerMode, minutes: Int) {
        viewModelScope.launch {
            val errors = c.coordinator.startTemporary(listOf(AudioCommand.Ringer(mode)), minutes, Labels.mode(getApplication(), mode))
            if (errors.isNotEmpty()) _messages.tryEmit(errors.first())
            refresh()
        }
    }

    fun endTemporary() {
        viewModelScope.launch {
            c.coordinator.endTemporary()
            refresh()
        }
    }

    fun setAutomation(enabled: Boolean) {
        viewModelScope.launch {
            c.storage.settings.update { it.copy(automationEnabled = enabled) }
            if (enabled) {
                AutomationService.start(getApplication(), AutomationService.ACTION_REFRESH)
                c.coordinator.evaluate("automation_on", forceReapply = true)
            } else {
                AutomationService.stop(getApplication())
                c.coordinator.evaluate("automation_off")
            }
        }
    }

    fun setAdaptive(enabled: Boolean) {
        viewModelScope.launch {
            c.storage.settings.update { it.copy(adaptive = it.adaptive.copy(enabled = enabled)) }
            AutomationService.start(getApplication(), AutomationService.ACTION_REFRESH)
            c.coordinator.evaluate("adaptive_toggled")
        }
    }
}

@Composable
fun HomeScreen(openSettings: () -> Unit, openTab: (String) -> Unit, vm: HomeViewModel = viewModel()) {
    val audio by vm.audio.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val automation by vm.automation.collectAsStateWithLifecycle()
    val report by vm.report.collectAsStateWithLifecycle()
    val placeNames by vm.placeNames.collectAsStateWithLifecycle()
    val noise by vm.noise.collectAsStateWithLifecycle()
    val adaptiveRunning by vm.adaptiveRunning.collectAsStateWithLifecycle()
    val serviceRunning by vm.serviceRunning.collectAsStateWithLifecycle()
    val missing = rememberMissingCapabilities()
    val requester = rememberCapabilityRequester()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    // AudioManager has no change stream; poll while the screen is visible.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.refresh()
                delay(2_000)
            }
        }
    }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    val calendar by vm.calendar.collectAsStateWithLifecycle()
    LaunchedEffect(lifecycle, settings?.jewish) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.refreshCalendar()
                delay(60_000)
            }
        }
    }

    Scaffold(
        topBar = { AppTopBar(stringResource(R.string.app_name), onSettings = openSettings) },
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { PermissionsCard(missing, onlyRequired = true, onGrant = requester) }

            item {
                SectionCard(stringResource(R.string.home_sound), Icons.AutoMirrored.Filled.VolumeUp) {
                    val modes = listOf(RingerMode.NORMAL, RingerMode.VIBRATE, RingerMode.SILENT)
                    Segmented(
                        labels = modes.map { Labels.mode(context, it) },
                        selected = modes.indexOf(audio?.ringerMode ?: RingerMode.NORMAL),
                        onSelect = { vm.setRinger(modes[it]) },
                    )
                    audio?.let { if (it.dnd != com.tuvyanoam.soundpilot.core.model.DndMode.OFF) Text(Labels.dnd(context, it.dnd)) }
                }
            }

            item {
                val temporary = automation?.temporary
                SectionCard(stringResource(R.string.home_quick_mute), Icons.Filled.Timer) {
                    if (temporary != null) {
                        val until = Instant.ofEpochMilli(temporary.untilMillis).atZone(java.time.ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
                        Text(stringResource(R.string.home_temporary_until, temporary.description, until))
                        Button(onClick = vm::endTemporary) { Text(stringResource(R.string.action_end_now)) }
                    } else {
                        var muteMode by rememberSaveable { mutableStateOf(RingerMode.VIBRATE) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(RingerMode.VIBRATE, RingerMode.SILENT).forEach { mode ->
                                FilterChip(
                                    selected = muteMode == mode,
                                    onClick = { muteMode = mode },
                                    label = { Text(Labels.mode(context, mode)) },
                                    leadingIcon = {
                                        Icon(if (mode == RingerMode.VIBRATE) Icons.Filled.Vibration else Icons.AutoMirrored.Filled.VolumeOff, null)
                                    },
                                )
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(30, 60, 120, 240).forEach { minutes ->
                                AssistChip(
                                    onClick = { vm.quickMute(muteMode, minutes) },
                                    label = {
                                        Text(
                                            if (minutes < 60) stringResource(R.string.minutes_short, minutes)
                                            else stringResource(R.string.hours_short, minutes / 60),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }

            calendar?.let { overview ->
                item {
                    SectionCard(stringResource(R.string.home_jewish), Icons.Filled.CalendarMonth) {
                        Text(if (Labels.isHebrew()) overview.hebrewDate else overview.englishHebrewDate, style = MaterialTheme.typography.titleLarge)
                        overview.current?.let { span ->
                            Text(
                                stringResource(R.string.home_holy_now, Labels.holyName(span), Labels.dayTime(span.endMillis)),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        overview.upcoming.take(2).forEach { span ->
                            Text(stringResource(R.string.home_holy_next, Labels.holyName(span)), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.home_holy_times, Labels.dayTime(span.startMillis), Labels.dayTime(span.endMillis)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        settings?.jewish?.let { j ->
                            Text(
                                stringResource(R.string.home_holy_location, j.locationName, j.candleLightingMinutes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item {
                SectionCard(stringResource(R.string.home_volumes), Icons.Filled.GraphicEq) {
                    val icons = mapOf(
                        AudioStream.RING to Icons.AutoMirrored.Filled.VolumeUp,
                        AudioStream.NOTIFICATION to Icons.Filled.Notifications,
                        AudioStream.MEDIA to Icons.Filled.MusicNote,
                        AudioStream.ALARM to Icons.Filled.Alarm,
                    )
                    AudioStream.entries.forEach { stream ->
                        VolumeSlider(
                            icon = icons.getValue(stream),
                            label = Labels.stream(context, stream),
                            percent = audio?.volume(stream) ?: 0,
                            levels = vm.levels(stream),
                            onChange = { vm.setVolume(stream, it) },
                        )
                    }
                }
            }

            item {
                SectionCard(stringResource(R.string.home_automation), Icons.Filled.AutoAwesome) {
                    SwitchRow(
                        title = stringResource(R.string.home_automation_switch),
                        subtitle = stringResource(if (serviceRunning) R.string.home_service_running else R.string.home_service_stopped),
                        checked = settings?.automationEnabled ?: true,
                        onChange = vm::setAutomation,
                    )
                    SwitchRow(
                        title = stringResource(R.string.home_adaptive),
                        subtitle = when {
                            Capability.MICROPHONE in missing -> stringResource(R.string.home_adaptive_needs_mic)
                            adaptiveRunning -> stringResource(R.string.home_adaptive_running)
                            else -> stringResource(R.string.home_adaptive_desc)
                        },
                        checked = settings?.adaptive?.enabled ?: false,
                        onChange = { enabled ->
                            if (enabled && Capability.MICROPHONE in missing) requester(Capability.MICROPHONE)
                            vm.setAdaptive(enabled)
                        },
                    )
                }
            }

            item {
                val ctx = report?.context
                SectionCard(stringResource(R.string.home_context), Icons.Filled.Place) {
                    val dash = "—"
                    InfoRow(Icons.Filled.Place, stringResource(R.string.ctx_place), ctx?.placeIds?.mapNotNull { placeNames[it] }?.joinToString()?.ifEmpty { null } ?: dash)
                    InfoRow(Icons.Filled.Wifi, stringResource(R.string.ctx_wifi), ctx?.wifiSsid ?: dash)
                    InfoRow(Icons.Filled.Bluetooth, stringResource(R.string.ctx_bluetooth), ctx?.bluetoothDevices?.joinToString()?.ifEmpty { null } ?: dash)
                    InfoRow(Icons.Filled.BatteryChargingFull, stringResource(R.string.ctx_charging), stringResource(if (ctx?.charging == true) R.string.yes else R.string.no))
                    InfoRow(Icons.Filled.Headphones, stringResource(R.string.ctx_headphones), stringResource(if (ctx?.headphones == true) R.string.yes else R.string.no))
                    InfoRow(Icons.Filled.GraphicEq, stringResource(R.string.ctx_noise), noise?.let { stringResource(R.string.db_value, it.db.toInt()) } ?: dash)
                    InfoRow(Icons.Filled.Event, stringResource(R.string.ctx_calendar), ctx?.calendarEvents?.joinToString { it.title }?.ifEmpty { null } ?: dash)
                    val active = report?.activeRules.orEmpty()
                    Spacer(Modifier.size(4.dp))
                    Text(stringResource(R.string.ctx_active_rules), style = MaterialTheme.typography.labelLarge)
                    if (active.isEmpty()) {
                        Text(stringResource(R.string.ctx_no_active_rules), style = MaterialTheme.typography.bodySmall)
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            active.forEach { rule -> AssistChip(onClick = { openTab(MainActivity.TAB_RULES) }, label = { Text(rule.name) }) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VolumeSlider(icon: ImageVector, label: String, percent: Int, levels: Int, onChange: (Int) -> Unit) {
    // While dragging, show the finger's position; otherwise the phone's actual volume.
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val shown = dragValue ?: (percent / 100f)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, label, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, modifier = Modifier.width(88.dp), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = shown,
            onValueChange = { dragValue = it },
            onValueChangeFinished = {
                dragValue?.let { onChange((it * 100).roundToInt()) }
                dragValue = null
            },
            steps = (levels - 1).coerceIn(0, 100),
            modifier = Modifier.weight(1f),
        )
        Text("${(shown * 100).roundToInt()}%", modifier = Modifier.width(44.dp), style = MaterialTheme.typography.bodySmall)
    }
}
