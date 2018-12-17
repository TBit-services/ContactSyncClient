/***************************************************************************************************
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 **************************************************************************************************/

package com.messageconcept.peoplesyncclient

import android.accounts.Account
import android.app.IntentService
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import androidx.annotation.WorkerThread
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import at.bitfire.dav4jvm.DavResource
import at.bitfire.dav4jvm.Response
import at.bitfire.dav4jvm.UrlUtils
import at.bitfire.dav4jvm.exception.HttpException
import at.bitfire.dav4jvm.property.*
import com.messageconcept.peoplesyncclient.log.Logger
import com.messageconcept.peoplesyncclient.model.*
import com.messageconcept.peoplesyncclient.model.Collection
import com.messageconcept.peoplesyncclient.settings.AccountSettings
import com.messageconcept.peoplesyncclient.ui.DebugInfoActivity
import com.messageconcept.peoplesyncclient.ui.NotificationUtils
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.lang.ref.WeakReference
import java.util.*
import java.util.logging.Level
import kotlin.collections.*

@Suppress("DEPRECATION")
class DavService: IntentService("DavService") {

    companion object {

        const val ACTION_REFRESH_COLLECTIONS = "refreshCollections"
        const val EXTRA_DAV_SERVICE_ID = "davServiceID"

        /** Initialize a forced synchronization. Expects intent data
            to be an URI of this format:
            contents://<authority>/<account.type>/<account name>
         **/
        const val ACTION_FORCE_SYNC = "forceSync"

        const val AUTO_SYNC = "autoSync"

        val DAV_COLLECTION_PROPERTIES = arrayOf(
                ResourceType.NAME,
                CurrentUserPrivilegeSet.NAME,
                DisplayName.NAME,
                Owner.NAME,
                AddressbookDescription.NAME, SupportedAddressData.NAME,
                CalendarDescription.NAME, CalendarColor.NAME, SupportedCalendarComponentSet.NAME,
                Source.NAME
        )

    }

    /**
     * List of [Service] IDs for which the collections are currently refreshed
     */
    private val runningRefresh = Collections.synchronizedSet(HashSet<Long>())

    /**
     * Currently registered [RefreshingStatusListener]s, which will be notified
     * when a collection refresh status changes
     */
    private val refreshingStatusListeners = Collections.synchronizedList(LinkedList<WeakReference<RefreshingStatusListener>>())

    @WorkerThread
    override fun onHandleIntent(intent: Intent?) {
        if (intent == null)
            return

        val id = intent.getLongExtra(EXTRA_DAV_SERVICE_ID, -1)
        val auto = intent.getBooleanExtra(AUTO_SYNC, false)

        when (intent.action) {
            ACTION_REFRESH_COLLECTIONS ->
                if (runningRefresh.add(id)) {
                    refreshingStatusListeners.forEach { listener ->
                        listener.get()?.onDavRefreshStatusChanged(id, true)
                    }

                    val db = AppDatabase.getInstance(this@DavService)
                    refreshCollections(db, id, auto)
                }

            ACTION_FORCE_SYNC -> {
                val uri = intent.data!!
                val authority = uri.authority!!
                val account = Account(
                        uri.pathSegments[1],
                        uri.pathSegments[0]
                )
                forceSync(authority, account)
            }

        }
    }


    /* BOUND SERVICE PART
       for communicating with the activities
    */

    interface RefreshingStatusListener {
        fun onDavRefreshStatusChanged(id: Long, refreshing: Boolean)
    }

    private val binder = InfoBinder()

    inner class InfoBinder: Binder() {
        fun isRefreshing(id: Long) = runningRefresh.contains(id)

        fun addRefreshingStatusListener(listener: RefreshingStatusListener, callImmediateIfRunning: Boolean) {
            refreshingStatusListeners += WeakReference<RefreshingStatusListener>(listener)
            if (callImmediateIfRunning)
                synchronized(runningRefresh) {
                    for (id in runningRefresh)
                        listener.onDavRefreshStatusChanged(id, true)
                }
        }

        fun removeRefreshingStatusListener(listener: RefreshingStatusListener) {
            val iter = refreshingStatusListeners.iterator()
            while (iter.hasNext()) {
                val item = iter.next().get()
                if (item == listener || item == null)
                    iter.remove()
            }
        }
    }

