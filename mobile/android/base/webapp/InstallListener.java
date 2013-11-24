package org.mozilla.gecko.webapp;

import org.mozilla.gecko.GeckoEvent;
import org.mozilla.gecko.GeckoAppShell;
import org.mozilla.gecko.GeckoProfile;
import org.mozilla.gecko.GeckoThread;
import org.mozilla.gecko.WebAppAllocator;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager.NameNotFoundException;
import android.text.TextUtils;
import android.util.Log;
import android.os.Environment;
import android.net.Uri;

import java.io.File;

public class InstallListener extends BroadcastReceiver {

    private static String LOGTAG = "GeckoInstallListener";
    private String data = null;

    public InstallListener(String data) {
      this.data = data;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String packageName = intent.getData().getSchemeSpecificPart();

        if (TextUtils.isEmpty(packageName)) {
            Log.i(LOGTAG, "No package name defined in intent");
            return;
        }
        ApkResources apkResources = null;

        try {
            apkResources = new ApkResources(context, packageName);
        } catch (NameNotFoundException e) {
            Log.e(LOGTAG, "Can't find package that's just been installed");
            return;
        }

        String manifestUrl = apkResources.getManifestUrl();
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

        if (GeckoThread.checkLaunchState(GeckoThread.LaunchState.GeckoRunning)) {

            InstallHelper installHelper = new InstallHelper(context, apkResources, null);
            WebAppAllocator slots = WebAppAllocator.getInstance(context);
            int i = slots.allocatePackage(packageName, "");
            installHelper.startInstall(GeckoProfile.get(context, "webapp" + i));
        }

        boolean isPackaged = apkResources.isPackaged();

        String manifestContent = apkResources.getManifest(context);

        Uri manifestUri = Uri.parse(manifestUrl);

        String origin = manifestUri.getScheme() + "://" + manifestUri.getAuthority();

        Log.i(LOGTAG, "manifestContent = " + manifestContent);

        Log.i(LOGTAG, String.format("data=%s",data));

        // TODO check if gecko events get queued if Gecko isn't running
        GeckoAppShell.sendEventToGecko(GeckoEvent.createBroadcastEvent(
                    "Webapps:ApkInstalled", String.format("{\"data\":%s, \"manifest\":%s}",
                    data, manifestContent)));

    }


}