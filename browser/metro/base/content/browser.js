// -*- Mode: js2; tab-width: 2; indent-tabs-mode: nil; js2-basic-offset: 2; js2-skip-preprocessor-directives: t; -*-
/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

let Cc = Components.classes;
let Ci = Components.interfaces;
let Cu = Components.utils;
let Cr = Components.results;

Cu.import("resource://gre/modules/PageThumbs.jsm");

// Page for which the start UI is shown
const kStartURI = "about:start";

// allow panning after this timeout on pages with registered touch listeners
const kTouchTimeout = 300;
const kSetInactiveStateTimeout = 100;

const kTabThumbnailDelayCapture = 500;

const XUL_NS = "http://www.mozilla.org/keymaster/gatekeeper/there.is.only.xul";

// See grid.xml, we use this to cache style info across loads of the startui.
var _richgridTileSizes = {};

// Override sizeToContent in the main window. It breaks things (bug 565887)
window.sizeToContent = function() {
  Cu.reportError("window.sizeToContent is not allowed in this window");
}

function getTabModalPromptBox(aWindow) {
  let browser = Browser.getBrowserForWindow(aWindow);
  return Browser.getTabModalPromptBox(browser);
}

/*
 * Returns the browser for the currently displayed tab.
 */
function getBrowser() {
  return Browser.selectedBrowser;
}

