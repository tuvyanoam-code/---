package com.tuvyanoam.soundpilot.platform

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.tuvyanoam.soundpilot.R

/** Everything the app may need the user to grant, how to check it, and where to grant it. */
enum class Capability(
    @StringRes val title: Int,
    @StringRes val reason: Int,
    /** English name given to Claude so it can tell the user what is missing. */
    val englishName: String,
    val required: Boolean,
) {
    DND_ACCESS(R.string.perm_dnd, R.string.perm_dnd_reason, "Do Not Disturb access", true),
    NOTIFICATIONS(R.string.perm_notifications, R.string.perm_notifications_reason, "Notifications", true),
    LOCATION(R.string.perm_location, R.string.perm_location_reason, "Location", false),
    BACKGROUND_LOCATION(R.string.perm_background_location, R.string.perm_background_location_reason, "Location: Allow all the time", false),
    MICROPHONE(R.string.perm_microphone, R.string.perm_microphone_reason, "Microphone", false),
    CALENDAR(R.string.perm_calendar, R.string.perm_calendar_reason, "Calendar", false),
    BLUETOOTH(R.string.perm_bluetooth, R.string.perm_bluetooth_reason, "Nearby devices (Bluetooth)", false),
    EXACT_ALARMS(R.string.perm_alarms, R.string.perm_alarms_reason, "Alarms & reminders", false),
    BATTERY(R.string.perm_battery, R.string.perm_battery_reason, "Unrestricted battery use", false),
}

object Permissions {
    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasPolicyAccess(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    fun hasFineLocation(context: Context) = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasBackgroundLocation(context: Context) = hasFineLocation(context) &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION))

    fun hasMicrophone(context: Context) = granted(context, Manifest.permission.RECORD_AUDIO)

    fun hasCalendar(context: Context) = granted(context, Manifest.permission.READ_CALENDAR)

    fun hasBluetooth(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(context, Manifest.permission.BLUETOOTH_CONNECT)

    fun hasNotifications(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(context, Manifest.permission.POST_NOTIFICATIONS)

    fun canScheduleExactAlarms(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun ignoresBatteryOptimizations(context: Context) =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun isGranted(context: Context, capability: Capability): Boolean = when (capability) {
        Capability.DND_ACCESS -> hasPolicyAccess(context)
        Capability.NOTIFICATIONS -> hasNotifications(context)
        Capability.LOCATION -> hasFineLocation(context)
        Capability.BACKGROUND_LOCATION -> hasBackgroundLocation(context)
        Capability.MICROPHONE -> hasMicrophone(context)
        Capability.CALENDAR -> hasCalendar(context)
        Capability.BLUETOOTH -> hasBluetooth(context)
        Capability.EXACT_ALARMS -> canScheduleExactAlarms(context)
        Capability.BATTERY -> ignoresBatteryOptimizations(context)
    }

    fun missing(context: Context): List<Capability> = Capability.entries.filterNot { isGranted(context, it) }

    /** Runtime permissions to request with the system dialog, or null when the capability is granted in Settings. */
    fun runtimePermissions(capability: Capability): Array<String>? = when (capability) {
        Capability.NOTIFICATIONS ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else null
        Capability.LOCATION -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        // Must be requested on its own, after foreground location.
        Capability.BACKGROUND_LOCATION ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION) else null
        Capability.MICROPHONE -> arrayOf(Manifest.permission.RECORD_AUDIO)
        Capability.CALENDAR -> arrayOf(Manifest.permission.READ_CALENDAR)
        Capability.BLUETOOTH ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else null
        Capability.DND_ACCESS, Capability.EXACT_ALARMS, Capability.BATTERY -> null
    }

    /** The system Settings screen where a capability is granted. */
    fun settingsIntent(context: Context, capability: Capability): Intent {
        val packageUri = Uri.parse("package:${context.packageName}")
        return when (capability) {
            Capability.DND_ACCESS -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            Capability.EXACT_ALARMS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri)
                else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
            Capability.BATTERY -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            Capability.NOTIFICATIONS ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
