/* -*- Mode: Java; c-basic-offset: 4; tab-width: 20; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;

import org.json.JSONObject;
import org.mozilla.gecko.webapps.Logger;
import org.mozilla.gecko.webapps.WebAppRegistry;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.TextView;

public class WebAppImpl extends GeckoApp {
    private static final String LOGTAG = "GeckoWebAppImpl";

    private URL mOrigin;
    private TextView mTitlebarText = null;
    private View mTitlebar = null;

    private View mSplashscreen;

    protected int getIndex() { return -1; }

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

        new WebAppRegistry().rebuildRegistry(getApplicationContext());

        String action = getIntent().getAction();
        Bundle extras = getIntent().getExtras();

        String title = extras != null ? extras.getString(Intent.EXTRA_SHORTCUT_NAME) : null;
        setTitle(title != null ? title : "Web App");

        mTitlebarText = (TextView)findViewById(R.id.webapp_title);
        mTitlebar = findViewById(R.id.webapp_titlebar);
        String packageName = extras != null ? extras.getString("packageName") : null;
        Log.d(LOGTAG, "2. Package name is " + packageName);
        if (packageName != null) {
            if (getIndex() == -1) {
                // we're not installed yet.
                Log.i(LOGTAG, "App " + packageName + " isn't installed yet");
                try {
                    installWebApp(packageName);
                } catch (Exception e) {
                    Log.e(LOGTAG, "Can't install " + packageName);
                }
                return;
            }
        } else if (!action.startsWith(ACTION_WEBAPP_PREFIX)) {
            Log.e(LOGTAG, "WebApp launch, but intent action is " + action + "!");
            return;
        } else {
            runWebApp();
        }

    }

    public void runWebApp() {
        // Try to use the origin stored in the WebAppAllocator first
        String origin = WebAppAllocator.getInstance(this).getAppForIndex(getIndex());
        try {
            mOrigin = new URL(origin);
        } catch (java.net.MalformedURLException ex) {
            // If we can't parse the this is an app protocol, just settle for not having an origin
            if (origin == null || !origin.startsWith("app://")) {
                return;
            }

            // If that failed fall back to the origin stored in the shortcut
            Log.i(LOGTAG, "Webapp is not registered with allocator");
            try {
                mOrigin = new URL(getIntent().getData().toString());
            } catch (java.net.MalformedURLException ex2) {
                Log.e(LOGTAG, "Unable to parse intent url: ", ex);
            }
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

            assert manifestUrlString != null;
            URL manifestUrl = new URL(manifestUrlString);
            GeckoAppShell.getEventDispatcher().registerEventListener("WebApps:PostInstall", this);
            Log.d(LOGTAG, "Installing " + packageName + " from " + manifestUrl);
            new WebAppRegistry().addApk(this, packageName);
        } else if ("packaged".equals(type)) {

        } else {

        }
    }

    @Override
    public void handleMessage(String event, JSONObject message) {
        if (event.equals("WebApps:PostInstall")) {
            Logger.i("WebApps:PostInstall: About to run " + message.toString());
            GeckoAppShell.getEventDispatcher().unregisterEventListener("WebApp:PostInstall", this);

            runWebApp();
        }
    }



    @Override
    protected void loadStartupTab(String uri) {
        String action = getIntent().getAction();
        if (GeckoApp.ACTION_WEBAPP_PREFIX.equals(action)) {
            // This action assumes the uri is not an installed WebApp. We will
            // use the WebAppAllocator to register the uri with an Android
            // process so it can run chromeless.
            int index = WebAppAllocator.getInstance(this).findAndAllocateIndex(uri, "App", (Bitmap) null);
            Intent appIntent = GeckoAppShell.getWebAppIntent(index, uri);
            startActivity(appIntent);
            finish();
        }
    }

    private void showSplash() {
        SharedPreferences prefs = getSharedPreferences("webapps", Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);

        // get the favicon dominant color, stored when the app was installed
        int[] colors = new int[2];
        int dominantColor = prefs.getInt(WebAppAllocator.iconKey(getIndex()), -1);

        // now lighten it, to ensure that the icon stands out in the center
        float[] f = new float[3];
        Color.colorToHSV(dominantColor, f);
        f[2] = Math.min(f[2]*2, 1.0f);
        colors[0] = Color.HSVToColor(255, f);

        // now generate a second, slightly darker version of the same color
        f[2] *= 0.75;
        colors[1] = Color.HSVToColor(255, f);

        // Draw the background gradient
        GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        gd.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        Display display = getWindowManager().getDefaultDisplay();
        gd.setGradientCenter(0.5f, 0.5f);
        gd.setGradientRadius(Math.max(display.getWidth()/2, display.getHeight()/2));
        mSplashscreen.setBackgroundDrawable(gd);

        // look for a logo.png in the profile dir and show it. If we can't find a logo show nothing
        File profile = getProfile().getDir();
        File logoFile = new File(profile, "logo.png");
        if (logoFile.exists()) {
            ImageView image = (ImageView)findViewById(R.id.splashscreen_icon);
            Drawable d = Drawable.createFromPath(logoFile.getPath());
            image.setImageDrawable(d);

            Animation fadein = AnimationUtils.loadAnimation(this, R.anim.grow_fade_in_center);
            fadein.setStartOffset(500);
            fadein.setDuration(1000);
            image.startAnimation(fadein);
        }
    }

    @Override
    protected String getDefaultProfileName() {
        String action = getIntent().getAction();
        if (!action.startsWith(ACTION_WEBAPP_PREFIX)) {
            Log.e(LOGTAG, "WebApp launch, but intent action is " + action + "!");
            return null;
        }

        return "webapp" + action.substring(ACTION_WEBAPP_PREFIX.length());
    }

    @Override
    protected int getSessionRestoreState(Bundle savedInstanceState) {
        // for now webapps never restore your session
        return RESTORE_NONE;
    }

    @Override
    public void onTabChanged(Tab tab, Tabs.TabEvents msg, Object data) {
        switch(msg) {
            case SELECTED:
            case LOCATION_CHANGE:
                if (Tabs.getInstance().isSelectedTab(tab)) {
                    final String urlString = tab.getURL();
                    final URL url;

                    try {
                        url = new URL(urlString);
                    } catch (java.net.MalformedURLException ex) {
                        mTitlebarText.setText(urlString);

                        // If we can't parse the url, and its an app protocol hide
                        // the titlebar and return, otherwise show the titlebar
                        // and the full url
                        if (!urlString.startsWith("app://")) {
                            mTitlebar.setVisibility(View.VISIBLE);
                        } else {
                            mTitlebar.setVisibility(View.GONE);
                        }
                        return;
                    }

                    if (mOrigin != null && mOrigin.getHost().equals(url.getHost())) {
                        mTitlebar.setVisibility(View.GONE);
                    } else {
                        mTitlebarText.setText(url.getProtocol() + "://" + url.getHost());
                        mTitlebar.setVisibility(View.VISIBLE);
                    }
                }
                break;
            case LOADED:
                if (mSplashscreen != null && mSplashscreen.getVisibility() == View.VISIBLE) {
                    Animation fadeout = AnimationUtils.loadAnimation(this, android.R.anim.fade_out);
                    fadeout.setAnimationListener(new Animation.AnimationListener() {
                        @Override
                        public void onAnimationEnd(Animation animation) {
                          mSplashscreen.setVisibility(View.GONE);
                        }
                        @Override
                        public void onAnimationRepeat(Animation animation) { }
                        @Override
                        public void onAnimationStart(Animation animation) { }
                    });
                    mSplashscreen.startAnimation(fadeout);
                }
                break;
            case START:
                if (mSplashscreen != null && mSplashscreen.getVisibility() == View.VISIBLE) {
                    View area = findViewById(R.id.splashscreen_progress);
                    area.setVisibility(View.VISIBLE);
                    Animation fadein = AnimationUtils.loadAnimation(this, android.R.anim.fade_in);
                    fadein.setDuration(1000);
                    area.startAnimation(fadein);
                }
                break;
        }
        super.onTabChanged(tab, msg, data);
    }

    @Override
    protected void geckoConnected() {
        super.geckoConnected();
        mLayerView.setOverScrollMode(View.OVER_SCROLL_NEVER);
    }
};