var Browser = {
  _debugEvents: false,
  _tabs: [],
  _selectedTab: null,
  _tabId: 0,
  windowUtils: window.QueryInterface(Ci.nsIInterfaceRequestor)
                     .getInterface(Ci.nsIDOMWindowUtils),

  get defaultBrowserWidth() {
    return window.innerWidth;
  },

  startup: function startup() {
    var self = this;

    try {
      messageManager.loadFrameScript("chrome://browser/content/Util.js", true);
      messageManager.loadFrameScript("chrome://browser/content/contenthandlers/Content.js", true);
      messageManager.loadFrameScript("chrome://browser/content/contenthandlers/FormHelper.js", true);
      messageManager.loadFrameScript("chrome://browser/content/library/SelectionPrototype.js", true);
      messageManager.loadFrameScript("chrome://browser/content/contenthandlers/SelectionHandler.js", true);
      messageManager.loadFrameScript("chrome://browser/content/contenthandlers/ContextMenuHandler.js", true);
      messageManager.loadFrameScript("chrome://browser/content/contenthandlers/FindHandler.js", true);
      messageManager.loadFrameScript("chrome://browser/content/contenthandlers/ConsoleAPIObserver.js", true);
    } catch (e) {
      // XXX whatever is calling startup needs to dump errors!
      dump("###########" + e + "\n");
    }

    /* handles dispatching clicks on browser into clicks in content or zooms */
    Elements.browsers.customDragger = new Browser.MainDragger();

    /* handles web progress management for open browsers */
    Elements.browsers.webProgress = WebProgress.init();

    // Call InputSourceHelper first so global listeners get called before
    // we start processing input in TouchModule.
    InputSourceHelper.init();

    TouchModule.init();
    GestureModule.init();
    BrowserTouchHandler.init();
    PopupBlockerObserver.init();
    APZCObserver.init();

    // Init the touch scrollbox
    this.contentScrollbox = Elements.browsers;
    this.contentScrollboxScroller = {
      scrollBy: function(aDx, aDy) {
        let view = getBrowser().getRootView();
        view.scrollBy(aDx, aDy);
      },

      scrollTo: function(aX, aY) {
        let view = getBrowser().getRootView();
        view.scrollTo(aX, aY);
      },

      getPosition: function(aScrollX, aScrollY) {
        let view = getBrowser().getRootView();
        let scroll = view.getPosition();
        aScrollX.value = scroll.x;
        aScrollY.value = scroll.y;
      }
    };

    ContentAreaObserver.init();

    function fullscreenHandler() {
      if (Browser.selectedBrowser.contentWindow.document.mozFullScreenElement)
        Elements.stack.setAttribute("fullscreen", "true");
      else
        Elements.stack.removeAttribute("fullscreen");
    }
    window.addEventListener("mozfullscreenchange", fullscreenHandler, true);

    BrowserUI.init();

    window.controllers.appendController(this);
    window.controllers.appendController(BrowserUI);

    Services.obs.addObserver(SessionHistoryObserver, "browser:purge-session-history", false);

    window.QueryInterface(Ci.nsIDOMChromeWindow).browserDOMWindow = new nsBrowserAccess();

    Elements.browsers.addEventListener("DOMUpdatePageReport", PopupBlockerObserver.onUpdatePageReport, false);

    // Make sure we're online before attempting to load
    Util.forceOnline();

    // If this is an intial window launch the commandline handler passes us the default
    // page as an argument.
    let commandURL = null;
    try {
      let argsObj = window.arguments[0].wrappedJSObject;
      if (argsObj && argsObj.pageloadURL) {
        // Talos tp-cmdline parameter
        commandURL = argsObj.pageloadURL;
      } else if (window.arguments && window.arguments[0]) {
        // BrowserCLH paramerter
        commandURL = window.arguments[0];
      }
    } catch (ex) {
      Util.dumpLn(ex);
    }

    messageManager.addMessageListener("DOMLinkAdded", this);
    messageManager.addMessageListener("Browser:FormSubmit", this);
    messageManager.addMessageListener("Browser:CanUnload:Return", this);
    messageManager.addMessageListener("scroll", this);
    messageManager.addMessageListener("Browser:CertException", this);
    messageManager.addMessageListener("Browser:BlockedSite", this);
    messageManager.addMessageListener("Browser:TapOnSelection", this);

    Task.spawn(function() {
      // Activation URIs come from protocol activations, secondary tiles, and file activations
      let activationURI = yield this.getShortcutOrURI(Services.metro.activationURI);

      let self = this;
      function loadStartupURI() {
        if (activationURI) {
          self.addTab(activationURI, true, null, { flags: Ci.nsIWebNavigation.LOAD_FLAGS_ALLOW_THIRD_PARTY_FIXUP });
        } else {
          let uri = commandURL || Browser.getHomePage();
          self.addTab(uri, true);
        }
      }

      // Should we restore the previous session (crash or some other event)
      let ss = Cc["@mozilla.org/browser/sessionstore;1"].getService(Ci.nsISessionStore);
      if (ss.shouldRestore() || Services.prefs.getBoolPref("browser.startup.sessionRestore")) {
        let bringFront = false;
        // First open any commandline URLs, except the homepage
        if (activationURI && activationURI != kStartURI) {
          this.addTab(activationURI, true, null, { flags: Ci.nsIWebNavigation.LOAD_FLAGS_ALLOW_THIRD_PARTY_FIXUP });
        } else if (commandURL && commandURL != kStartURI) {
          this.addTab(commandURL, true);
        } else {
          bringFront = true;
          // Initial window resizes call functions that assume a tab is in the tab list
          // and restored tabs are added too late. We add a dummy to to satisfy the resize
          // code and then remove the dummy after the session has been restored.
          let dummy = this.addTab("about:blank", true);
          let dummyCleanup = {
            observe: function(aSubject, aTopic, aData) {
              Services.obs.removeObserver(dummyCleanup, "sessionstore-windows-restored");
              if (aData == "fail")
                loadStartupURI();
              dummy.chromeTab.ignoreUndo = true;
              Browser.closeTab(dummy, { forceClose: true });
            }
          };
          Services.obs.addObserver(dummyCleanup, "sessionstore-windows-restored", false);
        }
        ss.restoreLastSession(bringFront);
      } else {
        loadStartupURI();
      }

      // Notify about our input type
      InputSourceHelper.fireUpdate();

      // Broadcast a UIReady message so add-ons know we are finished with startup
      let event = document.createEvent("Events");
      event.initEvent("UIReady", true, false);
      window.dispatchEvent(event);
    }.bind(this));
  },

  shutdown: function shutdown() {
    APZCObserver.shutdown();
    BrowserUI.uninit();
    ContentAreaObserver.shutdown();
    Appbar.shutdown();

    messageManager.removeMessageListener("Browser:FormSubmit", this);
    messageManager.removeMessageListener("scroll", this);
    messageManager.removeMessageListener("Browser:CertException", this);
    messageManager.removeMessageListener("Browser:BlockedSite", this);
    messageManager.removeMessageListener("Browser:TapOnSelection", this);

    Services.obs.removeObserver(SessionHistoryObserver, "browser:purge-session-history");

    window.controllers.removeController(this);
    window.controllers.removeController(BrowserUI);
  },

  getHomePage: function getHomePage(aOptions) {
    aOptions = aOptions || { useDefault: false };

    let url = kStartURI;
    try {
      let prefs = aOptions.useDefault ? Services.prefs.getDefaultBranch(null) : Services.prefs;
      url = prefs.getComplexValue("browser.startup.homepage", Ci.nsIPrefLocalizedString).data;
    }
    catch(e) { }

    return url;
  },

  get browsers() {
    return this._tabs.map(function(tab) { return tab.browser; });
  },

  /**
   * Load a URI in the current tab, or a new tab if necessary.
   * @param aURI String
   * @param aParams Object with optional properties that will be passed to loadURIWithFlags:
   *    flags, referrerURI, charset, postData.
   */
  loadURI: function loadURI(aURI, aParams) {
    let browser = this.selectedBrowser;

    // We need to keep about: pages opening in new "local" tabs. We also want to spawn
    // new "remote" tabs if opening web pages from a "local" about: page.
    dump("loadURI=" + aURI + "\ncurrentURI=" + browser.currentURI.spec + "\n");

    let params = aParams || {};
    try {
      let flags = params.flags || Ci.nsIWebNavigation.LOAD_FLAGS_NONE;
      let postData = ("postData" in params && params.postData) ? params.postData.value : null;
      let referrerURI = "referrerURI" in params ? params.referrerURI : null;
      let charset = "charset" in params ? params.charset : null;
      dump("loading tab: " + aURI + "\n");
      browser.loadURIWithFlags(aURI, flags, referrerURI, charset, postData);
    } catch(e) {
      dump("Error: " + e + "\n");
    }
  },

  /**
   * Determine if the given URL is a shortcut/keyword and, if so, expand it
   * @param aURL String
   * @param aPostDataRef Out param contains any required post data for a search
   * @return {Promise}
   * @result the expanded shortcut, or the original URL if not a shortcut
   */
  getShortcutOrURI: function getShortcutOrURI(aURL, aPostDataRef) {
    return Task.spawn(function() {
      if (!aURL)
        throw new Task.Result(aURL);

      let shortcutURL = null;
      let keyword = aURL;
      let param = "";

      let offset = aURL.indexOf(" ");
      if (offset > 0) {
        keyword = aURL.substr(0, offset);
        param = aURL.substr(offset + 1);
      }

      if (!aPostDataRef)
        aPostDataRef = {};

      let engine = Services.search.getEngineByAlias(keyword);
      if (engine) {
        let submission = engine.getSubmission(param);
        aPostDataRef.value = submission.postData;
        throw new Task.Result(submission.uri.spec);
      }

      try {
        [shortcutURL, aPostDataRef.value] = PlacesUtils.getURLAndPostDataForKeyword(keyword);
      } catch (e) {}

      if (!shortcutURL)
        throw new Task.Result(aURL);

      let postData = "";
      if (aPostDataRef.value)
        postData = unescape(aPostDataRef.value);

      if (/%s/i.test(shortcutURL) || /%s/i.test(postData)) {
        let charset = "";
        const re = /^(.*)\&mozcharset=([a-zA-Z][_\-a-zA-Z0-9]+)\s*$/;
        let matches = shortcutURL.match(re);
        if (matches)
          [, shortcutURL, charset] = matches;
        else {
          // Try to get the saved character-set.
          try {
            // makeURI throws if URI is invalid.
            // Will return an empty string if character-set is not found.
            charset = yield PlacesUtils.getCharsetForURI(Util.makeURI(shortcutURL));
          } catch (e) { dump("--- error " + e + "\n"); }
        }

        let encodedParam = "";
        if (charset)
          encodedParam = escape(convertFromUnicode(charset, param));
        else // Default charset is UTF-8
          encodedParam = encodeURIComponent(param);

        shortcutURL = shortcutURL.replace(/%s/g, encodedParam).replace(/%S/g, param);

        if (/%s/i.test(postData)) // POST keyword
          aPostDataRef.value = getPostDataStream(postData, param, encodedParam, "application/x-www-form-urlencoded");
      } else if (param) {
        // This keyword doesn't take a parameter, but one was provided. Just return
        // the original URL.
        aPostDataRef.value = null;

        throw new Task.Result(aURL);
      }

      throw new Task.Result(shortcutURL);
    });
  },

  /**
   * Return the currently active <browser> object
   */
  get selectedBrowser() {
    return (this._selectedTab && this._selectedTab.browser);
  },

  get tabs() {
    return this._tabs;
  },

  getTabModalPromptBox: function(aBrowser) {
    let browser = (aBrowser || getBrowser());
    let stack = browser.parentNode;
    let self = this;

    let promptBox = {
      appendPrompt : function(args, onCloseCallback) {
          let newPrompt = document.createElementNS(XUL_NS, "tabmodalprompt");
          newPrompt.setAttribute("promptType", args.promptType);
          stack.appendChild(newPrompt);
          browser.setAttribute("tabmodalPromptShowing", true);
          newPrompt.clientTop; // style flush to assure binding is attached

          let tab = self.getTabForBrowser(browser);
          tab = tab.chromeTab;

          newPrompt.metroInit(args, tab, onCloseCallback);
          return newPrompt;
      },

      removePrompt : function(aPrompt) {
          stack.removeChild(aPrompt);

          let prompts = this.listPrompts();
          if (prompts.length) {
          let prompt = prompts[prompts.length - 1];
              prompt.Dialog.setDefaultFocus();
          } else {
              browser.removeAttribute("tabmodalPromptShowing");
              browser.focus();
          }
      },

      listPrompts : function(aPrompt) {
          let els = stack.getElementsByTagNameNS(XUL_NS, "tabmodalprompt");
          // NodeList --> real JS array
          let prompts = Array.slice(els);
          return prompts;
      },
    };

    return promptBox;
  },

  getBrowserForWindowId: function getBrowserForWindowId(aWindowId) {
    for (let i = 0; i < this.browsers.length; i++) {
      if (this.browsers[i].contentWindowId == aWindowId)
        return this.browsers[i];
    }
    return null;
  },

  getBrowserForWindow: function(aWindow) {
    let windowID = aWindow.QueryInterface(Ci.nsIInterfaceRequestor)
                          .getInterface(Ci.nsIDOMWindowUtils).currentInnerWindowID;
    return this.getBrowserForWindowId(windowID);
  },

  getTabForBrowser: function getTabForBrowser(aBrowser) {
    let tabs = this._tabs;
    for (let i = 0; i < tabs.length; i++) {
      if (tabs[i].browser == aBrowser)
        return tabs[i];
    }
    return null;
  },

  getTabAtIndex: function getTabAtIndex(index) {
    if (index >= this._tabs.length || index < 0)
      return null;
    return this._tabs[index];
  },

  getTabFromChrome: function getTabFromChrome(chromeTab) {
    for (var t = 0; t < this._tabs.length; t++) {
      if (this._tabs[t].chromeTab == chromeTab)
        return this._tabs[t];
    }
    return null;
  },

  createTabId: function createTabId() {
    return this._tabId++;
  },

  /**
   * Create a new tab and add it to the tab list.
   *
   * If you are opening a new foreground tab in response to a user action, use
   * BrowserUI.addAndShowTab which will also show the tab strip.
   *
   * @param aURI String specifying the URL to load.
   * @param aBringFront Boolean (optional) Open the new tab in the foreground?
   * @param aOwner Tab object (optional) The "parent" of the new tab.
   *   This is the tab responsible for opening the new tab.  When the new tab
   *   is closed, we will return to a parent or "sibling" tab if possible.
   * @param aParams Object (optional) with optional properties:
   *   index: Number specifying where in the tab list to insert the new tab.
   *   flags, postData, charset, referrerURI: See loadURIWithFlags.
   */
  addTab: function browser_addTab(aURI, aBringFront, aOwner, aParams) {
    let params = aParams || {};

    if (aOwner && !('index' in params)) {
      // Position the new tab to the right of its owner...
      params.index = this._tabs.indexOf(aOwner) + 1;
      // ...and to the right of any siblings.
      while (this._tabs[params.index] && this._tabs[params.index].owner == aOwner) {
        params.index++;
      }
    }

    let newTab = new Tab(aURI, params, aOwner);

    if (params.index >= 0) {
      this._tabs.splice(params.index, 0, newTab);
    } else {
      this._tabs.push(newTab);
    }

    if (aBringFront)
      this.selectedTab = newTab;

    this._announceNewTab(newTab, params, aBringFront);
    return newTab;
  },

  closeTab: function closeTab(aTab, aOptions) {
    let tab = aTab instanceof XULElement ? this.getTabFromChrome(aTab) : aTab;
    if (!tab) {
      return;
    }

    if (aOptions && "forceClose" in aOptions && aOptions.forceClose) {
      this._doCloseTab(tab);
      return;
    }

    tab.browser.messageManager.sendAsyncMessage("Browser:CanUnload", {});
  },

  savePage: function() {
    ContentAreaUtils.saveDocument(this.selectedBrowser.contentWindow.document);
  },

  /*
   * helper for addTab related methods. Fires events related to
   * new tab creation.
   */
  _announceNewTab: function _announceNewTab(aTab, aParams, aBringFront) {
    let event = document.createEvent("UIEvents");
    event.initUIEvent("TabOpen", true, false, window, 0);
    aTab.chromeTab.dispatchEvent(event);
    aTab.browser.messageManager.sendAsyncMessage("Browser:TabOpen");
  },

  _doCloseTab: function _doCloseTab(aTab) {
    if (this._tabs.length === 1) {
      Browser.addTab(this.getHomePage());
    }

    let nextTab = this.getNextTab(aTab);

    // Tabs owned by the closed tab are now orphaned.
    this._tabs.forEach(function(item, index, array) {
      if (item.owner == aTab)
        item.owner = null;
    });

    // tray tab
    let event = document.createEvent("Events");
    event.initEvent("TabClose", true, false);
    aTab.chromeTab.dispatchEvent(event);

    // tab window
    event = document.createEvent("Events");
    event.initEvent("TabClose", true, false);
    aTab.browser.contentWindow.dispatchEvent(event);

    aTab.browser.messageManager.sendAsyncMessage("Browser:TabClose");

    let container = aTab.chromeTab.parentNode;
    aTab.destroy();
    this._tabs.splice(this._tabs.indexOf(aTab), 1);

    this.selectedTab = nextTab;

    event = document.createEvent("Events");
    event.initEvent("TabRemove", true, false);
    container.dispatchEvent(event);
  },

  getNextTab: function getNextTab(aTab) {
    let tabIndex = this._tabs.indexOf(aTab);
    if (tabIndex == -1)
      return null;

    if (this._selectedTab == aTab || this._selectedTab.chromeTab.hasAttribute("closing")) {
      let nextTabIndex = tabIndex + 1;
      let nextTab = null;

      while (nextTabIndex < this._tabs.length && (!nextTab || nextTab.chromeTab.hasAttribute("closing"))) {
        nextTab = this.getTabAtIndex(nextTabIndex);
        nextTabIndex++;
      }

      nextTabIndex = tabIndex - 1;
      while (nextTabIndex >= 0 && (!nextTab || nextTab.chromeTab.hasAttribute("closing"))) {
        nextTab = this.getTabAtIndex(nextTabIndex);
        nextTabIndex--;
      }

      if (!nextTab || nextTab.chromeTab.hasAttribute("closing"))
        return null;

      // If the next tab is not a sibling, switch back to the parent.
      if (aTab.owner && nextTab.owner != aTab.owner)
        nextTab = aTab.owner;

      if (!nextTab)
        return null;

      return nextTab;
    }

    return this._selectedTab;
  },

  get selectedTab() {
    return this._selectedTab;
  },

  set selectedTab(tab) {
    if (tab instanceof XULElement)
      tab = this.getTabFromChrome(tab);

    if (!tab)
      return;

    if (this._selectedTab == tab) {
      // Deck does not update its selectedIndex when children
      // are removed. See bug 602708
      Elements.browsers.selectedPanel = tab.notification;
      return;
    }

    let isFirstTab = this._selectedTab == null;
    let lastTab = this._selectedTab;
    let browser = tab.browser;

    this._selectedTab = tab;

    if (lastTab)
      lastTab.active = false;

    if (tab)
      tab.active = true;

    BrowserUI.update();

    if (isFirstTab) {
      BrowserUI._titleChanged(browser);
    } else {
      // Update all of our UI to reflect the new tab's location
      BrowserUI.updateURI();

      let event = document.createEvent("Events");
      event.initEvent("TabSelect", true, false);
      event.lastTab = lastTab;
      tab.chromeTab.dispatchEvent(event);
    }

    tab.lastSelected = Date.now();
  },

  supportsCommand: function(cmd) {
    return false;
  },

  isCommandEnabled: function(cmd) {
    return false;
  },

  doCommand: function(cmd) {
  },

  getNotificationBox: function getNotificationBox(aBrowser) {
    let browser = aBrowser || this.selectedBrowser;
    return browser.parentNode.parentNode;
  },

  /**
   * Handle cert exception message from content.
   */
  _handleCertException: function _handleCertException(aMessage) {
    let json = aMessage.json;
    if (json.action == "leave") {
      // Get the start page from the *default* pref branch, not the user's
      let url = Browser.getHomePage({ useDefault: true });
      this.loadURI(url);
    } else {
      // Handle setting an cert exception and reloading the page
      try {
        // Add a new SSL exception for this URL
        let uri = Services.io.newURI(json.url, null, null);
        let sslExceptions = new SSLExceptions();

        if (json.action == "permanent")
          sslExceptions.addPermanentException(uri, window);
        else
          sslExceptions.addTemporaryException(uri, window);
      } catch (e) {
        dump("EXCEPTION handle content command: " + e + "\n" );
      }

      // Automatically reload after the exception was added
      aMessage.target.reload();
    }
  },

  /**
   * Handle blocked site message from content.
   */
  _handleBlockedSite: function _handleBlockedSite(aMessage) {
    let formatter = Cc["@mozilla.org/toolkit/URLFormatterService;1"].getService(Ci.nsIURLFormatter);
    let json = aMessage.json;
    switch (json.action) {
      case "leave": {
        // Get the start page from the *default* pref branch, not the user's
        let url = Browser.getHomePage({ useDefault: true });
        this.loadURI(url);
        break;
      }
      case "report-malware": {
        // Get the stop badware "why is this blocked" report url, append the current url, and go there.
        try {
          let reportURL = formatter.formatURLPref("browser.safebrowsing.malware.reportURL");
          reportURL += json.url;
          this.loadURI(reportURL);
        } catch (e) {
          Cu.reportError("Couldn't get malware report URL: " + e);
        }
        break;
      }
      case "report-phishing": {
        // It's a phishing site, just link to the generic information page
        let url = Services.urlFormatter.formatURLPref("app.support.baseURL");
        this.loadURI(url + "phishing-malware");
        break;
      }
    }
  },

  pinSite: function browser_pinSite() {
    // Get a path to our app tile
    var file = Components.classes["@mozilla.org/file/directory_service;1"].
           getService(Components.interfaces.nsIProperties).
           get("CurProcD", Components.interfaces.nsIFile);
    // Get rid of the current working directory's metro subidr
    file = file.parent;
    file.append("tileresources");
    file.append("VisualElements_logo.png");
    var ios = Components.classes["@mozilla.org/network/io-service;1"].
              getService(Components.interfaces.nsIIOService);
    var uriSpec = ios.newFileURI(file).spec;
    Services.metro.pinTileAsync(this._currentPageTileID,
                                Browser.selectedBrowser.contentTitle, // short name
                                Browser.selectedBrowser.contentTitle, // display name
                                "metrobrowser -url " + Browser.selectedBrowser.currentURI.spec,
                            uriSpec, uriSpec);
  },

  get _currentPageTileID() {
    // We use a unique ID per URL, so just use an MD5 hash of the URL as the ID.
    let hasher = Cc["@mozilla.org/security/hash;1"].
                 createInstance(Ci.nsICryptoHash);
    hasher.init(Ci.nsICryptoHash.MD5);
    let stringStream = Cc["@mozilla.org/io/string-input-stream;1"].
                       createInstance(Ci.nsIStringInputStream);
    stringStream.data = Browser.selectedBrowser.currentURI.spec;
    hasher.updateFromStream(stringStream, -1);
    let hashASCII = hasher.finish(true);
    // Replace '/' with a valid filesystem character
    return ("FFTileID_" + hashASCII).replace('/', '_', 'g');
  },

  unpinSite: function browser_unpinSite() {
    if (!Services.metro.immersive)
      return;

    Services.metro.unpinTileAsync(this._currentPageTileID);
  },

  isSitePinned: function browser_isSitePinned() {
    if (!Services.metro.immersive)
      return false;

    return Services.metro.isTilePinned(this._currentPageTileID);
  },

  starSite: function browser_starSite(callback) {
    let uri = this.selectedBrowser.currentURI;
    let title = this.selectedBrowser.contentTitle;

    Bookmarks.addForURI(uri, title, callback);
  },

  unstarSite: function browser_unstarSite(callback) {
    let uri = this.selectedBrowser.currentURI;
    Bookmarks.removeForURI(uri, callback);
  },

  isSiteStarredAsync: function browser_isSiteStarredAsync(callback) {
    let uri = this.selectedBrowser.currentURI;
    Bookmarks.isURIBookmarked(uri, callback);
  },

  /** Rect should be in browser coordinates. */
  _getZoomLevelForRect: function _getZoomLevelForRect(rect) {
    const margin = 15;
    return this.selectedTab.clampZoomLevel(ContentAreaObserver.width / (rect.width + margin * 2));
  },

  /**
   * Find a good zoom rectangle for point that is specified in browser coordinates.
   * @return Rect in viewport coordinates
   */
  _getZoomRectForPoint: function _getZoomRectForPoint(x, y, zoomLevel) {
    let browser = getBrowser();
    x = x * browser.scale;
    y = y * browser.scale;

    zoomLevel = Math.min(ZoomManager.MAX, zoomLevel);
    let oldScale = browser.scale;
    let zoomRatio = zoomLevel / oldScale;
    let browserRect = browser.getBoundingClientRect();
    let newVisW = browserRect.width / zoomRatio, newVisH = browserRect.height / zoomRatio;
    let result = new Rect(x - newVisW / 2, y - newVisH / 2, newVisW, newVisH);

    // Make sure rectangle doesn't poke out of viewport
    return result.translateInside(new Rect(0, 0, browser.contentDocumentWidth * oldScale,
                                                 browser.contentDocumentHeight * oldScale));
  },

  /**
   * Convenience function for getting the scrollbox position off of a
   * scrollBoxObject interface.  Returns the actual values instead of the
   * wrapping objects.
   *
   * @param scroller a scrollBoxObject on which to call scroller.getPosition()
   */
  getScrollboxPosition: function getScrollboxPosition(scroller) {
    let x = {};
    let y = {};
    scroller.getPosition(x, y);
    return new Point(x.value, y.value);
  },

  forceChromeReflow: function forceChromeReflow() {
    let dummy = getComputedStyle(document.documentElement, "").width;
  },

  receiveMessage: function receiveMessage(aMessage) {
    let json = aMessage.json;
    let browser = aMessage.target;

    switch (aMessage.name) {
      case "DOMLinkAdded": {
        // checks for an icon to use for a web app
        // apple-touch-icon size is 57px and default size is 16px
        let rel = json.rel.toLowerCase().split(" ");
        if (rel.indexOf("icon") != -1) {
          // We use the sizes attribute if available
          // see http://www.whatwg.org/specs/web-apps/current-work/multipage/links.html#rel-icon
          let size = 16;
          if (json.sizes) {
            let sizes = json.sizes.toLowerCase().split(" ");
            sizes.forEach(function(item) {
              if (item != "any") {
                let [w, h] = item.split("x");
                size = Math.max(Math.min(w, h), size);
              }
            });
          }
          if (size > browser.appIcon.size) {
            browser.appIcon.href = json.href;
            browser.appIcon.size = size;
          }
        }
        else if ((rel.indexOf("apple-touch-icon") != -1) && (browser.appIcon.size < 57)) {
          // XXX should we support apple-touch-icon-precomposed ?
          // see http://developer.apple.com/safari/library/documentation/appleapplications/reference/safariwebcontent/configuringwebapplications/configuringwebapplications.html
          browser.appIcon.href = json.href;
          browser.appIcon.size = 57;
        }
        break;
      }
      case "Browser:FormSubmit":
        browser.lastLocation = null;
        break;

      case "Browser:CanUnload:Return": {
        if (json.permit) {
          let tab = this.getTabForBrowser(browser);
          BrowserUI.animateClosingTab(tab);
        }
        break;
      }
      case "Browser:CertException":
        this._handleCertException(aMessage);
        break;
      case "Browser:BlockedSite":
        this._handleBlockedSite(aMessage);
        break;
      case "Browser:TapOnSelection":
        if (!InputSourceHelper.isPrecise) {
          if (SelectionHelperUI.isActive) {
            SelectionHelperUI.shutdown();
          }
          if (SelectionHelperUI.canHandle(aMessage)) {
            SelectionHelperUI.openEditSession(aMessage);
          }
        }
        break;
    }
  },
};

