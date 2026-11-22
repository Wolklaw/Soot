// Soot defaults. The launcher applies this file once per config version,
// so anything you change later in Settings stays changed.

// --- interface ---
user_pref("toolkit.legacyUserProfileCustomizations.stylesheets", true);   // load chrome/userChrome.css
user_pref("extensions.activeThemeID", "firefox-compact-dark@mozilla.org");
user_pref("browser.theme.toolbar-theme", 0);
user_pref("browser.theme.content-theme", 0);
user_pref("browser.uidensity", 1);
user_pref("browser.compactmode.show", true);
user_pref("browser.tabs.inTitlebar", 1);
user_pref("browser.toolbars.bookmarks.visibility", "never");
user_pref("sidebar.revamp", false);
user_pref("sidebar.verticalTabs", false);
user_pref("identity.fxaccounts.toolbar.enabled", false);
user_pref("browser.aboutConfig.showWarning", false);
user_pref("browser.tabs.warnOnClose", false);

// --- no first-run pages, nags or promos ---
user_pref("browser.aboutwelcome.enabled", false);
user_pref("browser.shell.checkDefaultBrowser", false);
user_pref("browser.startup.homepage_override.mstone", "ignore");
user_pref("startup.homepage_welcome_url", "");
user_pref("startup.homepage_welcome_url.additional", "");
user_pref("startup.homepage_override_url", "");
user_pref("datareporting.policy.dataSubmissionPolicyBypassNotification", true);
user_pref("browser.discovery.enabled", false);
user_pref("extensions.getAddons.showPane", false);
user_pref("extensions.htmlaboutaddons.recommendations.enabled", false);
user_pref("browser.newtabpage.activity-stream.asrouter.userprefs.cfr.addons", false);
user_pref("browser.newtabpage.activity-stream.asrouter.userprefs.cfr.features", false);
user_pref("browser.preferences.moreFromMozilla", false);
user_pref("browser.vpn_promo.enabled", false);
user_pref("browser.promo.focus.enabled", false);
user_pref("browser.promo.pin.enabled", false);

// --- nothing sent to Mozilla ---
user_pref("datareporting.healthreport.uploadEnabled", false);
user_pref("datareporting.usage.uploadEnabled", false);
user_pref("toolkit.telemetry.unified", false);
user_pref("toolkit.telemetry.enabled", false);
user_pref("toolkit.telemetry.archive.enabled", false);
user_pref("toolkit.telemetry.newProfilePing.enabled", false);
user_pref("toolkit.telemetry.shutdownPingSender.enabled", false);
user_pref("toolkit.telemetry.updatePing.enabled", false);
user_pref("toolkit.telemetry.bhrPing.enabled", false);
user_pref("toolkit.telemetry.firstShutdownPing.enabled", false);
user_pref("toolkit.telemetry.coverage.opt-out", true);
user_pref("toolkit.coverage.opt-out", true);
user_pref("app.shield.optoutstudies.enabled", false);
user_pref("app.normandy.enabled", false);
user_pref("app.normandy.api_url", "");
user_pref("nimbus.rollouts.enabled", false);                       // rollouts are separate from studies: no remote feature or page changes
user_pref("services.sync.prefs.sync.nimbus.rollouts.enabled", false); // keep that choice to Soot, not your other synced browsers
user_pref("breakpad.reportURL", "");
user_pref("browser.tabs.crashReporting.sendReport", false);
user_pref("browser.crashReports.unsubmittedCheck.autoSubmit2", false);
user_pref("browser.newtabpage.activity-stream.feeds.telemetry", false);
user_pref("browser.newtabpage.activity-stream.telemetry", false);
user_pref("network.captive-portal-service.enabled", false);
user_pref("network.connectivity-service.enabled", false);

