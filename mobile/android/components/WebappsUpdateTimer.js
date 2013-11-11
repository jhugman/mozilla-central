/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

/**
 * This component triggers a periodic webapp update check.
 */

"use strict";

const Cc = Components.classes;
const Ci = Components.interfaces;
const Cu = Components.utils;

Cu.import("resource://gre/modules/XPCOMUtils.jsm");
Cu.import("resource://gre/modules/Services.jsm");
Cu.import("resource://gre/modules/WebappManager.jsm");

function dump(a) {
  Services.console.logStringMessage(a);
}

function WebappsUpdateTimer() {}

WebappsUpdateTimer.prototype = {
  QueryInterface: XPCOMUtils.generateQI([Ci.nsITimerCallback,
                                         Ci.nsISupportsWeakReference]),
  classID: Components.ID("{8f7002cb-e959-4f0a-a2e8-563232564385}"),

  notify: function(aTimer) {
    // If we are offline, wait to be online to start the update check.
    if (Services.io.offline) {
      dump("Network is offline. Setting up an offline status observer.");
      Services.obs.addObserver(this, "network:offline-status-changed", true);
      return;
    }

    WebappManager.checkForUpdates();
  },

  observe: function(aSubject, aTopic, aData) {
    if (aTopic !== "network:offline-status-changed" || aData !== "online") {
      return;
    }

    dump("Network is online. Checking for webapp updates.");
    Services.obs.removeObserver(this, "network:offline-status-changed");
    WebappManager.checkForUpdates();
  }
};

this.NSGetFactory = XPCOMUtils.generateNSGetFactory([WebappsUpdateTimer]);
