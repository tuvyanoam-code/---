package com.tuvyanoam.soundpilot.core.storage

import com.tuvyanoam.soundpilot.core.engine.EngineState
import com.tuvyanoam.soundpilot.core.model.Action
import com.tuvyanoam.soundpilot.core.model.ActivityEntry
import com.tuvyanoam.soundpilot.core.model.AppSettings
import com.tuvyanoam.soundpilot.core.model.AudioState
import com.tuvyanoam.soundpilot.core.model.Condition
import com.tuvyanoam.soundpilot.core.model.ContextSnapshot
import com.tuvyanoam.soundpilot.core.model.MemoryNote
import com.tuvyanoam.soundpilot.core.model.Place
import com.tuvyanoam.soundpilot.core.model.Rule
import com.tuvyanoam.soundpilot.core.model.Suggestion
import com.tuvyanoam.soundpilot.core.model.UserEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

/** Identifies what a rule does regardless of its name or id, so the same idea is not suggested twice. */
fun ruleSignature(conditions: List<Condition>, actions: List<Action>): String =
    StoreJson.encodeToString(ListSerializer(Condition.serializer()), conditions) + "|" +
        StoreJson.encodeToString(ListSerializer(Action.serializer()), actions.filterNot { it is Action.Notify })

class RuleRepository(private val store: JsonFileStore<List<Rule>>, private val clock: () -> Long) {
    val rules: Flow<List<Rule>> = store.flow

    suspend fun all(): List<Rule> = store.get()

    suspend fun get(id: String): Rule? = all().firstOrNull { it.id == id }

    /** Inserts or replaces by id. A blank id gets a fresh one. */
    suspend fun upsert(rule: Rule): Rule {
        val saved = rule.copy(
            id = rule.id.ifBlank { newId() },
            createdAt = if (rule.createdAt == 0L) clock() else rule.createdAt,
        )
        store.update { list ->
            val index = list.indexOfFirst { it.id == saved.id }
            if (index >= 0) list.toMutableList().also { it[index] = saved } else list + saved
        }
        return saved
    }

    suspend fun delete(id: String): Boolean {
        var removed = false
        store.update { list -> list.filterNot { (it.id == id).also { match -> if (match) removed = true } } }
        return removed
    }

    suspend fun setEnabled(id: String, enabled: Boolean): Boolean {
        var found = false
        store.update { list -> list.map { if (it.id == id) it.copy(enabled = enabled).also { found = true } else it } }
        return found
    }
}

class PlaceRepository(private val store: JsonFileStore<List<Place>>) {
    val places: Flow<List<Place>> = store.flow

    suspend fun all(): List<Place> = store.get()

    suspend fun get(id: String): Place? = all().firstOrNull { it.id == id }

    suspend fun upsert(place: Place): Place {
        val saved = place.copy(
            id = place.id.ifBlank { newId() },
            radiusMeters = place.radiusMeters.coerceIn(Place.MIN_RADIUS_METERS, Place.MAX_RADIUS_METERS),
        )
        store.update { list ->
            val index = list.indexOfFirst { it.id == saved.id }
            if (index >= 0) list.toMutableList().also { it[index] = saved } else list + saved
        }
        return saved
    }

    suspend fun delete(id: String): Boolean {
        var removed = false
        store.update { list -> list.filterNot { (it.id == id).also { match -> if (match) removed = true } } }
        return removed
    }
}

class EventLog(
    private val userEvents: JsonFileStore<List<UserEvent>>,
    private val activity: JsonFileStore<List<ActivityEntry>>,
    private val maxUserEvents: Int = 3000,
    private val maxActivity: Int = 500,
) {
    val userEventsFlow: Flow<List<UserEvent>> = userEvents.flow
    val activityFlow: Flow<List<ActivityEntry>> = activity.flow.map { it.asReversed() }

    suspend fun recordUserEvent(event: UserEvent) {
        userEvents.update { (it + event).takeLast(maxUserEvents) }
    }

    suspend fun addActivity(entry: ActivityEntry) {
        activity.update { (it + entry).takeLast(maxActivity) }
    }

    suspend fun userEventsSince(epochMillis: Long): List<UserEvent> = userEvents.get().filter { it.epochMillis >= epochMillis }

    suspend fun allUserEvents(): List<UserEvent> = userEvents.get()

    suspend fun recentActivity(limit: Int): List<ActivityEntry> = activity.get().takeLast(limit)

    suspend fun clear() {
        userEvents.set(emptyList())
        activity.set(emptyList())
    }
}

@Serializable
data class SuggestionData(
    val suggestions: List<Suggestion> = emptyList(),
    val dismissedSignatures: Set<String> = emptySet(),
)

class SuggestionRepository(private val store: JsonFileStore<SuggestionData>) {
    val suggestions: Flow<List<Suggestion>> = store.flow.map { data -> data.suggestions.sortedByDescending { it.confidence } }

    suspend fun all(): List<Suggestion> = store.get().suggestions

