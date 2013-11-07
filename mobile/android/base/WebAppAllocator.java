/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko;

import org.mozilla.gecko.gfx.BitmapUtils;
import org.mozilla.gecko.util.ThreadUtils;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.graphics.Bitmap;
import android.util.Log;

public class WebAppAllocator {
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

    public static String appKey(int index) {
        return "app" + index;
    }

    static public String iconKey(int index) {
        return "icon" + index;
    }

    public synchronized int findAndAllocateIndex(String app, String name, String aIconData) {
        Bitmap icon = (aIconData != null) ? BitmapUtils.getBitmapFromDataURI(aIconData) : null;
        return findAndAllocateIndex(app, name, icon);
    }

    public synchronized int findAndAllocateIndex(final String app, final String name, final Bitmap aIcon) {
        int index = getIndexForApp(app);
        if (index != -1)
            return index;

        for (int i = 0; i < MAX_WEB_APPS; ++i) {
            if (!mPrefs.contains(appKey(i))) {
                // found unused index i
                updateAppAllocation(app, i, aIcon);
                return i;
            }
        }

        // no more apps!
        return -1;
    }

    public synchronized void updateAppAllocation(final String app,
                                                 final int index,
                                                 final Bitmap aIcon) {
        if (aIcon != null) {
            updateColor(app, index, aIcon);
        } else {
            mPrefs.edit()
                  .putString(appKey(index), app)
                  .putInt(iconKey(index), -1).commit();
        }
    }

    public void updateColor(final String app, final int index,
            final Bitmap aIcon) {
        ThreadUtils.getBackgroundHandler().post(new Runnable() {
            @Override
            public void run() {
                int color = 0;
                try {
                    color = BitmapUtils.getDominantColor(aIcon);
                } catch (Exception e) {
                    Log.e(LOGTAG, "Exception during getDominantColor", e);
                }
                Editor edit = mPrefs.edit();
                if (app != null) {
                    edit.putString(appKey(index), app);
                }
                edit.putInt(iconKey(index), color).commit();
            }
        });
    }

    public synchronized int getIndexForApp(String app) {
        for (int i = 0; i < MAX_WEB_APPS; ++i) {
            if (mPrefs.getString(appKey(i), "").equals(app)) {
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
                    .commit();
            }
        });
    }
}
