/***************************************************************************************************
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 **************************************************************************************************/

package com.messageconcept.peoplesyncclient.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import com.messageconcept.peoplesyncclient.App
import com.messageconcept.peoplesyncclient.BuildConfig
import com.messageconcept.peoplesyncclient.R

class DefaultAccountsDrawerHandler: IAccountsDrawerHandler {

    companion object {
        private const val BETA_FEEDBACK_URI = "mailto:peoplesync.app@messageconcept.com?subject=${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME} feedback (${BuildConfig.VERSION_CODE})"
    }


    override fun initMenu(context: Context, menu: Menu) {
        if (BuildConfig.VERSION_NAME.contains("-alpha") || BuildConfig.VERSION_NAME.contains("-beta") || BuildConfig.VERSION_NAME.contains("-rc"))
            menu.findItem(R.id.nav_beta_feedback).isVisible = true
    }

    override fun onNavigationItemSelected(activity: Activity, item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_about ->
                activity.startActivity(Intent(activity, AboutActivity::class.java))
            R.id.nav_beta_feedback ->
                if (!UiUtils.launchUri(activity, Uri.parse(BETA_FEEDBACK_URI), Intent.ACTION_SENDTO, false))
                    Toast.makeText(activity, R.string.install_email_client, Toast.LENGTH_LONG).show()
            R.id.nav_app_settings ->
                activity.startActivity(Intent(activity, AppSettingsActivity::class.java))

            R.id.nav_website ->
                UiUtils.launchUri(activity,
                        App.homepageUrl(activity))

            else ->
                return false
        }

        return true
    }

}
