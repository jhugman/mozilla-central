/* -*- Mode: Java; c-basic-offset: 4; tab-width: 20; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;

import org.json.JSONException;
import org.json.JSONObject;
import org.mozilla.gecko.webapp.ApkResources;
import org.mozilla.gecko.webapp.InstallHelper;
import org.mozilla.gecko.webapp.InstallHelper.InstallCallback;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.TextView;

public class WebAppImpl extends GeckoApp implements InstallCallback {
    private static final String LOGTAG = "GeckoWebAppImpl";

    private static final String[] INSTALL_EVENT_NAMES = new String[] {"WebApps:PostInstall"};

    private URL mOrigin;
    private TextView mTitlebarText = null;
    private View mTitlebar = null;

    private View mSplashscreen;

    private ApkResources mApkResources;

    protected int getIndex() { return 0; }

    @Override
    public int getLayout() { return R.layout.web_app; }

    @Override
    public boolean hasTabsSideBar() { return false; }

    @Override
    public void onCreate(Bundle savedInstance)
    {

        String action = getIntent().getAction();
        Bundle extras = getIntent().getExtras();
        if (extras == null) {
            extras = savedInstance;
        }

        if (extras == null) {
            extras = new Bundle();
        }

        String packageName = extras.getString("packageName");

        mApkResources = new ApkResources(packageName);

        boolean isInstalled = extras.getBoolean("isInstalled", false);
        if (isInstalled) {
            // XXX GeckoThread uses the intent action to set this as a webapp
            getIntent().setAction(GeckoApp.ACTION_WEBAPP_PREFIX + getIndex());
        }

        // start Gecko.
        super.onCreate(savedInstance);

        mTitlebarText = (TextView)findViewById(R.id.webapp_title);
        mTitlebar = findViewById(R.id.webapp_titlebar);
        mSplashscreen = findViewById(R.id.splashscreen);

        if (!GeckoThread.checkLaunchState(GeckoThread.LaunchState.GeckoRunning) || !isInstalled) {
            // Show the splash screen if we need to start Gecko, or we need to install this.
            overridePendingTransition(R.anim.grow_fade_in_center, android.R.anim.fade_out);
            showSplash(isInstalled);
        } else {
            mSplashscreen.setVisibility(View.GONE);
        }

        if (!isInstalled) {
            startInstall(extras);
            return;
        }

        String title = extras != null ? extras.getString(Intent.EXTRA_SHORTCUT_NAME) : null;
        setTitle(title != null ? title : "Web App");

        // Try to use the origin stored in the WebAppAllocator first
        String origin = WebAppAllocator.getInstance(this).getAppForIndex(getIndex());
        try {
            mOrigin = new URL(origin);
        } catch (java.net.MalformedURLException ex) {
            // If we can't parse the this is an app protocol, just settle for not having an origin
            if (!origin.startsWith("app://")) {
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

    @Override
    protected String getURIFromIntent(Intent intent) {
        String uri = super.getURIFromIntent(intent);
        if (uri != null) {
            return uri;
        }
        // This is where we construct the URL from the Intent from the
        // the synthesized APK.

        // TODO Translate AndroidIntents into WebActivities here.
        try {
            return mApkResources.getManifestUrl(this);
        } catch (NameNotFoundException e) {
            Log.e(LOGTAG, "Can't find an app for package", e);
        }
        return null;
    }

    @Override
    protected void loadStartupTab(String uri) {
        String action = getIntent().getAction();

        // This is almost certainly redundant.
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

    private void showSplash(boolean isInstalled) {

        SharedPreferences prefs = getPreferences();

        // get the favicon dominant color, stored when the app was installed
        int dominantColor = prefs.getInt(WebAppAllocator.iconKey(getIndex()), -1);

        setBackgroundGradient(dominantColor);

        ImageView image = (ImageView)findViewById(R.id.splashscreen_icon);
        Drawable d = null;

        Uri uri = mApkResources.getLogoUri();

        if (uri != null) {
            image.setImageURI(uri);
            d = image.getDrawable();
        } else {
            // look for a logo.png in the profile dir and show it. If we can't find a logo show nothing
            File profile = getProfile().getDir();
            File logoFile = new File(profile, "logo.png");
            if (logoFile.exists()) {
                d = Drawable.createFromPath(logoFile.getPath());
                image.setImageDrawable(d);
            }
        }

        if (d != null) {
            if (dominantColor == -1) {
                Bitmap bitmap = ((BitmapDrawable) d).getBitmap();
                WebAppAllocator.getInstance(getApplicationContext()).updateAppAllocation(null, getIndex(), bitmap);
            }

            Animation fadein = AnimationUtils.loadAnimation(this, R.anim.grow_fade_in_center);
            fadein.setStartOffset(500);
            fadein.setDuration(1000);
            image.startAnimation(fadein);

        }
    }

    protected SharedPreferences getPreferences() {
        SharedPreferences prefs = getSharedPreferences("webapps", Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
        return prefs;
    }

    public void setBackgroundGradient(int dominantColor) {
        int[] colors = new int[2];
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
    }

    /* (non-Javadoc)
     * @see org.mozilla.gecko.GeckoApp#getDefaultProfileName()
     */
    @Override
    protected String getDefaultProfileName() {
        return "webapp" + getIndex();
    }

    @Override
    protected boolean getSessionRestoreState(Bundle savedInstanceState) {
        // for now webapps never restore your session
        return false;
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
                        if (urlString != null && !urlString.startsWith("app://")) {
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
                hideSplash();
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

    protected void hideSplash() {
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
    }

    protected void startInstall(Bundle extras) {
        InstallHelper installHelper = new InstallHelper(this.getApplicationContext(), mApkResources, this);
        JSONObject message = installHelper.createInstallMessage(extras);

        if (message == null) {
            throw new NullPointerException("Cannot find package name in the calling intent to install this app");
        }

        GeckoProfile profile = getProfile();
        try {
            message.putOpt("profilePath", profile.getDir());
        } catch (JSONException e) {
            // NOP
        }

        for (String eventName : INSTALL_EVENT_NAMES) {
            GeckoAppShell.registerEventListener(eventName, installHelper);
        }
        GeckoAppShell.sendEventToGecko(GeckoEvent.createBroadcastEvent("Webapps:AutoInstall", message.toString()));
    }

    @Override
    public void installCompleted(InstallHelper installHelper, String event, JSONObject message) {

        for (String eventName : INSTALL_EVENT_NAMES) {
            GeckoAppShell.unregisterEventListener(eventName, installHelper);
        }

        if (event == null) {
            return;
        }


        if (event.equals("WebApps:PostInstall")) {
            try {
                mOrigin = new URL(message.optString("origin"));
            } catch (MalformedURLException e) {
                Log.e(LOGTAG, "Cannot decode origin", e);
            }
            JSONObject launchObject = new JSONObject();
            try {
                launchObject.putOpt("url", message.optString("manifestURL"));
                launchObject.putOpt("name", message.optString("name", "WebApp"));
            } catch (JSONException e) {
                Log.e(LOGTAG, "Error populating launch message", e);
            }

            Log.i(LOGTAG, "Trying to launch: " + launchObject);
            GeckoAppShell.sendEventToGecko(GeckoEvent.createBroadcastEvent("Webapps:LaunchFromJava", launchObject.toString()));
        }

    }
}
