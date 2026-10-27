package com.tuvyanoam.soundpilot.core.engine

import com.tuvyanoam.soundpilot.core.model.Action
import com.tuvyanoam.soundpilot.core.model.AudioCommand
import com.tuvyanoam.soundpilot.core.model.AudioState
import com.tuvyanoam.soundpilot.core.model.AudioStream
import com.tuvyanoam.soundpilot.core.model.Condition
import com.tuvyanoam.soundpilot.core.model.ContextSnapshot
import com.tuvyanoam.soundpilot.core.model.DndMode
import com.tuvyanoam.soundpilot.core.model.RingerMode
import com.tuvyanoam.soundpilot.core.model.Rule
import kotlinx.serialization.Serializable

/** Who currently controls one setting, and what it was before a rule took it over. */
@Serializable
data class ControlledSetting(
    val ruleId: String,
    val value: String,
    val restore: Boolean,
)

/** Persisted between evaluations so the engine acts only on changes and can put things back. */
@Serializable
data class EngineState(
    val activeRuleIds: List<String> = emptyList(),
    /** Setting key -> the rule controlling it now. */
    val controlled: Map<String, ControlledSetting> = emptyMap(),
    /** Setting key -> its value before any rule took it over. */
    val baselines: Map<String, String> = emptyMap(),
)

data class Evaluation(
    val state: EngineState,
    val commands: List<AudioCommand>,
    /** Adaptive volume forced on or off by an active rule, or null to follow the global setting. */
    val adaptiveOverride: Boolean?,
    val activated: List<Rule>,
    val deactivated: List<String>,
    val notifications: List<String>,
) {
    val changed: Boolean get() = commands.isNotEmpty() || activated.isNotEmpty() || deactivated.isNotEmpty()
}

/**
 * Decides what to change when the context changes.
 *
 * The engine is edge triggered: a setting is written only when the rule controlling it changes (or its value
 * does). This means a manual change the user makes while a rule is active is left alone until the rules change.
 */
object RuleEngine {

    fun matches(condition: Condition, context: ContextSnapshot): Boolean = when (condition) {
        is Condition.AtPlace -> condition.placeId in context.placeIds
        is Condition.TimeWindow -> TimeMath.inWindow(context.dayOfWeek, context.minuteOfDay, condition)
        is Condition.InCalendarEvent -> context.calendarEvents.any { event ->
            (!condition.busyOnly || event.busy) &&
                (condition.keyword.isNullOrBlank() || event.title.contains(condition.keyword.trim(), ignoreCase = true))
        }
        is Condition.WifiConnected -> context.wifiSsid != null &&
            normalizeSsid(context.wifiSsid).equals(normalizeSsid(condition.ssid), ignoreCase = true)
        is Condition.BluetoothConnected -> context.bluetoothDevices.any { it.equals(condition.deviceName.trim(), ignoreCase = true) }
        is Condition.Charging -> context.charging == condition.charging
        is Condition.Headphones -> context.headphones == condition.connected
        is Condition.Noise -> context.noiseDb != null &&
            (condition.minDb == null || context.noiseDb >= condition.minDb) &&
            (condition.maxDb == null || context.noiseDb <= condition.maxDb)
    }

    fun isActive(rule: Rule, context: ContextSnapshot): Boolean =
        rule.enabled && rule.conditions.isNotEmpty() && rule.conditions.all { matches(it, context) }

    fun evaluate(
        rules: List<Rule>,
        context: ContextSnapshot,
        state: EngineState,
        current: AudioState,
        forceReapply: Boolean = false,
    ): Evaluation {
        val active = rules.filter { isActive(it, context) }
        val activeIds = active.map { it.id }
        val effective = effectiveActions(active)

        val commands = mutableListOf<AudioCommand>()
        val controlled = mutableMapOf<String, ControlledSetting>()
        val baselines = state.baselines.toMutableMap()

        for ((key, pair) in effective) {
            val (action, rule) = pair
            val value = valueOf(action) ?: continue
            val previous = state.controlled[key]
            if (previous == null && key !in baselines) {
                currentValue(key, current)?.let { baselines[key] = it }
            }
            controlled[key] = ControlledSetting(rule.id, value, rule.restoreOnExit)
            val changed = previous == null || previous.ruleId != rule.id || previous.value != value
            if (forceReapply || changed) commandFor(action)?.let(commands::add)
        }

        for ((key, previous) in state.controlled) {
            if (key in effective) continue
            val baseline = baselines.remove(key)
            if (previous.restore && baseline != null) commandFor(key, baseline)?.let(commands::add)
        }
        // Baselines only matter while a rule controls the key.
        baselines.keys.retainAll(controlled.keys)

        val previousIds = state.activeRuleIds.toSet()
        val activated = active.filter { it.id !in previousIds }
        val deactivated = state.activeRuleIds.filter { it !in activeIds }
        val notifications = activated.flatMap { rule -> rule.actions.filterIsInstance<Action.Notify>().map { it.message } }
        val adaptive = (effective[ADAPTIVE]?.first as? Action.SetAdaptiveVolume)?.enabled

        return Evaluation(
            state = EngineState(activeIds, controlled, baselines),
            commands = orderCommands(commands),
            adaptiveOverride = adaptive,
            activated = activated,
            deactivated = deactivated,
            notifications = notifications,
        )
    }

