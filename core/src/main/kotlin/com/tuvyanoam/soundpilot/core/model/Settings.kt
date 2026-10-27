package com.tuvyanoam.soundpilot.core.model

import kotlinx.serialization.Serializable

@Serializable
data class AdaptiveVolumeConfig(
    val enabled: Boolean = false,
    /** Ring volume used in a quiet room. */
    val minPercent: Int = 35,
    /** Ring volume used in a loud place. */
    val maxPercent: Int = 100,
    /** At or below this level the minimum volume is used. */
    val quietDb: Double = 40.0,
    /** At or above this level the maximum volume is used. */
    val loudDb: Double = 75.0,
    val sampleIntervalSeconds: Int = 120,
    /** Added to the microphone's dBFS reading to estimate dB SPL. Phones differ, so this is user adjustable. */
    val calibrationOffsetDb: Double = 90.0,
    /** Also scale notification volume, not only the ringtone. */
    val includeNotifications: Boolean = true,
)

@Serializable
data class AiSettings(
    val model: String = DEFAULT_MODEL,
    /** Let Claude react on its own when the phone's context changes and no rule covers it. */
    val smartMode: Boolean = false,
    val smartModeMaxPerHour: Int = 4,
    /** Analyze the activity log once a day and suggest rules. */
    val dailyAnalysis: Boolean = true,
) {
    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        val AVAILABLE_MODELS = listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5")
    }
}

@Serializable
data class AppSettings(
    val automationEnabled: Boolean = true,
    val adaptive: AdaptiveVolumeConfig = AdaptiveVolumeConfig(),
    val ai: AiSettings = AiSettings(),
    /** Record manual changes so the app can learn routines. */
    val learningEnabled: Boolean = true,
    val onboardingDone: Boolean = false,
)
