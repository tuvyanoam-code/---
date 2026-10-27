package com.tuvyanoam.soundpilot

import android.app.Application
import android.content.Context
import com.tuvyanoam.soundpilot.core.ai.AutomationToolbox
import com.tuvyanoam.soundpilot.core.ai.ClaudeAgent
import com.tuvyanoam.soundpilot.core.ai.ClaudeGateway
import com.tuvyanoam.soundpilot.core.ai.SmartModeRunner
import com.tuvyanoam.soundpilot.core.automation.AutomationCoordinator
import com.tuvyanoam.soundpilot.core.learning.LearningService
import com.tuvyanoam.soundpilot.core.platform.Clock
import com.tuvyanoam.soundpilot.core.platform.GeoPoint
import com.tuvyanoam.soundpilot.core.platform.PlatformHooks
import com.tuvyanoam.soundpilot.core.storage.Storage
import com.tuvyanoam.soundpilot.platform.AlarmWakeUpScheduler
import com.tuvyanoam.soundpilot.platform.AndroidAudioGateway
import com.tuvyanoam.soundpilot.platform.AndroidContextGateway
import com.tuvyanoam.soundpilot.platform.AndroidNotifier
import com.tuvyanoam.soundpilot.platform.AndroidPhrases
import com.tuvyanoam.soundpilot.platform.CalendarReader
import com.tuvyanoam.soundpilot.platform.DeviceSignals
import com.tuvyanoam.soundpilot.platform.GeofenceManager
import com.tuvyanoam.soundpilot.platform.LocationHelper
import com.tuvyanoam.soundpilot.platform.NoiseMonitor
import com.tuvyanoam.soundpilot.platform.Permissions
import com.tuvyanoam.soundpilot.platform.PresenceStore
import com.tuvyanoam.soundpilot.platform.SecureKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.util.Locale

/** Creates and holds the app's long-lived objects. One instance per process. */
class AppContainer(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val clock: Clock = Clock.System
    private val dataDir = File(app.filesDir, "data")

    val storage = Storage(dataDir, clock::now)
    val phrases = AndroidPhrases(app)
    val signals = DeviceSignals()
    val presence = PresenceStore(File(dataDir, "presence.json"))
    val calendar = CalendarReader(app)
    val audio = AndroidAudioGateway(app)
    val contextGateway = AndroidContextGateway(app, presence, signals, calendar, clock)
    val notifier = AndroidNotifier(app)
    val noiseMonitor = NoiseMonitor(app)
    val location = LocationHelper(app)
    val geofences = GeofenceManager(app, storage.places, presence, location)
    val keyStore = SecureKeyStore(app)

    val coordinator = AutomationCoordinator(
        storage = storage,
        audio = audio,
        context = contextGateway,
        scheduler = AlarmWakeUpScheduler(app),
        notifier = notifier,
        clock = clock,
        phrases = phrases,
        scope = scope,
    )

    private val platformHooks = object : PlatformHooks {
        override suspend fun currentLocation(): GeoPoint? = location.currentLocation()
        override fun missingCapabilities(): List<String> = Permissions.missing(app).map { it.englishName }
        override suspend fun placesChanged() {
            geofences.sync()
            coordinator.evaluate("places_changed")
        }
    }

    val gateway = ClaudeGateway({ keyStore.get() })
    val toolbox = AutomationToolbox(storage, coordinator, platformHooks, clock, phrases)
    val agent = ClaudeAgent(gateway, toolbox, storage) { languageName() }
    val learning = LearningService(storage, coordinator, agent, clock, phrases, notifier)
    private val smartMode = SmartModeRunner(agent, storage, clock, notifier)

    init {
        coordinator.contextChangeListener = smartMode
    }

    /** The language Claude should write in, following the app's current locale. */
    fun languageName(): String {
        val locale = app.resources.configuration.locales[0] ?: Locale.getDefault()
        return when (locale.language) {
            "iw", "he" -> "Hebrew"
            else -> locale.getDisplayLanguage(Locale.ENGLISH).ifBlank { "English" }
        }
    }
}

val Context.container: AppContainer get() = (applicationContext as SoundPilotApp).container
