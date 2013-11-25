package org.mozilla.gecko.webapp;

import java.io.FileNotFoundException;
import java.io.InputStream;

import org.json.JSONException;
import org.json.JSONObject;
import org.mozilla.gecko.GeckoAppShell;
import org.mozilla.gecko.GeckoEvent;
import org.mozilla.gecko.GeckoProfile;
import org.mozilla.gecko.WebAppAllocator;
import org.mozilla.gecko.gfx.BitmapUtils;
import org.mozilla.gecko.util.GeckoEventListener;
import org.mozilla.gecko.util.ThreadUtils;

import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;

public class InstallHelper implements GeckoEventListener {

    private static final String LOGTAG = "GeckoInstallHelper";

    private static final String[] INSTALL_EVENT_NAMES = new String[] {"WebApps:PostInstall"};

    private final Context mContext;

    private final InstallCallback mCallback;

    private final ApkResources mApkResources;

    public static interface InstallCallback {
        void installCompleted(InstallHelper installHelper, String event, JSONObject message);
    }

    public InstallHelper(Context context, ApkResources apkResources, InstallCallback cb) {
        mContext = context;
        mCallback = cb;
        mApkResources = apkResources;
    }

    public void startInstall(GeckoProfile profile) {
        JSONObject message = createInstallMessage();

        if (message == null) {
            throw new NullPointerException("Cannot find package name in the calling intent to install this app");
        }

        try {
            message.putOpt("profilePath", profile.getDir());
        } catch (JSONException e) {
            // NOP
        }

        for (String eventName : INSTALL_EVENT_NAMES) {
            GeckoAppShell.registerEventListener(eventName, this);
        }

        GeckoAppShell.sendEventToGecko(GeckoEvent.createBroadcastEvent("Webapps:AutoInstall", message.toString()));
        calculateColor();
    }

    private void calculateColor() {
        ThreadUtils.getBackgroundHandler().post(new Runnable() {
            @Override
            public void run() {
                // find the app launcher's launcher icon
                Bitmap bitmap = null;
                try {
                    InputStream inputStream = mContext.getContentResolver().openInputStream(mApkResources.getLogoUri());
                    BitmapDrawable d = (BitmapDrawable) Drawable.createFromStream(inputStream, null);
                    bitmap = d.getBitmap();
                } catch (FileNotFoundException e) {
                    Log.e(LOGTAG, "Can't find icon drawable", e);
                    return;
                }
                int color = -1;
                try {
                    color = BitmapUtils.getDominantColor(bitmap);
                } catch (Exception e) {
                    Log.e(LOGTAG, "Exception during getDominantColor", e);
                }
                if (color != -1) {
                    WebAppAllocator slots = WebAppAllocator.getInstance(mContext);
                    int index = slots.getIndexForApp(mApkResources.getPackageName());
                    slots.updateColor(index, color);
                }
            }
        });
    }

    private JSONObject createInstallMessage() {
        String packageName = mApkResources.getPackageName();
        if (packageName != null) {
            // we're not installed yet.
            try {
                return getInstallMessageFromPackage();
            } catch (Exception e) {
                Log.e(LOGTAG, "Can't install " + packageName, e);
            }
        }
        return null;
    }

    private JSONObject getInstallMessageFromPackage() throws NameNotFoundException, JSONException {

        JSONObject messageObject = new JSONObject();

        messageObject.putOpt("packageName", mApkResources.getPackageName());
        messageObject.putOpt("manifestUrl", mApkResources.getManifestUrl());
        messageObject.putOpt("title", mApkResources.getAppName());
        messageObject.putOpt("manifest", new JSONObject(mApkResources.getManifest(mContext)));

        String appType = mApkResources.getWebAppType();
        messageObject.putOpt("type", appType);
        if ("packaged".equals(appType)) {
            messageObject.putOpt("updateManifest", new JSONObject(mApkResources.getMiniManifest(mContext)));
        }

        return messageObject;
    }

    @Override
    public void handleMessage(String event, JSONObject message) {

        for (String eventName : INSTALL_EVENT_NAMES) {
            GeckoAppShell.unregisterEventListener(eventName, this);
        }

        if (mCallback != null) {
            mCallback.installCompleted(this, event, message);
        }
    }

}