    override fun onBind(intent: Intent?) = binder



    /* ACTION RUNNABLES
       which actually do the work
     */

    private fun forceSync(authority: String, account: Account) {
        Logger.log.info("Forcing $authority synchronization of $account")
        val extras = Bundle(2)
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)        // manual sync
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)     // run immediately (don't queue)
        ContentResolver.requestSync(account, authority, extras)
    }

    private fun refreshCollections(db: AppDatabase, serviceId: Long, autoSync: Boolean) {
        val homeSetDao = db.homeSetDao()
        val collectionDao = db.collectionDao()

        val service = db.serviceDao().get(serviceId) ?: throw IllegalArgumentException("Service not found")
        val account = Account(service.accountName, getString(R.string.account_type))

        val homeSets = homeSetDao.getByService(serviceId).associateBy { it.url }.toMutableMap()
        val collections = collectionDao.getByService(serviceId).associateBy { it.url }.toMutableMap()

        /**
         * Checks if the given URL defines home sets and adds them to the home set list.
         *
         * @param personal Whether this is the "outer" call of the recursion.
         *
         * *true* = found home sets belong to the current-user-principal; recurse if
         * calendar proxies or group memberships are found
         *
         * *false* = found home sets don't directly belong to the current-user-principal; don't recurse
         *
         * @throws java.io.IOException
         * @throws HttpException
         * @throws at.bitfire.dav4jvm.exception.DavException
         */
        fun queryHomeSets(client: OkHttpClient, url: HttpUrl, personal: Boolean = true) {
            val related = mutableSetOf<HttpUrl>()

            fun findRelated(root: HttpUrl, dav: Response) {
                // refresh home sets: calendar-proxy-read/write-for
                dav[CalendarProxyReadFor::class.java]?.let {
                    for (href in it.hrefs) {
                        Logger.log.fine("Principal is a read-only proxy for $href, checking for home sets")
                        root.resolve(href)?.let { proxyReadFor ->
                            related += proxyReadFor
                        }
                    }
                }
                dav[CalendarProxyWriteFor::class.java]?.let {
                    for (href in it.hrefs) {
                        Logger.log.fine("Principal is a read/write proxy for $href, checking for home sets")
                        root.resolve(href)?.let { proxyWriteFor ->
                            related += proxyWriteFor
                        }
                    }
                }

                // refresh home sets: direct group memberships
                dav[GroupMembership::class.java]?.let {
                    for (href in it.hrefs) {
                        Logger.log.fine("Principal is member of group $href, checking for home sets")
                        root.resolve(href)?.let { groupMembership ->
                            related += groupMembership
                        }
                    }
                }
            }

            val dav = DavResource(client, url)
            when (service.type) {
                Service.TYPE_CARDDAV ->
                    try {
                        dav.propfind(0, DisplayName.NAME, AddressbookHomeSet.NAME, GroupMembership.NAME) { response, _ ->
                            response[AddressbookHomeSet::class.java]?.let { homeSet ->
                                for (href in homeSet.hrefs)
                                    dav.location.resolve(href)?.let {
                                        val foundUrl = UrlUtils.withTrailingSlash(it)
                                        homeSets[foundUrl] = HomeSet(0, service.id, personal, foundUrl)
                                    }
                            }

                            if (personal)
                                findRelated(dav.location, response)
                        }
                    } catch (e: HttpException) {
                        if (e.code/100 == 4)
                            Logger.log.log(Level.INFO, "Ignoring Client Error 4xx while looking for addressbook home sets", e)
                        else
                            throw e
                    }
                Service.TYPE_CALDAV -> {
                    try {
                        dav.propfind(0, DisplayName.NAME, CalendarHomeSet.NAME, CalendarProxyReadFor.NAME, CalendarProxyWriteFor.NAME, GroupMembership.NAME) { response, _ ->
                            response[CalendarHomeSet::class.java]?.let { homeSet ->
                                for (href in homeSet.hrefs)
                                    dav.location.resolve(href)?.let {
                                        val foundUrl = UrlUtils.withTrailingSlash(it)
                                        homeSets[foundUrl] = HomeSet(0, service.id, personal, foundUrl)
                                    }
                            }

                            if (personal)
                                findRelated(dav.location, response)
                        }
                    } catch (e: HttpException) {
                        if (e.code/100 == 4)
                            Logger.log.log(Level.INFO, "Ignoring Client Error 4xx while looking for calendar home sets", e)
                        else
                            throw e
                    }
                }
            }

            // query related homesets (those that do not belong to the current-user-principal)
            for (resource in related)
                queryHomeSets(client, resource, false)
        }

        fun saveHomesets() {
            DaoTools(homeSetDao).syncAll(
                    homeSetDao.getByService(serviceId),
                    homeSets,
                    { it.url })
        }

        fun saveCollections() {
            DaoTools(collectionDao).syncAll(
                    collectionDao.getByService(serviceId),
                    collections, { it.url }) { new, old ->
                new.forceReadOnly = old.forceReadOnly
                new.sync = old.sync
            }
        }

        try {
            Logger.log.info("Refreshing ${service.type} collections of service #$service")

            // cancel previous notification
            NotificationManagerCompat.from(this)
                    .cancel(serviceId.toString(), NotificationUtils.NOTIFY_REFRESH_COLLECTIONS)

            // create authenticating OkHttpClient (credentials taken from account settings)
            HttpClient.Builder(this, AccountSettings(this, account))
                    .setForeground(true)
                    .build().use { client ->
                val httpClient = client.okHttpClient

                // refresh home set list (from principal)
                service.principal?.let { principalUrl ->
                    Logger.log.fine("Querying principal $principalUrl for home sets")
                    queryHomeSets(httpClient, principalUrl)
                }

                // remember selected collections
                val selectedCollections = HashSet<HttpUrl>()
                val deselectedCollections = HashSet<HttpUrl>()
                collections.forEach { (url, collection) ->
                    if (collection.sync)
                        selectedCollections += url
                    else
                        deselectedCollections += url
                }
                // remember number of collections before the refresh
                val numCollectionsOld = collections.size

                // now refresh homesets and their member collections
                val itHomeSets = homeSets.iterator()
                while (itHomeSets.hasNext()) {
                    val (homeSetUrl, homeSet) = itHomeSets.next()
                    Logger.log.fine("Listing home set $homeSetUrl")

                    try {
                        DavResource(httpClient, homeSetUrl).propfind(1, *DAV_COLLECTION_PROPERTIES) { response, relation ->
                            if (!response.isSuccess())
                                return@propfind

                            if (relation == Response.HrefRelation.SELF) {
                                // this response is about the homeset itself
                                homeSet.displayName = response[DisplayName::class.java]?.displayName
                                homeSet.privBind = response[CurrentUserPrivilegeSet::class.java]?.mayBind ?: true
                            }

                            // in any case, check whether the response is about a useable collection
                            val info = Collection.fromDavResponse(response) ?: return@propfind
                            info.serviceId = serviceId
                            info.refHomeSet = homeSet
                            info.confirmed = true
                            info.owner = response[Owner::class.java]?.href?.let { response.href.resolve(it) }
                            Logger.log.log(Level.FINE, "Found collection", info)

                            // remember usable collections
                            if ((service.type == Service.TYPE_CARDDAV && info.type == Collection.TYPE_ADDRESSBOOK) ||
                                    (service.type == Service.TYPE_CALDAV && arrayOf(Collection.TYPE_CALENDAR, Collection.TYPE_WEBCAL).contains(info.type)))
                                collections[response.href] = info
                        }
                    } catch(e: HttpException) {
                        if (e.code in arrayOf(403, 404, 410))
                            // delete home set only if it was not accessible (40x)
                            itHomeSets.remove()
                    }
                }

                // check/refresh unconfirmed collections
                val itCollections = collections.entries.iterator()
                while (itCollections.hasNext()) {
                    val (url, info) = itCollections.next()
                    if (!info.confirmed)
                        try {
                            // this collection doesn't belong to a homeset anymore, otherwise it would have been confirmed
                            info.homeSetId = null

                            DavResource(httpClient, url).propfind(0, *DAV_COLLECTION_PROPERTIES) { response, _ ->
                                if (!response.isSuccess())
                                    return@propfind

                                val collection = Collection.fromDavResponse(response) ?: return@propfind
                                collection.confirmed = true

                                // remove unusable collections
                                if ((service.type == Service.TYPE_CARDDAV && collection.type != Collection.TYPE_ADDRESSBOOK) ||
                                    (service.type == Service.TYPE_CALDAV && !arrayOf(Collection.TYPE_CALENDAR, Collection.TYPE_WEBCAL).contains(collection.type)) ||
                                    (collection.type == Collection.TYPE_WEBCAL && collection.source == null))
                                    itCollections.remove()
                            }
                        } catch(e: HttpException) {
                            if (e.code in arrayOf(403, 404, 410))
                                // delete collection only if it was not accessible (40x)
                                itCollections.remove()
                            else
                                throw e
                        }
                }

                // restore selections
                for (url in selectedCollections)
                    collections[url]?.let { it.sync = true }
                for (url in deselectedCollections)
                    collections[url]?.let { it.sync = false }

                val numCollectionsNew = collections.size;
                if (numCollectionsOld != numCollectionsNew) {
                    Logger.log.info("Number of collections changed for ${account.name} from ${numCollectionsOld} -> ${numCollectionsNew}, triggering sync")
                    val args = Bundle(1)
                    args.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                    ContentResolver.requestSync(account, getString(R.string.address_books_authority), args)
                }
            }

            db.runInTransaction {
                saveHomesets()

                // use refHomeSet (if available) to determine homeset ID
                for (collection in collections.values)
                    collection.refHomeSet?.let { homeSet ->
                        collection.homeSetId = homeSet.id
                    }
                saveCollections()
            }

        } catch(e: InvalidAccountException) {
            Logger.log.log(Level.SEVERE, "Invalid account", e)
        } catch(e: Exception) {
            Logger.log.log(Level.SEVERE, "Couldn't refresh collection list", e)

            val debugIntent = Intent(this, DebugInfoActivity::class.java)
            debugIntent.putExtra(DebugInfoActivity.EXTRA_CAUSE, e)
            debugIntent.putExtra(DebugInfoActivity.EXTRA_ACCOUNT, account)

            val priority: Int
            val alertOnlyOnce: Boolean
            val channel: String

            if (autoSync) {
                priority = NotificationCompat.PRIORITY_MIN
                channel = NotificationUtils.CHANNEL_SYNC_IO_ERRORS
                alertOnlyOnce = true
            } else {
                priority = NotificationCompat.PRIORITY_DEFAULT
                channel = NotificationUtils.CHANNEL_GENERAL
                alertOnlyOnce = false
            }
            val notify = NotificationUtils.newBuilder(this, channel)
                    .setSmallIcon(R.drawable.ic_sync_problem_notify)
                    .setContentTitle(getString(R.string.dav_service_refresh_failed))
                    .setContentText(getString(R.string.dav_service_refresh_couldnt_refresh))
                    .setContentIntent(PendingIntent.getActivity(this, 0, debugIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    .setSubText(account.name)
                    .setOnlyAlertOnce(alertOnlyOnce)
                    .setPriority(priority)
                    .setCategory(NotificationCompat.CATEGORY_ERROR)
                    .build()
            NotificationManagerCompat.from(this)
                    .notify(serviceId.toString(), NotificationUtils.NOTIFY_REFRESH_COLLECTIONS, notify)
        } finally {
            runningRefresh.remove(serviceId)
            refreshingStatusListeners.mapNotNull { it.get() }.forEach {
                it.onDavRefreshStatusChanged(serviceId, false)
            }
        }

    }

}