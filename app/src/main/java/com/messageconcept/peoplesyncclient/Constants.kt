/*
 * Copyright © Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */
package com.messageconcept.peoplesyncclient

object Constants {

    const val DAVDROID_GREEN_RGBA = 0xFF8bc34a.toInt()

    /**
     * Context label for [org.apache.commons.lang3.exception.ContextedException].
     * Context value is the [com.messageconcept.peoplesyncclient.resource.LocalResource]
     * which is related to the exception cause.
     */
    const val EXCEPTION_CONTEXT_LOCAL_RESOURCE = "localResource"

    /**
     * Context label for [org.apache.commons.lang3.exception.ContextedException].
     * Context value is the [okhttp3.HttpUrl] of the remote resource
     * which is related to the exception cause.
     */
    const val EXCEPTION_CONTEXT_REMOTE_RESOURCE = "remoteResource"

}
