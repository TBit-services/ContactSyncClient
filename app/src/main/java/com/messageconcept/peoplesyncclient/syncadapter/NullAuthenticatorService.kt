/***************************************************************************************************
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 **************************************************************************************************/
package com.messageconcept.peoplesyncclient.syncadapter

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.messageconcept.peoplesyncclient.ui.AccountsActivity

class NullAuthenticatorService: Service() {

    private lateinit var accountAuthenticator: AccountAuthenticator

    override fun onCreate() {
        accountAuthenticator = AccountAuthenticator(this)
    }

    override fun onBind(intent: Intent?) =
            accountAuthenticator.iBinder.takeIf { intent?.action == AccountManager.ACTION_AUTHENTICATOR_INTENT }


    private class AccountAuthenticator(
            val context: Context
    ): AbstractAccountAuthenticator(context) {

        override fun addAccount(response: AccountAuthenticatorResponse?, accountType: String?, authTokenType: String?, requiredFeatures: Array<String>?, options: Bundle?): Bundle {
            val intent = Intent(context, AccountsActivity::class.java)
            intent.putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response)
            val bundle = Bundle(1)
            bundle.putParcelable(AccountManager.KEY_INTENT, intent)
            return bundle
        }

        override fun editProperties(response: AccountAuthenticatorResponse?, accountType: String?) = null
        override fun getAuthTokenLabel(p0: String?) = null
        override fun confirmCredentials(p0: AccountAuthenticatorResponse?, p1: Account?, p2: Bundle?) = null
        override fun updateCredentials(p0: AccountAuthenticatorResponse?, p1: Account?, p2: String?, p3: Bundle?) = null
        override fun getAuthToken(p0: AccountAuthenticatorResponse?, p1: Account?, p2: String?, p3: Bundle?) = null
        override fun hasFeatures(p0: AccountAuthenticatorResponse?, p1: Account?, p2: Array<out String>?) = null

    }

}