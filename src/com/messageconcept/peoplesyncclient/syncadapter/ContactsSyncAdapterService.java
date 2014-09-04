/*******************************************************************************
 * Copyright (c) 2014 Richard Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 * 
 * Contributors:
 *     Richard Hirner (bitfire web engineering) - initial API and implementation
 ******************************************************************************/
package com.messageconcept.peoplesyncclient.syncadapter;

import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

import lombok.Synchronized;
import android.accounts.Account;
import android.app.Service;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;
import com.messageconcept.peoplesyncclient.resource.CardDavAddressBook;
import com.messageconcept.peoplesyncclient.resource.LocalAddressBook;
import com.messageconcept.peoplesyncclient.resource.LocalCollection;
import com.messageconcept.peoplesyncclient.resource.RemoteCollection;

public class ContactsSyncAdapterService extends Service {
	private static ContactsSyncAdapter syncAdapter;
	

	@Override @Synchronized
	public void onCreate() {
		if (syncAdapter == null)
			syncAdapter = new ContactsSyncAdapter(getApplicationContext());
	}

	@Override
	public void onDestroy() {
		syncAdapter.close();
		syncAdapter = null;
	}

	@Override
	public IBinder onBind(Intent intent) {
		return syncAdapter.getSyncAdapterBinder();
	}
	

	private static class ContactsSyncAdapter extends DavSyncAdapter {
		private final static String TAG = "peoplesyncclient.ContactsSyncAdapter";

		
		private ContactsSyncAdapter(Context context) {
			super(context);
			Log.i(TAG, "httpClient = " + httpClient);
		}

		@Override
		protected Map<LocalCollection<?>, RemoteCollection<?>> getSyncPairs(Account account, ContentProviderClient provider) {
			AddressBookAccountSettings settings = new AddressBookAccountSettings(getContext(), account);
			String	userName = settings.getUserName(),
					password = settings.getPassword(),
					addressBookURL = settings.getAddressBookURL();
			boolean preemptive = settings.getPreemptiveAuth();
			
			
			if (addressBookURL == null)
				return null;
			
			try {
				LocalCollection<?> database = new LocalAddressBook(account, provider, settings);
				Log.i(TAG, "httpClient 2 = " + httpClient);
				RemoteCollection<?> dav = new CardDavAddressBook(httpClient, addressBookURL, userName, password, preemptive);
				
				Map<LocalCollection<?>, RemoteCollection<?>> map = new HashMap<LocalCollection<?>, RemoteCollection<?>>();
				map.put(database, dav);
				
				return map;
			} catch (URISyntaxException ex) {
				Log.e(TAG, "Couldn't build address book URI", ex);
			}
			
			return null;
		}
	}
}
