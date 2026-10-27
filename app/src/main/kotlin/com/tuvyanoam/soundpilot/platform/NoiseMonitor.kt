package com.tuvyanoam.soundpilot.platform

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import com.tuvyanoam.soundpilot.core.engine.NoiseMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Measures the room's loudness from a short microphone sample. Audio is reduced to a single number in memory;
 * nothing is recorded to storage or sent anywhere.
 */
class NoiseMonitor(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)

    /** Raw level in dBFS, or null when the microphone is unavailable (permission, a call, another app recording). */
    @SuppressLint("MissingPermission")
    suspend fun measureDbfs(): Double? = withContext(Dispatchers.IO) {
        if (!Permissions.hasMicrophone(context)) return@withContext null
        if (audio.mode != AudioManager.MODE_NORMAL) return@withContext null
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return@withContext null
        val frame = SAMPLE_RATE / 10
        val record = try {
            AudioRecord(source(), SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, frame * 4))
        } catch (e: Exception) {
            return@withContext null
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return@withContext null
        }
        try {
            record.startRecording()
            val buffer = ShortArray(frame)
            val levels = mutableListOf<Double>()
            repeat(FRAMES + WARMUP_FRAMES) { i ->
                val read = record.read(buffer, 0, frame)
                if (read > 0 && i >= WARMUP_FRAMES) levels += NoiseMath.dbfs(buffer, read)
            }
            // Pure digital silence means the system muted our capture, not that the room is silent.
            NoiseMath.robustLevel(levels)?.takeIf { it > MUTED_DBFS }
        } catch (e: Exception) {
            null
        } finally {
            try {
                record.stop()
            } catch (e: IllegalStateException) {
                // Never started.
            }
            record.release()
        }
    }

    /** Prefers a source without automatic gain control, which would flatten loud and quiet rooms alike. */
    private fun source(): Int {
        val unprocessed = audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        return if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val FRAMES = 15
        private const val WARMUP_FRAMES = 3
        private const val MUTED_DBFS = -100.0
    }
}
