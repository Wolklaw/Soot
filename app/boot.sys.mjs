// Soot's own code inside the engine. Loaded once at startup by engine\soot.cfg.
// Gives the browser its own name, icon and taskbar identity, and adds the VAULT and CLEAR buttons.

const ROOT = Services.dirsvc.get("GreD", Ci.nsIFile).parent;
const APP = ROOT.clone();
APP.append("app");
const HUB = "chrome://soot/content/hub.html";
const APP_ID = "Soot.Browser";
const WIN = Services.appinfo.OS === "WINNT";

function file(...parts) {
  const f = APP.clone();
  for (const p of parts) f.append(p);
  return f;
}

// Where the rewritten engine texts live (see debrand below): app/l10n-auto. On Linux the app folder is
// usually read-only (/usr/lib/soot); then install.sh has filled it already, and if it could not, the
// texts go to the profile instead.
function autoDir() {
  const inApp = file("l10n-auto");
  if (WIN || inApp.exists() || APP.isWritable()) return inApp;
  const dir = Services.dirsvc.get("ProfD", Ci.nsIFile);
  dir.append("soot-l10n-auto");
  return dir;
}

// resource://soot-auto/ points at that folder, wherever it is
function mapAutoDir() {
  let spec = Services.io.newFileURI(autoDir()).spec;
  if (!spec.endsWith("/")) spec += "/";          // a folder that does not exist yet comes back without one
  Services.io.getProtocolHandler("resource").QueryInterface(Ci.nsIResProtocolHandler)
    .setSubstitution("soot-auto", Services.io.newURI(spec));
}

// ---- name and logos: replace the engine's branding ----

function registerBranding() {
  try {
    Components.manager.QueryInterface(Ci.nsIComponentRegistrar).autoRegister(file("chrome.manifest"));
  } catch (e) { console.error(e); }
  let haveAuto = false;
  try {
    mapAutoDir();
    haveAuto = true;
    const auto = autoDir();
    auto.append("props.manifest");
    if (auto.exists()) Components.manager.QueryInterface(Ci.nsIComponentRegistrar).autoRegister(auto);
  } catch (e) { console.error(e); }
  try {
    const reg = L10nRegistry.getInstance();
    if (haveAuto && !reg.hasSource("8-soot-auto")) {
      reg.registerSources([new L10nFileSource("8-soot-auto", "app", ["en-US"], "resource://soot-auto/{locale}/")]);
    }
    if (!reg.hasSource("9-soot")) {
      reg.registerSources([new L10nFileSource("9-soot", "app", ["en-US"], "resource://soot/l10n/{locale}/")]);
    }
  } catch (e) { console.error(e); }
}

// The engine spells its own name out in many text files. This rewrites every one of them into
// app\l10n-auto, which takes priority over the engine's copies. Runs again whenever the engine updates.
const rename = s => s.replace(/Mozilla Firefox/g, "Soot").replace(/Firefox/g, "Soot");

