package com.messageconcept.peoplesyncclient.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import okhttp3.HttpUrl

@Entity(tableName = "homeset",
        foreignKeys = [
            ForeignKey(entity = Service::class, parentColumns = arrayOf("id"), childColumns = arrayOf("serviceId"), onDelete = ForeignKey.CASCADE)
        ],
        indices = [
            // index by service; no duplicate URLs per service
            Index("serviceId", "url", unique = true)
        ]
)
data class HomeSet(
    @PrimaryKey(autoGenerate = true)
    override var id: Long,

    var serviceId: Long,
    var url: HttpUrl,

    var privBind: Boolean = true,

    var displayName: String? = null
): IdEntity()