Browser.MainDragger = function MainDragger() {
  this._horizontalScrollbar = document.getElementById("horizontal-scroller");
  this._verticalScrollbar = document.getElementById("vertical-scroller");
  this._scrollScales = { x: 0, y: 0 };

  Elements.browsers.addEventListener("PanBegin", this, false);
  Elements.browsers.addEventListener("PanFinished", this, false);
};

Browser.MainDragger.prototype = {
  isDraggable: function isDraggable(target, scroller) {
    return { x: true, y: true };
  },

  dragStart: function dragStart(clientX, clientY, target, scroller) {
    let browser = getBrowser();
    let bcr = browser.getBoundingClientRect();
    this._contentView = browser.getViewAt(clientX - bcr.left, clientY - bcr.top);
  },

  dragStop: function dragStop(dx, dy, scroller) {
    if (this._contentView && this._contentView._updateCacheViewport)
      this._contentView._updateCacheViewport();
    this._contentView = null;
  },

  dragMove: function dragMove(dx, dy, scroller, aIsKinetic) {
    let doffset = new Point(dx, dy);

    this._panContent(doffset);

    if (aIsKinetic && doffset.x != 0)
      return false;

    this._updateScrollbars();

    return !doffset.equals(dx, dy);
  },

  handleEvent: function handleEvent(aEvent) {
    let browser = getBrowser();
    switch (aEvent.type) {
      case "PanBegin": {
        let width = ContentAreaObserver.width, height = ContentAreaObserver.height;
        let contentWidth = browser.contentDocumentWidth * browser.scale;
        let contentHeight = browser.contentDocumentHeight * browser.scale;

        // Allow a small margin on both sides to prevent adding scrollbars
        // on small viewport approximation
        const ALLOWED_MARGIN = 5;
        const SCROLL_CORNER_SIZE = 8;
        this._scrollScales = {
          x: (width + ALLOWED_MARGIN) < contentWidth ? (width - SCROLL_CORNER_SIZE) / contentWidth : 0,
          y: (height + ALLOWED_MARGIN) < contentHeight ? (height - SCROLL_CORNER_SIZE) / contentHeight : 0
        }

        this._showScrollbars();
        break;
      }
      case "PanFinished":
        this._hideScrollbars();

        // Update the scroll position of the content
        browser._updateCSSViewport();
        break;
    }
  },

  _panContent: function md_panContent(aOffset) {
    if (this._contentView && !this._contentView.isRoot()) {
      this._panContentView(this._contentView, aOffset);
      // XXX we may need to have "escape borders" for iframe panning
      // XXX does not deal with scrollables within scrollables
    }
    // Do content panning
    this._panContentView(getBrowser().getRootView(), aOffset);
  },

  /** Pan scroller by the given amount. Updates doffset with leftovers. */
  _panContentView: function _panContentView(contentView, doffset) {
    let pos0 = contentView.getPosition();
    contentView.scrollBy(doffset.x, doffset.y);
    let pos1 = contentView.getPosition();
    doffset.subtract(pos1.x - pos0.x, pos1.y - pos0.y);
  },

  _updateScrollbars: function _updateScrollbars() {
    let scaleX = this._scrollScales.x, scaleY = this._scrollScales.y;
    let contentScroll = Browser.getScrollboxPosition(Browser.contentScrollboxScroller);

    if (scaleX)
      this._horizontalScrollbar.style.MozTransform =
        "translateX(" + Math.round(contentScroll.x * scaleX) + "px)";

    if (scaleY) {
      let y = Math.round(contentScroll.y * scaleY);
      let x = 0;

      this._verticalScrollbar.style.MozTransform =
        "translate(" + x + "px," + y + "px)";
    }
  },

  _showScrollbars: function _showScrollbars() {
    this._updateScrollbars();
    let scaleX = this._scrollScales.x, scaleY = this._scrollScales.y;
    if (scaleX) {
      this._horizontalScrollbar.width = ContentAreaObserver.width * scaleX;
      this._horizontalScrollbar.setAttribute("panning", "true");
    }

    if (scaleY) {
      this._verticalScrollbar.height = ContentAreaObserver.height * scaleY;
      this._verticalScrollbar.setAttribute("panning", "true");
    }
  },

  _hideScrollbars: function _hideScrollbars() {
    this._scrollScales.x = 0;
    this._scrollScales.y = 0;
    this._horizontalScrollbar.removeAttribute("panning");
    this._verticalScrollbar.removeAttribute("panning");
    this._horizontalScrollbar.removeAttribute("width");
    this._verticalScrollbar.removeAttribute("height");
    this._horizontalScrollbar.style.MozTransform = "";
    this._verticalScrollbar.style.MozTransform = "";
  }
};



