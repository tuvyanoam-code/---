package com.tuvyanoam.soundpilot.core.ai

import com.tuvyanoam.soundpilot.core.automation.AutomationCoordinator
import com.tuvyanoam.soundpilot.core.engine.RuleEngine
import com.tuvyanoam.soundpilot.core.model.AudioCommand
import com.tuvyanoam.soundpilot.core.model.AudioStream
import com.tuvyanoam.soundpilot.core.model.DndMode
import com.tuvyanoam.soundpilot.core.model.Place
import com.tuvyanoam.soundpilot.core.model.RingerMode
import com.tuvyanoam.soundpilot.core.model.Rule
import com.tuvyanoam.soundpilot.core.model.RuleOrigin
import com.tuvyanoam.soundpilot.core.model.Suggestion
import com.tuvyanoam.soundpilot.core.model.UserEvent
import com.tuvyanoam.soundpilot.core.platform.Clock
import com.tuvyanoam.soundpilot.core.platform.Phrases
import com.tuvyanoam.soundpilot.core.platform.PlatformHooks
import com.tuvyanoam.soundpilot.core.storage.Storage
import com.tuvyanoam.soundpilot.core.storage.newId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A function Claude can call. [properties] and [required] describe its input object. */
class AgentTool(
    val name: String,
    val description: String,
    val properties: Map<String, JsonObject>,
    val required: List<String>,
    val run: suspend (JsonObject) -> ToolResult,
)

/** [content] goes back to Claude; [summary], when set, is shown to the user. */
data class ToolResult(val content: String, val isError: Boolean = false, val summary: String? = null)

enum class AgentMode { CHAT, RULE_DRAFT, ANALYZE, SMART }

/** Things tools produce for the caller rather than for Claude. */
class RunCollector {
    var draft: Rule? = null
    var draftExplanation: String = ""
    val suggestions = mutableListOf<Suggestion>()
}

internal val ToolJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = false
    explicitNulls = false
}

