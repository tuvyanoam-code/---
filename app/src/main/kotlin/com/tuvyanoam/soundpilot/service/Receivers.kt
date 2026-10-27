package com.tuvyanoam.soundpilot.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import com.tuvyanoam.soundpilot.container
import com.tuvyanoam.soundpilot.core.automation.AutomationCoordinator
import com.tuvyanoam.soundpilot.core.model.AudioCommand
import com.tuvyanoam.soundpilot.core.model.RingerMode
import com.tuvyanoam.soundpilot.platform.Labels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Runs [block] off the main thread while keeping the broadcast alive (Android allows about ten seconds). */
internal fun BroadcastReceiver.runAsync(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        try {
            withTimeoutOrNull(9_000) { block() }
        } finally {
            pending.finish()
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.container
        runAsync {
            if (app.storage.settings.get().automationEnabled) AutomationService.start(context)
            LearningWorker.schedule(context)
            app.coordinator.evaluate("boot")
        }
        // Geofences do not survive a reboot. Registering them can take longer than a broadcast may run.
        app.scope.launch {
            if (app.geofences.sync()) app.coordinator.evaluate("geofences_restored")
        }
    }
}

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        val app = context.container
        runAsync {
            if (event.hasError()) {
                // Location was turned off: Android dropped the geofences, so presence is unknown.
                if (event.errorCode == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE) {
                    app.presence.set(emptySet())
                    app.coordinator.evaluate("geofence_unavailable")
                }
                return@runAsync
            }
            val ids = event.triggeringGeofences.orEmpty().map { it.requestId }
            when (event.geofenceTransition) {
                Geofence.GEOFENCE_TRANSITION_ENTER, Geofence.GEOFENCE_TRANSITION_DWELL -> app.presence.add(ids)
                Geofence.GEOFENCE_TRANSITION_EXIT -> app.presence.remove(ids)
            }
            app.coordinator.evaluate("geofence")
        }
    }
}

class WakeUpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val tag = intent.getStringExtra(EXTRA_TAG)
        runAsync {
            context.container.coordinator.evaluate(if (tag == AutomationCoordinator.TAG_TEMPORARY) "temporary" else "alarm")
        }
    }

    companion object {
        const val EXTRA_TAG = "tag"
    }
}

/** Buttons on the ongoing notification. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val coordinator = context.container.coordinator
        runAsync {
            when (intent.action) {
                ACTION_END_TEMPORARY -> coordinator.endTemporary()
                ACTION_SILENT_HOUR -> coordinator.startTemporary(
                    listOf(AudioCommand.Ringer(RingerMode.SILENT)), 60, Labels.mode(context, RingerMode.SILENT),
                )
                ACTION_VIBRATE_HOUR -> coordinator.startTemporary(
                    listOf(AudioCommand.Ringer(RingerMode.VIBRATE)), 60, Labels.mode(context, RingerMode.VIBRATE),
                )
            }
        }
    }

    companion object {
        const val ACTION_END_TEMPORARY = "com.tuvyanoam.soundpilot.action.END_TEMPORARY"
        const val ACTION_SILENT_HOUR = "com.tuvyanoam.soundpilot.action.SILENT_HOUR"
        const val ACTION_VIBRATE_HOUR = "com.tuvyanoam.soundpilot.action.VIBRATE_HOUR"

        fun pendingIntent(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(context, NotificationActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
