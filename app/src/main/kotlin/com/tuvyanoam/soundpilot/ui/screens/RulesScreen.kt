@file:OptIn(ExperimentalLayoutApi::class)

package com.tuvyanoam.soundpilot.ui.screens

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tuvyanoam.soundpilot.R
import com.tuvyanoam.soundpilot.container
import com.tuvyanoam.soundpilot.core.ai.ActionSpec
import com.tuvyanoam.soundpilot.core.ai.AgentMode
import com.tuvyanoam.soundpilot.core.ai.ConditionSpec
import com.tuvyanoam.soundpilot.core.ai.Conversation
import com.tuvyanoam.soundpilot.core.ai.RuleSpec
import com.tuvyanoam.soundpilot.core.ai.RuleSpecs
import com.tuvyanoam.soundpilot.core.ai.RunCollector
import com.tuvyanoam.soundpilot.core.model.Rule
import com.tuvyanoam.soundpilot.core.model.RuleOrigin
import com.tuvyanoam.soundpilot.platform.Labels
import com.tuvyanoam.soundpilot.ui.components.AppTopBar
import com.tuvyanoam.soundpilot.ui.components.EmptyState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A rule being edited. Editing happens on the flat [RuleSpec], the same shape Claude uses. */
data class RuleForm(
    val id: String,
    val origin: RuleOrigin,
    val createdAt: Long,
    val enabled: Boolean,
    val spec: RuleSpec,
    val isNew: Boolean,
    val explanation: String? = null,
)

class RulesViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    val rules = c.storage.rules.rules.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val places = c.storage.places.places.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val activeIds = c.storage.automation.state.map { it.engine.activeRuleIds.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    val report = c.coordinator.lastReport

    var form by mutableStateOf<RuleForm?>(null)
        private set
    var formError by mutableStateOf<String?>(null)
        private set
    var drafting by mutableStateOf(false)
        private set
    var draftError by mutableStateOf<String?>(null)
        private set

    fun newRule() {
        open(RuleForm("", RuleOrigin.USER, 0, true, RuleSpec(name = "", actions = ActionSpec(ringerMode = "VIBRATE")), isNew = true))
    }

    fun fromTemplate(template: RuleTemplate) {
        open(RuleForm("", RuleOrigin.TEMPLATE, 0, true, template.build(getApplication()), isNew = true))
    }

    fun edit(rule: Rule) {
        open(RuleForm(rule.id, rule.origin, rule.createdAt, rule.enabled, RuleSpecs.fromRule(rule), isNew = false))
    }

    private fun open(value: RuleForm) {
        form = value
        formError = null
    }

    fun update(transform: (RuleForm) -> RuleForm) {
        form = form?.let(transform)
        formError = null
    }

    fun updateSpec(transform: (RuleSpec) -> RuleSpec) = update { it.copy(spec = transform(it.spec)) }
    fun updateConditions(transform: (ConditionSpec) -> ConditionSpec) = updateSpec { it.copy(conditions = transform(it.conditions)) }
    fun updateActions(transform: (ActionSpec) -> ActionSpec) = updateSpec { it.copy(actions = transform(it.actions)) }

    fun close() {
        form = null
        formError = null
    }

    fun save() {
        val current = form ?: return
        viewModelScope.launch {
            val placeIds = c.storage.places.all().map { it.id }.toSet()
            val spec = current.spec.copy(name = current.spec.name.ifBlank { getApplication<Application>().getString(R.string.rule_unnamed) })
            RuleSpecs.toRule(spec, current.id, current.origin, placeIds, current.createdAt)
                .onSuccess { rule ->
                    c.storage.rules.upsert(rule.copy(enabled = current.enabled))
                    c.coordinator.evaluate("rule_saved")
                    close()
                }
                .onFailure { formError = it.message }
        }
    }

