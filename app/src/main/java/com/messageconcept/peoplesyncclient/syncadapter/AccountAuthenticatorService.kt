/***************************************************************************************************
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 **************************************************************************************************/
package com.messageconcept.peoplesyncclient.syncadapter

import android.accounts.*
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.messageconcept.peoplesyncclient.ui.setup.LoginActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


/**
 * Account authenticator for the main PeopleSync account type.
 *
 * Gets started when a PeopleSync account is removed, too, so it also watches for account removals
 * and contains the corresponding cleanup code.
 */
class AccountAuthenticatorService: Service(), OnAccountsUpdateListener {

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
            accountAuthenticator.iBinder.takeIf { intent?.action == AccountManager.ACTION_AUTHENTICATOR_INTENT }


    override fun onAccountsUpdated(accounts: Array<out Account>?) {
        CoroutineScope(Dispatchers.Default).launch {
            AccountUtils.cleanupAccounts(this@AccountAuthenticatorService)
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
