package com.tuvyanoam.soundpilot.service

import android.app.Notification
import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.tuvyanoam.soundpilot.R
import com.tuvyanoam.soundpilot.container
import com.tuvyanoam.soundpilot.core.automation.AutomationCoordinator
import com.tuvyanoam.soundpilot.core.engine.NoiseMath
import com.tuvyanoam.soundpilot.core.model.AudioStream
import com.tuvyanoam.soundpilot.core.model.RingerMode
import com.tuvyanoam.soundpilot.core.model.UserEvent
import com.tuvyanoam.soundpilot.platform.AndroidAudioGateway
import com.tuvyanoam.soundpilot.platform.AndroidContextGateway
import com.tuvyanoam.soundpilot.platform.AndroidNotifier
import com.tuvyanoam.soundpilot.platform.Labels
import com.tuvyanoam.soundpilot.platform.NotificationChannels
import com.tuvyanoam.soundpilot.platform.Permissions
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Keeps the automation responsive while the app is closed: listens for Wi-Fi, Bluetooth, headphones, power and
 * clock changes, samples ambient noise when needed, and notices the user's own sound changes so routines can be
 * learned. Place and time triggers also work without it (geofences and alarms), it just makes everything faster.
 */
class AutomationService : LifecycleService() {
    private val app by lazy { applicationContext.container }
    private val triggers = MutableSharedFlow<String>(extraBufferCapacity = 32)
    private val pendingUserChanges = mutableMapOf<String, Job>()
    private var micInForeground = false
    private var minuteTicks = 0
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        if (!startInForeground()) return
        _running.value = true
        registerListeners()
        lifecycleScope.launch { triggers.debounce(TRIGGER_DEBOUNCE_MS).collect { app.coordinator.evaluate(it) } }
        lifecycleScope.launch { app.coordinator.evaluate(AutomationCoordinator.REASON_STARTUP) }
        lifecycleScope.launch { runNoiseLoop() }
        lifecycleScope.launch { keepNotificationFresh() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_REFRESH -> {
                // Called when the app comes to the foreground: the only moment Android lets us add the microphone.
                if (!micInForeground && Permissions.hasMicrophone(this)) startInForeground()
                triggers.tryEmit("refresh")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        _running.value = false
        unregisterListeners()
        super.onDestroy()
    }

    /** Returns false (and stops) when Android does not allow a foreground service right now. */
    private fun startInForeground(): Boolean {
        val notification = buildNotification(getString(R.string.service_title_starting), "", temporary = false)
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        val mic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Permissions.hasMicrophone(this)) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        micInForeground = try {
            ServiceCompat.startForeground(this, AndroidNotifier.SERVICE_ID, notification, base or mic)
            mic != 0
        } catch (e: Exception) {
            // The microphone type is refused while the app is in the background (e.g. after a reboot).
            try {
                ServiceCompat.startForeground(this, AndroidNotifier.SERVICE_ID, notification, base)
                false
            } catch (e2: Exception) {
                stopSelf()
                return false
            }
        }
        return true
    }

    // ---- Noise ----

    private suspend fun runNoiseLoop() {
        combine(
            app.coordinator.adaptiveActive,
            app.coordinator.needsNoise,
            app.storage.settings.settings.map { it.adaptive }.distinctUntilChanged(),
        ) { adaptive, needsNoise, config -> Triple(adaptive, needsNoise, config) }
            .collectLatest { (adaptive, needsNoise, config) ->
                if (!adaptive && !needsNoise) return@collectLatest
                while (true) {
                    if (micInForeground) {
                        app.noiseMonitor.measureDbfs()?.let { dbfs ->
                            val db = NoiseMath.estimateSpl(dbfs, config.calibrationOffsetDb)
                            app.signals.reportNoise(db, app.clock.now())
                            app.coordinator.onNoiseSample(db)
                        }
                    }
                    delay(config.sampleIntervalSeconds.coerceAtLeast(MIN_SAMPLE_SECONDS) * 1000L)
                }
            }
    }

    // ---- Notification ----

    private suspend fun keepNotificationFresh() {
        combine(
            app.coordinator.lastReport,
            app.signals.noise,
            app.storage.automation.state,
        ) { report, noise, state -> Triple(report, noise, state) }
            .collectLatest { (report, noise, state) ->
                val mode = Labels.mode(this, app.audio.ringerMode())
                val temporary = state.temporary
                val text = buildList {
                    if (temporary != null) {
                        val until = Instant.ofEpochMilli(temporary.untilMillis).atZone(app.clock.zone())
                            .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
                        add(getString(R.string.service_temporary, temporary.description, until))
                    } else {
                        val active = report?.activeRules.orEmpty().map { it.name }
                        add(if (active.isEmpty()) getString(R.string.service_no_rule) else getString(R.string.service_active_rules, active.joinToString()))
                    }
                    noise?.let { add(getString(R.string.service_noise, it.db.toInt())) }
                }.joinToString(" · ")
                val notification = buildNotification(getString(R.string.service_title, mode), text, temporary != null)
                if (Permissions.hasNotifications(this@AutomationService)) {
                    try {
                        NotificationManagerCompat.from(this@AutomationService).notify(AndroidNotifier.SERVICE_ID, notification)
                    } catch (e: SecurityException) {
                        // Notifications were turned off.
                    }
                }
            }
    }

