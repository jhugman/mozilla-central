package org.mozilla.gecko.webapps;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.mozilla.gecko.GeckoAppShell;
import org.mozilla.gecko.GeckoEvent;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Bundle;
import android.os.SystemClock;

public class WebAppRegistry {
    public void addApk(Context context, String packageName) {
        try {
            ApplicationInfo app = context.getPackageManager().getApplicationInfo(packageName, PackageManager.GET_META_DATA);

            Bundle metadata = app.metaData;
            if (metadata != null) {
                String type = metadata.getString("webapp");

                if (type != null) {

                    addWebApp(getRegistry(context), app);

                }
            } else {
                // NO-OP
                // Prob a packaged app
            }
        } catch (NameNotFoundException e) {
            Logger.e("Package " + packageName + " not found");
        }
    }

    public SharedPreferences getRegistry(Context context) {
        return context.getSharedPreferences("webAppRegistry", Context.MODE_PRIVATE);
    }

    private SharedPreferences addWebApp(SharedPreferences registry, ApplicationInfo app) {
        Editor editor = registry.edit();
        addWebApp(editor, app).commit();
        return registry;
    }

    public Editor addWebApp(Editor editor, ApplicationInfo app) {
        Logger.i("Webapp " + app.packageName + " added");

        // TODO initiate the sucking out of the zip file from the assets directory
        // or precaching of the hosted app.
        String manifestUrl = app.metaData.getString("manifestUrl");
        if (manifestUrl != null) {
            Logger.i("Going to attempt installing " + manifestUrl);
            GeckoAppShell.sendEventToGecko(GeckoEvent.createBroadcastEvent("SynthAPK:AppAdded", manifestUrl));
            editor.putString(app.packageName, manifestUrl);
        } else {
            Logger.i("Can't install " + app.packageName + ": no manifest url");
            editor.remove(app.packageName);
        }
        return editor;
    }

    public void removeApk(Context context, String packageName) {
        if (getRegistry(context).contains(packageName)) {
            Editor editor = getRegistry(context).edit();
            removeWebApp(editor, packageName).commit();
        }
    }

    public Editor removeWebApp(Editor editor, String packageName) {
        Logger.i("Webapp " + packageName + " removed");

        // TODO remove the profile directory of the app that is to be
        // uninstalled.

        GeckoAppShell.sendEventToGecko(GeckoEvent.createBroadcastEvent("SynthAPK:AppRemoved", packageName));
        return editor.remove(packageName);
    }

    public void rebuildRegistry(Context context) {
        long then = SystemClock.currentThreadTimeMillis();

        SharedPreferences prefs = getRegistry(context);

        List<ApplicationInfo> apps = context.getPackageManager().getInstalledApplications(PackageManager.GET_META_DATA);
        Set<String> appPackages = new HashSet<String>();
        Editor editor = prefs.edit();
        for (ApplicationInfo app : apps) {
            Bundle metaData = app.metaData;
            boolean isWebapp = (metaData != null && metaData.getString("webapp") != null);
            if (!isWebapp) {
                continue;
            }

            String packageName = app.packageName;

            appPackages.add(packageName);

            if (!prefs.contains(packageName)) {
                // we should add this new package.
                addWebApp(editor, app);
            }


        }

        for (String packageName : prefs.getAll().keySet()) {
            if (!appPackages.contains(packageName)) {
                // we should do something to remove this package.
                removeWebApp(editor, packageName);
            }
        }
        editor.commit();

        log(context);

        long now = SystemClock.currentThreadTimeMillis();

        Logger.i("Rebuilding the registry took " + ((now - then) / 1000d) + " seconds");

    }

    public void log(Context context) {
        Logger.i("All apps currently installed: " + this.getRegistry(context).getAll().keySet());
    }
}
