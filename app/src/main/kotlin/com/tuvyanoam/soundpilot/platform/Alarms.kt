package com.tuvyanoam.soundpilot.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.tuvyanoam.soundpilot.core.platform.WakeUpScheduler
import com.tuvyanoam.soundpilot.service.WakeUpReceiver

/** Wakes the automation with AlarmManager: exact when the user allowed it, otherwise as close as Android permits. */
class AlarmWakeUpScheduler(private val context: Context) : WakeUpScheduler {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    override fun schedule(tag: String, atMillis: Long) {
        val intent = pendingIntent(tag)
        try {
            if (Permissions.canScheduleExactAlarms(context)) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, intent)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, intent)
            }
        } catch (e: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, intent)
        }
    }

    override fun cancel(tag: String) {
        alarms.cancel(pendingIntent(tag))
    }

    private fun pendingIntent(tag: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        tag.hashCode(),
        Intent(context, WakeUpReceiver::class.java)
            .setAction("${context.packageName}.WAKE_UP.$tag")
            .putExtra(WakeUpReceiver.EXTRA_TAG, tag),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
