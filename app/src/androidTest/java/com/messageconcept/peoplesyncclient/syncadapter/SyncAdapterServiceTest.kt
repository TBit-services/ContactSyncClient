package com.messageconcept.peoplesyncclient.syncadapter

import android.os.Bundle
import androidx.test.filters.SmallTest
import com.messageconcept.peoplesyncclient.syncadapter.SyncAdapterService.SyncAdapter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncAdapterServiceTest {

    @Test
    @SmallTest
    fun testPriorityCollections() {
        val extras = Bundle()
        assertTrue(SyncAdapter.priorityCollections(extras).isEmpty())

        extras.putString(SyncAdapterService.SYNC_EXTRAS_PRIORITY_COLLECTIONS, "")
        assertTrue(SyncAdapter.priorityCollections(extras).isEmpty())

        extras.putString(SyncAdapterService.SYNC_EXTRAS_PRIORITY_COLLECTIONS, "123")
        assertArrayEquals(longArrayOf(123), SyncAdapter.priorityCollections(extras).toLongArray())

        extras.putString(SyncAdapterService.SYNC_EXTRAS_PRIORITY_COLLECTIONS, ",x,")
        assertTrue(SyncAdapter.priorityCollections(extras).isEmpty())

        extras.putString(SyncAdapterService.SYNC_EXTRAS_PRIORITY_COLLECTIONS, "1,2,3")
        assertArrayEquals(longArrayOf(1,2,3), SyncAdapter.priorityCollections(extras).toLongArray())
    }

}