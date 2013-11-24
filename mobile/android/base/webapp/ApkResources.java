package org.mozilla.gecko.webapp;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

public class ApkResources {

    private static final String LOGTAG = "Gecko ApkResources";

    private final String mPackageName;

    private ApplicationInfo mInfo;

    public ApkResources(Context context, String packageName) throws NameNotFoundException {
        mPackageName = packageName;
        mInfo = context.getPackageManager().getApplicationInfo(
                    mPackageName, PackageManager.GET_META_DATA);;
    }

    private ApplicationInfo info() {
        return mInfo;
    }

    public String getPackageName() {
        return mPackageName;
    }

    private Bundle metadata() {
        return mInfo.metaData;
    }

    public String getManifest(Context context) {
        return readResource(context, "manifest");
    }

    public String getMiniManifest(Context context) {
        return readResource(context, "mini");
    }


    public String getManifestUrl() {
        return metadata().getString("manifestUrl");
    }

    public boolean isPackaged() {
        return "packaged".equals(metadata().getString("webapp", null));
    }

    private String readResource(Context context, String resourceName) {
        Uri resourceUri = Uri.parse("android.resource://" + mPackageName
                + "/raw/" + resourceName);
        StringBuilder fileContent = new StringBuilder();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(context
                    .getContentResolver().openInputStream(resourceUri)));
            String line;

            while ((line = r.readLine()) != null) {
                fileContent.append(line);
            }
        } catch (FileNotFoundException e) {
            Log.e(LOGTAG, String.format("File not found: \"%s\"", resourceName));
        } catch (IOException e) {
            Log.e(LOGTAG, String.format("Couldn't read file: \"%s\"", resourceName));
        }

        return fileContent.toString();
    }

    public Uri getLogoUri() {
        return Uri.parse("android.resource://" + mPackageName + "/" + info().icon);
    }

    public String getWebAppType() {
        return metadata().getString("webapp");
    }

    public String getAppName() {
        return info().name;
    }

}
