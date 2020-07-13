package com.messageconcept.peoplesyncclient.ui.intro

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.text.HtmlCompat
import androidx.databinding.ObservableBoolean
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.messageconcept.peoplesyncclient.App
import com.messageconcept.peoplesyncclient.PackageChangedReceiver
import com.messageconcept.peoplesyncclient.R
import com.messageconcept.peoplesyncclient.databinding.IntroOpentasksBinding
import com.messageconcept.peoplesyncclient.resource.LocalTaskList
import com.messageconcept.peoplesyncclient.settings.SettingsManager
import com.messageconcept.peoplesyncclient.ui.UiUtils
import com.messageconcept.peoplesyncclient.ui.intro.IIntroFragmentFactory.ShowMode
import com.messageconcept.peoplesyncclient.ui.intro.OpenTasksFragment.Model.Companion.HINT_OPENTASKS_NOT_INSTALLED
import com.google.android.material.snackbar.Snackbar

class OpenTasksFragment: Fragment() {

    val model by viewModels<Model>()


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = IntroOpentasksBinding.inflate(inflater, container, false)
        binding.lifecycleOwner = viewLifecycleOwner
        binding.model = model

        model.shallBeInstalled.observe(viewLifecycleOwner, Observer { shallBeInstalled ->
            if (shallBeInstalled && model.isInstalled.value == false) {
                // uncheck switch for the moment (until the app is installed)
                model.shallBeInstalled.value = false

                // prompt to install OpenTasks
                val uri = Uri.parse("market://details?id=org.dmfs.tasks")
                val intent = Intent(Intent.ACTION_VIEW, uri)
                if (intent.resolveActivity(requireActivity().packageManager) != null)
                    startActivity(intent)
                else
                    Snackbar.make(binding.root, R.string.intro_tasks_no_app_store, Snackbar.LENGTH_LONG).show()
            }
        })

        binding.text1.apply {
            text = HtmlCompat.fromHtml(getString(R.string.intro_tasks_text1, getString(R.string.app_name)), 0)
            movementMethod = LinkMovementMethod.getInstance()
        }

        binding.moreInfo.setOnClickListener {
            val context = requireActivity()
            UiUtils.launchUri(context, App.homepageUrl(context).buildUpon()
                    .appendEncodedPath("faq/tasks/advanced-task-features")
                    .build(), toastInstallBrowser = true)
        }
        binding.infoLeaveUnchecked.text = getString(R.string.intro_leave_unchecked, getString(R.string.app_settings_reset_hints))

        return binding.root
    }


    class Model(app: Application) : AndroidViewModel(app) {

        companion object {

            /**
             * Whether this fragment (which asks for OpenTasks installation) shall be shown.
             * If this setting is true or null/not set, the notice shall be shown. Only if this
             * setting is false, the notice shall not be shown.
             */
            const val HINT_OPENTASKS_NOT_INSTALLED = "hint_OpenTasksNotInstalled"

        }

        var isInstalled = MutableLiveData<Boolean>()
        val shallBeInstalled = MutableLiveData<Boolean>()
        val tasksWatcher = object: PackageChangedReceiver(app) {
            override fun onReceive(context: Context?, intent: Intent?) {
                checkInstalled()
            }
        }

        val dontShow = object: ObservableBoolean() {
            val settings = SettingsManager.getInstance(getApplication())
            override fun get() = settings.getBooleanOrNull(HINT_OPENTASKS_NOT_INSTALLED) == false
            override fun set(dontShowAgain: Boolean) {
                if (dontShowAgain)
                    settings.putBoolean(HINT_OPENTASKS_NOT_INSTALLED, false)
                else
                    settings.remove(HINT_OPENTASKS_NOT_INSTALLED)
                notifyChange()
            }
        }

        init {
            checkInstalled()
        }

        override fun onCleared() {
            tasksWatcher.close()
        }

        fun checkInstalled() {
            val installed = LocalTaskList.tasksProviderAvailable(getApplication())
            isInstalled.postValue(installed)
            shallBeInstalled.postValue(installed)
        }

    }


    class Factory: IIntroFragmentFactory {

        override fun shouldBeShown(context: Context, settingsManager: SettingsManager): ShowMode {
            // On Android <6, OpenTasks must be installed before DAVx5, so this fragment is not useful.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
                return ShowMode.DONT_SHOW

            return if (!LocalTaskList.tasksProviderAvailable(context) && settingsManager.getBooleanOrNull(HINT_OPENTASKS_NOT_INSTALLED) != false)
                ShowMode.SHOW
            else
                ShowMode.DONT_SHOW
        }

        override fun create() = OpenTasksFragment()

    }

}