/*
 * Copyright © Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */

package com.messageconcept.peoplesyncclient.syncadapter

import android.accounts.Account
import android.app.Service
import android.content.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Bundle
import androidx.core.content.getSystemService
import com.messageconcept.peoplesyncclient.InvalidAccountException
import com.messageconcept.peoplesyncclient.PermissionUtils
import com.messageconcept.peoplesyncclient.log.Logger
import com.messageconcept.peoplesyncclient.settings.AccountSettings
import com.messageconcept.peoplesyncclient.ui.account.WifiPermissionsActivity
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import java.util.logging.Level

abstract class SyncAdapterService: Service() {

    companion object {
        /**
         * Specifies an list of IDs which are requested to be synchronized before
         * the other collections. For instance, if some calendars of a CalDAV
         * account are visible in the calendar app and others are hidden, the visible calendars can
         * be synchronized first, so that the "Refresh" action in the calendar app is more responsive.
         *
         * Extra type: String (comma-separated list of IDs)
         *
         * In case of calendar sync, the extra value is a list of Android calendar IDs.
         * In case of task sync, the extra value is an a list of OpenTask task list IDs.
         */
        const val SYNC_EXTRAS_PRIORITY_COLLECTIONS = "priority_collections"

        /**
         * Requests a re-synchronization of all entries. For instance, if this extra is
         * set for a calendar sync, all remote events will be listed and checked for remote
         * changes again.
         *
         * Useful if settings which modify the remote resource list (like the CalDAV setting
         * "sync events n days in the past") have been changed.
         */
        const val SYNC_EXTRAS_RESYNC = "resync"

        /**
         * Requests a full re-synchronization of all entries. For instance, if this extra is
         * set for an address book sync, all contacts will be downloaded again and updated in the
         * local storage.
         *
         * Useful if settings which modify parsing/local behavior have been changed.
         */
        const val SYNC_EXTRAS_FULL_RESYNC = "full_resync"

        /** Keep a list of running syncs to block multiple calls at the same time, which
         * should not occur but sometimes do occur. */
        private val runningSyncs = ConcurrentHashMap<Pair<String, Account>, Lock>()

        /**
         * We use our own dispatcher to
         *
         *   - make sure that all threads have [Thread.getContextClassLoader] set, which is required for dav4jvm and ical4j (because they rely on [ServiceLoader]),
         *   - control the global number of sync worker threads.
         *
         * Threads created by a service automatically have a contextClassLoader.
         */
        val workDispatcher =
            ThreadPoolExecutor(0, Integer.min(Runtime.getRuntime().availableProcessors(), 6),
                10, TimeUnit.SECONDS, LinkedBlockingQueue()).asCoroutineDispatcher()
    }


    protected abstract fun syncAdapter(): AbstractThreadedSyncAdapter

    override fun onBind(intent: Intent?) = syncAdapter().syncAdapterBinder!!


    abstract class SyncAdapter(
            context: Context
    ): AbstractThreadedSyncAdapter(
            context,
            true    // isSyncable shouldn't be -1 because PeopleSync sets it to 0 or 1.
                                // However, if it is -1 by accident, set it to 1 to avoid endless sync loops.
    ) {

        companion object {
            fun priorityCollections(extras: Bundle): Set<Long> {
                val ids = mutableSetOf<Long>()
                extras.getString(SYNC_EXTRAS_PRIORITY_COLLECTIONS)?.let { rawIds ->
                    for (rawId in rawIds.split(','))
                        try {
                            ids += rawId.toLong()
                        } catch (e: NumberFormatException) {
                            Logger.log.log(Level.WARNING, "Couldn't parse SYNC_EXTRAS_PRIORITY_COLLECTIONS", e)
                        }
                }
                return ids
            }
        }

        abstract fun sync(account: Account, extras: Bundle, authority: String, provider: ContentProviderClient, syncResult: SyncResult)

        override fun onPerformSync(account: Account, extras: Bundle, authority: String, provider: ContentProviderClient, syncResult: SyncResult) {
            Logger.log.log(Level.INFO, "$authority sync of $account has been initiated", extras.keySet().joinToString(", "))

            /*
            Prevent multiple syncs of the same authority and account to run simultaneously.
             */
            val currentSync = Pair(authority, account)
            val currentSyncLock = runningSyncs.getOrPut(currentSync) { ReentrantLock() }
            if (currentSyncLock.tryLock())
                try {
                    // required for ServiceLoader -> ical4j -> ical4android
                    Thread.currentThread().contextClassLoader = context.classLoader

                    try {
                        if (/* always true in open-source edition */ true)
                            sync(account, extras, authority, provider, syncResult)
                    } catch (e: InvalidAccountException) {
                        Logger.log.log(Level.WARNING, "Account was removed during synchronization", e)
                    }

                    Logger.log.log(Level.INFO, "Sync for $currentSync finished", syncResult)
                } finally {
                    currentSyncLock.unlock()
                    // from now on, further threads can re-use the existing lock

                    runningSyncs -= currentSync
                    // from now on, further threads will create a new lock for the authority/account pair
                }
            else
                Logger.log.warning("There's already another $authority sync running for $account, aborting")
        }

        override fun onSecurityException(account: Account, extras: Bundle, authority: String, syncResult: SyncResult) {
            Logger.log.log(Level.WARNING, "Security exception when opening content provider for $authority")
        }

        override fun onSyncCanceled() {
            Logger.log.info("Sync thread cancelled! Interrupting sync")
            super.onSyncCanceled()
        }

        override fun onSyncCanceled(thread: Thread) {
            Logger.log.info("Sync thread ${thread.id} cancelled! Interrupting sync")
            super.onSyncCanceled(thread)
        }


        protected fun checkSyncConditions(settings: AccountSettings): Boolean {
            if (settings.getSyncWifiOnly()) {
                // WiFi required
                val connectivityManager = context.getSystemService<ConnectivityManager>()!!

                // check for connected WiFi network
                var wifiAvailable = false
                connectivityManager.allNetworks.forEach { network ->
                    connectivityManager.getNetworkCapabilities(network)?.let { capabilities ->
                        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                            wifiAvailable = true
                    }
                }
                if (!wifiAvailable) {
                    Logger.log.info("Not on connected WiFi, stopping")
                    return false
                }
                // if execution reaches this point, we're on a connected WiFi

                settings.getSyncWifiOnlySSIDs()?.let { onlySSIDs ->
                    // check required permissions and location status
                    if (!PermissionUtils.canAccessWifiSsid(context)) {
                        // not all permissions granted; show notification
                        val intent = Intent(context, WifiPermissionsActivity::class.java)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        intent.putExtra(WifiPermissionsActivity.EXTRA_ACCOUNT, settings.account)
                        PermissionUtils.notifyPermissions(context, intent)

                        Logger.log.warning("Can't access WiFi SSID, aborting sync")
                        return false
                    }

                    val wifi = context.getSystemService<WifiManager>()!!
                    val info = wifi.connectionInfo
                    if (info == null || !onlySSIDs.contains(info.ssid.trim('"'))) {
                        Logger.log.info("Connected to wrong WiFi network (${info.ssid}), aborting sync")
                        return false
                    } else
                        Logger.log.fine("Connected to WiFi network ${info.ssid}")
                }
            }
            return true
        }

    }

}
