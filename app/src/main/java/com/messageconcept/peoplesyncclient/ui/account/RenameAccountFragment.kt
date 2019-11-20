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
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.widget.EditText
import android.widget.LinearLayout
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import com.messageconcept.peoplesyncclient.DavUtils
import com.messageconcept.peoplesyncclient.R
import com.messageconcept.peoplesyncclient.closeCompat
import com.messageconcept.peoplesyncclient.log.Logger
import com.messageconcept.peoplesyncclient.model.AppDatabase
import com.messageconcept.peoplesyncclient.resource.LocalAddressBook
import com.messageconcept.peoplesyncclient.settings.AccountSettings
import java.util.logging.Level
import kotlin.concurrent.thread

@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
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

    @SuppressLint("Recycle")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val oldAccount: Account = arguments!!.getParcelable(ARG_ACCOUNT)!!

        val editText = EditText(requireActivity()).apply {
            setText(oldAccount.name)
            requestFocus()
        }
        val layout = LinearLayout(requireContext())
        val density = requireActivity().resources.displayMetrics.density.toInt()
        layout.setPadding(8*density, 8*density, 8*density, 8*density)
        layout.addView(editText)

        val model = ViewModelProviders.of(this).get(Model::class.java)
        model.finished.observe(this, Observer {
            this@RenameAccountFragment.requireActivity().finish()
        })

        return AlertDialog.Builder(requireActivity())
                .setTitle(R.string.account_rename)
                .setMessage(R.string.account_rename_new_name)
                .setView(layout)
                .setPositiveButton(R.string.account_rename_rename, DialogInterface.OnClickListener { _, _ ->
                    val newName = editText.text.toString()

                    if (newName == oldAccount.name)
                        return@OnClickListener

                    model.renameAccount(oldAccount, newName)
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

            thread {
                // remember sync intervals
                val oldSettings = AccountSettings(context, oldAccount)
                val authorities = arrayOf(
                        context.getString(R.string.address_books_authority),
                        CalendarContract.AUTHORITY
                )
                val syncIntervals = authorities.map { Pair(it, oldSettings.getSyncInterval(it)) }

                val accountManager = AccountManager.get(context)
                accountManager.renameAccount(oldAccount, newName, {
                    thread {
                        onAccountRenamed(accountManager, oldAccount, newName, syncIntervals)
                    }
                }, null)
            }
        }

        @SuppressLint("Recycle")
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
            Logger.log.log(Level.INFO, "Main thread", Looper.getMainLooper().thread)
            Logger.log.log(Level.INFO, "Current thread", Thread.currentThread())
            db.serviceDao().renameAccount(oldAccount.name, newName)

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