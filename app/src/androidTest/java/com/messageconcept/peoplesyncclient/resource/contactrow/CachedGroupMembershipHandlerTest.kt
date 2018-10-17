package com.messageconcept.peoplesyncclient.resource.contactrow

import android.Manifest
import android.content.ContentProviderClient
import android.content.ContentValues
import android.provider.ContactsContract
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.messageconcept.peoplesyncclient.resource.LocalContact
import com.messageconcept.peoplesyncclient.resource.LocalTestAddressBook
import at.bitfire.vcard4android.CachedGroupMembership
import at.bitfire.vcard4android.Contact
import at.bitfire.vcard4android.GroupMethod
import org.junit.*
import org.junit.Assert.assertArrayEquals

class CachedGroupMembershipHandlerTest {

    @JvmField
    @Rule
    val permissionRule = GrantPermissionRule.grant(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)!!

    private lateinit var provider: ContentProviderClient
    private lateinit var addressBook: LocalTestAddressBook

    @Before
    fun connect() {
        val context = InstrumentationRegistry.getInstrumentation().context
        provider = context.contentResolver.acquireContentProviderClient(ContactsContract.AUTHORITY)!!
        Assert.assertNotNull(provider)

        addressBook = LocalTestAddressBook(context, provider, GroupMethod.GROUP_VCARDS)
    }

    @After
    fun disconnect() {
        @Suppress("DEPRECATION")
        provider.release()
    }


    @Test
    fun testMembership() {
        val contact = Contact()
        val localContact = LocalContact(addressBook, contact, null, null, 0)
        CachedGroupMembershipHandler(localContact).handle(ContentValues().apply {
            put(CachedGroupMembership.GROUP_ID, 123456)
            put(CachedGroupMembership.RAW_CONTACT_ID, 789)
        }, contact)
        assertArrayEquals(arrayOf(123456L), localContact.cachedGroupMemberships.toArray())
    }

}