/** Every tool the assistant has, and which of them each mode may use. */
class AutomationToolbox(
    private val storage: Storage,
    private val coordinator: AutomationCoordinator,
    private val platform: PlatformHooks,
    private val clock: Clock,
    private val phrases: Phrases,
) {
    fun toolsFor(mode: AgentMode, collector: RunCollector): List<AgentTool> = when (mode) {
        AgentMode.CHAT -> listOf(
            phoneStatus, listRules, listPlaces, activity,
            setRinger, setVolume, setDnd, cancelTemporary,
            createRule, updateRule, deleteRule, setRuleEnabled,
            savePlaceHere, createPlace, configureAdaptive, remember, forget,
        )
        AgentMode.RULE_DRAFT -> listOf(phoneStatus, listRules, listPlaces, draftRule(collector))
        AgentMode.ANALYZE -> listOf(phoneStatus, listRules, listPlaces, activity, suggestRule(collector), remember)
        AgentMode.SMART -> listOf(phoneStatus, listRules, listPlaces, setRinger, setVolume, setDnd)
    }

    private fun tool(
        name: String,
        description: String,
        properties: Map<String, JsonObject> = emptyMap(),
        required: List<String> = emptyList(),
        run: suspend (JsonObject) -> ToolResult,
    ) = AgentTool(name, description, properties, required) { input ->
        try {
            run(input)
        } catch (e: SerializationException) {
            ToolResult("Invalid input: ${e.message}", isError = true)
        } catch (e: IllegalArgumentException) {
            ToolResult("Invalid input: ${e.message}", isError = true)
        }
    }

    private fun ok(message: String, summary: String? = null) = ToolResult(message, summary = summary)
    private fun error(message: String) = ToolResult(message, isError = true)

    // ---- Reading ----

    private val phoneStatus = tool(
        "get_phone_status",
        "Current sound settings (ringer mode, volumes, Do Not Disturb), the phone's context (places it is in, Wi-Fi, " +
            "Bluetooth, charging, headphones, ambient noise, calendar), active rules, any temporary mode and missing permissions.",
    ) { ok(statusJson().toString()) }

    private val listRules = tool("list_rules", "All automation rules with their ids, conditions and actions.") {
        val rules = storage.rules.all()
        val json = buildJsonObject {
            putJsonArray("rules") {
                rules.forEach { rule ->
                    addJsonObject {
                        put("id", rule.id)
                        put("origin", rule.origin.name)
                        put("rule", ToolJson.encodeToJsonElement(RuleSpec.serializer(), RuleSpecs.fromRule(rule)))
                    }
                }
            }
        }
        ok(json.toString())
    }

    private val listPlaces = tool("list_places", "Saved places (geofences) with ids, coordinates, radius and whether the phone is there now.") {
        val here = storage.automation.get().lastContext?.placeIds.orEmpty()
        val places = storage.places.all()
        val json = buildJsonObject {
            putJsonArray("places") {
                places.forEach { place ->
                    addJsonObject {
                        put("id", place.id)
                        put("name", place.name)
                        put("latitude", place.latitude)
                        put("longitude", place.longitude)
                        put("radius_meters", place.radiusMeters)
                        put("phone_is_here", place.id in here)
                    }
                }
            }
        }
        ok(json.toString())
    }

    private val activity = tool(
        "get_activity_log",
        "Manual sound changes the user made, each with its context, plus recent automation history. Use it to find routines.",
        mapOf("days" to Schema.integer("How many days back to read (1-30). Default 14.", 1, 30)),
    ) { input ->
        val days = ToolJson.decodeFromJsonElement<ActivityInput>(input).days.coerceIn(1, 30)
        ok(activityText(days))
    }

    // ---- Device ----

    private val minutesSchema = Schema.integer(
        "Optional: keep this for N minutes and then restore the previous settings (rules pause meanwhile). Omit to keep it.",
        1, AutomationCoordinator.MAX_TEMPORARY_MINUTES,
    )

    private val setRinger = tool(
        "set_ringer_mode",
        "Switch the ringer to normal (sound), vibrate or silent now.",
        mapOf("mode" to Schema.string("Ringer mode", listOf("NORMAL", "VIBRATE", "SILENT")), "minutes" to minutesSchema),
        listOf("mode"),
    ) { input ->
        val args = ToolJson.decodeFromJsonElement<RingerInput>(input)
        val mode = enumArg<RingerMode>(args.mode, "mode")
        val summary = phrases.toolRinger(mode, args.minutes)
        applyDevice(listOf(AudioCommand.Ringer(mode)), args.minutes, summary)
    }

    private val setVolume = tool(
        "set_volume",
        "Set the volume of one audio stream, in percent.",
        mapOf(
            "stream" to Schema.string("Which volume", listOf("RING", "NOTIFICATION", "MEDIA", "ALARM")),
            "percent" to Schema.integer("Volume percent", 0, 100),
            "minutes" to minutesSchema,
        ),
        listOf("stream", "percent"),
    ) { input ->
        val args = ToolJson.decodeFromJsonElement<VolumeInput>(input)
        val stream = enumArg<AudioStream>(args.stream, "stream")
        require(args.percent in 0..100) { "percent must be 0-100" }
        applyDevice(listOf(AudioCommand.Volume(stream, args.percent)), args.minutes, phrases.toolVolume(stream, args.percent, args.minutes))
    }

    private val setDnd = tool(
        "set_do_not_disturb",
        "Change Do Not Disturb. PRIORITY follows the phone's own priority settings (often starred contacts and repeat " +
            "callers); ALARMS allows only alarms; TOTAL_SILENCE blocks everything; OFF turns it off.",
        mapOf("mode" to Schema.string("Mode", listOf("OFF", "PRIORITY", "ALARMS", "TOTAL_SILENCE")), "minutes" to minutesSchema),
        listOf("mode"),
    ) { input ->
        val args = ToolJson.decodeFromJsonElement<DndInput>(input)
        val mode = enumArg<DndMode>(args.mode, "mode")
        applyDevice(listOf(AudioCommand.Dnd(mode)), args.minutes, phrases.toolDnd(mode, args.minutes))
    }

    private val cancelTemporary = tool("cancel_temporary_mode", "End a running temporary mode now and restore the earlier settings.") {
        if (storage.automation.get().temporary == null) return@tool ok("No temporary mode is running.")
        coordinator.endTemporary()
        ok("Temporary mode ended; previous settings restored and rules resumed.", phrases.toolTemporaryCancelled())
    }

    private suspend fun applyDevice(commands: List<AudioCommand>, minutes: Int?, summary: String): ToolResult {
        val errors = if (minutes != null) {
            coordinator.startTemporary(commands, minutes, summary)
        } else {
            coordinator.applyNow(commands, summary, fromAi = true)
        }
        if (errors.isNotEmpty()) return error("Could not apply: ${errors.joinToString()}. Tell the user what to grant in Settings.")
        val after = coordinator.currentAudio()
        val note = if (minutes != null) " Previous settings return in $minutes minutes." else ""
        return ok("Done. Ringer=${after.ringerMode}, volumes=${after.volumes}, dnd=${after.dnd}.$note", summary)
    }

    // ---- Rules ----

    private val createRule = tool(
        "create_rule",
        "Create and enable an automation rule. Create the place first if the rule needs a new one.",
        mapOf("rule" to RuleSpecs.schema),
        listOf("rule"),
    ) { input ->
        val spec = ToolJson.decodeFromJsonElement<RuleInput>(input).rule
        val placeIds = storage.places.all().map { it.id }.toSet()
        val rule = RuleSpecs.toRule(spec, "", RuleOrigin.AI, placeIds).getOrElse { return@tool error(it.message ?: "invalid rule") }
        val saved = storage.rules.upsert(rule)
        coordinator.evaluate("rule_created")
        ok("Created rule id=${saved.id}. Active now: ${isActiveNow(saved)}.", phrases.toolRuleCreated(saved.name))
    }

    private val updateRule = tool(
        "update_rule",
        "Replace an existing rule's definition. Read it with list_rules first and send the full new version.",
        mapOf("rule_id" to Schema.string("Id of the rule"), "rule" to RuleSpecs.schema),
        listOf("rule_id", "rule"),
    ) { input ->
        val args = ToolJson.decodeFromJsonElement<UpdateRuleInput>(input)
        val existing = storage.rules.get(args.ruleId) ?: return@tool error("No rule with id ${args.ruleId}")
        val placeIds = storage.places.all().map { it.id }.toSet()
        val rule = RuleSpecs.toRule(args.rule, existing.id, existing.origin, placeIds, existing.createdAt)
            .getOrElse { return@tool error(it.message ?: "invalid rule") }
        storage.rules.upsert(rule)
        coordinator.evaluate("rule_updated")
        ok("Updated rule ${existing.id}.", phrases.toolRuleUpdated(rule.name))
    }

    private val deleteRule = tool(
        "delete_rule",
        "Delete a rule permanently. Only do this when the user asked for it.",
        mapOf("rule_id" to Schema.string("Id of the rule")),
        listOf("rule_id"),
    ) { input ->
        val id = ToolJson.decodeFromJsonElement<RuleIdInput>(input).ruleId
        val existing = storage.rules.get(id) ?: return@tool error("No rule with id $id")
        storage.rules.delete(id)
        coordinator.evaluate("rule_deleted")
        ok("Deleted rule $id.", phrases.toolRuleDeleted(existing.name))
    }

    private val setRuleEnabled = tool(
        "set_rule_enabled",
        "Turn a rule on or off without deleting it.",
        mapOf("rule_id" to Schema.string("Id of the rule"), "enabled" to Schema.boolean("true to enable")),
        listOf("rule_id", "enabled"),
    ) { input ->
        val args = ToolJson.decodeFromJsonElement<EnableInput>(input)
        val existing = storage.rules.get(args.ruleId) ?: return@tool error("No rule with id ${args.ruleId}")
        storage.rules.setEnabled(args.ruleId, args.enabled)
        coordinator.evaluate("rule_toggled")
        ok("Rule ${args.ruleId} enabled=${args.enabled}.", phrases.toolRuleToggled(existing.name, args.enabled))
    }

    // ---- Places ----

    private val radiusSchema = Schema.integer("Radius in meters. Default 150.", Place.MIN_RADIUS_METERS, Place.MAX_RADIUS_METERS)

    private val savePlaceHere = tool(
        "save_place_here",
        "Save the phone's current location as a named place, so rules can use it.",
        mapOf("name" to Schema.string("Place name, e.g. 'בית', 'עבודה', 'בית כנסת'"), "radius_meters" to radiusSchema),
        listOf("name"),
    ) { input ->
        val args = ToolJson.decodeFromJsonElement<PlaceHereInput>(input)
        val location = platform.currentLocation()
            ?: return@tool error("Current location is unavailable (location permission off or no fix). Ask the user to add the place in the Places screen.")
        val place = storage.places.upsert(
            Place("", args.name.trim(), location.latitude, location.longitude, args.radius ?: Place.DEFAULT_RADIUS_METERS),
        )
        platform.placesChanged()
        ok("Saved place id=${place.id} at ${location.latitude},${location.longitude} radius ${place.radiusMeters}m.", phrases.toolPlaceSaved(place.name))
    }

    private val createPlace = tool(
        "create_place",
        "Save a named place from coordinates the user gave you.",
        mapOf(
            "name" to Schema.string("Place name"),
            "latitude" to Schema.number("Latitude"),
            "longitude" to Schema.number("Longitude"),
            "radius_meters" to radiusSchema,
        ),
        listOf("name", "latitude", "longitude"),
    ) { input ->
        val args = ToolJson.decodeFromJsonElement<PlaceInput>(input)
        require(args.latitude in -90.0..90.0 && args.longitude in -180.0..180.0) { "coordinates out of range" }
        val place = storage.places.upsert(Place("", args.name.trim(), args.latitude, args.longitude, args.radius ?: Place.DEFAULT_RADIUS_METERS))
        platform.placesChanged()
        ok("Saved place id=${place.id}.", phrases.toolPlaceSaved(place.name))
    }

    // ---- Settings & memory ----

    private val configureAdaptive = tool(
        "configure_adaptive_volume",
        "Configure noise-adaptive ring volume: louder in noisy places, quieter in quiet ones. Omit fields to keep them.",
        mapOf(
            "enabled" to Schema.boolean("Turn the feature on or off"),
            "min_percent" to Schema.integer("Ring volume in a quiet place", 0, 100),
            "max_percent" to Schema.integer("Ring volume in a loud place", 0, 100),
            "quiet_db" to Schema.number("Noise level treated as quiet (default 40)"),
            "loud_db" to Schema.number("Noise level treated as loud (default 75)"),
        ),
    ) { input ->
        val args = ToolJson.decodeFromJsonElement<AdaptiveInput>(input)
        val updated = storage.settings.update { s ->
            val a = s.adaptive
            s.copy(
                adaptive = a.copy(
                    enabled = args.enabled ?: a.enabled,
                    minPercent = (args.minPercent ?: a.minPercent).coerceIn(0, 100),
                    maxPercent = (args.maxPercent ?: a.maxPercent).coerceIn(0, 100),
                    quietDb = args.quietDb ?: a.quietDb,
                    loudDb = args.loudDb ?: a.loudDb,
                ),
            )
        }
        coordinator.evaluate("adaptive_configured")
        val missing = platform.missingCapabilities()
        val warn = if (updated.adaptive.enabled && missing.isNotEmpty()) " Missing: ${missing.joinToString()}." else ""
        ok("Adaptive volume: ${updated.adaptive}.$warn", phrases.toolAdaptive(updated.adaptive.enabled))
    }

    private val remember = tool(
        "remember",
        "Save a lasting note about the user's preferences or routines (e.g. 'prays at the synagogue on Saturday mornings'). " +
            "Notes are given to you in every future conversation. Save only things that will matter later.",
        mapOf("note" to Schema.string("The note, one short sentence")),
        listOf("note"),
    ) { input ->
        val note = ToolJson.decodeFromJsonElement<NoteInput>(input).note
        require(note.isNotBlank()) { "note must not be empty" }
        val saved = storage.memory.add(note)
        ok("Saved note id=${saved.id}.", phrases.toolRemembered(saved.text))
    }

    private val forget = tool(
        "forget",
        "Delete a saved note by id (ids are listed with the notes in the system prompt).",
        mapOf("note_id" to Schema.string("Id of the note")),
        listOf("note_id"),
    ) { input ->
        val id = ToolJson.decodeFromJsonElement<NoteIdInput>(input).noteId
        if (storage.memory.delete(id)) ok("Deleted note $id.", phrases.toolForgot()) else error("No note with id $id")
    }

    // ---- Proposals ----

    private fun suggestRule(collector: RunCollector) = tool(
        "suggest_rule",
        "Propose a rule for the user to approve. Call once per suggestion. Nothing changes until the user accepts it.",
        mapOf(
            "rule" to RuleSpecs.schema,
            "rationale" to Schema.string("Why, in the user's language, citing the evidence (one or two sentences)."),
            "confidence" to Schema.number("How sure you are, 0.0-1.0"),
        ),
        listOf("rule", "rationale", "confidence"),
    ) { input ->
        val args = ToolJson.decodeFromJsonElement<SuggestInput>(input)
        val placeIds = storage.places.all().map { it.id }.toSet()
        val rule = RuleSpecs.toRule(args.rule, "", RuleOrigin.AI, placeIds).getOrElse { return@tool error(it.message ?: "invalid rule") }
        val suggestion = Suggestion(
            id = newId(),
            rule = rule,
            rationale = args.rationale.trim().take(400),
            source = Suggestion.Source.CLAUDE,
            confidence = args.confidence.coerceIn(0.0, 1.0),
            evidenceCount = 0,
            createdAt = clock.now(),
        )
        if (!storage.suggestions.add(suggestion, storage.rules.all())) {
            return@tool ok("Skipped: an identical rule or suggestion already exists, or the user dismissed it before.")
        }
        collector.suggestions += suggestion
        ok("Suggestion recorded.", phrases.toolSuggested(rule.name))
    }

    private fun draftRule(collector: RunCollector) = tool(
        "draft_rule",
        "Return the rule you built from the user's description. It is shown to the user for review, not saved yet.",
        mapOf(
            "rule" to RuleSpecs.schema,
            "explanation" to Schema.string("One sentence in the user's language describing what the rule does."),
        ),
        listOf("rule"),
    ) { input ->
        val args = ToolJson.decodeFromJsonElement<DraftInput>(input)
        val placeIds = storage.places.all().map { it.id }.toSet()
        val rule = RuleSpecs.toRule(args.rule, "", RuleOrigin.AI, placeIds).getOrElse { return@tool error(it.message ?: "invalid rule") }
        collector.draft = rule
        collector.draftExplanation = args.explanation
        ok("Draft recorded. Reply with a one-line summary.", phrases.toolDrafted(rule.name))
    }

    // ---- Formatting ----

    private suspend fun isActiveNow(rule: Rule): Boolean {
        val context = storage.automation.get().lastContext ?: return false
        return RuleEngine.isActive(rule, context)
    }

    private val dayFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd EEE HH:mm", Locale.US)
    private val shortFormat = DateTimeFormatter.ofPattern("MM-dd EEE HH:mm", Locale.US)

    private fun formatTime(millis: Long, formatter: DateTimeFormatter = dayFormat): String =
        Instant.ofEpochMilli(millis).atZone(clock.zone()).format(formatter)

    suspend fun statusJson(): JsonObject {
        val audio = coordinator.currentAudio()
        val state = storage.automation.get()
        val settings = storage.settings.get()
        val places = storage.places.all().associate { it.id to it.name }
        val context = state.lastContext
        val rules = storage.rules.all()
        return buildJsonObject {
            put("local_time", formatTime(clock.now()))
            put("ringer_mode", audio.ringerMode.name)
            putJsonObject("volumes_percent") { audio.volumes.forEach { (k, v) -> put(k.name, v) } }
            put("do_not_disturb", audio.dnd.name)
            put("automation_enabled", settings.automationEnabled)
            if (context != null) {
                putJsonObject("context") {
                    putJsonArray("in_places") { context.placeIds.forEach { id -> add(places[id] ?: id) } }
                    context.wifiSsid?.let { put("wifi", it) }
                    putJsonArray("bluetooth") { context.bluetoothDevices.forEach { add(it) } }
                    put("charging", context.charging)
                    put("headphones", context.headphones)
                    context.noiseDb?.let { put("noise_db", it.toInt()) }
                    putJsonArray("calendar_now") {
                        context.calendarEvents.forEach { e -> addJsonObject { put("title", e.title); put("busy", e.busy) } }
                    }
                }
            }
            putJsonArray("active_rules") {
                rules.filter { it.id in state.engine.activeRuleIds }.forEach { add(it.name) }
            }
            putJsonObject("adaptive_volume") {
                put("enabled", settings.adaptive.enabled)
                put("running_now", coordinator.adaptiveActive.value)
                put("min_percent", settings.adaptive.minPercent)
                put("max_percent", settings.adaptive.maxPercent)
            }
            state.temporary?.let { t ->
                putJsonObject("temporary_mode") {
                    put("description", t.description)
                    put("until", formatTime(t.untilMillis))
                }
            }
            putJsonArray("missing_permissions") { platform.missingCapabilities().forEach { add(it) } }
        }
    }

    suspend fun activityText(days: Int, maxEvents: Int = 300): String {
        val since = clock.now() - days * 24L * 60 * 60 * 1000
        val places = storage.places.all().associate { it.id to it.name }
        val events = storage.events.userEventsSince(since).takeLast(maxEvents)
        val history = storage.events.recentActivity(40)
        return buildString {
            appendLine("Manual changes in the last $days days (${events.size}):")
            if (events.isEmpty()) appendLine("(none yet)")
            events.forEach { e ->
                val what = when (e.kind) {
                    UserEvent.Kind.RINGER -> "RINGER→${e.ringerMode}"
                    UserEvent.Kind.VOLUME -> "VOLUME ${e.stream}=${e.percent}%"
                    UserEvent.Kind.DND -> "DND→${e.dnd}"
                }
                val c = e.context
                val placeText = c.placeIds.joinToString(",") { id -> "${places[id] ?: "?"}($id)" }.ifEmpty { "-" }
                val calendar = c.calendarEvents.filter { it.busy }.joinToString(",") { "'${it.title.take(30)}'" }.ifEmpty { "-" }
                append(formatTime(e.epochMillis, shortFormat)).append(" | ").append(what)
                append(" | place=").append(placeText)
                append(" wifi=").append(c.wifiSsid ?: "-")
                append(" bt=").append(c.bluetoothDevices.joinToString(",").ifEmpty { "-" })
                append(" charging=").append(if (c.charging) "Y" else "N")
                append(" headphones=").append(if (c.headphones) "Y" else "N")
                append(" noise=").append(c.noiseDb?.toInt()?.toString() ?: "-")
                append(" busy_event=").appendLine(calendar)
            }
            appendLine()
            appendLine("Recent automation history:")
            history.forEach { appendLine("${formatTime(it.epochMillis, shortFormat)} ${it.type}: ${it.text}") }
        }
    }

    fun memoryText(notes: List<com.tuvyanoam.soundpilot.core.model.MemoryNote>): String =
        if (notes.isEmpty()) "(no notes yet)" else notes.joinToString("\n") { "- [${it.id}] ${it.text}" }

    fun nowText(): String = formatTime(clock.now()) + " (" + clock.zone().id + ")"

    private inline fun <reified E : Enum<E>> enumArg(value: String, field: String): E =
        enumValues<E>().firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
            ?: throw IllegalArgumentException("$field must be one of ${enumValues<E>().joinToString { it.name }}")
}

