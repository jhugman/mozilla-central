/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

"use strict";

this.EXPORTED_SYMBOLS = ["SessionCookies"];

const Cu = Components.utils;
const Ci = Components.interfaces;

Cu.import("resource://gre/modules/Services.jsm", this);
Cu.import("resource://gre/modules/XPCOMUtils.jsm", this);

XPCOMUtils.defineLazyModuleGetter(this, "PrivacyLevel",
  "resource:///modules/sessionstore/PrivacyLevel.jsm");

// MAX_EXPIRY should be 2^63-1, but JavaScript can't handle that precision.
const MAX_EXPIRY = Math.pow(2, 62);

// Creates a new nsIURI object.
function makeURI(uri) {
  return Services.io.newURI(uri, null, null);
}

/**
 * The external API implemented by the SessionCookies module.
 */
this.SessionCookies = Object.freeze({
  update: function (windows) {
    SessionCookiesInternal.update(windows);
  },

  getHostsForWindow: function (window, checkPrivacy = false) {
    return SessionCookiesInternal.getHostsForWindow(window, checkPrivacy);
  }
});

/**
 * The internal API.
 */
let SessionCookiesInternal = {
  /**
   * Stores whether we're initialized, yet.
   */
  _initialized: false,

  /**
   * Retrieve the list of all hosts contained in the given windows' session
   * history entries (per window) and collect the associated cookies for those
   * hosts, if any. The given state object is being modified.
   *
   * @param windows
   *        Array of window state objects.
   *        [{ tabs: [...], cookies: [...] }, ...]
   */
  update: function (windows) {
    this._ensureInitialized();

    for (let window of windows) {
      let cookies = [];

      // Collect all hosts for the current window.
      let hosts = this.getHostsForWindow(window, true);

      for (let [host, isPinned] in Iterator(hosts)) {
        for (let cookie of CookieStore.getCookiesForHost(host)) {
          // _getCookiesForHost() will only return hosts with the right privacy
          // rules, so there is no need to do anything special with this call
          // to PrivacyLevel.canSave().
          if (PrivacyLevel.canSave({isHttps: cookie.secure, isPinned: isPinned})) {
            cookies.push(cookie);
          }
        }
      }

      // Don't include/keep empty cookie sections.
      if (cookies.length) {
        window.cookies = cookies;
      } else if ("cookies" in window) {
        delete window.cookies;
      }
    }
  },

  /**
   * Returns a map of all hosts for a given window that we might want to
   * collect cookies for.
   *
   * @param window
   *        A window state object containing tabs with history entries.
   * @param checkPrivacy (bool)
   *        Whether to check the privacy level for each host.
   * @return {object} A map of hosts for a given window state object. The keys
   *                  will be hosts, the values are boolean and determine
   *                  whether we will use the deferred privacy level when
   *                  checking how much data to save on quitting.
   */
  getHostsForWindow: function (window, checkPrivacy = false) {
    let hosts = {};

    for (let tab of window.tabs) {
      for (let entry of tab.entries) {
        this._extractHostsFromEntry(entry, hosts, checkPrivacy, tab.pinned);
      }
    }

    return hosts;
  },

  /**
   * Handles observers notifications that are sent whenever cookies are added,
   * changed, or removed. Ensures that the storage is updated accordingly.
   */
  observe: function (subject, topic, data) {
    switch (data) {
      case "added":
      case "changed":
        this._updateCookie(subject);
        break;
      case "deleted":
        this._removeCookie(subject);
        break;
      case "cleared":
        CookieStore.clear();
        break;
      case "batch-deleted":
        this._removeCookies(subject);
        break;
      case "reload":
        CookieStore.clear();
        this._reloadCookies();
        break;
      default:
        throw new Error("Unhandled cookie-changed notification.");
    }
  },

  /**
   * If called for the first time in a session, iterates all cookies in the
   * cookies service and puts them into the store if they're session cookies.
   */
  _ensureInitialized: function () {
    if (!this._initialized) {
      this._reloadCookies();
      this._initialized = true;
      Services.obs.addObserver(this, "cookie-changed", false);
    }
  },

  /**
   * Fill a given map with hosts found in the given entry's session history and
   * any child entries.
   *
   * @param entry
   *        the history entry, serialized
   * @param hosts
   *        the hash that will be used to store hosts eg, { hostname: true }
   * @param checkPrivacy
   *        should we check the privacy level for https
   * @param isPinned
   *        is the entry we're evaluating for a pinned tab; used only if
   *        checkPrivacy
   */
  _extractHostsFromEntry: function (entry, hosts, checkPrivacy, isPinned) {
    let host = entry._host;
    let scheme = entry._scheme;

    // If host & scheme aren't defined, then we are likely here in the startup
    // process via _splitCookiesFromWindow. In that case, we'll turn entry.url
    // into an nsIURI and get host/scheme from that. This will throw for about:
    // urls in which case we don't need to do anything.
    if (!host && !scheme) {
      try {
        let uri = makeURI(entry.url);
        host = uri.host;
        scheme = uri.scheme;
        this._extractHostsFromHostScheme(host, scheme, hosts, checkPrivacy, isPinned);
      }
      catch (ex) { }
    }

    if (entry.children) {
      for (let child of entry.children) {
        this._extractHostsFromEntry(child, hosts, checkPrivacy, isPinned);
      }
    }
  },

  /**
   * Add a given host to a given map of hosts if the privacy level allows
   * saving cookie data for it.
   *
   * @param host
   *        the host of a uri (usually via nsIURI.host)
   * @param scheme
   *        the scheme of a uri (usually via nsIURI.scheme)
   * @param hosts
   *        the hash that will be used to store hosts eg, { hostname: true }
   * @param checkPrivacy
   *        should we check the privacy level for https
   * @param isPinned
   *        is the entry we're evaluating for a pinned tab; used only if
   *        checkPrivacy
   */
  _extractHostsFromHostScheme:
    function (host, scheme, hosts, checkPrivacy, isPinned) {
    // host and scheme may not be set (for about: urls for example), in which
    // case testing scheme will be sufficient.
    if (/https?/.test(scheme) && !hosts[host] &&
        (!checkPrivacy ||
         PrivacyLevel.canSave({isHttps: scheme == "https", isPinned: isPinned}))) {
      // By setting this to true or false, we can determine when looking at
      // the host in update() if we should check for privacy.
      hosts[host] = isPinned;
    } else if (scheme == "file") {
      hosts[host] = true;
    }
  },

  /**
   * Updates or adds a given cookie to the store.
   */
  _updateCookie: function (cookie) {
    cookie.QueryInterface(Ci.nsICookie2);

    if (cookie.isSession) {
      CookieStore.set(cookie);
    }
  },

  /**
   * Removes a given cookie from the store.
   */
  _removeCookie: function (cookie) {
    cookie.QueryInterface(Ci.nsICookie2);

    if (cookie.isSession) {
      CookieStore.delete(cookie);
    }
  },

  /**
   * Removes a given list of cookies from the store.
   */
  _removeCookies: function (cookies) {
    for (let i = 0; i < cookies.length; i++) {
      this._removeCookie(cookies.queryElementAt(i, Ci.nsICookie2));
    }
  },

  /**
   * Iterates all cookies in the cookies service and puts them into the store
   * if they're session cookies.
   */
  _reloadCookies: function () {
    let iter = Services.cookies.enumerator;
    while (iter.hasMoreElements()) {
      this._updateCookie(iter.getNext());
    }
  }
};