export async function debrand(force) {
  const outRoot = autoDir().path;
  const stamp = PathUtils.join(outRoot, "build.txt");
  const build = Services.appinfo.appBuildID + ":3";
  if (!force) {
    try { if ((await IOUtils.readUTF8(stamp)).trim() === build) return -1; } catch (e) {}
  }
  const { NetUtil } = ChromeUtils.importESModule("resource://gre/modules/NetUtil.sys.mjs");
  await IOUtils.remove(outRoot, { recursive: true, ignoreAbsent: true });
  const props = [];
  let count = 0;
  for (const key of ["GreD", "XCurProcD"]) {
    const jar = Services.dirsvc.get(key, Ci.nsIFile);
    jar.append("omni.ja");
    if (!jar.exists()) continue;
    const zr = Cc["@mozilla.org/libjar/zip-reader;1"].createInstance(Ci.nsIZipReader);
    zr.open(jar);
    try {
      const names = [...zr.findEntries("localization/en-US/*.ftl"), ...zr.findEntries("chrome/en-US/locale/*.properties")];
      for (const name of names) {
        const stream = zr.getInputStream(name);
        const text = NetUtil.readInputStreamToString(stream, stream.available(), { charset: "UTF-8" });
        if (!text.includes("Firefox")) continue;
        const fixed = text.split("\n").map(line => {
          if (!line.includes("Firefox") || line.startsWith("#")) return line;
          const m = /^(\s*[.\-]?[\w.\-]+\s*=)(.*)$/.exec(line);      // keep the identifier, change the text
          return m ? m[1] + rename(m[2]) : rename(line);
        }).join("\n");
        let target;
        if (name.endsWith(".ftl")) {
          const rel = name.slice("localization/en-US/".length);
          if (rel === "branding/brand.ftl") continue;               // written by hand in app\l10n
          target = PathUtils.join(outRoot, "en-US", ...rel.split("/"));
        } else {
          // chrome/en-US/locale/[en-US/]<package>/<file>  ->  chrome://<package>/locale/<file>
          const parts = name.slice("chrome/en-US/locale/".length).split("/");
          if (parts[0] === "en-US") parts.shift();
          const pkg = parts.shift();
          if (pkg === "branding") continue;
          const flat = pkg + "__" + parts.join("__");
          target = PathUtils.join(outRoot, "props", flat);
          props.push("override chrome://" + pkg + "/locale/" + parts.join("/") + " resource://soot-auto/props/" + flat);
        }
        await IOUtils.makeDirectory(PathUtils.parent(target), { createAncestors: true });
        await IOUtils.writeUTF8(target, fixed);
        count++;
      }
    } finally { zr.close(); }
  }
  await IOUtils.makeDirectory(outRoot, { createAncestors: true });
  await IOUtils.writeUTF8(PathUtils.join(outRoot, "props.manifest"), props.join("\n") + "\n");
  await IOUtils.writeUTF8(stamp, build);
  return count;
}

// ---- windows: taskbar identity and icon ----

function brandWindow(win) {
  try {
    Cc["@mozilla.org/windows-taskbar;1"].getService(Ci.nsIWinTaskbar).setGroupIdForWindow(win, APP_ID);
  } catch (e) {}
  try {
    const exe = ROOT.clone();
    exe.append("Soot.exe");
    if (exe.exists()) Cc["@mozilla.org/windows-ui-utils;1"].getService(Ci.nsIWindowsUIUtils).setWindowIconFromExe(win, exe.path, 0);
  } catch (e) {}
}

// Visual effects switches (EFFECTS page). Both on by default; the stylesheet keys off these attributes.
export const FX = { ui: "soot.effects.ui", extra: "soot.effects.extra", pages: "soot.effects.pages" };

function markFx(win) {
  const root = win.document.documentElement;
  for (const [attr, pref] of [["soot-fx", FX.ui], ["soot-fx-extra", FX.extra]]) {
    if (Services.prefs.getBoolPref(pref, true)) root.setAttribute(attr, "");
    else root.removeAttribute(attr);
  }
}

// Soot's own pages get plain addresses: about:vault, about:clear, about:effects
const PAGES = { vault: "vault", data: "clear", effects: "effects" };

function registerPages() {
  const registrar = Components.manager.QueryInterface(Ci.nsIComponentRegistrar);
  for (const name of Object.values(PAGES)) {
    const contract = "@mozilla.org/network/protocol/about;1?what=" + name;
    if (registrar.isContractIDRegistered(contract)) continue;
    const module = {
      QueryInterface: ChromeUtils.generateQI(["nsIAboutModule"]),
      getURIFlags() { return Ci.nsIAboutModule.ALLOW_SCRIPT | Ci.nsIAboutModule.IS_SECURE_CHROME_UI; },
      getChromeURI() { return Services.io.newURI(HUB); },
      newChannel(uri, loadInfo) {
        const channel = Services.io.newChannelFromURIWithLoadInfo(Services.io.newURI(HUB), loadInfo);
        channel.originalURI = uri;
        channel.owner = Services.scriptSecurityManager.getSystemPrincipal();
        return channel;
      },
    };
    const factory = {
      QueryInterface: ChromeUtils.generateQI(["nsIFactory"]),
      createInstance(iid) { return module.QueryInterface(iid); },
    };
    registrar.registerFactory(Services.uuid.generateUUID(), "about:" + name, contract, factory);
  }
}

