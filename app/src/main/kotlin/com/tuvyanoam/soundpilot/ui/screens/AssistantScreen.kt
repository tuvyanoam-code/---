@file:OptIn(ExperimentalLayoutApi::class)

package com.tuvyanoam.soundpilot.ui.screens

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tuvyanoam.soundpilot.R
import com.tuvyanoam.soundpilot.container
import com.tuvyanoam.soundpilot.core.ai.AgentMode
import com.tuvyanoam.soundpilot.core.ai.AgentReply
import com.tuvyanoam.soundpilot.core.ai.Conversation
import com.tuvyanoam.soundpilot.core.ai.ToolCallRecord
import com.tuvyanoam.soundpilot.ui.components.AppTopBar
import kotlinx.coroutines.launch
import java.util.Locale

data class ChatItem(val id: Long, val role: Role, val text: String, val tools: List<ToolCallRecord> = emptyList()) {
    enum class Role { USER, ASSISTANT, ERROR }
}

class AssistantViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    private val conversation = Conversation()
    private var nextId = 0L

    val items = mutableStateListOf<ChatItem>()
    val liveTools = mutableStateListOf<ToolCallRecord>()
    var busy by mutableStateOf(false)
        private set
    var hasKey by mutableStateOf(true)
        private set

    fun checkKey() {
        hasKey = c.keyStore.get() != null
    }

    fun send(text: String) {
        val message = text.trim()
        if (message.isEmpty() || busy) return
        items += ChatItem(nextId++, ChatItem.Role.USER, message)
        busy = true
        liveTools.clear()
        viewModelScope.launch {
            val reply = c.agent.run(AgentMode.CHAT, conversation, message, onToolCall = { liveTools += it })
            val problem = describeProblem(getApplication(), reply)
            val tools = reply.toolCalls.filter { it.summary != null || it.isError }
            if (problem == null) {
                items += ChatItem(nextId++, ChatItem.Role.ASSISTANT, reply.text, tools)
            } else {
                // Actions already taken stay visible; for refusals and failures the text is part of the problem.
                val partial = if (reply.outcome == AgentReply.Outcome.TRUNCATED) reply.text else ""
                if (tools.isNotEmpty() || partial.isNotBlank()) items += ChatItem(nextId++, ChatItem.Role.ASSISTANT, partial, tools)
                items += ChatItem(nextId++, ChatItem.Role.ERROR, problem)
            }
            liveTools.clear()
            busy = false
        }
    }

    fun newChat() {
        if (busy) return
        conversation.clear()
        items.clear()
    }
}

@Composable
fun AssistantScreen(openSettings: () -> Unit, vm: AssistantViewModel = viewModel()) {
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.checkKey() }
    LaunchedEffect(vm.items.size, vm.busy) {
        val count = vm.items.size + if (vm.busy) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spoken.isNullOrBlank()) vm.send(spoken)
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(R.string.tab_assistant),
                onSettings = openSettings,
                actions = {
                    IconButton(onClick = vm::newChat, enabled = !vm.busy) { Icon(Icons.Filled.AddComment, stringResource(R.string.chat_new)) }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (!vm.hasKey) {
                Card(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Key, null)
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.chat_need_key_title), style = MaterialTheme.typography.titleMedium)
                        }
                        Text(stringResource(R.string.chat_need_key_text))
                        Button(onClick = openSettings) { Text(stringResource(R.string.open_settings)) }
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (vm.items.isEmpty()) {
                    item { ChatIntro(onPick = vm::send, enabled = vm.hasKey) }
                }
                items(vm.items, key = { it.id }) { item -> ChatBubble(item) }
                if (vm.busy) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            ToolChips(vm.liveTools.filter { it.summary != null })
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                        try {
                            speech.launch(intent)
                        } catch (e: ActivityNotFoundException) {
                            android.widget.Toast.makeText(context, R.string.chat_no_speech, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = !vm.busy && vm.hasKey,
                ) { Icon(Icons.Filled.Mic, stringResource(R.string.chat_voice)) }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.chat_hint)) },
                    maxLines = 4,
                    enabled = vm.hasKey,
                )
                IconButton(
                    onClick = {
                        vm.send(input)
                        input = ""
                    },
                    enabled = input.isNotBlank() && !vm.busy && vm.hasKey,
                ) { Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.chat_send)) }
            }
        }
    }
}

@Composable
private fun ChatIntro(onPick: (String) -> Unit, enabled: Boolean) {
    val examples = listOf(
        R.string.chat_example_meetings,
        R.string.chat_example_hour,
        R.string.chat_example_car,
        R.string.chat_example_shabbat,
        R.string.chat_example_learned,
    ).map { stringResource(it) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.SmartToy, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.chat_intro_title), style = MaterialTheme.typography.titleMedium)
        }
        Text(stringResource(R.string.chat_intro_text), style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            examples.forEach { example -> AssistChip(onClick = { onPick(example) }, label = { Text(example) }, enabled = enabled) }
        }
    }
}

@Composable
private fun ChatBubble(item: ChatItem) {
    val user = item.role == ChatItem.Role.USER
    val colors = when (item.role) {
        ChatItem.Role.USER -> MaterialTheme.colorScheme.primaryContainer
        ChatItem.Role.ASSISTANT -> MaterialTheme.colorScheme.surfaceVariant
        ChatItem.Role.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(Modifier.widthIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (item.text.isNotBlank()) {
                Surface(color = colors, shape = RoundedCornerShape(16.dp)) {
                    Row(Modifier.padding(12.dp)) {
                        if (item.role == ChatItem.Role.ERROR) {
                            Icon(Icons.Filled.Error, null, Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                        }
                        Text(item.text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            ToolChips(item.tools)
        }
    }
}

@Composable
private fun ToolChips(tools: List<ToolCallRecord>) {
    if (tools.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tools.forEach { tool ->
            AssistChip(
                onClick = {},
                label = { Text(tool.summary ?: tool.name, style = MaterialTheme.typography.labelSmall) },
                leadingIcon = {
                    Icon(
                        if (tool.isError) Icons.Filled.Error else Icons.Filled.Check,
                        null,
                        Modifier.size(16.dp),
                        tint = if (tool.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                },
            )
        }
    }
}