@Serializable private data class ActivityInput(val days: Int = 14)
@Serializable private data class RingerInput(val mode: String, val minutes: Int? = null)
@Serializable private data class VolumeInput(val stream: String, val percent: Int, val minutes: Int? = null)
@Serializable private data class DndInput(val mode: String, val minutes: Int? = null)
@Serializable private data class RuleInput(val rule: RuleSpec)
@Serializable private data class UpdateRuleInput(@SerialName("rule_id") val ruleId: String, val rule: RuleSpec)
@Serializable private data class RuleIdInput(@SerialName("rule_id") val ruleId: String)
@Serializable private data class EnableInput(@SerialName("rule_id") val ruleId: String, val enabled: Boolean)
@Serializable private data class PlaceHereInput(val name: String, @SerialName("radius_meters") val radius: Int? = null)

@Serializable
private data class PlaceInput(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    @SerialName("radius_meters") val radius: Int? = null,
)

@Serializable
private data class AdaptiveInput(
    val enabled: Boolean? = null,
    @SerialName("min_percent") val minPercent: Int? = null,
    @SerialName("max_percent") val maxPercent: Int? = null,
    @SerialName("quiet_db") val quietDb: Double? = null,
    @SerialName("loud_db") val loudDb: Double? = null,
)

@Serializable private data class NoteInput(val note: String)
@Serializable private data class NoteIdInput(@SerialName("note_id") val noteId: String)
@Serializable private data class SuggestInput(val rule: RuleSpec, val rationale: String, val confidence: Double = 0.7)
@Serializable private data class DraftInput(val rule: RuleSpec, val explanation: String = "")
