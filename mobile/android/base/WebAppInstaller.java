/* -*- Mode: Java; c-basic-offset: 4; tab-width: 20; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko;

import java.net.MalformedURLException;
import java.net.URL;

import org.json.JSONException;
import org.json.JSONObject;

import org.mozilla.gecko.gfx.BitmapUtils;
import org.mozilla.gecko.webapps.Logger;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;

public class WebAppInstaller extends GeckoApp {
    private static final String LOGTAG = "GeckoWebAppInstaller";

    private View mSplashscreen;

    private String mProfileName;

    protected int getIndex() { return 0; }

    @Override
    public int getLayout() { return R.layout.web_app_installer; }

    @Override
    public boolean hasTabsSideBar() { return false; }

    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        JSONObject message = getInstallMessage();
//        if (message != null) {
//            WebAppAllocator allocator = WebAppAllocator.getInstance(getApplicationContext());
//            String origin = message.optString("origin");
//            int index = allocator.findAndAllocateIndex(origin, "", (Bitmap) null);
//            if (index >= 0) {
//                mProfileName = "webapp" + index;
//            }
//        }


        if (mProfileName == null) {
            // something bad has happened;
            mProfileName = "webapp-installer";
        }

        super.onCreate(savedInstanceState);

        mSplashscreen = findViewById(R.id.splashscreen);
        //if (!GeckoThread.checkLaunchState(GeckoThread.LaunchState.GeckoRunning)) {
            overridePendingTransition(R.anim.grow_fade_in_center, android.R.anim.fade_out);
            showSplash();
        //}

        if (message != null) {
            if ("hosted".equals(message.optString("type"))) {
                GeckoAppShell.sendEventToGecko(GeckoEvent.createBroadcastEvent("Webapps:AutoInstall", message.toString()));
            } else {
                GeckoAppShell.sendEventToGecko(GeckoEvent.createBroadcastEvent("Webapps:AutoInstallPackage", message.toString()));
            }
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

        return sb.toString();
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
            WebAppAllocator allocator = WebAppAllocator.getInstance();
            int index = allocator.getIndexForApp(originalOrigin);

            Intent intent = new Intent();

            Log.i(LOGTAG, "Webapp action is: " + "org.mozilla.gecko.WEBAPP" + index);
            intent.putExtra("appAction", "org.mozilla.gecko.WEBAPP" + index);

            intent.putExtra("appUri", manifestUrl);

            intent.putExtra("fennecPackageName", getPackageName());
            intent.putExtra("slotClassName", getPackageName() + ".WebApps$WebApp" + index);

            if (getParent() == null) {
                setResult(RESULT_OK, intent);
            } else {
                getParent().setResult(RESULT_OK, intent);
            }
            finish();
            Process.killProcess(Process.myPid());
        }
    }

    /**
     * Update the app allocation with the new origin, in case it has changed
     * (i.e. to an app: URL); and set an icon.
     *
     * @see GeckoAppShell::postInstallWebApp
     */
    public int postInstallWebApp(String aTitle, String aURI, String aOrigin, String aIconURL, String aOriginalOrigin) {
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

        ImageView image = (ImageView)findViewById(R.id.splashscreen_icon);



        Animation fadein = AnimationUtils.loadAnimation(this, R.anim.grow_fade_in_center);
        fadein.setStartOffset(500);
        fadein.setDuration(1000);
        image.startAnimation(fadein);
        image.setImageResource(R.drawable.webapp_generic_icon);

    }

    @Override
    protected String getDefaultProfileName() {
        return mProfileName;
    }

    @Override
    protected boolean getSessionRestoreState(Bundle savedInstanceState) {
        // for now webapps never restore your session
        return false;
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
