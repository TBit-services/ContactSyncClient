/***************************************************************************************************
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 **************************************************************************************************/

package com.messageconcept.peoplesyncclient.ui.webdav

import com.messageconcept.peoplesyncclient.TestUtils
import com.messageconcept.peoplesyncclient.model.WebDavMount
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddWebdavMountActivityTest {

    val model = AddWebdavMountActivity.Model(TestUtils.targetApplication)
    val web = MockWebServer()

    @Test
    fun testHasWebDav_NoDavHeader() {
        web.enqueue(MockResponse().setResponseCode(200))
        assertFalse(model.hasWebDav(WebDavMount(name = "Test", url = web.url("/")), null))
    }

    @Test
    fun testHasWebDav_DavClass_1() {
        web.enqueue(MockResponse()
            .setResponseCode(200)
            .addHeader("DAV", "1"))
        assertTrue(model.hasWebDav(WebDavMount(name = "Test", url = web.url("/")), null))
    }

    @Test
    fun testHasWebDav_DavClass_1and2() {
        web.enqueue(MockResponse()
            .setResponseCode(200)
            .addHeader("DAV", "1,2"))
        assertTrue(model.hasWebDav(WebDavMount(name = "Test", url = web.url("/")), null))
    }

    @Test
    fun testHasWebDav_DavClass_2() {
        web.enqueue(MockResponse()
            .setResponseCode(200)
            .addHeader("DAV", "2"))
        assertTrue(model.hasWebDav(WebDavMount(name = "Test", url = web.url("/")), null))
    }

}