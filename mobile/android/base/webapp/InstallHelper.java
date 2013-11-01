package org.mozilla.gecko.webapp;

import java.net.MalformedURLException;
import java.net.URL;

import org.json.JSONException;
import org.json.JSONObject;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;
import android.util.Log;

public class InstallHelper {

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
                Log.e(LOGTAG, "Can't install " + packageName);
            }
        }
        return null;
    }

    private JSONObject getInstallMessageFromPackage(String packageName) throws NameNotFoundException, MalformedURLException, JSONException {
        ApplicationInfo app = mContext.getPackageManager()
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

}
