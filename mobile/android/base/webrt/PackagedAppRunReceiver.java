package org.mozilla.gecko.webrt;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;

import java.io.*;

import org.mozilla.gecko.GeckoAppShell;
import org.mozilla.gecko.GeckoEvent;
import org.mozilla.gecko.GeckoThread;
import org.json.JSONException;
import org.json.JSONObject;

public class PackagedAppRunReceiver extends BroadcastReceiver {

  private static final String LOGTAG = "GeckoPackagedAppRunReceiver";


  /*
   * Steps taken when running
   *  - get mini manifest
   *  - check if package is installed
   *  - if app already installed
   *    - start app
   *  - if app isn't installed
   *    - get archive file
   *    - get Fennec to create profile dir
   *    - put mini manifest and archive in correct location
   *    - send request to Fennec to install
   */
  public void onReceive(Context context, Intent intent) {

    String packageName = intent.getStringExtra("PACKAGE_NAME");
		String authority = packageName;
		if(intent.hasExtra("AUTHORITY")) {
		  authority = intent.getStringExtra("AUTHORITY");
		}

    if(TextUtils.isEmpty(packageName)) {
      Log.i(LOGTAG, "No 'PACKAGE_NAME' extra defined in intent");
      return;
    }

    Log.i(LOGTAG, "invoke packaged app run receiver : " + packageName);

    ApplicationInfo app = null;
    try {
        app = context.getPackageManager().getApplicationInfo(packageName, PackageManager.GET_META_DATA);
    } catch (PackageManager.NameNotFoundException e) {
        e.printStackTrace();
				throw new RuntimeException("Package name not found: " + packageName);
    }

    Bundle metadata = app.metaData;
    if (metadata != null) {
        String type = metadata.getString("webapp");
        if ("packaged".equals(type)) {
            // NO-OP
        } else {
            throw new RuntimeException("Must be a packaged app to install");
        }
    } else {
        throw new RuntimeException("'webapp' metadata needs to be defined in AndroidManifest.xml");
    }
    String originUrl = metadata.getString("originUrl");

		if(TextUtils.isEmpty(originUrl)) {
      throw new RuntimeException("Origin URL not defined in metadata");
		}
    try {
      JSONObject data = new JSONObject();
      data.put("originUrl", originUrl);
			data.put("packageName", packageName);
			data.put("authority", authority);
      Log.i(LOGTAG, "sending data : " + data.toString());
    
      // for the purpse of the demo lets assume that Fennec is up and running
      GeckoAppShell.sendEventToGecko(GeckoEvent.createBroadcastEvent("WebApps:InstallApkPackagedApp", data.toString()));
     /* 
		  If Fennec isn't running here thenb we need to work out how to get it started silently.  
			This below approach won't work as there are context dependencies within GeckoThread which rely on an Activity Context

		  GeckoThread sGeckoThread = new GeckoThread(null, null);
      if(GeckoThread.checkAndSetLaunchState(GeckoThread.LaunchState.Launching, GeckoThread.LaunchState.Launched)) {
        sGeckoThread.start();
			}
			*/
      //GeckoAppShell.sendEventToGecko(GeckoEvent.createBroadcastEvent("webapps-ask-package-install", data.toString()));
		} catch (JSONException e) {
      Log.e(LOGTAG, "Exception creating message to allow mixed content", e);
    }
  }



  // The following doesn't do anything - just here so I don't lose them
  private void savedLines(Context context) {

        String packageName = "";
    //TODO check app isn't installed already
    //     need to get info from metadata and call through to JS
    //     Q: how do we get back from JS with a result?
    //        - should we send through the package name and have the below code somewhere where it can be used?


    // get mini manifest
    String contentProviderAuthorityName = packageName;
    ParcelFileDescriptor manifestFile = null;
    try {
      manifestFile = context.getContentResolver().openFileDescriptor(Uri.parse("content://" + contentProviderAuthorityName + "/manifest"), "r");
    } catch (FileNotFoundException e) {
      Log.e(LOGTAG, "FileNotFound exception whilst transferring manifest from " + packageName, e);
      e.printStackTrace();
    }

    Log.i(LOGTAG, "manifest file - size: " + manifestFile.getStatSize());


    // get webapp archive
    ParcelFileDescriptor archiveFile = null;
    try {
      archiveFile = context.getContentResolver().openFileDescriptor(Uri.parse("content://" + contentProviderAuthorityName + "/archive"), "r");
    } catch (FileNotFoundException e) {
      Log.e(LOGTAG, "FileNotFound exception whilst transferring archive file from " + packageName, e);
      e.printStackTrace();
    }

    Log.i(LOGTAG, "file - size: " + archiveFile.getStatSize());
    // Make sure Fennec is running and find a way to kick it off silently in the BG if it's not

  }

  private void writeFile(ParcelFileDescriptor descriptor, File toWrite) throws IOException {

    InputStream fileStream = new FileInputStream(descriptor.getFileDescriptor());
    OutputStream newFile = new FileOutputStream(toWrite);

    byte[] buffer = new byte[1024];
    int length;

    while ((length = fileStream.read(buffer)) > 0) {
        newFile.write(buffer, 0, length);
    }

    newFile.flush();
    fileStream.close();
    newFile.close();
    }
  }

