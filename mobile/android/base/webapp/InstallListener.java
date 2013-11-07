package org.mozilla.gecko.webapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;


public class InstallListener extends BroadcastReceiver {

    private static String LOGTAG = "GeckoInstallListener";

    public void onReceive(Context context, Intent intent) {
        String packageName = intent.getData().getSchemeSpecificPart();

        if (TextUtils.isEmpty(packageName)) {
            Log.i(LOGTAG, "No package name defined in intent");
            return;
        }

        ApplicationInfo app = null;
        try {
            app = context.getPackageManager().getApplicationInfo(packageName, PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }

        Bundle metadata = app.metaData;
        if (metadata != null) {
            String manifestUrl = metadata.getString("manifestUrl").replace(":", "").replace("/", "").replace(".", "").replace("?", "").replace("&", "");
            Log.i(LOGTAG, "ManifestURL: " + (TextUtils.isEmpty(manifestUrl) ? "nothing" : manifestUrl));
        } else {
            Log.i(LOGTAG, "'manifestUrl' metadata needs to be defined in AndroidManifest.xml");
        }
    }

}