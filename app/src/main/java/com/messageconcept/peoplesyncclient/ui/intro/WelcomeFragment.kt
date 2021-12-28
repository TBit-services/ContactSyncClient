/***************************************************************************************************
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 **************************************************************************************************/

package com.messageconcept.peoplesyncclient.ui.intro

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.messageconcept.peoplesyncclient.App
import com.messageconcept.peoplesyncclient.BuildConfig
import com.messageconcept.peoplesyncclient.databinding.IntroWelcomeBinding
import com.messageconcept.peoplesyncclient.settings.SettingsManager

class WelcomeFragment: Fragment() {

    private var _binding: IntroWelcomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = IntroWelcomeBinding.inflate(inflater, container, false)

        if (true /* ose build */) {
            binding.logo.apply {
                alpha = 0f
                animate()
                    .alpha(1f)
                    .setDuration(300)
            }
            binding.yourDataYourChoice.apply {
                translationX = -1000f
                animate()
                    .translationX(0f)
                    .setDuration(300)
            }
            binding.takeControl.apply {
                translationX = 1000f
                animate()
                    .translationX(0f)
                    .setDuration(300)
            }
        }

        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }


    class Factory : IIntroFragmentFactory {

        override fun shouldBeShown(context: Context, settingsManager: SettingsManager) = IIntroFragmentFactory.ShowMode.SHOW_NOT_ALONE

        override fun create() = WelcomeFragment()

    }

}
