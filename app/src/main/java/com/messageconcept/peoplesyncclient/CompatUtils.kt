package com.messageconcept.peoplesyncclient

import android.content.ContentProviderClient
import android.os.Build

fun ContentProviderClient.closeCompat() {
    if (Build.VERSION.SDK_INT >= 24)
        close()
    else
        release()
}
