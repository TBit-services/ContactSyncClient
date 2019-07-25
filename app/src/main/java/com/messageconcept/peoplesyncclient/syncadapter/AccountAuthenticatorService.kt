/*
 * Copyright © Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */
package com.messageconcept.peoplesyncclient.syncadapter

import android.accounts.*
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.annotation.WorkerThread
import com.messageconcept.peoplesyncclient.R
import com.messageconcept.peoplesyncclient.log.Logger
import com.messageconcept.peoplesyncclient.model.AppDatabase
import com.messageconcept.peoplesyncclient.resource.LocalAddressBook
import com.messageconcept.peoplesyncclient.resource.LocalAddressBook.Companion.USER_DATA_MAIN_ACCOUNT_TYPE
import com.messageconcept.peoplesyncclient.ui.setup.LoginActivity
import java.util.logging.Level
import kotlin.concurrent.thread


/**
 * Account authenticator for the main PeopleSync account type.
 *
 * Gets started when a PeopleSync account is removed, too, so it also watches for account removals
 * and contains the corresponding cleanup code.
 */
class AccountAuthenticatorService: Service(), OnAccountsUpdateListener {

    companion object {

        @WorkerThread
        fun cleanupAccounts(context: Context) {
            Logger.log.info("Cleaning up orphaned accounts")

            val accountManager = AccountManager.get(context)
            val accountNames = accountManager.getAccountsByType(context.getString(R.string.account_type))
                    .map { it.name }

            // Determine if we only have old style addressbook accounts.
            // If so, this most likely means that we weren't able to contact the PeopleSync
            // server yet and sync the addressbooks after an upgrade. In this case, don't delete
            // the old addressbooks yet.
            var newAccountAvailable = false
            accountManager.getAccountsByType(context.getString(R.string.account_type_address_book))
                    .forEach {
                        if (accountManager.getUserData(it, USER_DATA_MAIN_ACCOUNT_TYPE) == context.getString(R.string.account_type)) {
                            newAccountAvailable = true;
                        }
                    }

            // delete orphaned address book accounts
            accountManager.getAccountsByType(context.getString(R.string.account_type_address_book))
                    .map { LocalAddressBook(context, it, null) }
                    .forEach {
                        try {
                            if (!accountNames.contains(it.mainAccount.name) && newAccountAvailable)
                                it.delete()
                        } catch(e: Exception) {
                            Logger.log.log(Level.SEVERE, "Couldn't delete address book account", e)
                        }
                    }

            // delete orphaned services in DB
            val db = AppDatabase.getInstance(context)
            val serviceDao = db.serviceDao()
            if (accountNames.isEmpty())
                serviceDao.deleteAll()
            else
                serviceDao.deleteExceptAccounts(accountNames.toTypedArray())
        }

    }


    private lateinit var accountManager: AccountManager
    private lateinit var accountAuthenticator: AccountAuthenticator

    override fun onCreate() {
        accountManager = AccountManager.get(this)
        accountManager.addOnAccountsUpdatedListener(this, null, true)

        accountAuthenticator = AccountAuthenticator(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        accountManager.removeOnAccountsUpdatedListener(this)
    }

    override fun onBind(intent: Intent?) =
            accountAuthenticator.iBinder.takeIf { intent?.action == android.accounts.AccountManager.ACTION_AUTHENTICATOR_INTENT }


    override fun onAccountsUpdated(accounts: Array<out Account>?) {
        thread {
            cleanupAccounts(this)
        }
    }


    private class AccountAuthenticator(
            val context: Context
    ): AbstractAccountAuthenticator(context) {

        override fun addAccount(response: AccountAuthenticatorResponse?, accountType: String?, authTokenType: String?, requiredFeatures: Array<String>?, options: Bundle?): Bundle {
            val intent = Intent(context, LoginActivity::class.java)
            intent.putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response)
            val bundle = Bundle(1)
            bundle.putParcelable(AccountManager.KEY_INTENT, intent)
            return bundle
        }

        override fun editProperties(response: AccountAuthenticatorResponse?, accountType: String?)  = null
        override fun getAuthTokenLabel(p0: String?) = null
        override fun confirmCredentials(p0: AccountAuthenticatorResponse?, p1: Account?, p2: Bundle?) = null
        override fun updateCredentials(p0: AccountAuthenticatorResponse?, p1: Account?, p2: String?, p3: Bundle?) = null
        override fun getAuthToken(p0: AccountAuthenticatorResponse?, p1: Account?, p2: String?, p3: Bundle?) = null
        override fun hasFeatures(p0: AccountAuthenticatorResponse?, p1: Account?, p2: Array<out String>?) = null

    }
}
