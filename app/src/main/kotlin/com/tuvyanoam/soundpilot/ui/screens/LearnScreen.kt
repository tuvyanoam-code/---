package com.tuvyanoam.soundpilot.ui.screens

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.ToggleOff
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tuvyanoam.soundpilot.R
import com.tuvyanoam.soundpilot.container
import com.tuvyanoam.soundpilot.core.model.ActivityEntry
import com.tuvyanoam.soundpilot.core.model.Suggestion
import com.tuvyanoam.soundpilot.platform.Labels
import com.tuvyanoam.soundpilot.ui.components.AppTopBar
import com.tuvyanoam.soundpilot.ui.components.SectionCard
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class LearnViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private fun <T> share(flow: kotlinx.coroutines.flow.Flow<T>, initial: T) =
        flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val suggestions = share(c.storage.suggestions.suggestions, emptyList())
    val notes = share(c.storage.memory.notes, emptyList())
    val activity = share(c.storage.events.activityFlow.map { it.take(150) }, emptyList())
    val eventCount = share(c.storage.events.userEventsFlow.map { it.size }, 0)
    val places = share(c.storage.places.places.map { list -> list.associate { it.id to it.name } }, emptyMap())

    var mining by mutableStateOf(false)
        private set
    var analyzing by mutableStateOf(false)
        private set
    var analysis by mutableStateOf<String?>(null)
        private set

    fun mine() {
        mining = true
        viewModelScope.launch {
            c.learning.runOnDevice()
            mining = false
        }
    }

    fun analyze() {
        if (analyzing) return
        analyzing = true
        analysis = null
        viewModelScope.launch {
            val (reply, _) = c.learning.runClaudeAnalysis()
            analyzing = false
            analysis = describeProblem(getApplication(), reply) ?: reply.text
        }
    }

    fun accept(id: String) {
        viewModelScope.launch { c.learning.accept(id) }
    }

    fun dismiss(id: String) {
        viewModelScope.launch { c.learning.dismiss(id) }
    }

    fun deleteNote(id: String) {
        viewModelScope.launch { c.storage.memory.delete(id) }
    }
}

@Composable
fun LearnScreen(openSettings: () -> Unit, vm: LearnViewModel = viewModel()) {
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val notes by vm.notes.collectAsStateWithLifecycle()
    val activity by vm.activity.collectAsStateWithLifecycle()
    val eventCount by vm.eventCount.collectAsStateWithLifecycle()
    val places by vm.places.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val timeFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)

    Scaffold(
        topBar = { AppTopBar(stringResource(R.string.tab_learn), onSettings = openSettings) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard(stringResource(R.string.learn_title), Icons.Filled.Lightbulb) {
                    Text(stringResource(R.string.learn_intro, eventCount), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = vm::mine, enabled = !vm.mining) {
                            Icon(Icons.Filled.PhoneAndroid, null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.learn_on_device))
                        }
                        FilledTonalButton(onClick = vm::analyze, enabled = !vm.analyzing) {
                            Icon(Icons.Filled.AutoAwesome, null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.learn_with_claude))
                        }
                        if (vm.mining || vm.analyzing) CircularProgressIndicator(Modifier.size(24.dp))
                    }
                    vm.analysis?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }

            item { Text(stringResource(R.string.learn_suggestions), style = MaterialTheme.typography.titleMedium) }
            if (suggestions.isEmpty()) {
                item { Text(stringResource(R.string.learn_no_suggestions), style = MaterialTheme.typography.bodyMedium) }
            }
            items(suggestions, key = { it.id }) { suggestion ->
                SuggestionCard(
                    suggestion = suggestion,
                    conditions = Labels.conditions(context, suggestion.rule.conditions, places),
                    actions = Labels.actions(context, suggestion.rule.actions),
                    onAccept = { vm.accept(suggestion.id) },
                    onDismiss = { vm.dismiss(suggestion.id) },
                )
            }

            item {
                SectionCard(stringResource(R.string.learn_memory), Icons.Filled.Memory) {
                    if (notes.isEmpty()) Text(stringResource(R.string.learn_memory_empty), style = MaterialTheme.typography.bodySmall)
                    notes.forEach { note ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(note.text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            IconButton(onClick = { vm.deleteNote(note.id) }) { Icon(Icons.Filled.Close, stringResource(R.string.delete)) }
                        }
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.History, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.learn_activity), style = MaterialTheme.typography.titleMedium)
                }
            }
            if (activity.isEmpty()) {
                item { Text(stringResource(R.string.learn_activity_empty), style = MaterialTheme.typography.bodyMedium) }
            }
            items(activity) { entry ->
                ListItem(
                    headlineContent = { Text(entry.text) },
                    supportingContent = {
                        Text(Instant.ofEpochMilli(entry.epochMillis).atZone(ZoneId.systemDefault()).format(timeFormat))
                    },
                    leadingContent = { Icon(iconFor(entry.type), null) },
                )
            }
        }
    }
}

private fun iconFor(type: ActivityEntry.Type): ImageVector = when (type) {
    ActivityEntry.Type.RULE_ON -> Icons.Filled.ToggleOn
    ActivityEntry.Type.RULE_OFF -> Icons.Filled.ToggleOff
    ActivityEntry.Type.USER_CHANGE -> Icons.Filled.Person
    ActivityEntry.Type.AI_ACTION -> Icons.Filled.SmartToy
    ActivityEntry.Type.ADAPTIVE -> Icons.Filled.AutoAwesome
    ActivityEntry.Type.TEMPORARY -> Icons.Filled.Timer
    ActivityEntry.Type.ERROR -> Icons.Filled.Error
}

@Composable
private fun SuggestionCard(
    suggestion: Suggestion,
    conditions: String,
    actions: String,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (suggestion.source == Suggestion.Source.CLAUDE) Icons.Filled.AutoAwesome else Icons.Filled.PhoneAndroid,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(suggestion.rule.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("${(suggestion.confidence * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
            }
            Text(stringResource(R.string.rule_when, conditions), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.rule_then, actions), style = MaterialTheme.typography.bodyMedium)
            if (suggestion.rationale.isNotBlank()) {
                Text(suggestion.rationale, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAccept) { Text(stringResource(R.string.learn_accept)) }
                OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.learn_dismiss)) }
            }
        }
    }
}
