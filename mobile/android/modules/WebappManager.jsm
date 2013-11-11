/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

"use strict";

this.EXPORTED_SYMBOLS = ["WebappsUpdater"];

const Cc = Components.classes;
const Cu = Components.utils;

Cu.import("resource://gre/modules/XPCOMUtils.jsm");
Cu.import("resource://gre/modules/Services.jsm");

function dump(a) {
  Services.console.logStringMessage(a);
}

this.WebappsUpdater = {
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
  }
};
