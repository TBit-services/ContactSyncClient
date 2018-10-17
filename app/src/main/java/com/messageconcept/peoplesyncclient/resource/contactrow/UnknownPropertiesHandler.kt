/***************************************************************************************************
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 **************************************************************************************************/

package com.messageconcept.peoplesyncclient.resource.contactrow

import android.content.ContentValues
import com.messageconcept.peoplesyncclient.model.UnknownProperties
import at.bitfire.vcard4android.Contact
import at.bitfire.vcard4android.contactrow.DataRowHandler

object UnknownPropertiesHandler: DataRowHandler() {

    override fun forMimeType() = UnknownProperties.CONTENT_ITEM_TYPE

    override fun handle(values: ContentValues, contact: Contact) {
        super.handle(values, contact)

        contact.unknownProperties = values.getAsString(UnknownProperties.UNKNOWN_PROPERTIES)
    }

}