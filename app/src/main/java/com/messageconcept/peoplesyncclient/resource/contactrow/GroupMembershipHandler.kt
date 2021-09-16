package com.messageconcept.peoplesyncclient.resource.contactrow

import android.content.ContentValues
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import com.messageconcept.peoplesyncclient.log.Logger
import com.messageconcept.peoplesyncclient.resource.LocalContact
import at.bitfire.vcard4android.Contact
import at.bitfire.vcard4android.GroupMethod
import at.bitfire.vcard4android.contactrow.DataRowHandler
import org.apache.commons.lang3.StringUtils

class GroupMembershipHandler(val localContact: LocalContact): DataRowHandler() {

    override fun forMimeType() = GroupMembership.CONTENT_ITEM_TYPE

    override fun handle(values: ContentValues, contact: Contact) {
        super.handle(values, contact)

        val groupId = values.getAsLong(GroupMembership.GROUP_ROW_ID)
        localContact.groupMemberships += groupId

        if (localContact.addressBook.groupMethod == GroupMethod.CATEGORIES) {
            val group = localContact.addressBook.findGroupById(groupId)
            StringUtils.trimToNull(group.getContact().displayName)?.let { groupName ->
                Logger.log.fine("Adding membership in group $groupName as category")
                contact.categories.add(groupName)
            }
        }
    }

}