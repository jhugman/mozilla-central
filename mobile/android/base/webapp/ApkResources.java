package org.mozilla.gecko.webapp;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

public class ApkResources {

    private static final String LOGTAG = "Gecko ApkResources";

    private final String mPackageName;

    public ApkResources(String packageName) {
        mPackageName = packageName;
    }

    public String getManifest(Context context) {
        return readResource(context, "manifest");
    }

    public String getMiniManifest(Context context) {
        return readResource(context, "mini");
    }

    private String readResource(Context context, String resourceName) {
        Uri resourceUri = Uri.parse("android.resource://" + mPackageName + "/raw/" + resourceName);
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