    /** For every setting, the action that wins among the active rules, and the rule it came from. */
    fun effectiveActions(active: List<Rule>): Map<String, Pair<Action, Rule>> {
        val result = linkedMapOf<String, Pair<Action, Rule>>()
        active.sortedWith(
            compareByDescending<Rule> { it.priority }
                .thenByDescending { it.conditions.size }
                .thenByDescending { it.createdAt },
        ).forEach { rule ->
            rule.actions.forEach { action ->
                val key = keyOf(action) ?: return@forEach
                if (key !in result) result[key] = action to rule
            }
        }
        return result
    }

    private const val RINGER = "ringer"
    private const val DND = "dnd"
    private const val ADAPTIVE = "adaptive"
    private const val VOLUME_PREFIX = "volume:"

    fun keyOf(action: Action): String? = when (action) {
        is Action.SetRinger -> RINGER
        is Action.SetVolume -> VOLUME_PREFIX + action.stream.name
        is Action.SetDnd -> DND
        is Action.SetAdaptiveVolume -> ADAPTIVE
        is Action.Notify -> null
    }

    private fun valueOf(action: Action): String? = when (action) {
        is Action.SetRinger -> action.mode.name
        is Action.SetVolume -> action.percent.coerceIn(0, 100).toString()
        is Action.SetDnd -> action.mode.name
        is Action.SetAdaptiveVolume -> action.enabled.toString()
        is Action.Notify -> null
    }

    private fun currentValue(key: String, current: AudioState): String? = when {
        key == RINGER -> current.ringerMode.name
        key == DND -> current.dnd.name
        key.startsWith(VOLUME_PREFIX) -> current.volumes[streamOf(key)]?.toString()
        else -> null
    }

    private fun commandFor(action: Action): AudioCommand? = when (action) {
        is Action.SetRinger -> AudioCommand.Ringer(action.mode)
        is Action.SetVolume -> AudioCommand.Volume(action.stream, action.percent.coerceIn(0, 100))
        is Action.SetDnd -> AudioCommand.Dnd(action.mode)
        is Action.SetAdaptiveVolume, is Action.Notify -> null
    }

    private fun commandFor(key: String, value: String): AudioCommand? = when {
        key == RINGER -> runCatching { RingerMode.valueOf(value) }.getOrNull()?.let { AudioCommand.Ringer(it) }
        key == DND -> runCatching { DndMode.valueOf(value) }.getOrNull()?.let { AudioCommand.Dnd(it) }
        key.startsWith(VOLUME_PREFIX) -> {
            val stream = streamOf(key)
            val percent = value.toIntOrNull()
            if (stream != null && percent != null) AudioCommand.Volume(stream, percent) else null
        }
        else -> null
    }

    private fun streamOf(key: String): AudioStream? =
        runCatching { AudioStream.valueOf(key.removePrefix(VOLUME_PREFIX)) }.getOrNull()

    /**
     * Do-not-disturb first, then ringer mode, then volumes: leaving DND can reset the ringer, and setting a ring
     * volume above zero can switch a vibrating phone back to normal on some devices.
     */
    private fun orderCommands(commands: List<AudioCommand>): List<AudioCommand> = commands.sortedBy {
        when (it) {
            is AudioCommand.Dnd -> 0
            is AudioCommand.Ringer -> 1
            is AudioCommand.Volume -> 2
        }
    }

    fun normalizeSsid(ssid: String): String = ssid.trim().removeSurrounding("\"")
}
