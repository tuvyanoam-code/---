package com.tuvyanoam.soundpilot.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class RingerMode { NORMAL, VIBRATE, SILENT }

@Serializable
enum class AudioStream { RING, NOTIFICATION, MEDIA, ALARM }

/** Mirrors the Android interruption filters, in a platform independent form. */
@Serializable
enum class DndMode { OFF, PRIORITY, ALARMS, TOTAL_SILENCE }

/** A snapshot of the phone's sound settings. Volumes are percentages (0-100) per stream. */
@Serializable
data class AudioState(
    val ringerMode: RingerMode,
    val volumes: Map<AudioStream, Int>,
    val dnd: DndMode,
) {
    fun volume(stream: AudioStream): Int? = volumes[stream]
}

/** A single change to apply to the phone. */
@Serializable
sealed interface AudioCommand {
    @Serializable
    data class Ringer(val mode: RingerMode) : AudioCommand

    @Serializable
    data class Volume(val stream: AudioStream, val percent: Int) : AudioCommand

    @Serializable
    data class Dnd(val mode: DndMode) : AudioCommand
}

/** Commands that bring the phone back to [state]. */
fun AudioState.toCommands(): List<AudioCommand> = buildList {
    add(AudioCommand.Dnd(dnd))
    add(AudioCommand.Ringer(ringerMode))
    volumes.forEach { (stream, percent) -> add(AudioCommand.Volume(stream, percent)) }
}
