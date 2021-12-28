/***************************************************************************************************
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 **************************************************************************************************/

package com.messageconcept.peoplesyncclient.model

import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Update

interface SyncableDao<T: IdEntity> {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(items: List<T>): LongArray

    @Update
    fun update(item: T)

    @Delete
    fun delete(item: T)

}