const OPEN_APPTAB = 100; // Hack until we get a real API

function nsBrowserAccess() { }

nsBrowserAccess.prototype = {
  QueryInterface: function(aIID) {
    if (aIID.equals(Ci.nsIBrowserDOMWindow) || aIID.equals(Ci.nsISupports))
      return this;
    throw Cr.NS_NOINTERFACE;
  },

  _getBrowser: function _getBrowser(aURI, aOpener, aWhere, aContext) {
    let isExternal = (aContext == Ci.nsIBrowserDOMWindow.OPEN_EXTERNAL);
    if (isExternal && aURI && aURI.schemeIs("chrome"))
      return null;

    let loadflags = isExternal ?
                      Ci.nsIWebNavigation.LOAD_FLAGS_FROM_EXTERNAL :
                      Ci.nsIWebNavigation.LOAD_FLAGS_NONE;
    let location;
    if (aWhere == Ci.nsIBrowserDOMWindow.OPEN_DEFAULTWINDOW) {
      switch (aContext) {
        case Ci.nsIBrowserDOMWindow.OPEN_EXTERNAL :
          aWhere = Services.prefs.getIntPref("browser.link.open_external");
          break;
        default : // OPEN_NEW or an illegal value
          aWhere = Services.prefs.getIntPref("browser.link.open_newwindow");
      }
    }

    let browser;
    if (aWhere == Ci.nsIBrowserDOMWindow.OPEN_NEWWINDOW) {
      let url = aURI ? aURI.spec : "about:blank";
      let newWindow = openDialog("chrome://browser/content/browser.xul", "_blank",
                                 "all,dialog=no", url, null, null, null);
      // since newWindow.Browser doesn't exist yet, just return null
      return null;
    } else if (aWhere == Ci.nsIBrowserDOMWindow.OPEN_NEWTAB) {
      let owner = isExternal ? null : Browser.selectedTab;
      let tab = Browser.addTab("about:blank", true, owner);
      if (isExternal)
        tab.closeOnExit = true;
      browser = tab.browser;
    } else if (aWhere == OPEN_APPTAB) {
      Browser.tabs.forEach(function(aTab) {
        if ("appURI" in aTab.browser && aTab.browser.appURI.spec == aURI.spec) {
          Browser.selectedTab = aTab;
          browser = aTab.browser;
        }
      });

      if (!browser) {
        // Make a new tab to hold the app
        let tab = Browser.addTab("about:blank", true);
        browser = tab.browser;
        browser.appURI = aURI;
      } else {
        // Just use the existing browser, but return null to keep the system from trying to load the URI again
        browser = null;
      }
    } else { // OPEN_CURRENTWINDOW and illegal values
      browser = Browser.selectedBrowser;
    }

    try {
      let referrer;
      if (aURI && browser) {
        if (aOpener) {
          location = aOpener.location;
          referrer = Services.io.newURI(location, null, null);
        }
        browser.loadURIWithFlags(aURI.spec, loadflags, referrer, null, null);
      }
      browser.focus();
    } catch(e) { }

    // We are loading web content into this window, so make sure content is visible
    // XXX Can we remove this?  It seems to be reproduced in BrowserUI already.
    BrowserUI.showContent();

    return browser;
  },

  openURI: function browser_openURI(aURI, aOpener, aWhere, aContext) {
    let browser = this._getBrowser(aURI, aOpener, aWhere, aContext);
    return browser ? browser.contentWindow : null;
  },

  openURIInFrame: function browser_openURIInFrame(aURI, aOpener, aWhere, aContext) {
    let browser = this._getBrowser(aURI, aOpener, aWhere, aContext);
    return browser ? browser.QueryInterface(Ci.nsIFrameLoaderOwner) : null;
  },

  isTabContentWindow: function(aWindow) {
    return Browser.browsers.some(function (browser) browser.contentWindow == aWindow);
  },

  get contentWindow() {
    return Browser.selectedBrowser.contentWindow;
  }
};