// --- new tab page: search box only ---
user_pref("browser.newtabpage.activity-stream.showSponsored", false);
user_pref("browser.newtabpage.activity-stream.showSponsoredTopSites", false);
user_pref("browser.newtabpage.activity-stream.showSponsoredCheckboxes", false);
user_pref("browser.newtabpage.activity-stream.feeds.topsites", false);
user_pref("browser.newtabpage.activity-stream.feeds.section.topstories", false);
user_pref("browser.newtabpage.activity-stream.feeds.section.highlights", false);
user_pref("browser.newtabpage.activity-stream.feeds.snippets", false);
user_pref("browser.newtabpage.activity-stream.showWeather", false);
user_pref("browser.newtabpage.activity-stream.system.showWeather", false);
user_pref("browser.newtabpage.activity-stream.newtabWallpapers.enabled", false);
user_pref("browser.newtabpage.activity-stream.newtabWallpapers.v2.enabled", false);
user_pref("browser.newtabpage.activity-stream.widgets.system.enabled", false);
user_pref("browser.newtabpage.activity-stream.widgets.enabled", false);
user_pref("browser.newtabpage.activity-stream.showSearch", true);
// Mozilla can push a newer new tab page to the engine between releases ("train-hop"). It brings its own
// layout and resets the story and sponsored settings above, so Soot keeps the new tab that ships with the engine.
user_pref("browser.newtabpage.trainhopAddon.xpiBaseURL", "");
user_pref("browser.newtabpage.trainhopAddonDeployment.version", "");
user_pref("browser.newtabpage.activity-stream.improvesearch.handoffToAwesomebar", false);

// --- address bar: no sponsored or trending suggestions ---
user_pref("browser.urlbar.suggest.quicksuggest.sponsored", false);
user_pref("browser.urlbar.suggest.quicksuggest.nonsponsored", false);
user_pref("browser.urlbar.quicksuggest.enabled", false);
user_pref("browser.urlbar.suggest.trending", false);
user_pref("browser.urlbar.suggest.weather", false);
user_pref("browser.urlbar.suggest.yelp", false);
user_pref("browser.urlbar.suggest.mdn", false);
user_pref("browser.urlbar.suggest.addons", false);
user_pref("browser.urlbar.trimURLs", false);

// --- AI features off ---
user_pref("browser.ml.chat.enabled", false);
user_pref("browser.ml.chat.sidebar", false);
user_pref("browser.ml.chat.shortcuts", false);
user_pref("browser.ml.linkPreview.enabled", false);
user_pref("browser.tabs.groups.smart.enabled", false);
user_pref("browser.tabs.groups.smart.userEnabled", false);
user_pref("extensions.ml.enabled", false);

// --- privacy: block trackers ---
user_pref("browser.contentblocking.category", "strict");          // trackers, cross-site cookies, fingerprinters, cryptominers
user_pref("privacy.trackingprotection.enabled", true);
user_pref("privacy.trackingprotection.socialtracking.enabled", true);
user_pref("privacy.trackingprotection.emailtracking.enabled", true);
user_pref("privacy.trackingprotection.cryptomining.enabled", true);
user_pref("privacy.trackingprotection.fingerprinting.enabled", true);
user_pref("network.cookie.cookieBehavior", 5);                     // every site gets its own cookie jar
user_pref("privacy.fingerprintingProtection", true);               // blur the details sites use to recognise this PC
user_pref("privacy.fingerprintingProtection.pbmode", true);
user_pref("privacy.bounceTrackingProtection.mode", 1);             // clear redirect trackers
user_pref("privacy.globalprivacycontrol.enabled", true);           // tell sites not to sell or share data
user_pref("privacy.query_stripping.strip_on_share.enabled", true);
user_pref("network.http.referer.XOriginTrimmingPolicy", 2);        // other sites only learn the site you came from, not the page
user_pref("dom.private-attribution.submission.enabled", false);    // no ad measurement reports
user_pref("beacon.enabled", false);                                // no background tracking pings when leaving a page
user_pref("browser.send_pings", false);
user_pref("network.prefetch-next", false);                         // do not load links you have not clicked
user_pref("network.predictor.enable-prefetch", false);
user_pref("media.peerconnection.ice.default_address_only", true);  // video calls do not reveal local addresses
user_pref("media.peerconnection.ice.proxy_only_if_behind_proxy", true);   // and stay inside a proxy when one is set
user_pref("network.IDN_show_punycode", true);                      // show look-alike addresses for what they are
user_pref("extensions.pocket.enabled", false);

// --- privacy: nothing typed is sent before you press Enter ---
user_pref("browser.search.suggest.enabled", false);
user_pref("browser.urlbar.suggest.searches", false);
user_pref("browser.urlbar.showSearchSuggestionsFirst", false);

