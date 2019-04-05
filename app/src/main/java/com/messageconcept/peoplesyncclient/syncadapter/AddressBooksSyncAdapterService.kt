/*
 * Copyright © Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */
package com.messageconcept.peoplesyncclient.syncadapter

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.*
import android.content.pm.PackageManager
import android.database.DatabaseUtils
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.messageconcept.peoplesyncclient.DavService
import com.messageconcept.peoplesyncclient.DavServiceUtils
import com.messageconcept.peoplesyncclient.R
import com.messageconcept.peoplesyncclient.log.Logger
import com.messageconcept.peoplesyncclient.model.CollectionInfo
import com.messageconcept.peoplesyncclient.model.ServiceDB
import com.messageconcept.peoplesyncclient.model.ServiceDB.Collections
import com.messageconcept.peoplesyncclient.resource.LocalAddressBook
import com.messageconcept.peoplesyncclient.resource.LocalAddressBook.Companion.USER_DATA_MAIN_ACCOUNT_NAME_OLD
import com.messageconcept.peoplesyncclient.settings.AccountSettings
import com.messageconcept.peoplesyncclient.ui.AccountActivity
import okhttp3.HttpUrl
import java.lang.IllegalStateException
import java.util.logging.Level

class AddressBooksSyncAdapterService : SyncAdapterService() {

    override fun syncAdapter() = AddressBooksSyncAdapter(this)


    class AddressBooksSyncAdapter(
            context: Context
    ) : SyncAdapter(context) {

        override fun sync(account: Account, extras: Bundle, authority: String, provider: ContentProviderClient, syncResult: SyncResult) {
            try {
                val accountSettings = AccountSettings(context, account)

                /* don't run sync if
                   - sync conditions (e.g. "sync only in WiFi") are not met AND
                   - this is is an automatic sync (i.e. manual syncs are run regardless of sync conditions)
                 */
                if (!extras.containsKey(ContentResolver.SYNC_EXTRAS_MANUAL) && !checkSyncConditions(accountSettings))
                    return

                updateLocalAddressBooks(account, syncResult)

                for (addressBookAccount in LocalAddressBook.findAll(context, null, account).map { it.account }) {
                    Logger.log.log(Level.INFO, "Running sync for address book", addressBookAccount)
                    val syncExtras = Bundle(extras)
                    syncExtras.putBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_SETTINGS, true)
                    syncExtras.putBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_BACKOFF, true)
                    ContentResolver.requestSync(addressBookAccount, ContactsContract.AUTHORITY, syncExtras)
                }
            } catch (e: Exception) {
                Logger.log.log(Level.SEVERE, "Couldn't sync address books", e)
            }

            Logger.log.info("Address book sync complete")
        }

        private fun updateLocalAddressBooks(account: Account, syncResult: SyncResult) {
            ServiceDB.OpenHelper(context).use { dbHelper ->
                val db = dbHelper.readableDatabase

                fun getService() =
                        db.query(ServiceDB.Services._TABLE, arrayOf(ServiceDB.Services.ID),
                                "${ServiceDB.Services.ACCOUNT_NAME}=? AND ${ServiceDB.Services.SERVICE}=?",
                                arrayOf(account.name, ServiceDB.Services.SERVICE_CARDDAV), null, null, null)?.use { c ->
                            if (c.moveToNext())
                                c.getLong(0)
                            else
                                null
                        }

                fun remoteAddressBooks(service: Long?): MutableMap<HttpUrl, CollectionInfo> {
                    val collections = mutableMapOf<HttpUrl, CollectionInfo>()
                    service?.let {
                        db.query(Collections._TABLE, null,
                                Collections.SERVICE_ID + "=? AND " + Collections.SYNC, arrayOf(service.toString()), null, null, null)?.use { cursor ->
                            while (cursor.moveToNext()) {
                                val values = ContentValues(cursor.columnCount)
                                DatabaseUtils.cursorRowToContentValues(cursor, values)
                                val info = CollectionInfo(values)
                                collections[info.url] = info
                            }
                        }
                    }
                    return collections
                }

                // enumerate remote and local address books
                val service = getService()

                if (service != null) {
                    Logger.log.info("Refreshing collections")
                    DavServiceUtils.refreshCollections(context, service, true, false)
                }

                val remote = remoteAddressBooks(service)

                if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                    if (remote.isEmpty()) {
                        Logger.log.info("No contacts permission, but no address book selected for synchronization")
                        return
                    } else {
                        // no contacts permission, but address books should be synchronized -> show notification
                        val intent = Intent(context, AccountActivity::class.java)
                        intent.putExtra(AccountActivity.EXTRA_ACCOUNT, account)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                        notifyPermissions(intent)
                    }
                }

                val contactsProvider = context.contentResolver.acquireContentProviderClient(ContactsContract.AUTHORITY)
                try {
                    if (contactsProvider == null) {
                        Logger.log.severe("Couldn't access contacts provider")
                        syncResult.databaseError = true
                        return
                    }

                    // delete/update local address books
                    for (addressBook in LocalAddressBook.findAll(context, contactsProvider, account)) {
                        val url = HttpUrl.parse(addressBook.url)!!
                        val info = remote[url]
                        if (info == null) {
                            Logger.log.log(Level.INFO, "Deleting obsolete local address book", url)
                            addressBook.delete()
                        } else {
                            // remote CollectionInfo found for this local collection, update data
                            try {
                                Logger.log.log(Level.FINE, "Updating local address book $url", info)
                                addressBook.update(info)
                            } catch (e: Exception) {
                                Logger.log.log(Level.WARNING, "Couldn't rename address book account", e)
                            }
                            // we already have a local address book for this remote collection, don't take into consideration anymore
                            remote -= url
                        }
                    }

                    // create new local address books
                    for ((_, info) in remote) {
                        Logger.log.log(Level.INFO, "Adding local address book", info)
                        LocalAddressBook.create(context, contactsProvider, account, info)
                    }
                    // trigger cleanup of old accounts now that we discovered new address books
                    val accountManager = AccountManager.get(context)
                    val oldAccounts = accountManager.getAccountsByType(context.getString(R.string.account_type_address_book))
                            .filter { accountManager.getUserData(it, USER_DATA_MAIN_ACCOUNT_NAME_OLD) != null }
                    if (remote.isNotEmpty() && oldAccounts.isNotEmpty()) {
                        Logger.log.info("New address books found, triggering cleanup of old accounts")
                        AccountAuthenticatorService.cleanupAccounts(context);
                    }
                } finally {
                    @Suppress("DEPRECATION")
                    if (Build.VERSION.SDK_INT >= 24)
                        contactsProvider?.close()
                    else
                        contactsProvider?.release()
                }
            }
        }

    }

}