/**
 * Handler for blocked popups, triggered by DOMUpdatePageReport events in browser.xml
 */
var PopupBlockerObserver = {
  init: function init() {
    Elements.browsers.addEventListener("mousedown", this, true);
  },

  handleEvent: function handleEvent(aEvent) {
    switch (aEvent.type) {
      case "mousedown":
        let box = Browser.getNotificationBox();
        let notification = box.getNotificationWithValue("popup-blocked");
        if (notification && !notification.contains(aEvent.target))
          box.removeNotification(notification);
        break;
    }
  },

  onUpdatePageReport: function onUpdatePageReport(aEvent) {
    var cBrowser = Browser.selectedBrowser;
    if (aEvent.originalTarget != cBrowser)
      return;

    if (!cBrowser.pageReport)
      return;

    let result = Services.perms.testExactPermission(Browser.selectedBrowser.currentURI, "popup");
    if (result == Ci.nsIPermissionManager.DENY_ACTION)
      return;

    // Only show the notification again if we've not already shown it. Since
    // notifications are per-browser, we don't need to worry about re-adding
    // it.
    if (!cBrowser.pageReport.reported) {
      if (Services.prefs.getBoolPref("privacy.popups.showBrowserMessage")) {
        var brandShortName = Strings.brand.GetStringFromName("brandShortName");
        var popupCount = cBrowser.pageReport.length;

        let strings = Strings.browser;
        let message = PluralForm.get(popupCount, strings.GetStringFromName("popupWarning.message"))
                                .replace("#1", brandShortName)
                                .replace("#2", popupCount);

        var notificationBox = Browser.getNotificationBox();
        var notification = notificationBox.getNotificationWithValue("popup-blocked");
        if (notification) {
          notification.label = message;
        }
        else {
          var buttons = [
            {
              isDefault: false,
              label: strings.GetStringFromName("popupButtonAllowOnce2"),
              accessKey: "",
              callback: function() { PopupBlockerObserver.showPopupsForSite(); }
            },
            {
              label: strings.GetStringFromName("popupButtonAlwaysAllow3"),
              accessKey: "",
              callback: function() { PopupBlockerObserver.allowPopupsForSite(true); }
            },
            {
              label: strings.GetStringFromName("popupButtonNeverWarn3"),
              accessKey: "",
              callback: function() { PopupBlockerObserver.allowPopupsForSite(false); }
            }
          ];

          const priority = notificationBox.PRIORITY_WARNING_MEDIUM;
          notificationBox.appendNotification(message, "popup-blocked",
                                             "chrome://browser/skin/images/infobar-popup.png",
                                             priority, buttons);
        }
      }
      // Record the fact that we've reported this blocked popup, so we don't
      // show it again.
      cBrowser.pageReport.reported = true;
    }
  },

  allowPopupsForSite: function allowPopupsForSite(aAllow) {
    var currentURI = Browser.selectedBrowser.currentURI;
    Services.perms.add(currentURI, "popup", aAllow
                       ?  Ci.nsIPermissionManager.ALLOW_ACTION
                       :  Ci.nsIPermissionManager.DENY_ACTION);

    Browser.getNotificationBox().removeCurrentNotification();
  },

  showPopupsForSite: function showPopupsForSite() {
    let uri = Browser.selectedBrowser.currentURI;
    let pageReport = Browser.selectedBrowser.pageReport;
    if (pageReport) {
      for (let i = 0; i < pageReport.length; ++i) {
        var popupURIspec = pageReport[i].popupWindowURI.spec;

        // Sometimes the popup URI that we get back from the pageReport
        // isn't useful (for instance, netscape.com's popup URI ends up
        // being "http://www.netscape.com", which isn't really the URI of
        // the popup they're trying to show).  This isn't going to be
        // useful to the user, so we won't create a menu item for it.
        if (popupURIspec == "" || !Util.isURLMemorable(popupURIspec) || popupURIspec == uri.spec)
          continue;

        let popupFeatures = pageReport[i].popupWindowFeatures;
        let popupName = pageReport[i].popupWindowName;

        Browser.addTab(popupURIspec, false, Browser.selectedTab);
      }
    }
  }
};