    fun delete() {
        val id = form?.id?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            c.storage.rules.delete(id)
            c.coordinator.evaluate("rule_deleted")
            close()
        }
    }

    fun setEnabled(rule: Rule, enabled: Boolean) {
        viewModelScope.launch {
            c.storage.rules.setEnabled(rule.id, enabled)
            c.coordinator.evaluate("rule_toggled")
        }
    }

    fun draft(description: String) {
        if (description.isBlank() || drafting) return
        drafting = true
        draftError = null
        viewModelScope.launch {
            val collector = RunCollector()
            val reply = c.agent.run(AgentMode.RULE_DRAFT, Conversation(), description, collector)
            drafting = false
            val rule = collector.draft
            if (rule != null) {
                open(
                    RuleForm(
                        id = "",
                        origin = RuleOrigin.AI,
                        createdAt = 0,
                        enabled = true,
                        spec = RuleSpecs.fromRule(rule),
                        isNew = true,
                        explanation = collector.draftExplanation.ifBlank { reply.text }.ifBlank { null },
                    ),
                )
            } else {
                draftError = describeProblem(getApplication(), reply) ?: reply.text.ifBlank { null }
            }
        }
    }
}

@Composable
fun RulesScreen(openSettings: () -> Unit, vm: RulesViewModel = viewModel()) {
    val form = vm.form
    if (form != null) {
        BackHandler { vm.close() }
        RuleEditor(vm, form)
        return
    }

    val rules by vm.rules.collectAsStateWithLifecycle()
    val places by vm.places.collectAsStateWithLifecycle()
    val activeIds by vm.activeIds.collectAsStateWithLifecycle()
    val placeNames = places.associate { it.id to it.name }
    val context = LocalContext.current

    Scaffold(
        topBar = { AppTopBar(stringResource(R.string.tab_rules), onSettings = openSettings) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = vm::newRule,
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text(stringResource(R.string.rule_new)) },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { DescribeRuleCard(vm) }
            item {
                Text(stringResource(R.string.rule_templates), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.size(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ruleTemplates) { template ->
                        AssistChip(
                            onClick = { vm.fromTemplate(template) },
                            label = { Text(stringResource(template.title)) },
                            leadingIcon = { Icon(template.icon, null) },
                        )
                    }
                }
            }
            if (rules.isEmpty()) {
                item { EmptyState(Icons.Filled.Tune, stringResource(R.string.rules_empty_title), stringResource(R.string.rules_empty_text)) }
            }
            items(rules.sortedWith(compareByDescending<Rule> { it.id in activeIds }.thenBy { it.name }), key = { it.id }) { rule ->
                RuleCard(
                    rule = rule,
                    active = rule.id in activeIds,
                    conditions = Labels.conditions(context, rule.conditions, placeNames),
                    actions = Labels.actions(context, rule.actions),
                    onClick = { vm.edit(rule) },
                    onToggle = { vm.setEnabled(rule, it) },
                )
            }
        }
    }
}

@Composable
private fun DescribeRuleCard(vm: RulesViewModel) {
    var text by rememberSaveable { mutableStateOf("") }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoAwesome, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.rule_describe_title), style = MaterialTheme.typography.titleMedium)
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.rule_describe_hint)) },
                minLines = 2,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { vm.draft(text) }, enabled = text.isNotBlank() && !vm.drafting) {
                    Text(stringResource(R.string.rule_describe_button))
                }
                if (vm.drafting) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
            vm.draftError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun RuleCard(rule: Rule, active: Boolean, conditions: String, actions: String, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(rule.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = rule.enabled, onCheckedChange = onToggle)
            }
            Text(stringResource(R.string.rule_when, conditions), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.rule_then, actions), style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (active) SuggestionChip(onClick = {}, label = { Text(stringResource(R.string.rule_active_now)) })
                when (rule.origin) {
                    RuleOrigin.AI -> SuggestionChip(onClick = {}, label = { Text(stringResource(R.string.origin_ai)) })
                    RuleOrigin.LEARNED -> SuggestionChip(onClick = {}, label = { Text(stringResource(R.string.origin_learned)) })
                    else -> Unit
                }
            }
        }
    }
}
