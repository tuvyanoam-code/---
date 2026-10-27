package com.tuvyanoam.soundpilot.core.automation

import com.tuvyanoam.soundpilot.core.engine.AdaptiveVolumeTracker
import com.tuvyanoam.soundpilot.core.engine.RuleEngine
import com.tuvyanoam.soundpilot.core.engine.TimeMath
import com.tuvyanoam.soundpilot.core.model.ActivityEntry
import com.tuvyanoam.soundpilot.core.model.AudioCommand
import com.tuvyanoam.soundpilot.core.model.AudioState
import com.tuvyanoam.soundpilot.core.model.AudioStream
import com.tuvyanoam.soundpilot.core.model.Condition
import com.tuvyanoam.soundpilot.core.model.ContextSnapshot
import com.tuvyanoam.soundpilot.core.model.DndMode
import com.tuvyanoam.soundpilot.core.model.RingerMode
import com.tuvyanoam.soundpilot.core.model.Rule
import com.tuvyanoam.soundpilot.core.model.UserEvent
import com.tuvyanoam.soundpilot.core.model.toCommands
import com.tuvyanoam.soundpilot.core.platform.AudioGateway
import com.tuvyanoam.soundpilot.core.platform.Clock
import com.tuvyanoam.soundpilot.core.platform.ContextGateway
import com.tuvyanoam.soundpilot.core.platform.Phrases
import com.tuvyanoam.soundpilot.core.platform.UserNotifier
import com.tuvyanoam.soundpilot.core.platform.WakeUpScheduler
import com.tuvyanoam.soundpilot.core.storage.Storage
import com.tuvyanoam.soundpilot.core.storage.TemporaryOverride
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What one evaluation saw and did. */
data class EvaluationReport(
    val reason: String,
    val context: ContextSnapshot,
    val previousContext: ContextSnapshot?,
    val activeRules: List<Rule>,
    val commandsApplied: List<AudioCommand>,
    val errors: List<String>,
    val temporaryActive: Boolean,
) {
    val contextChanged: Boolean get() = context.differsMeaningfullyFrom(previousContext)
}

/** Receives evaluations that changed the context without any rule reacting, e.g. to let Claude decide. */
fun interface ContextChangeListener {
    suspend fun onUnhandledContextChange(report: EvaluationReport)
}

/**
 * The heart of the automation: gathers context, runs the rule engine, applies the result, handles temporary
 * overrides and noise-adaptive volume, and records what happened. All entry points are serialized.
 */
