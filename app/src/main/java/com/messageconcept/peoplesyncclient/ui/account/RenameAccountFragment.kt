/***************************************************************************************************
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 **************************************************************************************************/

package com.messageconcept.peoplesyncclient.ui.account

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.app.Application
import android.app.Dialog
import android.content.ContentResolver
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.WorkerThread
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.messageconcept.peoplesyncclient.DavUtils
import com.messageconcept.peoplesyncclient.InvalidAccountException
import com.messageconcept.peoplesyncclient.R
import com.messageconcept.peoplesyncclient.closeCompat
import com.messageconcept.peoplesyncclient.log.Logger
import com.messageconcept.peoplesyncclient.model.AppDatabase
import com.messageconcept.peoplesyncclient.resource.LocalAddressBook
import com.messageconcept.peoplesyncclient.settings.AccountSettings
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import java.util.logging.Level

class RenameAccountFragment: DialogFragment() {

    companion object {

        private const val ARG_ACCOUNT = "account"

        fun newInstance(account: Account): RenameAccountFragment {
            val fragment = RenameAccountFragment()
            val args = Bundle(1)
            args.putParcelable(ARG_ACCOUNT, account)
            fragment.arguments = args
            return fragment
        }

    }

    val model by viewModels<Model>()


    @SuppressLint("Recycle")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val oldAccount: Account = requireArguments().getParcelable(ARG_ACCOUNT)!!

        val editText = EditText(requireActivity()).apply {
            setText(oldAccount.name)
            requestFocus()
        }
        val layout = LinearLayout(requireContext())
        val density = requireActivity().resources.displayMetrics.density.toInt()
        layout.setPadding(8*density, 8*density, 8*density, 8*density)
        layout.addView(editText)

        model.finished.observe(this, {
            this@RenameAccountFragment.requireActivity().finish()
        })

        return MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.account_rename)
                .setMessage(R.string.account_rename_new_name)
                .setView(layout)
                .setPositiveButton(R.string.account_rename_rename, DialogInterface.OnClickListener { _, _ ->
                    val newName = editText.text.toString()
                    if (newName == oldAccount.name)
                        return@OnClickListener

                    model.renameAccount(oldAccount, newName)

                    requireActivity().finish()
                })
                .setNegativeButton(android.R.string.cancel) { _, _ -> }
                .create()
    }


    class Model(
            application: Application
    ): AndroidViewModel(application) {

        val finished = MutableLiveData<Boolean>()

        fun renameAccount(oldAccount: Account, newName: String) {
            val context = getApplication<Application>()

            // remember sync intervals
            val oldSettings = try {
                AccountSettings(context, oldAccount)
            } catch (e: InvalidAccountException) {
                Toast.makeText(context, R.string.account_invalid, Toast.LENGTH_LONG).show()
                finished.value = true
                return
            }

            val authorities = arrayOf(
                    context.getString(R.string.address_books_authority)
            )
            val syncIntervals = authorities.map { Pair(it, oldSettings.getSyncInterval(it)) }

            val accountManager = AccountManager.get(context)
            try {
                accountManager.renameAccount(oldAccount, newName, {
                    if (it.result?.name == newName /* success */)
                        viewModelScope.launch(Dispatchers.Default + NonCancellable) {
                            onAccountRenamed(accountManager, oldAccount, newName, syncIntervals)
                        }
                }, null)
            } catch (e: Exception) {
                Logger.log.log(Level.WARNING, "Couldn't rename account", e)
                Toast.makeText(context, R.string.account_rename_couldnt_rename, Toast.LENGTH_LONG).show()
            }
        }

        @SuppressLint("Recycle")
        @WorkerThread
        fun onAccountRenamed(accountManager: AccountManager, oldAccount: Account, newName: String, syncIntervals: List<Pair<String, Long?>>) {
            // account has now been renamed
            Logger.log.info("Updating account name references")
            val context = getApplication<Application>()

            // cancel maybe running synchronization
            ContentResolver.cancelSync(oldAccount, null)
            for (addrBookAccount in accountManager.getAccountsByType(context.getString(R.string.account_type_address_book)))
                ContentResolver.cancelSync(addrBookAccount, null)

            // update account name references in database
            val db = AppDatabase.getInstance(context)
            try {
                db.serviceDao().renameAccount(oldAccount.name, newName)
            } catch (e: Exception) {
                Toast.makeText(context, R.string.account_rename_couldnt_rename, Toast.LENGTH_LONG).show()
                Logger.log.log(Level.SEVERE, "Couldn't update service DB", e)
                return
            }

            // update main account of address book accounts
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED)
                try {
                    context.contentResolver.acquireContentProviderClient(ContactsContract.AUTHORITY)?.let { provider ->
                        for (addrBookAccount in accountManager.getAccountsByType(context.getString(R.string.account_type_address_book)))
                            try {
                                val addressBook = LocalAddressBook(context, addrBookAccount, provider)
                                if (oldAccount == addressBook.mainAccount)
                                    addressBook.mainAccount = Account(newName, oldAccount.type)
                            } finally {
                                provider.closeCompat()
                            }
                    }
                } catch (e: Exception) {
                    Logger.log.log(Level.SEVERE, "Couldn't update address book accounts", e)
                }

            // calendar provider doesn't allow changing account_name of Events
            // (all events will have to be downloaded again)

            // retain sync intervals
            val newAccount = Account(newName, oldAccount.type)
            val newSettings = AccountSettings(context, newAccount)
            for ((authority, interval) in syncIntervals) {
                if (interval == null)
                    ContentResolver.setIsSyncable(newAccount, authority, 0)
                else {
                    ContentResolver.setIsSyncable(newAccount, authority, 1)
                    newSettings.setSyncInterval(authority, interval)
                }
            }

            // synchronize again
            DavUtils.requestSync(context, newAccount)
        }

    }

}