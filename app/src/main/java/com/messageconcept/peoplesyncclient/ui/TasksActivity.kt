/***************************************************************************************************
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 **************************************************************************************************/

package com.messageconcept.peoplesyncclient.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class TasksActivity: AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null)
            supportFragmentManager.beginTransaction()
                    .add(android.R.id.content, TasksFragment())
                    .commit()
    }

}