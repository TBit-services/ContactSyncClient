/*
 * Copyright © Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */

package at.bitfire.davdroid.settings

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import android.util.NoSuchPropertyException
import androidx.preference.PreferenceManager
import at.bitfire.davdroid.log.Logger
import at.bitfire.davdroid.model.AppDatabase

class SharedPreferencesProvider(
        val context: Context,
        val settingsManager: SettingsManager
): SettingsProvider, SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        private const val META_VERSION = "version"
        private const val CURRENT_VERSION = 0
    }

    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)

    init {
        val meta = context.getSharedPreferences("meta", MODE_PRIVATE)
        val version = meta.getInt(META_VERSION, -1)
        if (version == -1) {
            // first call, check whether to migrate from SQLite database (DAVdroid <1.9)
            firstCall(context)
            meta.edit().putInt(META_VERSION, CURRENT_VERSION).apply()
        }

        preferences.registerOnSharedPreferenceChangeListener(this)
    }

    override fun forceReload() {
    }

    override fun close() {
        preferences.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun canWrite() = true

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {
        settingsManager.onSettingsChanged()
    }


    override fun contains(key: String) = preferences.contains(key)

    private fun<T> getValue(key: String, reader: (SharedPreferences) -> T): T? =
            try {
                if (preferences.contains(key))
                    reader(preferences)
                else
                    null
            } catch(e: ClassCastException) {
                null
            }

    override fun getBoolean(key: String) =
            getValue(key) { preferences -> preferences.getBoolean(key, /* will never be used: */ false) }

    override fun getInt(key: String) =
            getValue(key) { preferences -> preferences.getInt(key, /* will never be used: */ -1) }

    override fun getLong(key: String) =
            getValue(key) { preferences -> preferences.getLong(key, /* will never be used: */ -1) }

    override fun getString(key: String): String? =
            preferences.getString(key, /* will never be used: */ null)


    private fun<T> putValue(key: String, value: T?, writer: (SharedPreferences.Editor, T) -> Unit) {
        if (value == null)
            remove(key)
        else {
            Logger.log.fine("Writing setting $key = $value")
            val edit = preferences.edit()
            writer(edit, value)
            edit.apply()
        }
    }

    override fun putBoolean(key: String, value: Boolean?) =
            putValue(key, value) { editor, v -> editor.putBoolean(key, v) }

    override fun putInt(key: String, value: Int?) =
            putValue(key, value) { editor, v -> editor.putInt(key, v) }

    override fun putLong(key: String, value: Long?) =
            putValue(key, value) { editor, v -> editor.putLong(key, v) }

    override fun putString(key: String, value: String?) =
            putValue(key, value) { editor, v -> editor.putString(key, v) }

    override fun remove(key: String) {
        Logger.log.fine("Removing setting $key")
        preferences.edit().remove(key).apply()
    }


    private fun firstCall(context: Context) {
        // remove possible artifacts from DAVdroid <1.9
        val edit = preferences.edit()
        edit.remove("override_proxy")
        edit.remove("proxy_host")
        edit.remove("proxy_port")
        edit.remove("log_to_external_storage")
        edit.apply()

        // open ServiceDB to upgrade it and possibly migrate settings
        AppDatabase.getInstance(context)
    }


    class Factory : SettingsProviderFactory {
        override fun getProviders(context: Context, settingsManager: SettingsManager) = listOf(SharedPreferencesProvider(context, settingsManager))
    }

}