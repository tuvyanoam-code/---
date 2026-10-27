package com.tuvyanoam.soundpilot.core.ai

import com.tuvyanoam.soundpilot.core.automation.ContextChangeListener
import com.tuvyanoam.soundpilot.core.automation.EvaluationReport
import com.tuvyanoam.soundpilot.core.platform.Clock
import com.tuvyanoam.soundpilot.core.platform.UserNotifier
import com.tuvyanoam.soundpilot.core.storage.Storage
import kotlinx.coroutines.sync.Mutex

/**
 * Lets Claude handle situations no rule covers: when the phone arrives somewhere, a meeting starts, the car
 * connects and so on. Opt-in and rate limited, because every run is a paid API call.
 */
class SmartModeRunner(
    private val agent: ClaudeAgent,
    private val storage: Storage,
    private val clock: Clock,
    private val notifier: UserNotifier,
) : ContextChangeListener {
    private val running = Mutex()

    override suspend fun onUnhandledContextChange(report: EvaluationReport) {
        val settings = storage.settings.get().ai
        if (!settings.smartMode || !agent.hasKey()) return
        if (!running.tryLock()) return
        try {
            val now = clock.now()
            val recent = storage.automation.get().smartRuns.filter { now - it < HOUR_MILLIS }
            if (recent.size >= settings.smartModeMaxPerHour.coerceAtLeast(1)) return
            if (recent.any { now - it < MIN_GAP_MILLIS }) return
            storage.automation.update { it.copy(smartRuns = recent + now) }

            val reply = agent.run(AgentMode.SMART, Conversation(), describe(report))
            val acted = reply.toolCalls.any { it.summary != null && !it.isError }
            if (reply.succeeded && acted) {
                val text = reply.text.takeUnless { it.isBlank() || it.contains(Prompts.NO_CHANGE) }
                    ?: reply.toolCalls.mapNotNull { it.summary }.joinToString(", ")
                notifier.aiAction(text)
            }
        } finally {
            running.unlock()
        }
    }

    private suspend fun describe(report: EvaluationReport): String {
        val places = storage.places.all().associate { it.id to it.name }
        val before = report.previousContext
        val after = report.context
        fun names(ids: Set<String>) = ids.map { places[it] ?: it }
        return buildString {
            appendLine("The phone's situation changed:")
            if (before == null) {
                appendLine("- (no earlier state known)")
            } else {
                val entered = after.placeIds - before.placeIds
                val left = before.placeIds - after.placeIds
                if (entered.isNotEmpty()) appendLine("- Arrived at: ${names(entered).joinToString()}")
                if (left.isNotEmpty()) appendLine("- Left: ${names(left).joinToString()}")
                if (after.wifiSsid != before.wifiSsid) appendLine("- Wi-Fi: ${before.wifiSsid ?: "none"} -> ${after.wifiSsid ?: "none"}")
                val btOn = after.bluetoothDevices - before.bluetoothDevices
                val btOff = before.bluetoothDevices - after.bluetoothDevices
                if (btOn.isNotEmpty()) appendLine("- Bluetooth connected: ${btOn.joinToString()}")
                if (btOff.isNotEmpty()) appendLine("- Bluetooth disconnected: ${btOff.joinToString()}")
                if (after.headphones != before.headphones) appendLine("- Headphones ${if (after.headphones) "connected" else "disconnected"}")
                if (after.inBusyEvent != before.inBusyEvent) {
                    val titles = after.calendarEvents.filter { it.busy }.joinToString { "'${it.title}'" }
                    appendLine(if (after.inBusyEvent) "- A calendar event started: $titles" else "- The calendar event ended")
                }
            }
            appendLine("Now: places=${names(after.placeIds)}, noise=${after.noiseDb?.toInt() ?: "unknown"} dB.")
            append("Check get_phone_status if you need more, then decide.")
        }
    }

    companion object {
        private const val HOUR_MILLIS = 60 * 60 * 1000L
        private const val MIN_GAP_MILLIS = 5 * 60 * 1000L
    }
}
