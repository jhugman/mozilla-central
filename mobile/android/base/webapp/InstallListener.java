package org.mozilla.gecko.webapp;

import org.mozilla.gecko.GeckoEvent;
import org.mozilla.gecko.GeckoAppShell;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.os.Environment;
import android.net.Uri;

import java.io.File;

public class InstallListener extends BroadcastReceiver {

    private static String LOGTAG = "GeckoInstallListener";
    private String requestId = null;

    public InstallListener(String requestId) {
      this.requestId = requestId;
    }

    @Override
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
        if(metadata == null) {
          Log.i(LOGTAG, "No metadata found.");
          return;
        }

        String manifestUrl = metadata.getString("manifestUrl");
        if(TextUtils.isEmpty(manifestUrl)) {
            Log.i(LOGTAG, "No manifest URL present in metadata");
            return;
        }

        String manifestUrlFilename = manifestUrl.replaceAll("[^a-zA-Z0-9]", "");
        Log.i(LOGTAG, "ManifestURL: " + (TextUtils.isEmpty(manifestUrl) ? "nothing" : manifestUrl));
        Log.i(LOGTAG, "ManifestURL FileName: " + (TextUtils.isEmpty(manifestUrlFilename) ? "nothing" : manifestUrlFilename));

        File apkFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), manifestUrlFilename + ".apk");
        if(apkFile.exists()) {
          apkFile.delete();
          Log.i(LOGTAG, "Downloaded APK file deleted");
        }

        ApkResources res = new ApkResources(packageName);
 
        boolean isPackaged = res.isPackaged(context);

        String manifestContent = res.getManifest(context);

        Uri manifestUri = Uri.parse(manifestUrl);

        String origin = manifestUri.getScheme() + "://" + manifestUri.getAuthority();

        Log.i(LOGTAG, "manifestContent = " + manifestContent);


        // TODO check if gecko events get queued if Gecko isn't running
        GeckoAppShell.sendEventToGecko(GeckoEvent.createBroadcastEvent(
                    "Webapps:AppInstalled", String.format("{\"app\": {\"manifestURL\":\"%s\"," +
                                            "\"origin\":\"%s\", \"isPackaged\":%s," +
                                            "\"packageName\":\"%s\", \"requestId\":\"%s\", \"manifest\": %s}}", 
                                            manifestUrl, origin, isPackaged, packageName, requestId, manifestContent)));

    }


}