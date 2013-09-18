/* -*- Mode: Java; c-basic-offset: 4; tab-width: 20; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko;

import java.net.MalformedURLException;

import org.json.JSONObject;

import org.mozilla.gecko.gfx.BitmapUtils;
import org.mozilla.gecko.GeckoThread.LaunchState;
import org.mozilla.gecko.webapps.Logger;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

public class WebAppInstaller extends GeckoApp {
    private static final String LOGTAG = "GeckoWebAppInstaller";

    private View mSplashscreen;

    protected int getIndex() { return 0; }

    @Override
    public int getLayout() { return R.layout.web_app; }

    @Override
    public boolean hasTabsSideBar() { return false; }

    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        mSplashscreen = findViewById(R.id.splashscreen);
        if (!GeckoThread.checkLaunchState(GeckoThread.LaunchState.GeckoRunning)) {
            overridePendingTransition(R.anim.grow_fade_in_center, android.R.anim.fade_out);
            showSplash();
        }


        String action = getIntent().getAction();
        Bundle extras = getIntent().getExtras();

        String packageName = extras != null ? extras.getString("packageName") : null;
        Log.d(LOGTAG, "Package name is " + packageName);
        if (packageName != null) {
            // we're not installed yet.
            Log.i(LOGTAG, "App " + packageName + " isn't installed yet");
            try {
                installWebApp(packageName);
            } catch (Exception e) {
                Log.e(LOGTAG, "Can't install " + packageName);
            }
            return;
        }

    }


    private void installWebApp(String packageName) throws NameNotFoundException, MalformedURLException {
        ApplicationInfo app = getPackageManager()
                .getApplicationInfo(packageName,
                        PackageManager.GET_META_DATA);

        Bundle metadata = app.metaData;

        String type = metadata.getString("webapp");
        if ("hosted".equals(type)) {
            String manifestUrlString = metadata.getString("manifestUrl");
            Log.i(LOGTAG, "Installing hosted app from " + manifestUrlString);
            //GeckoAppShell.getEventDispatcher().registerEventListener("WebApps:PostInstall", this);
            GeckoAppShell.sendEventToGecko(GeckoEvent.createBroadcastEvent("Webapps:AutoInstall", manifestUrlString));
        } else if ("packaged".equals(type)) {
            String manifestUrlString = metadata.getString("manifestUrl");
            Log.i(LOGTAG, "Installing packaged app from " + manifestUrlString);
            //GeckoAppShell.getEventDispatcher().registerEventListener("WebApps:PostInstall", this);
            GeckoAppShell.sendEventToGecko(GeckoEvent.createBroadcastEvent("Webapps:AutoInstallPackage", manifestUrlString));
        } else {

        }
    }

    @Override
    public void handleMessage(String event, JSONObject message) {
        super.handleMessage(event, message);
        if (event.equals("WebApps:PostInstall")) {
            Logger.i("WebApps:PostInstall: About to run " + message.toString());
            GeckoAppShell.getEventDispatcher().unregisterEventListener("WebApps:PostInstall", this);

            String name = message.optString("name");
            String manifestUrl = message.optString("manifestURL");
            String origin = message.optString("origin");
            String iconUrl = message.optString("iconURL");
            String originalOrigin = message.optString("originalOrigin");
            int index = this.postInstallWebApp(name, manifestUrl, origin, iconUrl, originalOrigin);

            Intent intent = new Intent();

            Log.i(LOGTAG, "Webapp action is: " + "org.mozilla.gecko.WEBAPP" + index);
            intent.putExtra("appAction", "org.mozilla.gecko.WEBAPP" + index);

            // Set appUri to originalOrigin because origin will be changed to
            // an app: URL for a packaged app, but the registry still indexes it
            // by the original origin (i.e. URL of the mini-manifest).
            intent.putExtra("appUri", originalOrigin);

            intent.putExtra("fennecPackageName", getPackageName());
            intent.putExtra("slotClassName", getPackageName() + ".WebApps$WebApp" + index);

            if (getParent() == null) {
                setResult(RESULT_OK, intent);
            } else {
                getParent().setResult(RESULT_OK, intent);
            }
            finish();
        }
    }

    /**
     * Update the app allocation with the new origin, in case it has changed
     * (i.e. to an app: URL); and set an icon.
     *
     * @see GeckoAppShell::postInstallWebApp
     */
    public static int postInstallWebApp(String aTitle, String aURI, String aOrigin, String aIconURL, String aOriginalOrigin) {
    	WebAppAllocator allocator = WebAppAllocator.getInstance();
        int index = allocator.getIndexForApp(aOriginalOrigin);
    	assert index != -1 && aIconURL != null;
    	allocator.updateAppAllocation(aOrigin, index, BitmapUtils.getBitmapFromDataURI(aIconURL));
        return index;
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
        String profile = "webapp-installer";
        return profile;
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