var SessionHistoryObserver = {
  observe: function sho_observe(aSubject, aTopic, aData) {
    if (aTopic != "browser:purge-session-history")
      return;

    let back = document.getElementById("cmd_back");
    back.setAttribute("disabled", "true");
    let forward = document.getElementById("cmd_forward");
    forward.setAttribute("disabled", "true");

    let urlbar = document.getElementById("urlbar-edit");
    if (urlbar) {
      // Clear undo history of the URL bar
      urlbar.editor.transactionManager.clear();
    }
  }
};

function getNotificationBox(aBrowser) {
  return Browser.getNotificationBox(aBrowser);
}

function showDownloadManager(aWindowContext, aID, aReason) {
  // TODO: Bug 883962: Toggle the downloads infobar as our current "download manager".
}

function Tab(aURI, aParams, aOwner) {
  this._id = null;
  this._browser = null;
  this._notification = null;
  this._loading = false;
  this._progressActive = false;
  this._progressCount = 0;
  this._chromeTab = null;
  this._eventDeferred = null;
  this._updateThumbnailTimeout = null;

  this.owner = aOwner || null;

  // Set to 0 since new tabs that have not been viewed yet are good tabs to
  // toss if app needs more memory.
  this.lastSelected = 0;

  // aParams is an object that contains some properties for the initial tab
  // loading like flags, a referrerURI, a charset or even a postData.
  this.create(aURI, aParams || {}, aOwner);

  // default tabs to inactive (i.e. no display port)
  this.active = false;
}

