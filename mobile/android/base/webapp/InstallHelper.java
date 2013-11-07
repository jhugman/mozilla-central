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

    public InstallHelper(Context context) {
        mContext = context;
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

        Bundle metadata = getPackageMetadata(packageName);
        String urlString = metadata.getString("manifestUrl");

        JSONObject messageObject = new JSONObject();
        messageObject.putOpt("manifestUrl", urlString);
        messageObject.putOpt("type", metadata.getString("webapp"));
        messageObject.putOpt("packageName", packageName);

        return messageObject;
    }

    protected Bundle getPackageMetadata(String packageName)
            throws NameNotFoundException {
        ApplicationInfo app = mContext.getPackageManager()
                .getApplicationInfo(packageName,
                        PackageManager.GET_META_DATA);

        Bundle metadata = app.metaData;
        return metadata;
    }

    @Override
    public void handleMessage(String event, JSONObject message) {
        Log.i(LOGTAG, "Install complete: " + event + "\n" + message);
    }

}
