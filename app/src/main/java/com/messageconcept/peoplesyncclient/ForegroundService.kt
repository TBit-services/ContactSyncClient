/***************************************************************************************************
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 **************************************************************************************************/

package com.messageconcept.peoplesyncclient

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.messageconcept.peoplesyncclient.settings.Settings
import com.messageconcept.peoplesyncclient.settings.SettingsManager
import com.messageconcept.peoplesyncclient.ui.AppSettingsActivity
import com.messageconcept.peoplesyncclient.ui.NotificationUtils

class ForegroundService : Service() {

    companion object {

        /**
         * Starts/stops a foreground service, according to the app setting [Settings.FOREGROUND_SERVICE].
         */
        const val ACTION_FOREGROUND = "foreground"

        fun isEnabled(context: Context): Boolean {
            val settings = SettingsManager.getInstance(context)
            return settings.getBooleanOrNull(Settings.FOREGROUND_SERVICE) == true
        }

        fun startIfEnabled(context: Context) {
            if (isEnabled(context)) {
                val serviceIntent = Intent(ACTION_FOREGROUND, null, context, ForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= 26)
                    context.startForegroundService(serviceIntent)
                else
                    context.startService(serviceIntent)
            }
        }

    }


    override fun onBind(intent: Intent?): Nothing? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (isEnabled(this)) {
            val settingsIntent = Intent(this, AppSettingsActivity::class.java).apply {
                putExtra(AppSettingsActivity.EXTRA_SCROLL_TO, Settings.FOREGROUND_SERVICE)
            }
            val builder = NotificationCompat.Builder(this, NotificationUtils.CHANNEL_STATUS)
                    .setSmallIcon(R.drawable.ic_foreground_notify)
                    .setContentTitle(getString(R.string.foreground_service_notify_title))
                    .setContentText(getString(R.string.foreground_service_notify_text))
                    .setStyle(NotificationCompat.BigTextStyle())
                    .setContentIntent(PendingIntent.getActivity(this, 0, settingsIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            startForeground(NotificationUtils.NOTIFY_FOREGROUND, builder.build())
            return START_STICKY
        } else {
            stopForeground(true)
            return START_NOT_STICKY
        }
    }

}