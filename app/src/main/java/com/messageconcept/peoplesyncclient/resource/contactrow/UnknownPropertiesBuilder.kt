/***************************************************************************************************
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 **************************************************************************************************/

package com.messageconcept.peoplesyncclient.resource.contactrow

import android.net.Uri
import com.messageconcept.peoplesyncclient.model.UnknownProperties
import at.bitfire.vcard4android.BatchOperation
import at.bitfire.vcard4android.Contact
import at.bitfire.vcard4android.contactrow.DataRowBuilder
import java.util.*

class UnknownPropertiesBuilder(dataRowUri: Uri, rawContactId: Long?, contact: Contact)
    : DataRowBuilder(Factory.mimeType(), dataRowUri, rawContactId, contact) {

    override fun build(): List<BatchOperation.CpoBuilder> {
        val result = LinkedList<BatchOperation.CpoBuilder>()
        contact.unknownProperties?.let { unknownProperties ->
            result += newDataRow().withValue(UnknownProperties.UNKNOWN_PROPERTIES, unknownProperties)
        }
        return result
    }


    object Factory: DataRowBuilder.Factory<UnknownPropertiesBuilder> {
        override fun mimeType() = UnknownProperties.CONTENT_ITEM_TYPE
        override fun newInstance(dataRowUri: Uri, rawContactId: Long?, contact: Contact) =
            UnknownPropertiesBuilder(dataRowUri, rawContactId, contact)
    }

}