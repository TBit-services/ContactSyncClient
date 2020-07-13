package com.messageconcept.peoplesyncclient

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

abstract class PackageChangedReceiver(
        val context: Context
): BroadcastReceiver(), AutoCloseable {

    init {
        val filter = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply {
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        context.registerReceiver(this, filter)
    }

    override fun close() {
        context.unregisterReceiver(this)
    }

}