package org.mozilla.gecko.webapp;

import org.json.JSONException;
import org.json.JSONObject;
import org.mozilla.gecko.GeckoAppShell;
import org.mozilla.gecko.GeckoEvent;
import org.mozilla.gecko.GeckoProfile;
import org.mozilla.gecko.util.GeckoEventListener;

import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
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
