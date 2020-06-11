/*
 * Copyright © Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */

package com.messageconcept.peoplesyncclient.syncadapter

import android.accounts.Account
import android.content.Context
import android.content.SyncResult
import android.os.Bundle
import at.bitfire.dav4jvm.DavCalendar
import at.bitfire.dav4jvm.DavResponseCallback
import at.bitfire.dav4jvm.Response
import at.bitfire.dav4jvm.exception.DavException
import at.bitfire.dav4jvm.property.CalendarData
import at.bitfire.dav4jvm.property.GetCTag
import at.bitfire.dav4jvm.property.GetETag
import at.bitfire.dav4jvm.property.SyncToken
import com.messageconcept.peoplesyncclient.DavUtils
import com.messageconcept.peoplesyncclient.R
import com.messageconcept.peoplesyncclient.log.Logger
import com.messageconcept.peoplesyncclient.model.SyncState
import com.messageconcept.peoplesyncclient.resource.LocalResource
import com.messageconcept.peoplesyncclient.resource.LocalTask
import com.messageconcept.peoplesyncclient.resource.LocalTaskList
import com.messageconcept.peoplesyncclient.settings.AccountSettings
import at.bitfire.ical4android.InvalidCalendarException
import at.bitfire.ical4android.Task
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.Reader
import java.io.StringReader
import java.util.logging.Level

/**
 * Synchronization manager for CalDAV collections; handles tasks (VTODO)
 */
class TasksSyncManager(
        context: Context,
        account: Account,
        accountSettings: AccountSettings,
        extras: Bundle,
        authority: String,
        syncResult: SyncResult,
        localCollection: LocalTaskList
): SyncManager<LocalTask, LocalTaskList, DavCalendar>(context, account, accountSettings, extras, authority, syncResult, localCollection) {

    override fun prepare(): Boolean {
        collectionURL = (localCollection.syncId ?: return false).toHttpUrlOrNull() ?: return false
        davCollection = DavCalendar(httpClient.okHttpClient, collectionURL)

        return true
    }

    override fun queryCapabilities() =
            useRemoteCollection {
                var syncState: SyncState? = null
                it.propfind(0, GetCTag.NAME, SyncToken.NAME) { response, relation ->
                    if (relation == Response.HrefRelation.SELF)
                    syncState = syncState(response)
                }
                syncState
            }

    override fun syncAlgorithm() = SyncAlgorithm.PROPFIND_REPORT

    override fun prepareUpload(resource: LocalTask): RequestBody = useLocal(resource) {
        val task = requireNotNull(resource.task)
        Logger.log.log(Level.FINE, "Preparing upload of task ${resource.fileName}", task)

        val os = ByteArrayOutputStream()
        task.write(os)

        os.toByteArray().toRequestBody(DavCalendar.MIME_ICALENDAR_UTF8)
    }

    override fun listAllRemote(callback: DavResponseCallback) {
        useRemoteCollection { remote ->
            Logger.log.info("Querying tasks")
            remote.calendarQuery("VTODO", null, null, callback)
        }
    }

    override fun downloadRemote(bunch: List<HttpUrl>) {
        Logger.log.info("Downloading ${bunch.size} iCalendars: $bunch")
        // multiple iCalendars, use calendar-multi-get
        useRemoteCollection {
            it.multiget(bunch) { response, _ ->
                useRemote(response) {
                    if (!response.isSuccess()) {
                        Logger.log.warning("Received non-successful multiget response for ${response.href}")
                        return@useRemote
                    }

                    val eTag = response[GetETag::class.java]?.eTag
                            ?: throw DavException("Received multi-get response without ETag")

                    val calendarData = response[CalendarData::class.java]
                    val iCal = calendarData?.iCalendar
                            ?: throw DavException("Received multi-get response without address data")

                    processVTodo(DavUtils.lastSegmentOfUrl(response.href), eTag, StringReader(iCal))
                }
            }
        }
    }

    override fun postProcess() {
        val touched = localCollection.touchRelations()
        Logger.log.info("Touched $touched relations")
    }

    // helpers

    private fun processVTodo(fileName: String, eTag: String, reader: Reader) {
        val tasks: List<Task>
        try {
            tasks = Task.tasksFromReader(reader)
        } catch (e: InvalidCalendarException) {
            Logger.log.log(Level.SEVERE, "Received invalid iCalendar, ignoring", e)
            notifyInvalidResource(e, fileName)
            return
        }

        if (tasks.size == 1) {
            val newData = tasks.first()

            // update local task, if it exists
            useLocal(localCollection.findByName(fileName)) { local ->
                if (local != null) {
                    Logger.log.log(Level.INFO, "Updating $fileName in local task list", newData)
                    local.eTag = eTag
                    local.update(newData)
                    syncResult.stats.numUpdates++
                } else {
                    Logger.log.log(Level.INFO, "Adding $fileName to local task list", newData)
                    useLocal(LocalTask(localCollection, newData, fileName, eTag, LocalResource.FLAG_REMOTELY_PRESENT)) {
                        it.add()
                    }
                    syncResult.stats.numInserts++
                }
            }
        } else
            Logger.log.info("Received VCALENDAR with not exactly one VTODO; ignoring $fileName")
    }

    override fun notifyInvalidResourceTitle(): String =
            context.getString(R.string.sync_invalid_task)

}