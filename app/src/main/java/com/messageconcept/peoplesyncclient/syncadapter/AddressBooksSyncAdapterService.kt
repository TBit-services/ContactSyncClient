/***************************************************************************************************
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 **************************************************************************************************/
package com.messageconcept.peoplesyncclient.syncadapter

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.Context
import android.content.SyncResult
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.messageconcept.peoplesyncclient.DavService
import com.messageconcept.peoplesyncclient.DavServiceUtils
import com.messageconcept.peoplesyncclient.R
import com.messageconcept.peoplesyncclient.closeCompat
import com.messageconcept.peoplesyncclient.log.Logger
import com.messageconcept.peoplesyncclient.model.AppDatabase
import com.messageconcept.peoplesyncclient.model.Collection
import com.messageconcept.peoplesyncclient.model.Service
import com.messageconcept.peoplesyncclient.resource.LocalAddressBook
import com.messageconcept.peoplesyncclient.resource.LocalAddressBook.Companion.USER_DATA_MAIN_ACCOUNT_NAME_OLD
import com.messageconcept.peoplesyncclient.settings.AccountSettings
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.logging.Level

class AddressBooksSyncAdapterService : SyncAdapterService() {

    override fun syncAdapter() = AddressBooksSyncAdapter(this)


    class AddressBooksSyncAdapter(context: Context): SyncAdapter(context) {

        override fun sync(account: Account, extras: Bundle, authority: String, provider: ContentProviderClient, syncResult: SyncResult) {
            try {
                val accountSettings = AccountSettings(context, account)

                /* don't run sync if
                   - sync conditions (e.g. "sync only in WiFi") are not met AND
                   - this is is an automatic sync (i.e. manual syncs are run regardless of sync conditions)
                 */
                if (!extras.containsKey(ContentResolver.SYNC_EXTRAS_MANUAL) && !checkSyncConditions(accountSettings))
                    return

                if (updateLocalAddressBooks(account, syncResult))
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

        private fun updateLocalAddressBooks(account: Account, syncResult: SyncResult): Boolean {
            val db = AppDatabase.getInstance(context)
            val service = db.serviceDao().getByAccountAndType(account.name, Service.TYPE_CARDDAV)

            if (service != null) {
                Logger.log.info("Refreshing collections")
                DavServiceUtils.refreshCollections(context, db, service.id, true, false)
            }

            val remoteAddressBooks = mutableMapOf<HttpUrl, Collection>()
            if (service != null)
                for (collection in db.collectionDao().getByServiceAndSync(service.id))
                    remoteAddressBooks[collection.url] = collection

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                if (remoteAddressBooks.isEmpty())
                    Logger.log.info("No contacts permission, but no address book selected for synchronization")
                else
                    Logger.log.warning("No contacts permission, but address books are selected for synchronization")
                return false
            }

            val contactsProvider = context.contentResolver.acquireContentProviderClient(ContactsContract.AUTHORITY)
            try {
                if (contactsProvider == null) {
                    Logger.log.severe("Couldn't access contacts provider")
                    syncResult.databaseError = true
                    return false
                }

                // delete/update local address books
                for (addressBook in LocalAddressBook.findAll(context, contactsProvider, account)) {
                    val url = addressBook.url.toHttpUrl()
                    val info = remoteAddressBooks[url]
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
                        remoteAddressBooks -= url
                    }
                }

                // create new local address books
                for ((_, info) in remoteAddressBooks) {
                    Logger.log.log(Level.INFO, "Adding local address book", info)
                    LocalAddressBook.create(context, contactsProvider, account, info)
                }
                // trigger cleanup of old accounts now that we discovered new address books
                val accountManager = AccountManager.get(context)
                val oldAccounts = accountManager.getAccountsByType(context.getString(R.string.account_type_address_book))
                        .filter { accountManager.getUserData(it, USER_DATA_MAIN_ACCOUNT_NAME_OLD) != null }
                if (remoteAddressBooks.isNotEmpty() && oldAccounts.isNotEmpty()) {
                    Logger.log.info("New address books found, triggering cleanup of old accounts")
                    AccountUtils.cleanupAccounts(context);
                }
            } finally {
                contactsProvider?.closeCompat()
            }

            return true
        }

    }

}
