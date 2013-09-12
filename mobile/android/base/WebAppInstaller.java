/* -*- Mode: Java; c-basic-offset: 4; tab-width: 20; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko;

import java.net.MalformedURLException;
import java.net.URL;

import org.json.JSONException;
import org.json.JSONObject;

import org.mozilla.gecko.GeckoThread.LaunchState;
import org.mozilla.gecko.webapps.Logger;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

public class WebAppInstaller extends GeckoApp {
    private static final String LOGTAG = "GeckoWebAppInstaller";

    private View mSplashscreen;

    private String mProfileName;

    protected int getIndex() { return 0; }

    @Override
    public int getLayout() { return R.layout.web_app; }

    @Override
    public boolean hasTabsSideBar() { return false; }

    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        JSONObject message = getInstallMessage();
        if (message != null) {
            WebAppAllocator allocator = WebAppAllocator.getInstance(getApplicationContext());
            String origin = message.optString("origin");
            int index = allocator.findAndAllocateIndex(origin, "", (Bitmap) null);
            if (index >= 0) {
                mProfileName = "webapp" + index;
            }
        }

        if (mProfileName == null) {
            // something bad has happened;
            mProfileName = "webapp-installer";
        }

        super.onCreate(savedInstanceState);

        mSplashscreen = findViewById(R.id.splashscreen);
        if (!GeckoThread.checkLaunchState(GeckoThread.LaunchState.GeckoRunning)) {
            overridePendingTransition(R.anim.grow_fade_in_center, android.R.anim.fade_out);
            showSplash();
        }

        if (message != null) {
            GeckoAppShell.sendEventToGecko(GeckoEvent.createBroadcastEvent("Webapps:AutoInstall", message.toString()));
        }

    }

    public JSONObject getInstallMessage() {
        Bundle extras = getIntent().getExtras();

        String packageName = extras != null ? extras.getString("packageName") : null;
        if (packageName != null) {
            // we're not installed yet.
            try {
                return getInstallMessageFromPackage(packageName);

            } catch (Exception e) {
                Log.e(LOGTAG, "Can't install " + packageName);
            }
        }
        return null;
    }


    private JSONObject getInstallMessageFromPackage(String packageName) throws NameNotFoundException, MalformedURLException, JSONException {
        ApplicationInfo app = getPackageManager()
                .getApplicationInfo(packageName,
                        PackageManager.GET_META_DATA);

        Bundle metadata = app.metaData;

        JSONObject messageObject = new JSONObject();
        String urlString = metadata.getString("manifestUrl");
        URL url = new URL(urlString);
        messageObject.putOpt("origin", getOrigin(url));
        messageObject.putOpt("manifestUrl", urlString);
        messageObject.putOpt("type", metadata.getString("webapp"));

        return messageObject;
    }

    private String getOrigin(URL url) {
        StringBuilder sb = new StringBuilder();
        sb.append(url.getProtocol()).append("://");
        sb.append(url.getHost());
        int port = url.getPort();
        if (port >= 0) {
            sb.append(":").append(port);
        }

        return url.toString();//sb.toString();
    }

    @Override
    public void handleMessage(String event, JSONObject message) {
        super.handleMessage(event, message);
        if (event.equals("WebApps:PostInstall")) {
            Logger.i("WebApps:PostInstall: About to run " + message.toString());
            GeckoAppShell.getEventDispatcher().unregisterEventListener("WebApps:PostInstall", this);

            Intent intent = new Intent();
            String origin = message.optString("origin");
            int index = WebAppAllocator.getInstance().getIndexForApp(origin);
            if (index >= 0) {
                Log.i(LOGTAG, "Webapp action is: " + "org.mozilla.gecko.WEBAPP" + index);
                intent.putExtra("appAction", "org.mozilla.gecko.WEBAPP" + index);
                intent.putExtra("appUri", origin);

                intent.putExtra("fennecPackageName", getPackageName());
                intent.putExtra("slotClassName", getPackageName() + ".WebApps$WebApp" + index);
            }

            if (getParent() == null) {
                setResult(RESULT_OK, intent);
            } else {
                getParent().setResult(RESULT_OK, intent);
            }
            finish();
        }
    }



    @Override
    protected void loadStartupTab(String uri) {
        // NOP
    }

    private void showSplash() {
        // NOP
    }

    @Override
    protected String getDefaultProfileName() {
        return mProfileName;
    }

    @Override
    protected int getSessionRestoreState(Bundle savedInstanceState) {
        // for now webapps never restore your session
        return RESTORE_NONE;
    }

    @Override
    public void onTabChanged(Tab tab, Tabs.TabEvents msg, Object data) {
        // NOP.
    }

    @Override
    protected void geckoConnected() {
        super.geckoConnected();
        mLayerView.setOverScrollMode(View.OVER_SCROLL_NEVER);
    }
};
