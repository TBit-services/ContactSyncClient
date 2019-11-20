/*
 * Copyright © Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */

package com.messageconcept.peoplesyncclient.ui.setup

import android.content.Context
import android.content.Intent
import android.content.RestrictionsManager
import android.net.MailTo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.security.KeyChain
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProviders
import com.messageconcept.peoplesyncclient.R
import com.messageconcept.peoplesyncclient.databinding.LoginCredentialsFragmentBinding
import com.messageconcept.peoplesyncclient.model.Credentials
import java.net.URI
import java.net.URISyntaxException

class DefaultLoginCredentialsFragment: Fragment() {

    companion object {
        private const val KEY_LOGIN_BASE_URL = "login_base_url"
        private const val KEY_LOGIN_USER_NAME = "login_user_name"
    }

    private lateinit var model: DefaultLoginCredentialsModel
    private lateinit var loginModel: LoginModel

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        model = ViewModelProviders.of(this).get(DefaultLoginCredentialsModel::class.java)
        loginModel = ViewModelProviders.of(requireActivity()).get(LoginModel::class.java)

        val v = LoginCredentialsFragmentBinding.inflate(inflater, container, false)
        v.lifecycleOwner = viewLifecycleOwner
        v.model = model

        // initialize model on first call
        if (savedInstanceState == null)
            activity?.intent?.let { model.initialize(it) }


        val restrictionsManager = activity?.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
        val appRestrictions = restrictionsManager.applicationRestrictions
        var entries = restrictionsManager.getManifestRestrictions(activity?.applicationContext?.packageName)

        if (appRestrictions.containsKey(KEY_LOGIN_BASE_URL)) {
            model.baseUrl.value = appRestrictions.getString(KEY_LOGIN_BASE_URL)
            model.loginWithEmailAddress.value = false
            model.loginWithUrlAndCertificate.value = false
            model.loginWithUrlAndUsername.value = true
        }
        if (appRestrictions.containsKey(KEY_LOGIN_USER_NAME)) {
            model.username.value = appRestrictions.getString(KEY_LOGIN_USER_NAME)
        }

        v.selectCertificate.setOnClickListener {
            KeyChain.choosePrivateKeyAlias(requireActivity(), { alias ->
                Handler(Looper.getMainLooper()).post {
                    model.certificateAlias.value = alias
                }
            }, null, null, null, -1, model.certificateAlias.value)
        }

        v.login.setOnClickListener {
            if (validate())
                requireFragmentManager().beginTransaction()
                        .replace(android.R.id.content, DetectConfigurationFragment(), null)
                        .addToBackStack(null)
                        .commit()
        }

        return v.root
    }

    private fun validate(): Boolean {
        var valid = false

        fun validateUrl() {
            model.baseUrlError.value = null
            try {
                val uri = URI(model.baseUrl.value.orEmpty())
                if (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
                    valid = true
                    loginModel.baseURI = uri
                } else
                    model.baseUrlError.value = getString(R.string.login_url_must_be_http_or_https)
            } catch (e: Exception) {
                model.baseUrlError.value = e.localizedMessage
            }
        }

        fun validatePassword(): String? {
            model.passwordError.value = null
            val password = model.password.value
            if (password.isNullOrEmpty()) {
                valid = false
                model.passwordError.value = getString(R.string.login_password_required)
            }
            return password
        }

        when {
            model.loginWithEmailAddress.value == true -> {
                // login with email address
                model.usernameError.value = null
                val email = model.username.value.orEmpty()
                if (email.matches(Regex(".+@.+"))) {
                    // already looks like an email address
                    try {
                        loginModel.baseURI = URI(MailTo.MAILTO_SCHEME, email, null)
                        valid = true
                    } catch (e: URISyntaxException) {
                        model.usernameError.value = e.localizedMessage
                    }
                } else {
                    valid = false
                    model.usernameError.value = getString(R.string.login_email_address_error)
                }

                val password = validatePassword()

                if (valid)
                    loginModel.credentials = Credentials(email, password, null)
            }

            model.loginWithUrlAndUsername.value == true -> {
                validateUrl()

                model.usernameError.value = null
                val username = model.username.value
                if (username.isNullOrEmpty()) {
                    valid = false
                    model.usernameError.value = getString(R.string.login_user_name_required)
                }

                val password = validatePassword()

                if (valid)
                    loginModel.credentials = Credentials(username, password, null)
            }

            model.loginWithUrlAndCertificate.value == true -> {
                validateUrl()

                model.certificateAliasError.value = null
                val alias = model.certificateAlias.value
                if (alias.isNullOrBlank()) {
                    valid = false
                    model.certificateAliasError.value = ""      // error icon without text
                }

                if (valid)
                    loginModel.credentials = Credentials(null, null, alias)
            }
        }

        return valid
    }


    class Factory: ILoginCredentialsFragment {

        override fun getFragment(intent: Intent) = DefaultLoginCredentialsFragment()

    }

}