Tab.prototype = {
  get browser() {
    return this._browser;
  },

  get notification() {
    return this._notification;
  },

  get chromeTab() {
    return this._chromeTab;
  },

  get pageShowPromise() {
    return this._eventDeferred ? this._eventDeferred.promise : null;
  },

  startLoading: function startLoading() {
    if (this._loading) {
      let stack = new Error().stack;
      throw "Already Loading!\n" + stack;
    }
    this._loading = true;
  },

  endLoading: function endLoading() {
    if (!this._loading) {
      let stack = new Error().stack;
      throw "Not Loading!\n" + stack;
    }
    this._loading = false;
    this.updateFavicon();
  },

  isLoading: function isLoading() {
    return this._loading;
  },

  create: function create(aURI, aParams, aOwner) {
    this._eventDeferred = Promise.defer();

    this._chromeTab = Elements.tabList.addTab(aParams.index);
    this._id = Browser.createTabId();
    let browser = this._createBrowser(aURI, null);

    let self = this;
    function onPageShowEvent(aEvent) {
      browser.removeEventListener("pageshow", onPageShowEvent);
      if (self._eventDeferred) {
        self._eventDeferred.resolve(self);
      }
      self._eventDeferred = null;
    }
    browser.addEventListener("pageshow", onPageShowEvent, true);
    browser.addEventListener("DOMWindowCreated", this, false);
    Elements.browsers.addEventListener("SizeChanged", this, false);

    browser.messageManager.addMessageListener("Content:StateChange", this);
    Services.obs.addObserver(this, "metro_viewstate_changed", false);

    if (aOwner)
      this._copyHistoryFrom(aOwner);
    this._loadUsingParams(browser, aURI, aParams);
  },

  updateViewport: function (aEvent) {
    // <meta name=viewport> is not yet supported; just use the browser size.
    this.browser.setWindowSize(this.browser.clientWidth, this.browser.clientHeight);
  },

  handleEvent: function (aEvent) {
    switch (aEvent.type) {
      case "DOMWindowCreated":
      case "SizeChanged":
        this.updateViewport();
        break;
    }
  },

  receiveMessage: function(aMessage) {
    switch (aMessage.name) {
      case "Content:StateChange":
        // update the thumbnail now...
        this.updateThumbnail();
        // ...and in a little while to capture page after load.
        if (aMessage.json.stateFlags & Ci.nsIWebProgressListener.STATE_STOP) {
          clearTimeout(this._updateThumbnailTimeout);
          this._updateThumbnailTimeout = setTimeout(() => {
            this.updateThumbnail();
          }, kTabThumbnailDelayCapture);
        }
        break;
    }
  },

  observe: function BrowserUI_observe(aSubject, aTopic, aData) {
    switch (aTopic) {
      case "metro_viewstate_changed":
        if (aData !== "snapped") {
          this.updateThumbnail();
        }
        break;
    }
  },

  destroy: function destroy() {
    this._browser.messageManager.removeMessageListener("Content:StateChange", this);
    this._browser.removeEventListener("DOMWindowCreated", this, false);
    Elements.browsers.removeEventListener("SizeChanged", this, false);
    Services.obs.removeObserver(this, "metro_viewstate_changed", false);
    clearTimeout(this._updateThumbnailTimeout);

    Elements.tabList.removeTab(this._chromeTab);
    this._chromeTab = null;
    this._destroyBrowser();
  },

  resurrect: function resurrect() {
    let dead = this._browser;
    let active = this.active;

    // Hold onto the session store data
    let session = { data: dead.__SS_data, extra: dead.__SS_extdata };

    // We need this data to correctly create and position the new browser
    // If this browser is already a zombie, fallback to the session data
    let currentURL = dead.__SS_restore ? session.data.entries[0].url : dead.currentURI.spec;
    let sibling = dead.nextSibling;

    // Destory and re-create the browser
    this._destroyBrowser();
    let browser = this._createBrowser(currentURL, sibling);
    if (active)
      this.active = true;

    // Reattach session store data and flag this browser so it is restored on select
    browser.__SS_data = session.data;
    browser.__SS_extdata = session.extra;
    browser.__SS_restore = true;
  },

  _copyHistoryFrom: function _copyHistoryFrom(tab) {
    let otherHistory = tab._browser._webNavigation.sessionHistory;
    let history = this._browser._webNavigation.sessionHistory;

    // Ensure that history is initialized
    history.QueryInterface(Ci.nsISHistoryInternal);

    for (let i = 0, length = otherHistory.index; i <= length; i++)
      history.addEntry(otherHistory.getEntryAtIndex(i, false), true);
  },

  _loadUsingParams: function _loadUsingParams(aBrowser, aURI, aParams) {
    let flags = aParams.flags || Ci.nsIWebNavigation.LOAD_FLAGS_NONE;
    let postData = ("postData" in aParams && aParams.postData) ? aParams.postData.value : null;
    let referrerURI = "referrerURI" in aParams ? aParams.referrerURI : null;
    let charset = "charset" in aParams ? aParams.charset : null;
    aBrowser.loadURIWithFlags(aURI, flags, referrerURI, charset, postData);
  },

  _createBrowser: function _createBrowser(aURI, aInsertBefore) {
    if (this._browser)
      throw "Browser already exists";

    // Create a notification box around the browser. Note this includes
    // the input overlay we use to shade content from input events when
    // we're intercepting touch input.
    let notification = this._notification = document.createElement("notificationbox");

    let browser = this._browser = document.createElement("browser");
    browser.id = "browser-" + this._id;
    this._chromeTab.linkedBrowser = browser;

    browser.setAttribute("type", "content");

    let useRemote = Services.prefs.getBoolPref("browser.tabs.remote");
    let useLocal = Util.isLocalScheme(aURI);
    browser.setAttribute("remote", (!useLocal && useRemote) ? "true" : "false");

    // Append the browser to the document, which should start the page load
    let stack = document.createElementNS(XUL_NS, "stack");
    stack.className = "browserStack";
    stack.appendChild(browser);
    stack.setAttribute("flex", "1");
    notification.appendChild(stack);
    Elements.browsers.insertBefore(notification, aInsertBefore);

    notification.dir = "reverse";

     // let the content area manager know about this browser.
    ContentAreaObserver.onBrowserCreated(browser);

    // stop about:blank from loading
    browser.stop();

    let fl = browser.QueryInterface(Ci.nsIFrameLoaderOwner).frameLoader;
    fl.renderMode = Ci.nsIFrameLoader.RENDER_MODE_ASYNC_SCROLL;

    return browser;
  },

  _destroyBrowser: function _destroyBrowser() {
    if (this._browser) {
      let notification = this._notification;
      let browser = this._browser;
      browser.active = false;

      this._notification = null;
      this._browser = null;
      this._loading = false;

      Elements.browsers.removeChild(notification);
    }
  },

  /**
   * Takes a scale and restricts it based on this tab's zoom limits.
   * @param aScale The original scale.
   */
  clampZoomLevel: function clampZoomLevel(aScale) {
    return Util.clamp(aScale, ZoomManager.MIN, ZoomManager.MAX);
  },

  updateThumbnail: function updateThumbnail() {
    PageThumbs.captureToCanvas(this.browser.contentWindow, this._chromeTab.thumbnailCanvas);
  },

  updateFavicon: function updateFavicon() {
    this._chromeTab.updateFavicon(this._browser.mIconURL);
  },

  set active(aActive) {
    if (!this._browser)
      return;

    let notification = this._notification;
    let browser = this._browser;

    if (aActive) {
      browser.setAttribute("type", "content-primary");
      Elements.browsers.selectedPanel = notification;
      browser.active = true;
      Elements.tabList.selectedTab = this._chromeTab;
      browser.focus();
    } else {
      browser.messageManager.sendAsyncMessage("Browser:Blur", { });
      browser.setAttribute("type", "content");
      browser.active = false;
    }
  },

  get active() {
    if (!this._browser)
      return false;
    return this._browser.getAttribute("type") == "content-primary";
  },

  toString: function() {
    return "[Tab " + (this._browser ? this._browser.currentURI.spec : "(no browser)") + "]";
  }
};