/**
 * The internal cookie storage that keeps track of every active session cookie.
 * These are stored using maps per host, path, and cookie name.
 */
let CookieStore = {
  /**
   * The internal structure holding all known cookies.
   *
   * Host =>
   *  Path =>
   *    Name => {path: "/", name: "sessionid", secure: true}
   *
   * Maps are used for storage but the data structure is equivalent to this:
   *
   * this._hosts = {
   *   "www.mozilla.org": {
   *     "/": {
   *       "username": {name: "username", value: "my_name_is", etc...},
   *       "sessionid": {name: "sessionid", value: "1fdb3a", etc...}
   *     }
   *   },
   *   "tbpl.mozilla.org": {
   *     "/path": {
   *       "cookiename": {name: "cookiename", value: "value", etc...}
   *     }
   *   }
   * };
   */
  _hosts: new Map(),

  /**
   * Returns the list of stored session cookies for a given host.
   *
   * @param host
   *        A string containing the host name we want to get cookies for.
   */
  getCookiesForHost: function (host) {
    if (!this._hosts.has(host)) {
      return [];
    }

    let cookies = [];

    for (let pathToNamesMap of this._hosts.get(host).values()) {
      cookies = cookies.concat([cookie for (cookie of pathToNamesMap.values())]);
    }

    return cookies;
  },

  /**
   * Stores a given cookie.
   *
   * @param cookie
   *        The nsICookie2 object to add to the storage.
   */
  set: function (cookie) {
    let jscookie = {host: cookie.host, value: cookie.value};

    // Only add properties with non-default values to save a few bytes.
    if (cookie.path) {
      jscookie.path = cookie.path;
    }

    if (cookie.name) {
      jscookie.name = cookie.name;
    }

    if (cookie.isSecure) {
      jscookie.secure = true;
    }

    if (cookie.isHttpOnly) {
      jscookie.httponly = true;
    }

    if (cookie.expiry < MAX_EXPIRY) {
      jscookie.expiry = cookie.expiry;
    }

    this._ensureMap(cookie).set(cookie.name, jscookie);
  },

  /**
   * Removes a given cookie.
   *
   * @param cookie
   *        The nsICookie2 object to be removed from storage.
   */
  delete: function (cookie) {
    this._ensureMap(cookie).delete(cookie.name);
  },

  /**
   * Removes all cookies.
   */
  clear: function () {
    this._hosts.clear();
  },

  /**
   * Creates all maps necessary to store a given cookie.
   *
   * @param cookie
   *        The nsICookie2 object to create maps for.
   *
   * @return The newly created Map instance mapping cookie names to
   *         internal jscookies, in the given path of the given host.
   */
  _ensureMap: function (cookie) {
    if (!this._hosts.has(cookie.host)) {
      this._hosts.set(cookie.host, new Map());
    }

    let pathToNamesMap = this._hosts.get(cookie.host);

    if (!pathToNamesMap.has(cookie.path)) {
      pathToNamesMap.set(cookie.path, new Map());
    }

    return pathToNamesMap.get(cookie.path);
  }
};
