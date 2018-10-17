/***************************************************************************************************
 * Copyright © All Contributors. See LICENSE and AUTHORS in the root directory for details.
 **************************************************************************************************/

package com.messageconcept.peoplesyncclient.ui.setup

import androidx.lifecycle.ViewModel
import com.messageconcept.peoplesyncclient.model.Credentials
import java.net.URI

class LoginModel: ViewModel() {

    var baseURI: URI? = null
    var credentials: Credentials? = null

    var configuration: DavResourceFinder.Configuration? = null

}