// Helper used to hide IPC / non-IPC differences for rendering to a canvas
function rendererFactory(aBrowser, aCanvas) {
  let wrapper = {};

  if (aBrowser.contentWindow) {
    let ctx = aCanvas.getContext("2d");
    let draw = function(browser, aLeft, aTop, aWidth, aHeight, aColor, aFlags) {
      ctx.drawWindow(browser.contentWindow, aLeft, aTop, aWidth, aHeight, aColor, aFlags);
      let e = document.createEvent("HTMLEvents");
      e.initEvent("MozAsyncCanvasRender", true, true);
      aCanvas.dispatchEvent(e);
    };
    wrapper.checkBrowser = function(browser) {
      return browser.contentWindow;
    };
    wrapper.drawContent = function(callback) {
      callback(ctx, draw);
    };
  }
  else {
    let ctx = aCanvas.MozGetIPCContext("2d");
    let draw = function(browser, aLeft, aTop, aWidth, aHeight, aColor, aFlags) {
      ctx.asyncDrawXULElement(browser, aLeft, aTop, aWidth, aHeight, aColor, aFlags);
    };
    wrapper.checkBrowser = function(browser) {
      return !browser.contentWindow;
    };
    wrapper.drawContent = function(callback) {
      callback(ctx, draw);
    };
  }

  return wrapper;
};