    /** Replaces the on-device suggestions while keeping Claude's. */
    suspend fun replaceOnDevice(fresh: List<Suggestion>, existingRules: List<Rule>) {
        store.update { data ->
            val blocked = data.dismissedSignatures + existingRules.map { ruleSignature(it.conditions, it.actions) }
            val kept = data.suggestions.filter { it.source != Suggestion.Source.ON_DEVICE }
            val keptSignatures = kept.map { ruleSignature(it.rule.conditions, it.rule.actions) }.toSet()
            val added = fresh.filter {
                val sig = ruleSignature(it.rule.conditions, it.rule.actions)
                sig !in blocked && sig !in keptSignatures
            }
            data.copy(suggestions = kept + added)
        }
    }

    /** Adds a suggestion unless it was dismissed before or duplicates an existing one. Returns false if skipped. */
    suspend fun add(suggestion: Suggestion, existingRules: List<Rule>): Boolean {
        var added = false
        store.update { data ->
            val sig = ruleSignature(suggestion.rule.conditions, suggestion.rule.actions)
            val blocked = data.dismissedSignatures + existingRules.map { ruleSignature(it.conditions, it.actions) } +
                data.suggestions.map { ruleSignature(it.rule.conditions, it.rule.actions) }
            if (sig in blocked) data else data.copy(suggestions = data.suggestions + suggestion).also { added = true }
        }
        return added
    }

    suspend fun remove(id: String): Suggestion? {
        var removed: Suggestion? = null
        store.update { data ->
            removed = data.suggestions.firstOrNull { it.id == id }
            data.copy(suggestions = data.suggestions.filterNot { it.id == id })
        }
        return removed
    }

    suspend fun dismiss(id: String) {
        store.update { data ->
            val target = data.suggestions.firstOrNull { it.id == id } ?: return@update data
            data.copy(
                suggestions = data.suggestions.filterNot { it.id == id },
                dismissedSignatures = data.dismissedSignatures + ruleSignature(target.rule.conditions, target.rule.actions),
            )
        }
    }
}

class MemoryRepository(private val store: JsonFileStore<List<MemoryNote>>, private val clock: () -> Long) {
    val notes: Flow<List<MemoryNote>> = store.flow

    suspend fun all(): List<MemoryNote> = store.get()

    suspend fun add(text: String): MemoryNote {
        val note = MemoryNote(newId(), text.trim().take(MAX_NOTE_LENGTH), clock())
        store.update { (it + note).takeLast(MAX_NOTES) }
        return note
    }

    suspend fun delete(id: String): Boolean {
        var removed = false
        store.update { list -> list.filterNot { (it.id == id).also { match -> if (match) removed = true } } }
        return removed
    }

    companion object {
        const val MAX_NOTES = 60
        const val MAX_NOTE_LENGTH = 400
    }
}

class SettingsRepository(private val store: JsonFileStore<AppSettings>) {
    val settings: Flow<AppSettings> = store.flow

    suspend fun get(): AppSettings = store.get()

    suspend fun update(transform: (AppSettings) -> AppSettings): AppSettings = store.update(transform)
}

/** A "silence for an hour" style override that pauses rules until it expires. */
@Serializable
data class TemporaryOverride(
    val untilMillis: Long,
    val previous: AudioState,
    val description: String,
)

@Serializable
data class AutomationState(
    val engine: EngineState = EngineState(),
    val temporary: TemporaryOverride? = null,
    val lastContext: ContextSnapshot? = null,
    val adaptiveOverride: Boolean? = null,
    /** When Claude's smart mode last ran, for rate limiting. */
    val smartRuns: List<Long> = emptyList(),
    val lastAnalysisMillis: Long = 0,
)

class AutomationStateStore(private val store: JsonFileStore<AutomationState>) {
    val state: Flow<AutomationState> = store.flow

    suspend fun get(): AutomationState = store.get()

    suspend fun update(transform: (AutomationState) -> AutomationState): AutomationState = store.update(transform)
}

/** Creates every repository under one directory. */
class Storage(dir: File, clock: () -> Long) {
    val rules = RuleRepository(JsonFileStore(File(dir, "rules.json"), ListSerializer(Rule.serializer()), { emptyList() }), clock)
    val places = PlaceRepository(JsonFileStore(File(dir, "places.json"), ListSerializer(Place.serializer()), { emptyList() }))
    val events = EventLog(
        JsonFileStore(File(dir, "user_events.json"), ListSerializer(UserEvent.serializer()), { emptyList() }),
        JsonFileStore(File(dir, "activity.json"), ListSerializer(ActivityEntry.serializer()), { emptyList() }),
    )
    val suggestions = SuggestionRepository(JsonFileStore(File(dir, "suggestions.json"), SuggestionData.serializer(), { SuggestionData() }))
    val memory = MemoryRepository(JsonFileStore(File(dir, "memory.json"), ListSerializer(MemoryNote.serializer()), { emptyList() }), clock)
    val settings = SettingsRepository(JsonFileStore(File(dir, "settings.json"), AppSettings.serializer(), { AppSettings() }))
    val automation = AutomationStateStore(JsonFileStore(File(dir, "automation_state.json"), AutomationState.serializer(), { AutomationState() }))
}