export function openHub(win, section) {
  // switchToTabHavingURI wants an nsIURI (it reads .spec and .displaySpec); a plain string silently opens nothing
  const uri = Services.io.newURI("about:" + (PAGES[section] || "vault"));
  win.switchToTabHavingURI(uri, true, {
    triggeringPrincipal: Services.scriptSecurityManager.getSystemPrincipal(),
  });
}

function addButtons() {
  const { CustomizableUI } = ChromeUtils.importESModule("moz-src:///browser/components/customizableui/CustomizableUI.sys.mjs");
  const make = (id, label, tip, section) => {
    try {
      CustomizableUI.createWidget({
        id, label, tooltiptext: tip, removable: true,
        defaultArea: CustomizableUI.AREA_NAVBAR,
        onCommand(event) {
          // the engine renamed ownerGlobal to documentGlobal (version 160); accept either
          const node = event.target;
          openHub(node.documentGlobal || node.ownerGlobal || node.ownerDocument.defaultView, section);
        },
      });
    } catch (e) { console.error(e); }
  };
  make("soot-vault-button", "Vault", "Passwords, notes and sign-in codes", "vault");
  make("soot-clear-button", "Clear", "Clear cache and cookies, import favourites", "data");
}

// ---- vault lock: one password for Soot and its vault ----
// The engine asks for the master password when Soot starts (or the first time a page needs a saved login).
// Typing it there signs in the key store, and that sign-in opens the vault too: the vault page asks only when
// the key store is signed out (never signed in, or locked by LOCK NOW or the remembered time running out).

const keyToken = () => Cc["@mozilla.org/security/internalkeytoken;1"].createInstance(Ci.nsIPKCS11Token);
const PW_KEK = "lockstore::kek::password:profile";      // same record the engine's ProfileKekPassword uses
const timers = () => ChromeUtils.importESModule("resource://gre/modules/Timer.sys.mjs");

export const Vault = {
  until: 0,          // time the vault stays open to; 0 = not set, Infinity = until Soot closes. Never written to disk.
  visitOnly: false,  // "this visit only": open until the vault page that was unlocked closes
  _timer: null,

  get open() {
    const t = keyToken();
    if (!t.hasPassword || !t.isLoggedIn) return false;
    if (!this.visitOnly && this.until === 0) this.adopt();
    return this.visitOnly || Date.now() < this.until;
  },

  // minutes: 0 = this visit only, -1 = until Soot closes
  _keepOpen(minutes) {
    const { setTimeout, clearTimeout } = timers();
    if (this._timer) { clearTimeout(this._timer); this._timer = null; }
    this.visitOnly = minutes === 0;
    if (minutes > 0) {
      this.until = Date.now() + minutes * 60000;
      this._timer = setTimeout(() => this.lock(), minutes * 60000);
    } else this.until = minutes < 0 ? Infinity : 0;
  },

  // The master password was typed in the engine's own prompt: the vault is open for the time chosen on the vault page.
  adopt() {
    if (!keyToken().isLoggedIn) return;
    this._keepOpen(Services.prefs.getIntPref("soot.vault.remember", 15));
  },

  async unlock(password, minutes) {
    const t = keyToken();
    if (!t.hasPassword) return false;
    let ok = false;
    // This engine has no "check password" call. Changing the password to itself only succeeds with the
    // right one, and leaves the key store signed in, which is exactly an unlock without the engine's own dialog.
    try { await t.changePassword(password, password); ok = t.isLoggedIn; } catch (e) { ok = false; }
    if (!ok) return false;
    try {
      const lockstore = Cc["@mozilla.org/security/lockstore;1"].getService(Ci.nsILockstore);
      if (Services.prefs.getBoolPref("security.lockstore.unlock.enabled", false) && (await lockstore.kekExists(PW_KEK)) && !lockstore.isKekUnlocked(PW_KEK)) {
        await lockstore.unlockKek(PW_KEK, password, Number.MAX_SAFE_INTEGER);
      }
    } catch (e) { console.error(e); }
    this._keepOpen(minutes);
    return true;
  },

  lock() {
    try {
      const { clearTimeout } = timers();
      if (this._timer) { clearTimeout(this._timer); this._timer = null; }
    } catch (e) {}
    this.until = 0;
    this.visitOnly = false;
    try { Cc["@mozilla.org/security/sdr;1"].getService(Ci.nsISecretDecoderRing).logoutAndTeardown(); }
    catch (e) { try { keyToken().logout(); } catch (e2) {} }
    Services.obs.notifyObservers(null, "soot-vault-locked");
  },
};

