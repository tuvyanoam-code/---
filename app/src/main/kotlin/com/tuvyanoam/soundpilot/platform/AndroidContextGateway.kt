package com.tuvyanoam.soundpilot.platform

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.tuvyanoam.soundpilot.core.engine.RuleEngine
import com.tuvyanoam.soundpilot.core.model.CalendarEventInfo
import com.tuvyanoam.soundpilot.core.model.ContextSnapshot
import com.tuvyanoam.soundpilot.core.platform.Clock
import com.tuvyanoam.soundpilot.core.platform.ContextGateway
import com.tuvyanoam.soundpilot.core.storage.JsonFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.time.Instant

/** Signals pushed by the automation service's listeners, kept in memory. */
class DeviceSignals {
    @Volatile
    var wifiSsid: String? = null

    @Volatile
    var aclDevices: Set<String> = emptySet()

    private val _noise = MutableStateFlow<NoiseReading?>(null)
    val noise: StateFlow<NoiseReading?> = _noise.asStateFlow()

    fun reportNoise(db: Double, atMillis: Long) {
        _noise.value = NoiseReading(db, atMillis)
    }

    /** The last noise reading if it is recent enough to describe the present. */
    fun recentNoise(now: Long): Double? = _noise.value?.takeIf { now - it.atMillis < NOISE_MAX_AGE_MS }?.db

    data class NoiseReading(val db: Double, val atMillis: Long)

    companion object {
        private const val NOISE_MAX_AGE_MS = 10 * 60 * 1000L
    }
}

/** Which saved places the phone is inside, maintained from geofence transitions and location checks. */
class PresenceStore(file: File) {
    private val store = JsonFileStore(file, SetSerializer(String.serializer()), { emptySet() })
    val flow = store.flow

    suspend fun current(): Set<String> = store.get()
    suspend fun set(ids: Set<String>) = store.set(ids)
    suspend fun add(ids: Collection<String>) = store.update { it + ids }
    suspend fun remove(ids: Collection<String>) = store.update { it - ids.toSet() }
}

class CalendarReader(private val context: Context) {
    private data class Instance(val title: String, val begin: Long, val end: Long, val busy: Boolean)

    @Volatile
    private var cache: Pair<Long, List<Instance>>? = null

    private suspend fun instances(now: Long): List<Instance> {
        if (!Permissions.hasCalendar(context)) return emptyList()
        cache?.let { (at, list) -> if (now - at < CACHE_MS) return list }
        val list = withContext(Dispatchers.IO) { query(now - DAY_MS, now + DAY_MS) }
        cache = now to list
        return list
    }

    private fun query(begin: Long, end: Long): List<Instance> {
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.AVAILABILITY,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
        )
        val result = mutableListOf<Instance>()
        try {
            CalendarContract.Instances.query(context.contentResolver, projection, begin, end)?.use { c ->
                while (c.moveToNext()) {
                    val allDay = c.getInt(3) == 1
                    val declined = c.getInt(5) == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED
                    if (allDay || declined) continue
                    result += Instance(
                        title = c.getString(0).orEmpty(),
                        begin = c.getLong(1),
                        end = c.getLong(2),
                        busy = c.getInt(4) == CalendarContract.Events.AVAILABILITY_BUSY,
                    )
                }
            }
        } catch (e: SecurityException) {
            return emptyList()
        }
        return result
    }

    suspend fun currentEvents(now: Long): List<CalendarEventInfo> =
        instances(now).filter { now >= it.begin && now < it.end }.map { CalendarEventInfo(it.title, it.busy, it.end) }

    suspend fun nextChange(now: Long): Long? =
        instances(now).flatMap { listOf(it.begin, it.end) }.filter { it > now }.minOrNull()

    fun invalidate() {
        cache = null
    }

    companion object {
        private const val CACHE_MS = 60_000L
        private const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}

class AndroidContextGateway(
    private val context: Context,
    private val presence: PresenceStore,
    private val signals: DeviceSignals,
    private val calendar: CalendarReader,
    private val clock: Clock,
) : ContextGateway {
    private val audio = context.getSystemService(AudioManager::class.java)

    override suspend fun snapshot(): ContextSnapshot {
        val now = clock.now()
        val time = Instant.ofEpochMilli(now).atZone(clock.zone())
        return ContextSnapshot(
            epochMillis = now,
            dayOfWeek = time.dayOfWeek.value,
            minuteOfDay = time.hour * 60 + time.minute,
            placeIds = presence.current(),
            wifiSsid = wifiSsid(),
            bluetoothDevices = bluetoothDevices(),
            charging = charging(),
            headphones = headphones(),
            noiseDb = signals.recentNoise(now),
            calendarEvents = calendar.currentEvents(now),
        )
    }

    override suspend fun nextCalendarChangeMillis(): Long? = calendar.nextChange(clock.now())

    @SuppressLint("MissingPermission")
    fun wifiSsid(): String? {
        signals.wifiSsid?.let { return it }
        if (!Permissions.hasFineLocation(context)) return null
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        val info = wifi.connectionInfo ?: return null
        @Suppress("DEPRECATION")
        if (info.networkId == -1) return null
        @Suppress("DEPRECATION")
        return cleanSsid(info.ssid)
    }

    /** Bluetooth names from connection events plus connected Bluetooth audio devices (which need no permission). */
    fun bluetoothDevices(): Set<String> {
        val audioDevices = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type in BLUETOOTH_TYPES }
            .mapNotNull { it.productName?.toString()?.trim()?.ifEmpty { null } }
        return signals.aclDevices + audioDevices
    }

    fun headphones(): Boolean = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADPHONE_TYPES }

    fun charging(): Boolean {
        val battery = ContextCompat.registerReceiver(
            context,
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        ) ?: return false
        return battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

    companion object {
        val BLUETOOTH_TYPES: Set<Int> = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(AudioDeviceInfo.TYPE_BLE_HEADSET)
                add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
            }
        }

        val HEADPHONE_TYPES: Set<Int> = buildSet {
            add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
            add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
            add(AudioDeviceInfo.TYPE_USB_HEADSET)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        }

        fun cleanSsid(raw: String?): String? {
            val ssid = raw?.let(RuleEngine::normalizeSsid)?.trim()
            return if (ssid.isNullOrEmpty() || ssid == "<unknown ssid>") null else ssid
        }
    }
}
