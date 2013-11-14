/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

"use strict";

this.EXPORTED_SYMBOLS = ["WebappManager"];

const Cc = Components.classes;
const Ci = Components.interfaces;
const Cu = Components.utils;

Cu.import("resource://gre/modules/XPCOMUtils.jsm");
Cu.import("resource://gre/modules/Services.jsm");
Cu.import("resource://gre/modules/NetUtil.jsm");
Cu.import("resource://gre/modules/FileUtils.jsm");

function dump(a) {
  Services.console.logStringMessage(a);
}

function sendMessageToJava(aMessage) {
  return Services.androidBridge.handleGeckoMessage(JSON.stringify(aMessage));
}
this.WebappManager = {
  QueryInterface: XPCOMUtils.generateQI([Ci.nsIObserver,
                                         Ci.nsISupportsWeakReference]),

  observe: function(aSubject, aTopic, aData) {
    switch (aTopic) {
      case "webapps-download-apk":
        this._downloadApk(aData);
        break;
    }
  },

  _checkingForUpdates: false,

  _updateApps: function(aApps) {
    dump("_updateApps: " + aApps.length + " apps to update");
    this._checkingForUpdates = false;
  },

  // Trigger apps update check and wait for all to be done before
  // notifying gaia.
  checkForUpdates: function() {
    dump("checkForUpdates (" + this._checkingForUpdates + ")");
    // Don't start twice.
    if (this._checkingForUpdates) {
      return;
    }

    this._checkingForUpdates = true;

    let window = Services.wm.getMostRecentWindow("navigator:browser");
    let all = window.navigator.mozApps.mgmt.getAll();

    all.onsuccess = (function() {
      let appsCount = this.result.length;
      let appsChecked = 0;
      let appsToUpdate = [];
      this.result.forEach(function(aApp) {
        let update = aApp.checkForUpdate();
        update.onsuccess = function() {
          if (aApp.downloadAvailable) {
            appsToUpdate.push(aApp.manifestURL);
          }

          appsChecked += 1;
          if (appsChecked == appsCount) {
            this._updateApps(appsToUpdate);
          }
        }
        update.onerror = function() {
          appsChecked += 1;
          if (appsChecked == appsCount) {
            this._updateApps(appsToUpdate);
          }
        }
      });
    }).bind(this);

    all.onerror = (function() {
      // Could not get the app list, just notify to update nothing.
      this._updateApps([]);
    }).bind(this);
  },

  _downloadApk: function(aManifestUrl) {
    dump("_downloadApk for " + aManifestUrl);

    // Get the endpoint URL and convert it to an nsIURI/nsIURL object.
    const GENERATOR_URL_PREF = "dom.mozApps.apkGeneratorEndpoint";
    const GENERATOR_URL_BASE = Services.prefs.getCharPref(GENERATOR_URL_PREF);
    let generatorUrl = NetUtil.newURI(GENERATOR_URL_BASE).
                       QueryInterface(Ci.nsIURL);

    // Populate the query part of the URL with the manifest URL parameter.
    let params = {
      manifestUrl: aManifestUrl,
    };
    generatorUrl.query =
      [p + "=" + encodeURIComponent(params[p]) for (p in params)].join("&");

    // Trigger the download.
    debug("downloading APK from " + generatorUrl.spec);

    let filePath = sendMessageToJava({
      type: "WebApps:GetTempFilePath",
      fileName: aManifestUrl.replace(/[^a-zA-Z0-9]/gi, "")
    });
    dump("saving APK to " + filePath);

    NetUtil.asyncFetch(generatorUrl, function(aInputStream, aStatus) {
      try {
        if (Components.isSuccessCode(aStatus)) {

          let file = Cc["@mozilla.org/file/local;1"].
                     createInstance(Ci.nsILocalFile);
          file.initWithPath(filePath);

          let outputStream = FileUtils.openSafeFileOutputStream(file);

          NetUtil.asyncCopy(aInputStream, outputStream, function(aResult) {
            if (!Components.isSuccessCode(aResult)) {
              dump("Downloading failed")
            } else {
              dump("Downloaded successfully");
              sendMessageToJava({
                type: "WebApps:InstallApk",
                filePath: filePath
              });
            }
          });
         } else {
           dump("can't download - status returned: " + aStatus);
         }
      } catch (e) {
        dump("Error in fetch - " + e);
      }
     });
  },
};

Services.obs.addObserver(this.WebappManager, "webapps-download-apk", true);