// ---- phone sync: serves the vault to paired phones over the local network, encrypted end to end ----
// Pairing is an ECDH key agreement; both screens then show the same 6-digit code, which the user confirms here.
// After that the phone fetches /vault and gets AES-256-GCM ciphertext only it can open. Nothing leaves the LAN.

const b64 = bytes => btoa(String.fromCharCode(...new Uint8Array(bytes)));
const unb64 = text => Uint8Array.from(atob(text), c => c.charCodeAt(0));
const subtle = () => (globalThis.crypto || Services.appShell.hiddenDOMWindow.crypto).subtle;
const randomBytes = n => (globalThis.crypto || Services.appShell.hiddenDOMWindow.crypto).getRandomValues(new Uint8Array(n));
const sdr = () => Cc["@mozilla.org/login-manager/crypto/SDR;1"].getService(Ci.nsILoginManagerCrypto);

async function sha256(...parts) {
  const total = parts.reduce((n, p) => n + p.length, 0);
  const all = new Uint8Array(total);
  let at = 0;
  for (const p of parts) { all.set(p, at); at += p.length; }
  return new Uint8Array(await subtle().digest("SHA-256", all));
}

export const Phone = {
  PORT: 47600,
  devices: [],
  socket: null,

  get enabled() { return Services.prefs.getBoolPref("soot.phone.enabled", false); },

  load() {
    try {
      const raw = Services.prefs.getStringPref("soot.phone.devices", "");
      this.devices = raw ? JSON.parse(sdr().decrypt(raw)) : [];
    } catch (e) { this.devices = []; }
  },

  save() {
    Services.prefs.setStringPref("soot.phone.devices", sdr().encrypt(JSON.stringify(this.devices)));
    Services.obs.notifyObservers(null, "soot-phone-changed");
  },

  confirm(id) { const d = this.devices.find(x => x.id === id); if (d) { d.ok = true; this.save(); } },
  remove(id) { this.devices = this.devices.filter(x => x.id !== id); this.save(); },

  setEnabled(on) {
    Services.prefs.setBoolPref("soot.phone.enabled", on);
    if (on) this.listen(); else this.stop();
  },

  stop() { try { this.socket?.close(); } catch (e) {} this.socket = null; },

  listen() {
    if (this.socket) return;
    this.load();
    const sock = Cc["@mozilla.org/network/server-socket;1"].createInstance(Ci.nsIServerSocket);
    sock.init(this.PORT, false, 8);
    sock.asyncListen({ onSocketAccepted: (s, transport) => this.accept(transport), onStopListening() {} });
    this.socket = sock;
  },

  // Home networks, Tailscale and this PC only. The firewall rule says the same, but a desktop whose
  // firewall zone opens every high port (Fedora's, SparkOS's) would let any network in otherwise.
  localPeer(host) {
    const ip = String(host || "").replace(/^::ffff:/i, "").split("%")[0].toLowerCase();
    const v4 = /^(\d+)\.(\d+)\.(\d+)\.(\d+)$/.exec(ip);
    if (v4) {
      const [a, b] = [Number(v4[1]), Number(v4[2])];
      return a === 10 || a === 127 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168)
        || (a === 100 && b >= 64 && b <= 127) || (a === 169 && b === 254);
    }
    return ip === "::1" || /^fe[89ab][0-9a-f]:/.test(ip) || /^f[cd][0-9a-f]{2}:/.test(ip);
  },

  accept(transport) {
    let host = "";
    try { host = transport.host; } catch (e) {}
    if (!this.localPeer(host)) {
      try { transport.close(Cr.NS_ERROR_ABORT); } catch (e) {}
      return;
    }
    const input = transport.openInputStream(0, 0, 0).QueryInterface(Ci.nsIAsyncInputStream);
    const output = transport.openOutputStream(Ci.nsITransport.OPEN_BLOCKING, 0, 0);
    const bin = Cc["@mozilla.org/binaryinputstream;1"].createInstance(Ci.nsIBinaryInputStream);
    bin.setInputStream(input);
    let buf = "";
    const done = (status, obj) => {
      const body = JSON.stringify(obj);
      const head = "HTTP/1.1 " + status + "\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
      try { output.write(head + body, head.length + body.length); } catch (e) {}
      try { output.close(); input.close(); } catch (e) {}
    };
    const onReady = {
      onInputStreamReady: stream => {
        try {
          buf += bin.readBytes(stream.available());
          const split = buf.indexOf("\r\n\r\n");
          if (split < 0) { if (buf.length > 65536) return done("400 Bad Request", { error: "too large" }); return stream.asyncWait(onReady, 0, 0, Services.tm.mainThread); }
          const need = parseInt((/content-length:\s*(\d+)/i.exec(buf.slice(0, split)) || [0, "0"])[1], 10);
          if (need > 65536) return done("400 Bad Request", { error: "too large" });
          if (buf.length - split - 4 < need) return stream.asyncWait(onReady, 0, 0, Services.tm.mainThread);
          const [method, target] = buf.slice(0, buf.indexOf("\r\n")).split(" ");
          this.route(method, target, buf.slice(split + 4, split + 4 + need)).then(r => done(r[0], r[1]), () => done("500 Error", { error: "failed" }));
        } catch (e) { done("400 Bad Request", { error: "bad request" }); }
      },
    };
    input.asyncWait(onReady, 0, 0, Services.tm.mainThread);
  },

  async route(method, target, body) {
    const url = new URL(target, "http://soot");
    if (method === "POST" && url.pathname === "/pair") {
      const req = JSON.parse(body);
      const phonePub = unb64(String(req.pub));
      const pair = await subtle().generateKey({ name: "ECDH", namedCurve: "P-256" }, true, ["deriveBits"]);
      const theirs = await subtle().importKey("spki", phonePub, { name: "ECDH", namedCurve: "P-256" }, false, []);
      const shared = new Uint8Array(await subtle().deriveBits({ name: "ECDH", public: theirs }, pair.privateKey, 256));
      const enc = new TextEncoder();
      const key = await sha256(shared, enc.encode("soot-sync-key"));
      const sasBytes = await sha256(shared, enc.encode("soot-sync-sas"));
      const sas = String(((sasBytes[0] << 24 | sasBytes[1] << 16 | sasBytes[2] << 8 | sasBytes[3]) >>> 0) % 1000000).padStart(6, "0");
      const id = b64(randomBytes(6)).replace(/[^A-Za-z0-9]/g, "x");
      this.devices = this.devices.filter(d => d.ok);          // one pending pairing at a time
      this.devices.push({ id, name: String(req.name || "Phone").slice(0, 40), key: b64(key), sas, ok: false });
      this.save();
      return ["200 OK", { id, pub: b64(await subtle().exportKey("spki", pair.publicKey)) }];
    }
    if (method === "GET" && url.pathname === "/vault") {
      const d = this.devices.find(x => x.id === url.searchParams.get("id"));
      if (!d) return ["404 Not Found", { error: "unknown device" }];
      if (!d.ok) return ["403 Forbidden", { error: "confirm the code on the PC" }];
      const token = Cc["@mozilla.org/security/internalkeytoken;1"].createInstance(Ci.nsIPKCS11Token);
      if (token.hasPassword && !token.isLoggedIn) return ["423 Locked", { error: "the vault is locked on the PC" }];
      const logins = (await Services.logins.getAllLogins()).filter(l => !l.origin.startsWith("chrome://"))
        .map(l => ({ o: l.origin, u: l.username, p: l.password }));
      let extra = { notes: [], codes: [] };
      try {
        const path = PathUtils.join(PathUtils.profileDir, "soot-vault.json");
        if (await IOUtils.exists(path)) extra = Object.assign(extra, JSON.parse(sdr().decrypt(await IOUtils.readUTF8(path))));
      } catch (e) {}
      const plain = new TextEncoder().encode(JSON.stringify({ ts: Date.now(), logins, notes: extra.notes, codes: extra.codes }));
      const iv = randomBytes(12);
      const key = await subtle().importKey("raw", unb64(d.key), "AES-GCM", false, ["encrypt"]);
      const data = await subtle().encrypt({ name: "AES-GCM", iv }, key, plain);
      d.last = Date.now();
      this.save();
      return ["200 OK", { iv: b64(iv), data: b64(data) }];
    }
    return ["404 Not Found", { error: "not found" }];
  },

  // this PC's addresses on the local network, for the phone to connect to (the launcher works them out at start)
  addresses() {
    const fromLauncher = (Services.env.get("SOOT_LAN_IPS") || "").split(",").filter(Boolean);
    if (fromLauncher.length) return Promise.resolve(fromLauncher);
    return new Promise(resolve => {
      try {
        Cc["@mozilla.org/network-info-service;1"].getService(Ci.nsINetworkInfoService).listNetworkAddresses({
          onListedNetworkAddresses: list => resolve(list.filter(a => /^\d+\.\d+\.\d+\.\d+$/.test(a) && !a.startsWith("127.") && !a.startsWith("169.254."))),
          onListNetworkAddressesFailed: () => resolve([]),
        });
      } catch (e) { resolve([]); }
    });
  },
};