    private fun buildNotification(title: String, text: String, temporary: Boolean): Notification {
        val builder = NotificationCompat.Builder(this, NotificationChannels.SERVICE)
            .setSmallIcon(R.drawable.ic_stat_auto)
            .setColor(ContextCompat.getColor(this, R.color.notification_accent))
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(AndroidNotifier.openApp(this, null))
        if (temporary) {
            builder.addAction(0, getString(R.string.action_end_now), NotificationActionReceiver.pendingIntent(this, NotificationActionReceiver.ACTION_END_TEMPORARY))
        } else {
            builder.addAction(0, getString(R.string.action_silent_hour), NotificationActionReceiver.pendingIntent(this, NotificationActionReceiver.ACTION_SILENT_HOUR))
            builder.addAction(0, getString(R.string.action_vibrate_hour), NotificationActionReceiver.pendingIntent(this, NotificationActionReceiver.ACTION_VIBRATE_HOUR))
        }
        return builder.build()
    }

    // ---- Listeners ----

    private val soundReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (app.audio.isOwnChange()) return
            when (intent.action) {
                AudioManager.RINGER_MODE_CHANGED_ACTION -> recordLater("ringer") {
                    app.coordinator.recordUserChange(UserEvent.Kind.RINGER, ringerMode = app.audio.ringerMode())
                }
                VOLUME_CHANGED_ACTION -> {
                    val stream = AndroidAudioGateway.streamOf(intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)) ?: return
                    recordLater("volume:$stream") {
                        // Turning the ringtone all the way down is a ringer mode change, recorded on its own.
                        val ringerLinked = stream == AudioStream.RING || stream == AudioStream.NOTIFICATION
                        if (ringerLinked && app.audio.ringerMode() != RingerMode.NORMAL) return@recordLater
                        app.coordinator.recordUserChange(UserEvent.Kind.VOLUME, stream = stream, percent = app.audio.percent(stream))
                    }
                }
                NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> recordLater("dnd") {
                    app.coordinator.recordUserChange(UserEvent.Kind.DND, dnd = app.audio.dnd())
                }
            }
        }
    }

    /** Waits for the user to finish (volume keys fire many events) before recording the final value. */
    private fun recordLater(key: String, block: suspend () -> Unit) {
        pendingUserChanges.remove(key)?.cancel()
        pendingUserChanges[key] = lifecycleScope.launch {
            delay(USER_CHANGE_SETTLE_MS)
            pendingUserChanges.remove(key)
            if (!app.audio.isOwnChange()) block()
        }
    }

    private val contextReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_TIME_TICK -> {
                    minuteTicks++
                    if (minuteTicks % TICKS_PER_EVALUATION == 0) {
                        app.calendar.invalidate()
                        triggers.tryEmit("tick")
                    }
                }
                Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED -> triggers.tryEmit("power")
                Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_TIME_CHANGED -> triggers.tryEmit("time")
                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val name = bluetoothName(intent)
                    if (name != null) {
                        app.signals.aclDevices = if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED) {
                            app.signals.aclDevices + name
                        } else {
                            app.signals.aclDevices - name
                        }
                    }
                    triggers.tryEmit("bluetooth")
                }
            }
        }
    }

    private fun bluetoothName(intent: Intent): String? {
        if (!Permissions.hasBluetooth(this)) return null
        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return null
        return try {
            device.name?.trim()?.ifEmpty { null }
        } catch (e: SecurityException) {
            null
        }
    }

    private inner class WifiCallback : ConnectivityManager.NetworkCallback {
        constructor() : super()

        @RequiresApi(Build.VERSION_CODES.S)
        constructor(flags: Int) : super(flags)

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                AndroidContextGateway.cleanSsid((capabilities.transportInfo as? WifiInfo)?.ssid)
            } else {
                null
            }
            // Capabilities change often (signal strength); only a different network matters.
            if (ssid != null && ssid != app.signals.wifiSsid) {
                app.signals.wifiSsid = ssid
                triggers.tryEmit("wifi")
            }
        }

        override fun onAvailable(network: Network) {
            // Before Android 10 the network name is not in the capabilities; it is read on evaluation instead.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) triggers.tryEmit("wifi")
        }

        override fun onLost(network: Network) {
            app.signals.wifiSsid = null
            triggers.tryEmit("wifi")
        }
    }

    private fun registerListeners() {
        ContextCompat.registerReceiver(
            this,
            soundReceiver,
            IntentFilter().apply {
                addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
                addAction(VOLUME_CHANGED_ACTION)
                addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            },
            // The volume broadcast is not a documented system broadcast, so it needs an exported receiver.
            ContextCompat.RECEIVER_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this,
            contextReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        val audioManager = getSystemService(AudioManager::class.java)
        audioDeviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                triggers.tryEmit("audio_devices")
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                triggers.tryEmit("audio_devices")
            }
        }.also { audioManager.registerAudioDeviceCallback(it, Handler(Looper.getMainLooper())) }

        val connectivity = getSystemService(ConnectivityManager::class.java)
        val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            WifiCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO)
        } else {
            WifiCallback()
        }
        try {
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                callback,
            )
            networkCallback = callback
        } catch (e: Exception) {
            // Too many callbacks or no permission: Wi-Fi is still read on each evaluation.
        }
    }

    private fun unregisterListeners() {
        runCatching { unregisterReceiver(soundReceiver) }
        runCatching { unregisterReceiver(contextReceiver) }
        audioDeviceCallback?.let { getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(it) }
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
    }

    companion object {
        const val ACTION_REFRESH = "com.tuvyanoam.soundpilot.action.REFRESH"
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val TRIGGER_DEBOUNCE_MS = 1_500L
        private const val USER_CHANGE_SETTLE_MS = 2_500L
        private const val TICKS_PER_EVALUATION = 5
        private const val MIN_SAMPLE_SECONDS = 30

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        /** Starts the service. Fails silently where Android forbids starting it (app in the background). */
        fun start(context: Context, action: String? = null) {
            val intent = Intent(context, AutomationService::class.java).setAction(action)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException or IllegalStateException.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AutomationService::class.java))
        }
    }
}
