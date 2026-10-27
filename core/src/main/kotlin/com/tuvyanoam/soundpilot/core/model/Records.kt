package com.tuvyanoam.soundpilot.core.model

import kotlinx.serialization.Serializable

/** A sound change the user made by hand. These are the raw material for learning. */
@Serializable
data class UserEvent(
    val epochMillis: Long,
    val kind: Kind,
    val ringerMode: RingerMode? = null,
    val stream: AudioStream? = null,
    val percent: Int? = null,
    val dnd: DndMode? = null,
    val context: ContextSnapshot,
) {
    @Serializable
    enum class Kind { RINGER, VOLUME, DND }
}

/** A human readable history line shown in the app. */
@Serializable
data class ActivityEntry(
    val epochMillis: Long,
    val type: Type,
    val text: String,
    val ruleId: String? = null,
) {
    @Serializable
    enum class Type { RULE_ON, RULE_OFF, USER_CHANGE, AI_ACTION, ADAPTIVE, TEMPORARY, ERROR }
}

/** Something the assistant remembers about the user between conversations. */
@Serializable
data class MemoryNote(
    val id: String,
    val text: String,
    val createdAt: Long,
)

/** A rule proposed by Claude or by the on-device pattern miner, waiting for the user's decision. */
@Serializable
data class Suggestion(
    val id: String,
    val rule: Rule,
    val rationale: String,
    val source: Source,
    val confidence: Double,
    val evidenceCount: Int,
    val createdAt: Long,
) {
    @Serializable
    enum class Source { ON_DEVICE, CLAUDE }
}
