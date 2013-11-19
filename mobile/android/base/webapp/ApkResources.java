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

    public ApkResources(String packageName) {
        mPackageName = packageName;
    }

    private ApplicationInfo info(Context context) throws NameNotFoundException {
        if (mInfo == null) {
            mInfo = context.getPackageManager().getApplicationInfo(
                    mPackageName, PackageManager.GET_META_DATA);
        }
        return mInfo;
    }

    private Bundle metadata(Context context) throws NameNotFoundException {
        // Beware the null pointer exception.
        return info(context).metaData;
    }

    public String getManifest(Context context) {
        return readResource(context, "manifest");
    }

    public String getMiniManifest(Context context) {
        return readResource(context, "mini");
    }


    public String getManifestUrl(Context context) throws NameNotFoundException {
        return metadata(context).getString("packageName");
    }

    public boolean isPackaged(Context context) {
        try {
            return "packaged".equals(metadata(context).getString("webapp", null));
        } catch (NameNotFoundException e) {
            return false;
        }
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

}