class AutomationCoordinator(
    private val storage: Storage,
    private val audio: AudioGateway,
    private val context: ContextGateway,
    private val scheduler: WakeUpScheduler,
    private val notifier: UserNotifier,
    private val clock: Clock,
    private val phrases: Phrases,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val adaptiveTracker = AdaptiveVolumeTracker()

    @Volatile
    var contextChangeListener: ContextChangeListener? = null

    private val _adaptiveActive = MutableStateFlow(false)

    /** Whether noise-adaptive ring volume should currently run. The microphone sampler follows this. */
    val adaptiveActive: StateFlow<Boolean> = _adaptiveActive.asStateFlow()

    private val _needsNoise = MutableStateFlow(false)

    /** True when an enabled rule has a noise condition, so noise must be sampled even without adaptive volume. */
    val needsNoise: StateFlow<Boolean> = _needsNoise.asStateFlow()

    private val _lastReport = MutableStateFlow<EvaluationReport?>(null)
    val lastReport: StateFlow<EvaluationReport?> = _lastReport.asStateFlow()

    suspend fun evaluate(reason: String, forceReapply: Boolean = false): EvaluationReport? {
        val report = mutex.withLock { evaluateLocked(reason, forceReapply) } ?: return null
        val listener = contextChangeListener
        if (listener != null && report.contextChanged && report.commandsApplied.isEmpty() && !report.temporaryActive &&
            report.reason != REASON_STARTUP
        ) {
            scope.launch { runCatching { listener.onUnhandledContextChange(report) } }
        }
        return report
    }

    private suspend fun evaluateLocked(reason: String, forceReapplyRequested: Boolean): EvaluationReport? {
        val settings = storage.settings.get()
        val rules = storage.rules.all()
        _needsNoise.value = rules.any { rule -> rule.enabled && rule.conditions.any { it is Condition.Noise } }
        if (!settings.automationEnabled) {
            scheduler.cancel(TAG_EVALUATE)
            _adaptiveActive.value = false
            return null
        }

        var forceReapply = forceReapplyRequested
        var state = storage.automation.get()
        val now = clock.now()
        val errors = mutableListOf<String>()

        val temporary = state.temporary
        if (temporary != null && now >= temporary.untilMillis) {
            errors += applyAll(temporary.previous.toCommands())
            state = storage.automation.update { it.copy(temporary = null) }
            scheduler.cancel(TAG_TEMPORARY)
            log(ActivityEntry.Type.TEMPORARY, phrases.temporaryEnd())
            forceReapply = true
        }
        val activeTemporary = state.temporary

        val snapshot = context.snapshot()
        // While a temporary mode runs, rules see the settings it will return to, not the temporary ones.
        val current = activeTemporary?.previous ?: audio.read()
        val evaluation = RuleEngine.evaluate(rules, snapshot, state.engine, current, forceReapply)

        val applied = if (activeTemporary == null) evaluation.commands else emptyList()
        errors += applyAll(applied)

        val namesById = rules.associate { it.id to it.name }
        evaluation.activated.forEach { log(ActivityEntry.Type.RULE_ON, phrases.ruleOn(it.name), it.id) }
        evaluation.deactivated.forEach { id ->
            namesById[id]?.let { log(ActivityEntry.Type.RULE_OFF, phrases.ruleOff(it), id) }
        }
        errors.forEach { log(ActivityEntry.Type.ERROR, phrases.applyFailed(it)) }
        if (activeTemporary == null) evaluation.notifications.forEach(notifier::ruleMessage)

        val previousContext = state.lastContext
        storage.automation.update {
            it.copy(engine = evaluation.state, lastContext = snapshot, adaptiveOverride = evaluation.adaptiveOverride)
        }

        val ringControlledByRule = evaluation.state.controlled.containsKey("volume:" + AudioStream.RING.name)
        val adaptiveWanted = evaluation.adaptiveOverride ?: settings.adaptive.enabled
        _adaptiveActive.value = adaptiveWanted && !ringControlledByRule && activeTemporary == null
        if (!_adaptiveActive.value) adaptiveTracker.reset()

        scheduleNextWakeUp(rules, snapshot, activeTemporary)

        val report = EvaluationReport(
            reason = reason,
            context = snapshot,
            previousContext = previousContext,
            activeRules = rules.filter { it.id in evaluation.state.activeRuleIds },
            commandsApplied = applied,
            errors = errors,
            temporaryActive = activeTemporary != null,
        )
        _lastReport.value = report
        return report
    }

    private suspend fun scheduleNextWakeUp(rules: List<Rule>, snapshot: ContextSnapshot, temporary: TemporaryOverride?) {
        val now = clock.now()
        val candidates = mutableListOf<Long>()
        TimeMath.minutesUntilNextBoundary(rules, snapshot.dayOfWeek, snapshot.minuteOfDay)?.let { minutes ->
            val startOfMinute = now - now % MINUTE_MILLIS
            candidates += startOfMinute + minutes * MINUTE_MILLIS + BOUNDARY_SLACK_MILLIS
        }
        if (rules.any { r -> r.enabled && r.conditions.any { it is Condition.InCalendarEvent } }) {
            context.nextCalendarChangeMillis()?.let { candidates += it + BOUNDARY_SLACK_MILLIS }
        }
        temporary?.let { candidates += it.untilMillis }
        val next = candidates.filter { it > now }.minOrNull()
        if (next != null) scheduler.schedule(TAG_EVALUATE, next) else scheduler.cancel(TAG_EVALUATE)
    }

    /** Applies changes requested directly by the user or by Claude, outside of any rule. */
    suspend fun applyNow(commands: List<AudioCommand>, description: String, fromAi: Boolean): List<String> = mutex.withLock {
        val errors = applyAll(commands)
        if (fromAi) {
            log(ActivityEntry.Type.AI_ACTION, phrases.aiAction(description))
        }
        errors.forEach { log(ActivityEntry.Type.ERROR, phrases.applyFailed(it)) }
        errors
    }

    /**
     * Applies [commands] for [minutes], then restores the settings from before. Rules are paused meanwhile.
     * Starting a new temporary mode while one runs keeps the original settings to return to.
     */
    suspend fun startTemporary(commands: List<AudioCommand>, minutes: Int, description: String): List<String> {
        val errors = mutex.withLock {
            val now = clock.now()
            val state = storage.automation.get()
            val previous = state.temporary?.previous ?: audio.read()
            val result = applyAll(commands)
            val until = now + minutes.coerceIn(1, MAX_TEMPORARY_MINUTES) * MINUTE_MILLIS
            storage.automation.update { it.copy(temporary = TemporaryOverride(until, previous, description)) }
            scheduler.schedule(TAG_TEMPORARY, until)
            _adaptiveActive.value = false
            log(ActivityEntry.Type.TEMPORARY, phrases.temporaryStart(description, minutes))
            result
        }
        return errors
    }

    suspend fun endTemporary() {
        mutex.withLock {
            val temporary = storage.automation.get().temporary ?: return@withLock
            applyAll(temporary.previous.toCommands())
            storage.automation.update { it.copy(temporary = null) }
            scheduler.cancel(TAG_TEMPORARY)
            log(ActivityEntry.Type.TEMPORARY, phrases.temporaryEnd())
        }
        evaluate(REASON_TEMPORARY_END, forceReapply = true)
    }

    /** Feeds one ambient noise reading. Adjusts ring volume when adaptive volume is active. */
    suspend fun onNoiseSample(noiseDb: Double) {
        if (_needsNoise.value) evaluate(REASON_NOISE)
        if (!_adaptiveActive.value) return
        mutex.withLock {
            val settings = storage.settings.get()
            val current = audio.read()
            if (current.ringerMode != RingerMode.NORMAL || current.dnd != DndMode.OFF) return@withLock
            val ring = current.volume(AudioStream.RING) ?: return@withLock
            val target = adaptiveTracker.onSample(noiseDb, settings.adaptive, ring) ?: return@withLock
            val commands = buildList {
                add(AudioCommand.Volume(AudioStream.RING, target))
                if (settings.adaptive.includeNotifications) add(AudioCommand.Volume(AudioStream.NOTIFICATION, target))
            }
            val errors = applyAll(commands)
            if (errors.isEmpty()) {
                log(ActivityEntry.Type.ADAPTIVE, phrases.adaptiveChange(target, noiseDb.toInt()))
            }
        }
    }

    /** Records a sound change the user made by hand, for the activity log and for learning routines. */
    suspend fun recordUserChange(
        kind: UserEvent.Kind,
        ringerMode: RingerMode? = null,
        stream: AudioStream? = null,
        percent: Int? = null,
        dnd: DndMode? = null,
    ) {
        val settings = storage.settings.get()
        val text = when (kind) {
            UserEvent.Kind.RINGER -> ringerMode?.let(phrases::userRinger)
            UserEvent.Kind.VOLUME -> if (stream != null && percent != null) phrases.userVolume(stream, percent) else null
            UserEvent.Kind.DND -> dnd?.let(phrases::userDnd)
        } ?: return
        log(ActivityEntry.Type.USER_CHANGE, text)
        if (!settings.learningEnabled) return
        val snapshot = runCatching { context.snapshot() }.getOrNull() ?: return
        storage.events.recordUserEvent(UserEvent(clock.now(), kind, ringerMode, stream, percent, dnd, snapshot))
    }

    suspend fun currentAudio(): AudioState = audio.read()

    private suspend fun applyAll(commands: List<AudioCommand>): List<String> = commands.mapNotNull { audio.apply(it) }.distinct()

    private suspend fun log(type: ActivityEntry.Type, text: String, ruleId: String? = null) {
        storage.events.addActivity(ActivityEntry(clock.now(), type, text, ruleId))
    }

    companion object {
        const val TAG_EVALUATE = "evaluate"
        const val TAG_TEMPORARY = "temporary"
        const val REASON_STARTUP = "startup"
        const val REASON_NOISE = "noise"
        const val REASON_TEMPORARY_END = "temporary_end"
        const val MAX_TEMPORARY_MINUTES = 24 * 60
        private const val MINUTE_MILLIS = 60_000L
        private const val BOUNDARY_SLACK_MILLIS = 2_000L
    }
}
