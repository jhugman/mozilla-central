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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.StringBuilder;



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

        String manifestContent = readResource(context, packageName, "manifest");

        Uri manifestUri = Uri.parse(manifestUrl);

        String origin = manifestUri.getScheme() + "://" + manifestUri.getAuthority();

        Log.i(LOGTAG, "manifestContent = " + manifestContent);

        GeckoAppShell.sendEventToGecko(GeckoEvent.createBroadcastEvent(
                    "Webapps:AppInstalled", "{\"app\": {\"manifestURL\":\"" + manifestUrl + "\"," +
                                            "\"origin\":\"" + origin + "\"," + 
                                            "\"packageName\":\"" + packageName + "\"," + 
                                            "\"manifest\": " + manifestContent + "}}"));
        
    }

    private String readResource(Context context, String packageName, String resourceName) {
        Uri resourceUri = Uri.parse("android.resource://" + packageName + "/raw/" + resourceName);
        StringBuilder fileContent = new StringBuilder();
        try {
          BufferedReader r = new BufferedReader(new InputStreamReader(context.getContentResolver().openInputStream(resourceUri)));
          String line;

          while ((line = r.readLine()) != null) {
              fileContent.append(line);
          }
        } catch (FileNotFoundException e) {
            Log.e(LOGTAG, String.format("file not found: \"%s\"", resourceName));
        } catch (IOException e) {
            Log.e(LOGTAG, "couldn't read file: " + resourceName);
        }  

        return fileContent.toString();        
    }
}