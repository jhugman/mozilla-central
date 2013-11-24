/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko;

import org.mozilla.gecko.util.ThreadUtils;

import android.content.Context;
import android.content.SharedPreferences;

public class WebAppAllocator {
    private static final String PREFIX_ORIGIN = "webapp-origin-";
    private static final String PREFIX_PACKAGE_NAME = "webapp-package-name-";
    private final String LOGTAG = "GeckoWebAppAllocator";
    // The number of WebApp# and WEBAPP# activites/apps/intents
    private final static int MAX_WEB_APPS = 100;

    protected static WebAppAllocator sInstance = null;
    public static WebAppAllocator getInstance() {
        return getInstance(GeckoAppShell.getContext());
    }

    public static synchronized WebAppAllocator getInstance(Context cx) {
        if (sInstance == null) {
            sInstance = new WebAppAllocator(cx);
        }

        return sInstance;
    }

    SharedPreferences mPrefs;

    protected WebAppAllocator(Context context) {
        mPrefs = context.getSharedPreferences("webapps", Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
    }



    private static String appKey(int index) {
        return PREFIX_PACKAGE_NAME + index;
    }

    public static String iconKey(int index) {
        return "web-app-color-" + index;
    }

    public static String originKey(int i) {
        return PREFIX_ORIGIN + i;
    }

    public synchronized int allocatePackage(final String packageName, final String title) {
        int index = getIndexForApp(packageName);
        if (index != -1)
            return index;

        for (int i = 0; i < MAX_WEB_APPS; ++i) {
            if (!mPrefs.contains(appKey(i))) {
                // found unused index i
                putPackageName(i, packageName);
                return i;
            }
        }

        // no more apps!
        return -1;
    }

    public synchronized WebAppAllocator putPackageName(final int index,
                                                 final String packageName) {
        return commit(edit().putString(appKey(index), packageName));
    }

    public WebAppAllocator updateColor(int index, int color) {
        return commit(edit().putInt(iconKey(index), color));
    }

    public synchronized int getIndexForApp(String packageName) {
        return findSlotForPrefix(PREFIX_PACKAGE_NAME, packageName);
    }

    public synchronized int getIndexForOrigin(String origin) {
        return findSlotForPrefix(PREFIX_ORIGIN, origin);
    }

    protected int findSlotForPrefix(String prefix, String value) {
        for (int i = 0; i < MAX_WEB_APPS; ++i) {
            if (mPrefs.getString(prefix + i, "").equals(value)) {
                return i;
            }
        }
        return -1;
    }

    public synchronized String getAppForIndex(int index) {
        return mPrefs.getString(appKey(index), null);
    }

    public synchronized int releaseIndexForApp(String app) {
        int index = getIndexForApp(app);
        if (index == -1)
            return -1;

        releaseIndex(index);
        return index;
    }

    public synchronized void releaseIndex(final int index) {
        ThreadUtils.postToBackgroundThread(new Runnable() {
            @Override
            public void run() {
                mPrefs.edit()
                    .remove(appKey(index))
                    .remove(iconKey(index))
                    .remove(originKey(index))
                    .commit();
            }
        });
    }

    private SharedPreferences.Editor mEditor = null;

    public synchronized WebAppAllocator begin() {
        mEditor = edit();
        return this;
    }

    public WebAppAllocator end() {
        commit(mEditor);
        return this;
    }

    private SharedPreferences.Editor edit() {
        if (mEditor != null) {
            return mEditor;
        }
        return mPrefs.edit();
    }

    private WebAppAllocator commit(SharedPreferences.Editor edit) {
        if (edit == null) {
            return this;
        }
        if (edit == mEditor || mEditor == null) {
            edit.commit();
            mEditor = null;
        }
        return this;
    }

    public WebAppAllocator putOrigin(int index, String origin) {
        return commit(edit().putString(originKey(index), origin));
    }

    public String getOrigin(int index) {
        return mPrefs.getString(originKey(index), null);
    }

    public int getColor(int index) {
        return mPrefs.getInt(iconKey(index), -1);
    }
}
