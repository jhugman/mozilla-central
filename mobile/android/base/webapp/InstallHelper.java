package org.mozilla.gecko.webapp;

import java.net.MalformedURLException;
import org.json.JSONException;
import org.json.JSONObject;
import org.mozilla.gecko.util.GeckoEventListener;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;
import android.util.Log;

public class InstallHelper implements GeckoEventListener {

    private static final String LOGTAG = "GeckoInstallHelper";

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

    public JSONObject createInstallMessage(Bundle extras) {
        String packageName = extras != null ? extras.getString("packageName") : null;
        if (packageName != null) {
            // we're not installed yet.
            try {
                return getInstallMessageFromPackage(packageName);

            } catch (Exception e) {
                Log.e(LOGTAG, "Can't install " + packageName, e);
            }
        }
        return null;
    }

    private JSONObject getInstallMessageFromPackage(String packageName) throws NameNotFoundException, MalformedURLException, JSONException {

        ApplicationInfo app = mContext.getPackageManager()
                .getApplicationInfo(packageName,
                        PackageManager.GET_META_DATA);

        Bundle metadata = app.metaData;
        String urlString = metadata.getString("manifestUrl");

        JSONObject messageObject = new JSONObject();
        messageObject.putOpt("manifestUrl", urlString);
        String appType = metadata.getString("webapp");
        messageObject.putOpt("type", appType);
        messageObject.putOpt("packageName", packageName);
        messageObject.putOpt("title", app.name);

        messageObject.putOpt("manifest", new JSONObject(mApkResources.getManifest(mContext)));

        if ("packaged".equals(appType)) {
            messageObject.putOpt("updateManifest", new JSONObject(mApkResources.getMiniManifest(mContext)));
        }

        return messageObject;
    }

    @Override
    public void handleMessage(String event, JSONObject message) {
        Log.i(LOGTAG, "Install complete: " + event + "\n" + message);

        mCallback.installCompleted(this, event, message);


    }

}
