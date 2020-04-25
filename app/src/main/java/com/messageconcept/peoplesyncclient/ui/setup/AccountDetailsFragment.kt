/*
 * Copyright © Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */

package com.messageconcept.peoplesyncclient.ui.setup

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract
import android.text.Editable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import com.messageconcept.peoplesyncclient.Constants
import com.messageconcept.peoplesyncclient.DavService
import com.messageconcept.peoplesyncclient.InvalidAccountException
import com.messageconcept.peoplesyncclient.R
import com.messageconcept.peoplesyncclient.databinding.LoginAccountDetailsBinding
import com.messageconcept.peoplesyncclient.log.Logger
import com.messageconcept.peoplesyncclient.model.AppDatabase
import com.messageconcept.peoplesyncclient.model.Credentials
import com.messageconcept.peoplesyncclient.model.HomeSet
import com.messageconcept.peoplesyncclient.model.Service
import com.messageconcept.peoplesyncclient.settings.AccountSettings
import com.messageconcept.peoplesyncclient.settings.Settings
import at.bitfire.vcard4android.GroupMethod
import com.google.android.material.snackbar.Snackbar
import java.util.logging.Level
import kotlin.concurrent.thread

class AccountDetailsFragment: Fragment() {

    private lateinit var loginModel: LoginModel
    private lateinit var model: AccountDetailsModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loginModel = ViewModelProvider(requireActivity()).get(LoginModel::class.java)
        model = ViewModelProvider(this).get(AccountDetailsModel::class.java)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val v = LoginAccountDetailsBinding.inflate(inflater, container, false)
        v.lifecycleOwner = viewLifecycleOwner
        v.details = model

        val config = loginModel.configuration ?: throw IllegalStateException()

        model.name.value = config.calDAV?.email ?:
                loginModel.credentials?.userName ?:
                loginModel.credentials?.certificateAlias

        // CardDAV-specific
        val settings = Settings.getInstance(requireActivity())
        v.carddav.visibility = if (config.cardDAV != null) View.VISIBLE else View.GONE
        if (settings.has(AccountSettings.KEY_CONTACT_GROUP_METHOD))
            v.contactGroupMethod.isEnabled = false

        v.createAccount.setOnClickListener {
            val name = model.name.value
            if (name.isNullOrBlank())
                model.nameError.value = getString(R.string.login_account_name_required)
            else {
                // check whether account name already exists
                val am = AccountManager.get(requireActivity())
                if (am.getAccountsByType(getString(R.string.account_type)).any { it.name == name }) {
                    model.nameError.value = getString(R.string.login_account_name_already_taken)
                    return@setOnClickListener
                }

                v.createAccountProgress.visibility = View.VISIBLE
                v.createAccount.visibility = View.GONE

                model.createAccount(
                        name,
                        loginModel.credentials!!,
                        config,
                        GroupMethod.CATEGORIES
                ).observe(viewLifecycleOwner, Observer<Boolean> { success ->
                    if (success)
                        requireActivity().finish()
                    else {
                        Snackbar.make(requireActivity().findViewById(android.R.id.content), R.string.login_account_not_created, Snackbar.LENGTH_LONG).show()

                        v.createAccountProgress.visibility = View.GONE
                        v.createAccount.visibility = View.VISIBLE
                    }
                })
            }
        }

        val forcedGroupMethod = settings.getString(AccountSettings.KEY_CONTACT_GROUP_METHOD)?.let { GroupMethod.valueOf(it) }
        if (forcedGroupMethod != null) {
            v.contactGroupMethod.isEnabled = false
            for ((i, method) in resources.getStringArray(R.array.settings_contact_group_method_values).withIndex()) {
                if (method == forcedGroupMethod.name) {
                    v.contactGroupMethod.setSelection(i)
                    break
                }
            }
        } else
            v.contactGroupMethod.isEnabled = true

        return v.root
    }


    class AccountDetailsModel(
            application: Application
    ): AndroidViewModel(application) {

        val name = MutableLiveData<String>()
        val nameError = MutableLiveData<String>()

        fun clearNameError(s: Editable) {
            nameError.value = null
        }

        fun createAccount(name: String, credentials: Credentials, config: DavResourceFinder.Configuration, groupMethod: GroupMethod): LiveData<Boolean> {
            val result = MutableLiveData<Boolean>()
            val context = getApplication<Application>()
            thread {
                val account = Account(name, context.getString(R.string.account_type))

                // create Android account
                val userData = AccountSettings.initialUserData(credentials)
                Logger.log.log(Level.INFO, "Creating Android account with initial config", arrayOf(account, userData))

                val accountManager = AccountManager.get(context)
                if (!accountManager.addAccountExplicitly(account, credentials.password, userData)) {
                    result.postValue(false)
                    return@thread
                }

                // add entries for account to service DB
                Logger.log.log(Level.INFO, "Writing account configuration to database", config)
                val db = AppDatabase.getInstance(context)
                try {
                    val accountSettings = AccountSettings(context, account)

                    val refreshIntent = Intent(context, DavService::class.java)
                    refreshIntent.action = DavService.ACTION_REFRESH_COLLECTIONS

                    if (config.cardDAV != null) {
                        // insert CardDAV service

                        val id = insertService(db, name, Service.TYPE_CARDDAV, config.cardDAV)

                        // initial CardDAV account settings
                        accountSettings.setGroupMethod(groupMethod)

                        // start CardDAV service detection (refresh collections)
                        refreshIntent.putExtra(DavService.EXTRA_DAV_SERVICE_ID, id)
                        context.startService(refreshIntent)

                        // contact sync is automatically enabled by isAlwaysSyncable="true" in res/xml/sync_address_books.xml
                        accountSettings.setSyncInterval(context.getString(R.string.address_books_authority), Constants.DEFAULT_SYNC_INTERVAL)
                    } else
                        ContentResolver.setIsSyncable(account, context.getString(R.string.address_books_authority), 0)

                    if (config.calDAV != null) {
                        // insert CalDAV service
                        val id = insertService(db, name, Service.TYPE_CALDAV, config.calDAV)

                        // start CalDAV service detection (refresh collections)
                        refreshIntent.putExtra(DavService.EXTRA_DAV_SERVICE_ID, id)
                        context.startService(refreshIntent)

                        // calendar sync is automatically enabled by isAlwaysSyncable="true" in res/xml/sync_calendars.xml
                        accountSettings.setSyncInterval(CalendarContract.AUTHORITY, Constants.DEFAULT_SYNC_INTERVAL)

                    } else {
                        ContentResolver.setIsSyncable(account, CalendarContract.AUTHORITY, 0)
                    }

                } catch(e: InvalidAccountException) {
                    Logger.log.log(Level.SEVERE, "Couldn't access account settings", e)
                    result.postValue(false)
                    return@thread
                }
                result.postValue(true)
            }
            return result
        }

        private fun insertService(db: AppDatabase, accountName: String, type: String, info: DavResourceFinder.Configuration.ServiceInfo): Long {
            // insert service
            val service = Service(0, accountName, type, info.principal)
            val serviceId = db.serviceDao().insertOrReplace(service)

            // insert home sets
            val homeSetDao = db.homeSetDao()
            for (homeSet in info.homeSets) {
                homeSetDao.insertOrReplace(HomeSet(0, serviceId, homeSet))
            }

            // insert collections
            val collectionDao = db.collectionDao()
            for (collection in info.collections.values) {
                collection.serviceId = serviceId
                collectionDao.insertOrReplace(collection)
            }

            return serviceId
        }

    }

}