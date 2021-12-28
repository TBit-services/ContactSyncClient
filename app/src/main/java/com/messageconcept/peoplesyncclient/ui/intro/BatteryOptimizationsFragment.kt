/***************************************************************************************************
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 **************************************************************************************************/

package com.messageconcept.peoplesyncclient.ui.intro

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.getSystemService
import androidx.databinding.ObservableBoolean
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import com.messageconcept.peoplesyncclient.App
import com.messageconcept.peoplesyncclient.BuildConfig
import com.messageconcept.peoplesyncclient.R
import com.messageconcept.peoplesyncclient.databinding.IntroBatteryOptimizationsBinding
import com.messageconcept.peoplesyncclient.settings.SettingsManager
import com.messageconcept.peoplesyncclient.ui.UiUtils
import com.messageconcept.peoplesyncclient.ui.intro.BatteryOptimizationsFragment.Model.Companion.HINT_AUTOSTART_PERMISSION
import com.messageconcept.peoplesyncclient.ui.intro.BatteryOptimizationsFragment.Model.Companion.HINT_BATTERY_OPTIMIZATIONS
import org.apache.commons.text.WordUtils
import java.util.*

class BatteryOptimizationsFragment: Fragment() {

    companion object {
        const val REQUEST_IGNORE_BATTERY_OPTIMIZATIONS = 0
    }

    val model by viewModels<Model>()


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = IntroBatteryOptimizationsBinding.inflate(inflater, container, false)
        binding.lifecycleOwner = viewLifecycleOwner
        binding.model = model

        model.shouldBeWhitelisted.observe(viewLifecycleOwner, { shouldBeWhitelisted ->
            @SuppressLint("BatteryLife")
            if (shouldBeWhitelisted && !model.isWhitelisted.value!! && Build.VERSION.SDK_INT >= 23)
               startActivityForResult(Intent(
                       android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                       Uri.parse("package:" + BuildConfig.APPLICATION_ID)
               ), REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        })
        binding.batteryText.text = getString(R.string.intro_battery_text, getString(R.string.app_name))

        binding.autostartHeading.text = getString(R.string.intro_autostart_title, WordUtils.capitalize(Build.MANUFACTURER))
        binding.autostartText.setText(R.string.intro_autostart_text)
        binding.autostartMoreInfo.setOnClickListener {
            UiUtils.launchUri(requireActivity(), App.homepageUrl(requireActivity()).buildUpon()
                    .appendPath("faq").appendPath("synchronization-is-not-run-as-expected")
                    .appendQueryParameter("manufacturer", Build.MANUFACTURER.lowercase(Locale.ROOT)).build())
        }

        binding.infoLeaveUnchecked.text = getString(R.string.intro_leave_unchecked, getString(R.string.app_settings_reset_hints))

        return binding.root
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            model.checkWhitelisted()
    }

    override fun onResume() {
        super.onResume()
        model.checkWhitelisted()
    }


    class Model(app: Application): AndroidViewModel(app) {

        companion object {

            /**
             * Whether the request for whitelisting from battery optimizations shall be shown.
             * If this setting is true or null/not set, the notice shall be shown. Only if this
             * setting is false, the notice shall not be shown.
             */
            const val HINT_BATTERY_OPTIMIZATIONS = "hint_BatteryOptimizations"

            /**
             * Whether the autostart permission notice shall be shown. If this setting is true
             * or null/not set, the notice shall be shown. Only if this setting is false, the notice
             * shall not be shown.
             *
             * Type: Boolean
             */
            const val HINT_AUTOSTART_PERMISSION = "hint_AutostartPermissions"

            /**
             * List of manufacturers which are known to restrict background processes or otherwise
             * block synchronization.
             *
             * See https://www.davx5.com/faq/synchronization-is-not-run-as-expected for why this is evil.
             * See https://github.com/jaredrummler/AndroidDeviceNames/blob/master/json/ for manufacturer values.
             */
            private val evilManufacturers = arrayOf("asus", "huawei", "lenovo", "letv", "meizu", "nokia",
                    "oneplus", "oppo", "samsung", "sony", "vivo", "wiko", "xiaomi", "zte")

            /**
             * Whether the device has been produced by an evil manufacturer.
             *
             * Always true for debug builds (to test the UI).
             *
             * @see evilManufacturers
             */
            val manufacturerWarning =
                    (evilManufacturers.contains(Build.MANUFACTURER.lowercase(Locale.ROOT)) || BuildConfig.DEBUG)

            fun isWhitelisted(context: Context) =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val powerManager = context.getSystemService<PowerManager>()!!
                        powerManager.isIgnoringBatteryOptimizations(BuildConfig.APPLICATION_ID)
                    } else
                        true
        }

        val settings = SettingsManager.getInstance(app)

        val shouldBeWhitelisted = MutableLiveData<Boolean>()
        val isWhitelisted = MutableLiveData<Boolean>()
        val dontShowBattery = object: ObservableBoolean() {
            override fun get() = settings.getBooleanOrNull(HINT_BATTERY_OPTIMIZATIONS) == false
            override fun set(dontShowAgain: Boolean) {
                if (dontShowAgain)
                    settings.putBoolean(HINT_BATTERY_OPTIMIZATIONS, false)
                else
                    settings.remove(HINT_BATTERY_OPTIMIZATIONS)
                notifyChange()
            }
        }

        val dontShowAutostart = object: ObservableBoolean() {
            override fun get() = settings.getBooleanOrNull(HINT_AUTOSTART_PERMISSION) == false
            override fun set(dontShowAgain: Boolean) {
                if (dontShowAgain)
                    settings.putBoolean(HINT_AUTOSTART_PERMISSION, false)
                else
                    settings.remove(HINT_AUTOSTART_PERMISSION)
                notifyChange()
            }
        }

        fun checkWhitelisted() {
            val whitelisted = isWhitelisted(getApplication())
            isWhitelisted.value = whitelisted
            shouldBeWhitelisted.value = whitelisted

            // if PeopleSync is whitelisted, always show a reminder as soon as it's not whitelisted anymore
            if (whitelisted)
                settings.remove(HINT_BATTERY_OPTIMIZATIONS)
        }

    }


    class Factory: IIntroFragmentFactory {

        override fun shouldBeShown(context: Context, settingsManager: SettingsManager) =
                // show fragment when:
                // 1. PeopleSync is not whitelisted yet and "don't show anymore" has not been clicked, and/or
                // 2a. evil manufacturer AND
                // 2b. "don't show anymore" has not been clicked
                if (
                        (!Model.isWhitelisted(context) && settingsManager.getBooleanOrNull(HINT_BATTERY_OPTIMIZATIONS) != false) ||
                        (Model.manufacturerWarning && settingsManager.getBooleanOrNull(HINT_AUTOSTART_PERMISSION) != false)
                )
                    IIntroFragmentFactory.ShowMode.SHOW
                else
                    IIntroFragmentFactory.ShowMode.DONT_SHOW

        override fun create() = BatteryOptimizationsFragment()
    }

}
