package com.messageconcept.peoplesyncclient.model

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy

@Dao
interface SyncStatsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrReplace(syncStats: SyncStats)

}