// --- privacy: no background calls home ---
user_pref("datareporting.policy.dataSubmissionEnabled", false);
user_pref("toolkit.telemetry.server", "data:,");
user_pref("toolkit.telemetry.reportingpolicy.firstRun", false);
user_pref("browser.newtabpage.activity-stream.telemetry.structuredIngestion.endpoint", "");
user_pref("browser.ping-centre.telemetry", false);
user_pref("messaging-system.rsexperimentloader.enabled", false);   // no remote experiments or in-browser promos
user_pref("messaging-system.askForFeedback", false);
user_pref("browser.messaging-system.whatsNewPanel.enabled", false);
user_pref("browser.uitour.enabled", false);
user_pref("extensions.getAddons.cache.enabled", false);            // do not send the add-on list for "recommendations"
user_pref("browser.shopping.experience2023.enabled", false);
user_pref("browser.ml.enable", false);                             // no on-device AI engine loaded
// Notifications stay as the engine ships them: sites may ask, you decide per site. Set explicitly so a
// profile that once had them blocked gets them back.
user_pref("dom.push.connection.enabled", true);
user_pref("permissions.default.desktop-notification", 0);
user_pref("geo.provider.ms-windows-location", true);               // location, when you allow it, comes from Windows, not Google

// blank pages and page backgrounds stay dark until a site paints
user_pref("browser.display.background_color.dark", "#04090b");

// --- small extras that cost nothing ---
user_pref("cookiebanners.service.mode", 1);                    // decline cookie banners automatically where possible
user_pref("cookiebanners.service.mode.privateBrowsing", 1);
user_pref("privacy.query_stripping.enabled", true);            // strip tracking tags from links
user_pref("privacy.query_stripping.enabled.pbmode", true);

// --- fill saved cards and addresses into forms ---
user_pref("extensions.formautofill.addresses.supported", "on");
user_pref("extensions.formautofill.creditCards.supported", "on");
user_pref("extensions.formautofill.addresses.enabled", true);
user_pref("extensions.formautofill.creditCards.enabled", true);

// --- no background helpers, no start-with-Windows prompt ---
user_pref("default-browser-agent.enabled", false);
user_pref("browser.startup.windowsLaunchOnLogin.enabled", false);
user_pref("browser.startup.windowsLaunchOnLogin.disableLaunchOnLoginPrompt", true);

// --- speed ---
user_pref("toolkit.cosmeticAnimations.enabled", false);          // no tab open/close or UI easing
user_pref("browser.tabs.hoverPreview.enabled", false);           // no tab thumbnails on hover
user_pref("browser.download.animateNotifications", false);
user_pref("nglayout.initialpaint.delay", 0);                     // paint the first frame at once
user_pref("nglayout.initialpaint.delay_in_oopif", 0);
user_pref("content.notify.interval", 100000);                    // fewer re-layouts while a page streams in
user_pref("browser.sessionstore.interval", 60000);               // save the session every minute, not every 15 s
user_pref("browser.sessionstore.restore_on_demand", true);       // background tabs load when first opened
user_pref("browser.sessionstore.restore_pinned_tabs_on_demand", true);
user_pref("browser.cache.disk.smart_size.enabled", true);
user_pref("browser.cache.memory.capacity", 262144);              // 256 MB of page cache in RAM
user_pref("network.http.max-persistent-connections-per-server", 10);
user_pref("network.dnsCacheEntries", 1000);
user_pref("network.dnsCacheExpiration", 3600);
user_pref("network.ssl_tokens_cache_capacity", 20480);           // reuse TLS sessions
user_pref("network.predictor.enabled", true);
user_pref("network.http.speculative-parallel-limit", 6);
user_pref("image.mem.decode_bytes_at_a_time", 65536);
user_pref("gfx.canvas.accelerated", true);
user_pref("media.memory_cache_max_size", 262144);
user_pref("browser.urlbar.maxRichResults", 8);
user_pref("browser.urlbar.delay", 20);                           // suggestions appear sooner

// --- one taskbar identity: private windows share Soot's icon, no separate engine shortcuts ---
user_pref("browser.privateWindowSeparation.enabled", false);
user_pref("browser.shell.customIcon.enabled", false);
