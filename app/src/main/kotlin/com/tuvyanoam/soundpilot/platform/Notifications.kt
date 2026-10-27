package com.tuvyanoam.soundpilot.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tuvyanoam.soundpilot.R
import com.tuvyanoam.soundpilot.core.platform.UserNotifier
import com.tuvyanoam.soundpilot.ui.MainActivity
import java.util.concurrent.atomic.AtomicInteger

object NotificationChannels {
    const val SERVICE = "service"
    const val RULES = "rules"
    const val ASSISTANT = "assistant"
    const val SUGGESTIONS = "suggestions"

    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(SERVICE, context.getString(R.string.channel_service), NotificationManager.IMPORTANCE_MIN).apply {
                    description = context.getString(R.string.channel_service_desc)
                    setShowBadge(false)
                },
                NotificationChannel(RULES, context.getString(R.string.channel_rules), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(ASSISTANT, context.getString(R.string.channel_assistant), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(SUGGESTIONS, context.getString(R.string.channel_suggestions), NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }
}

class AndroidNotifier(private val context: Context) : UserNotifier {
    private val ruleIds = AtomicInteger(0)

    override fun ruleMessage(text: String) {
        post(NotificationChannels.RULES, context.getString(R.string.app_name), text, RULE_BASE_ID + ruleIds.incrementAndGet() % 20, null)
    }

    override fun aiAction(summary: String) {
        post(NotificationChannels.ASSISTANT, context.getString(R.string.notif_ai_title), summary, AI_ID, MainActivity.TAB_ASSISTANT)
    }

    override fun suggestionsAvailable(count: Int) {
        val text = context.resources.getQuantityString(R.plurals.notif_suggestions_text, count, count)
        post(NotificationChannels.SUGGESTIONS, context.getString(R.string.notif_suggestions_title), text, SUGGESTIONS_ID, MainActivity.TAB_LEARN)
    }

    private fun post(channel: String, title: String, text: String, id: Int, tab: String?) {
        if (!Permissions.hasNotifications(context)) return
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_auto)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context, tab))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }

    companion object {
        const val SERVICE_ID = 1
        private const val AI_ID = 2
        private const val SUGGESTIONS_ID = 3
        private const val RULE_BASE_ID = 100

        fun openApp(context: Context, tab: String?): PendingIntent = PendingIntent.getActivity(
            context,
            tab?.hashCode() ?: 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_TAB, tab),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
