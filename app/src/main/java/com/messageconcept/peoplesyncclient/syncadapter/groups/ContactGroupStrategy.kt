package com.messageconcept.peoplesyncclient.syncadapter.groups

import at.bitfire.vcard4android.Contact

interface ContactGroupStrategy {

    fun beforeUploadDirty()
    fun verifyContactBeforeSaving(contact: Contact)
    fun postProcess()

}