// The engine likes to ask Windows to pin itself to the taskbar, and Windows words that request with
// the engine's own name. Soot has its own Start menu entry to pin, so every pin request is switched off.
function noPinPrompts() {
  for (const path of ["moz-src:///browser/components/shell/ShellService.sys.mjs", "resource:///modules/ShellService.sys.mjs"]) {
    try {
      const { ShellService } = ChromeUtils.importESModule(path);
      const seen = new Set();
      for (let o = ShellService; o && o !== Object.prototype; o = Object.getPrototypeOf(o)) {
        for (const k of Object.getOwnPropertyNames(o)) {
          if (seen.has(k) || !/pin/i.test(k) || /ping|typing/i.test(k)) continue;
          seen.add(k);
          let isFn = false;
          try { isFn = typeof ShellService[k] === "function"; } catch (e) {}
          if (isFn) { try { ShellService[k] = async () => false; } catch (e) {} }
        }
      }
      return [...seen];
    } catch (e) {}
  }
  return [];
}
export const pinPatched = [];

let started = false;
export function start() {
  if (started) return;
  started = true;
  registerBranding();
  try { registerPages(); } catch (e) { console.error(e); }
  try { pinPatched.push(...noPinPrompts()); } catch (e) { console.error(e); }

  const once = (topic, fn) => {
    const obs = () => { Services.obs.removeObserver(obs, topic); try { fn(); } catch (e) { console.error(e); } };
    Services.obs.addObserver(obs, topic);
  };
  // linux/install.sh starts the engine once with SOOT_PREGEN=1 to write the rewritten texts, then it quits.
  if (Services.env.get("SOOT_PREGEN")) {
    once("final-ui-startup", () => {
      const quit = () => Services.startup.quit(Ci.nsIAppStartup.eForceQuit);
      debrand(true).catch(e => console.error(e)).then(quit, quit);
    });
    return;
  }
  // the master password typed in the engine's prompt (at start, or when a page needed a login) opens the vault
  Services.obs.addObserver(() => { try { Vault.adopt(); } catch (e) { console.error(e); } }, "passwordmgr-crypto-login");
  once("final-ui-startup", () => {
    registerBranding(); addButtons();
    try { if (Phone.enabled) Phone.listen(); } catch (e) { console.error(e); }
    debrand(false).catch(e => console.error(e));     // only does work after an engine update
  });

  Services.obs.addObserver(win => {
    brandWindow(win);
    markFx(win);
  }, "browser-delayed-startup-finished");

  Services.prefs.addObserver("soot.effects.", () => {
    for (const win of Services.wm.getEnumerator("navigator:browser")) markFx(win);
  });

  // every other window (library, dialogs, about box) gets the icon too
  Services.ww.registerNotification((subject, topic) => {
    if (topic !== "domwindowopened") return;
    subject.addEventListener("load", () => {
      try {
        if (subject.document.documentElement.getAttribute("windowtype") !== "navigator:browser") brandWindow(subject);
      } catch (e) {}
    }, { once: true });
  });

}
