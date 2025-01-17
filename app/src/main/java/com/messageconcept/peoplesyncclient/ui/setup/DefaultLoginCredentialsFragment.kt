/***************************************************************************************************
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 **************************************************************************************************/

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
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import com.messageconcept.peoplesyncclient.R
import com.messageconcept.peoplesyncclient.databinding.LoginCredentialsFragmentBinding
import com.messageconcept.peoplesyncclient.model.Credentials
import com.messageconcept.peoplesyncclient.settings.AccountSettings.Companion.KEY_BASE_URL
import com.messageconcept.peoplesyncclient.settings.AccountSettings.Companion.KEY_LOGIN_BASE_URL
import com.messageconcept.peoplesyncclient.settings.AccountSettings.Companion.KEY_LOGIN_PASSWORD
import com.messageconcept.peoplesyncclient.settings.AccountSettings.Companion.KEY_LOGIN_USER_NAME
import com.google.android.material.snackbar.Snackbar
import java.net.URI
import java.net.URISyntaxException

class DefaultLoginCredentialsFragment : Fragment() {

    val loginModel by activityViewModels<LoginModel>()
    val model by viewModels<DefaultLoginCredentialsModel>()


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val v = LoginCredentialsFragmentBinding.inflate(inflater, container, false)
        v.lifecycleOwner = viewLifecycleOwner
        v.model = model

        // initialize model on first call
        if (savedInstanceState == null)
            activity?.intent?.let { model.initialize(it) }

        val restrictionsManager = activity?.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
        val appRestrictions = restrictionsManager.applicationRestrictions
        var entries = restrictionsManager.getManifestRestrictions(activity?.applicationContext?.packageName)

        if (appRestrictions.containsKey(KEY_LOGIN_BASE_URL) && !appRestrictions.getString(KEY_LOGIN_BASE_URL).isNullOrEmpty()) {
            model.baseUrl.value = appRestrictions.getString(KEY_LOGIN_BASE_URL)
            model.loginAdvanced.value = false
            model.loginWithEmailAddress.value = false
            model.loginWithUrlAndUsername.value = true
            model.loginUrlManaged.value = true
        }
        if (appRestrictions.containsKey(KEY_LOGIN_USER_NAME) && !appRestrictions.getString(KEY_LOGIN_USER_NAME).isNullOrEmpty()) {
            model.username.value = appRestrictions.getString(KEY_LOGIN_USER_NAME)
            model.loginUsernameManaged.value = true
        }
        if (appRestrictions.containsKey(KEY_LOGIN_PASSWORD) && !appRestrictions.getString(KEY_LOGIN_PASSWORD).isNullOrEmpty()) {
            model.password.value = appRestrictions.getString(KEY_LOGIN_PASSWORD)
            model.loginPasswordManaged.value = true
        }

        if (model.loginUrlManaged.value == true && model.loginUsernameManaged.value == true && model.loginPasswordManaged.value == true)
            if (validate())
                requireFragmentManager().beginTransaction()
                        .replace(android.R.id.content, DetectConfigurationFragment(), null)
                        .commit()

        v.loginUrlBaseUrlEdittext.setAdapter(DefaultLoginCredentialsModel.LoginUrlAdapter(requireActivity()))

        v.selectCertificate.setOnClickListener {
            KeyChain.choosePrivateKeyAlias(requireActivity(), { alias ->
                Handler(Looper.getMainLooper()).post {

                    // Show a Snackbar to add a certificate if no certificate was found
                    // API Versions < 29 still handle this automatically
                    if (alias == null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)  {
                        Snackbar.make(v.root, R.string.login_no_certificate_found, Snackbar.LENGTH_LONG)
                                .setAction(R.string.login_install_certificate) {
                                    startActivity(KeyChain.createInstallIntent())
                                }
                                .show()
                        }
                    else
                       model.certificateAlias.value = alias
                }
            }, null, null, null, -1, model.certificateAlias.value)
        }

        v.login.setOnClickListener {
            if (validate())
                parentFragmentManager.beginTransaction()
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
                val originalUrl = model.baseUrl.value.orEmpty()
                val uri = URI(originalUrl)
                if (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
                    // http:// or https:// scheme → OK
                    valid = true
                    loginModel.baseURI = uri
                } else if (uri.scheme == null) {
                    // empty URL scheme, assume https://
                    model.baseUrl.value = "https://$originalUrl"
                    validateUrl()
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
                val baseUrl = if (model.loginUrlManaged.value == true) model.baseUrl.value else null

                if (valid)
                    loginModel.credentials = Credentials(username, password, null, baseUrl)
            }

            model.loginAdvanced.value == true -> {
                validateUrl()

                model.certificateAliasError.value = null
                val alias = model.certificateAlias.value
                if (model.loginUseClientCertificate.value == true && alias.isNullOrBlank()) {
                    valid = false
                    model.certificateAliasError.value = ""      // error icon without text
                }

                model.usernameError.value = null
                val username = model.username.value

                model.passwordError.value = null
                val password = model.password.value

                if (model.loginUseUsernamePassword.value == true) {
                    if (username.isNullOrEmpty()) {
                        valid = false
                        model.usernameError.value = getString(R.string.login_user_name_required)
                    }
                    validatePassword()
                }

                // loginModel.credentials stays null if login is tried with Base URL only
                if (valid)
                    loginModel.credentials = when {
                        // username/password and client certificate
                        model.loginUseUsernamePassword.value == true && model.loginUseClientCertificate.value == true ->
                            Credentials(username, password, alias)

                        // user/name password only
                        model.loginUseUsernamePassword.value == true && model.loginUseClientCertificate.value == false ->
                            Credentials(username, password)

                        // client certificate only
                        model.loginUseUsernamePassword.value == false && model.loginUseClientCertificate.value == true ->
                            Credentials(certificateAlias = alias)

                        // anonymous (neither username/password nor client certificate)
                        else ->
                            null
                    }
            }
        }

        return valid
    }


    class Factory : LoginCredentialsFragment {

        override fun getFragment(intent: Intent) = DefaultLoginCredentialsFragment()

    }

}
