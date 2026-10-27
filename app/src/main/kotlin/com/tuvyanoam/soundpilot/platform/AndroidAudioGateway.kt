package com.tuvyanoam.soundpilot.platform

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import com.tuvyanoam.soundpilot.R
import com.tuvyanoam.soundpilot.core.model.AudioCommand
import com.tuvyanoam.soundpilot.core.model.AudioState
import com.tuvyanoam.soundpilot.core.model.AudioStream
import com.tuvyanoam.soundpilot.core.model.DndMode
import com.tuvyanoam.soundpilot.core.model.RingerMode
import com.tuvyanoam.soundpilot.core.platform.AudioGateway
import kotlin.math.roundToInt

/** Reads and writes ringer mode, stream volumes and Do Not Disturb through the Android system services. */
class AndroidAudioGateway(private val context: Context) : AudioGateway {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)

    @Volatile
    private var lastOwnChange = 0L

    /**
     * True for a few seconds after this app changed a setting. The system broadcasts our own changes too, and
     * they must not be mistaken for the user's when learning routines.
     */
    fun isOwnChange(): Boolean = SystemClock.elapsedRealtime() - lastOwnChange < OWN_CHANGE_WINDOW_MS

    private fun markOwnChange() {
        lastOwnChange = SystemClock.elapsedRealtime()
    }

    override suspend fun read(): AudioState = AudioState(
        ringerMode = ringerMode(),
        volumes = AudioStream.entries.associateWith { percent(it) },
        dnd = dnd(),
    )

    fun ringerMode(): RingerMode = when (audio.ringerMode) {
        AudioManager.RINGER_MODE_SILENT -> RingerMode.SILENT
        AudioManager.RINGER_MODE_VIBRATE -> RingerMode.VIBRATE
        else -> RingerMode.NORMAL
    }

    fun dnd(): DndMode = when (notifications.currentInterruptionFilter) {
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> DndMode.PRIORITY
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> DndMode.ALARMS
        NotificationManager.INTERRUPTION_FILTER_NONE -> DndMode.TOTAL_SILENCE
        else -> DndMode.OFF
    }

    fun percent(stream: AudioStream): Int {
        val id = streamId(stream)
        val max = audio.getStreamMaxVolume(id)
        if (max <= 0) return 0
        return (audio.getStreamVolume(id) * 100f / max).roundToInt().coerceIn(0, 100)
    }

    fun levels(stream: AudioStream): Int = audio.getStreamMaxVolume(streamId(stream))

    override suspend fun apply(command: AudioCommand): String? {
        val policyAccess = notifications.isNotificationPolicyAccessGranted
        return try {
            when (command) {
                is AudioCommand.Ringer -> {
                    if (command.mode == RingerMode.SILENT && !policyAccess) return missingAccess()
                    if (ringerMode() == command.mode) return null
                    markOwnChange()
                    audio.ringerMode = when (command.mode) {
                        RingerMode.NORMAL -> AudioManager.RINGER_MODE_NORMAL
                        RingerMode.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
                        RingerMode.SILENT -> AudioManager.RINGER_MODE_SILENT
                    }
                }
                is AudioCommand.Volume -> setVolume(command.stream, command.percent)
                is AudioCommand.Dnd -> {
                    if (!policyAccess) return missingAccess()
                    if (dnd() == command.mode) return null
                    markOwnChange()
                    notifications.setInterruptionFilter(
                        when (command.mode) {
                            DndMode.OFF -> NotificationManager.INTERRUPTION_FILTER_ALL
                            DndMode.PRIORITY -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
                            DndMode.ALARMS -> NotificationManager.INTERRUPTION_FILTER_ALARMS
                            DndMode.TOTAL_SILENCE -> NotificationManager.INTERRUPTION_FILTER_NONE
                        },
                    )
                }
            }
            null
        } catch (e: SecurityException) {
            missingAccess()
        }
    }

    private fun setVolume(stream: AudioStream, percent: Int) {
        // Raising the ring or notification volume while vibrating or silent switches many phones back to
        // sound, overriding the ringer mode a rule just chose. Leave those alone until the phone rings normally.
        if ((stream == AudioStream.RING || stream == AudioStream.NOTIFICATION) && ringerMode() != RingerMode.NORMAL) return
        val id = streamId(stream)
        val max = audio.getStreamMaxVolume(id)
        val min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) audio.getStreamMinVolume(id) else 0
        var index = (percent.coerceIn(0, 100) / 100.0 * max).roundToInt().coerceIn(min, max)
        // A ringtone at level 0 means vibrate mode; a non-zero request keeps at least one step.
        if (percent > 0 && index == 0) index = 1.coerceAtMost(max)
        if (audio.getStreamVolume(id) == index) return
        markOwnChange()
        audio.setStreamVolume(id, index, 0)
    }

    private fun missingAccess(): String = context.getString(R.string.error_dnd_access)

    companion object {
        private const val OWN_CHANGE_WINDOW_MS = 4_000L

        fun streamId(stream: AudioStream): Int = when (stream) {
            AudioStream.RING -> AudioManager.STREAM_RING
            AudioStream.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
            AudioStream.MEDIA -> AudioManager.STREAM_MUSIC
            AudioStream.ALARM -> AudioManager.STREAM_ALARM
        }

        fun streamOf(streamId: Int): AudioStream? = when (streamId) {
            AudioManager.STREAM_RING -> AudioStream.RING
            AudioManager.STREAM_NOTIFICATION -> AudioStream.NOTIFICATION
            AudioManager.STREAM_MUSIC -> AudioStream.MEDIA
            AudioManager.STREAM_ALARM -> AudioStream.ALARM
            else -> null
        }
    }
}
