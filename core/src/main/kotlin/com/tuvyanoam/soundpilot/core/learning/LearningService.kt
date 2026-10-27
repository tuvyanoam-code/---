package com.tuvyanoam.soundpilot.core.learning

import com.tuvyanoam.soundpilot.core.ai.AgentMode
import com.tuvyanoam.soundpilot.core.ai.AgentReply
import com.tuvyanoam.soundpilot.core.ai.ClaudeAgent
import com.tuvyanoam.soundpilot.core.ai.Conversation
import com.tuvyanoam.soundpilot.core.ai.RunCollector
import com.tuvyanoam.soundpilot.core.automation.AutomationCoordinator
import com.tuvyanoam.soundpilot.core.model.Rule
import com.tuvyanoam.soundpilot.core.model.Suggestion
import com.tuvyanoam.soundpilot.core.platform.Clock
import com.tuvyanoam.soundpilot.core.platform.Phrases
import com.tuvyanoam.soundpilot.core.platform.UserNotifier
import com.tuvyanoam.soundpilot.core.storage.Storage

/** Turns the activity log into rule suggestions, on the device and (optionally) with Claude. */
class LearningService(
    private val storage: Storage,
    private val coordinator: AutomationCoordinator,
    private val agent: ClaudeAgent,
    private val clock: Clock,
    private val phrases: Phrases,
    private val notifier: UserNotifier,
    private val miner: PatternMiner = PatternMiner(),
) {
    /** Free and private: runs the pattern miner over the log. Returns the suggestions now pending. */
    suspend fun runOnDevice(): List<Suggestion> {
        val found = miner.mine(
            events = storage.events.allUserEvents(),
            places = storage.places.all(),
            now = clock.now(),
            zone = clock.zone(),
            phrases = phrases,
        )
        storage.suggestions.replaceOnDevice(found, storage.rules.all())
        return storage.suggestions.all()
    }

    /** Asks Claude to look for routines. Suggestions it makes are stored as they come in. */
    suspend fun runClaudeAnalysis(): Pair<AgentReply, List<Suggestion>> {
        val collector = RunCollector()
        val reply = agent.run(
            AgentMode.ANALYZE,
            Conversation(),
            "Analyze my activity log from the last 30 days and suggest automation rules for my routines.",
            collector,
        )
        if (reply.succeeded) storage.automation.update { it.copy(lastAnalysisMillis = clock.now()) }
        return reply to collector.suggestions.toList()
    }

    /** The daily job: on-device mining always, Claude when enabled and a key is set. Notifies about new ideas. */
    suspend fun daily() {
        val settings = storage.settings.get()
        if (!settings.learningEnabled) return
        val before = storage.suggestions.all().map { it.id }.toSet()
        runOnDevice()
        val lastAnalysis = storage.automation.get().lastAnalysisMillis
        if (settings.ai.dailyAnalysis && agent.hasKey() && clock.now() - lastAnalysis > MIN_ANALYSIS_GAP_MILLIS) {
            val newEvents = storage.events.userEventsSince(lastAnalysis).size
            // Only spend an API call when there is something new to learn from.
            if (newEvents >= MIN_NEW_EVENTS_FOR_ANALYSIS) runClaudeAnalysis()
        }
        val fresh = storage.suggestions.all().count { it.id !in before }
        if (fresh > 0) notifier.suggestionsAvailable(fresh)
    }

    suspend fun accept(suggestionId: String): Rule? {
        val suggestion = storage.suggestions.remove(suggestionId) ?: return null
        val saved = storage.rules.upsert(suggestion.rule.copy(id = "", createdAt = 0))
        coordinator.evaluate("suggestion_accepted")
        return saved
    }

    suspend fun dismiss(suggestionId: String) = storage.suggestions.dismiss(suggestionId)

    companion object {
        private const val MIN_ANALYSIS_GAP_MILLIS = 20 * 60 * 60 * 1000L
        private const val MIN_NEW_EVENTS_FOR_ANALYSIS = 3
    }
}
