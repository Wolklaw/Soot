// Logic for Soot's own pages: vault, clear and import, effects. Runs with browser privileges,
// so nothing from a login, note or server is ever inserted as HTML: text only.
"use strict";

const { Phone, Vault } = ChromeUtils.importESModule("resource://soot/boot.sys.mjs");
const $ = (sel, root = document) => root.querySelector(sel);
const topWin = window.browsingContext.topChromeWindow;
const WIN = Services.appinfo.OS === "WINNT";
// a few sentences name Windows; elsewhere they carry their wording for this system in data-other
if (!WIN) for (const node of document.querySelectorAll("[data-other]")) node.textContent = node.dataset.other;

function el(tag, props = {}, ...kids) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (k === "class") e.className = v;
    else if (k === "text") e.textContent = v;
    else if (k.startsWith("on")) e.addEventListener(k.slice(2), v);
    else e.setAttribute(k, v);
  }
  for (const kid of kids) if (kid != null) e.append(kid);
  return e;
}

const tear = node => {
  if (!window.glitch || !Services.prefs.getBoolPref("soot.effects.pages", true)) return;
  window.glitch(node || $("#panel"));
};

function say(node, text, bad) {
  node.textContent = text;
  node.classList.toggle("bad", !!bad);
  tear($("#panel"));
}

async function copy(text, button) {
  await navigator.clipboard.writeText(text);
  if (button) {
    const old = button.textContent;
    button.textContent = "COPIED";
    setTimeout(() => { button.textContent = old; }, 1200);
  }
}

async function pickFile(title, save, filterName, filterExt, defaultName) {
  const fp = Cc["@mozilla.org/filepicker;1"].createInstance(Ci.nsIFilePicker);
  fp.init(window.browsingContext, title, save ? Ci.nsIFilePicker.modeSave : Ci.nsIFilePicker.modeOpen);
  fp.appendFilter(filterName, filterExt);
  if (defaultName) fp.defaultString = defaultName;
  const rv = await new Promise(resolve => fp.open(resolve));
  return rv === Ci.nsIFilePicker.returnCancel ? null : fp.file.path;
}

// ---------- sections ----------

const TITLES = { vault: "VAULT", data: "CLEAR & IMPORT", effects: "EFFECTS" };

function show(section) {
  if (!TITLES[section]) section = "vault";
  for (const s of document.querySelectorAll("main > section")) s.hidden = s.id !== section;
  for (const b of document.querySelectorAll(".tabs button")) b.setAttribute("aria-current", String(b.dataset.go === section));
  const title = $("#title");
  title.textContent = TITLES[section];
  title.setAttribute("data-text", TITLES[section]);
  document.title = TITLES[section][0] + TITLES[section].slice(1).toLowerCase() + " - Soot";
  $("#eyebrow").textContent = section === "vault" ? "SOOT  ·  PASSWORDS, NOTES AND SIGN-IN CODES"
    : section === "effects" ? "SOOT  ·  GLITCH EFFECTS ON OR OFF" : "SOOT  ·  CACHE, COOKIES AND FAVOURITES";
  if (section === "vault") loadLogins();
  tear(title);
  tear($("#panel"));
}

// each section has its own plain address: about:vault, about:clear, about:effects
const ABOUT = { vault: "about:vault", data: "about:clear", effects: "about:effects" };

// effects switches: each checkbox is one boolean pref, on unless turned off
for (const [id, pref] of [["fx-ui", "soot.effects.ui"], ["fx-extra", "soot.effects.extra"], ["fx-pages", "soot.effects.pages"]]) {
  const box = $("#" + id);
  box.checked = Services.prefs.getBoolPref(pref, true);
  box.addEventListener("change", () => { Services.prefs.setBoolPref(pref, box.checked); tear(); });
}
function fromUrl() {
  const base = location.href.split(/[#?]/)[0];
  for (const [section, url] of Object.entries(ABOUT)) if (url === base) return section;
  return location.hash.slice(1) || "vault";
}
for (const b of document.querySelectorAll(".tabs button")) b.addEventListener("click", () => {
  if (location.protocol === "about:") location.href = ABOUT[b.dataset.go]; else location.hash = b.dataset.go;
});
window.addEventListener("hashchange", () => show(fromUrl()));

function showSub(name) {
  for (const n of ["logins", "notes", "codes", "generator", "cards", "share", "phone"]) $("#sub-" + n).hidden = n !== name;
  if (name === "share") fillShareList();
  if (name === "phone") renderPhone();
  for (const b of document.querySelectorAll(".subtabs button")) b.setAttribute("aria-current", String(b.dataset.sub === name));
  if (name === "notes") renderNotes();
  if (name === "codes") renderCodes();
  if (name === "generator") newPassword();
  tear($("#panel"));
}
for (const b of document.querySelectorAll(".subtabs button")) b.addEventListener("click", () => showSub(b.dataset.sub));

// ---------- vault: logins ----------

const LoginInfo = Components.Constructor("@mozilla.org/login-manager/loginInfo;1", Ci.nsILoginInfo, "init");
let logins = [];
let breached = null;      // Set of guids once a breach check has run
let siteBreached = null;  // Map of guid -> breach, from the engine's list of leaked sites
let editing = null;

function isWeak(p) {
  if (p.length < 10) return true;
  const classes = [/[a-z]/, /[A-Z]/, /\d/, /[^A-Za-z0-9]/].filter(r => r.test(p)).length;
  if (p.length < 14 && classes < 3) return true;
  return /^(.)\1+$/.test(p) || /password|123456|qwerty|azerty|letmein|iloveyou|welcome/i.test(p);
}

function flags(login, counts, twins) {
  const out = [];
  if (twins && twins.get(accountKey(login.origin, login.username)) > 1) out.push(["DUPLICATE", "mid"]);
  if (siteBreached && siteBreached.has(login.guid)) out.push(["SITE BREACHED", ""]);
  if (breached && breached.has(login.guid)) out.push(["BREACHED", ""]);
  if (isWeak(login.password)) out.push(["WEAK", ""]);
  if (counts.get(login.password) > 1) out.push(["REUSED", "mid"]);
  if (login.timePasswordChanged && Date.now() - login.timePasswordChanged > 2 * 365 * 864e5) out.push(["OLD", "mid"]);
  return out;
}

// ---------- vault password: the engine's key store, locked with a password of your own ----------

const token = () => Cc["@mozilla.org/security/internalkeytoken;1"].createInstance(Ci.nsIPKCS11Token);
let shownOpen = false;         // this page has shown the open vault (for "this visit only")

// The vault opens with the master password: typed when Soot started, or here.
function vaultOpen() {
  const open = token().hasPassword && Vault.open;
  if (open) shownOpen = true;
  return open;
}

function renderLock() {
  const has = token().hasPassword;
  const open = vaultOpen();
  $("#gate").hidden = open;
  $("#vault-body").hidden = !open;
  if (!open) {
    $("#gate-title").textContent = has ? "VAULT LOCKED" : "CHOOSE A MASTER PASSWORD";
    $("#gate-text").textContent = has
      ? "Type your master password, the one Soot asks for when it starts, to see your logins, notes and sign-in codes."
      : "The vault needs a master password before it opens. Soot asks for it once when it starts, and that also opens the vault. Use 12 characters or more; a few random words work well. There is no way to recover it: if you forget it, the vault cannot be opened.";
    $("#gate-pw-label").textContent = has ? "MASTER PASSWORD" : "NEW MASTER PASSWORD (12 CHARACTERS OR MORE)";
    $("#gate-pw2-row").hidden = has;
    $("#gate-go").textContent = has ? "UNLOCK" : "SET PASSWORD AND OPEN";
    const sel = $("#gate-remember");
    sel.value = String(Services.prefs.getIntPref("soot.vault.remember", 15));
    if (sel.selectedIndex < 0) sel.value = "15";
    return;
  }
  const state = $("#lock-state");
  const left = Vault.until === Infinity ? "UNTIL SOOT CLOSES"
    : Vault.until > Date.now() ? "FOR " + Math.max(1, Math.round((Vault.until - Date.now()) / 60000)) + " MIN" : "THIS VISIT ONLY";
  state.textContent = "UNLOCKED  \u00b7  " + left;
  state.className = "chip ok";
  $("#lock-now").hidden = false;
  $("#l-old-row").hidden = false;
}

function emptyVault() {
  logins = []; store = null; breached = null;
  clearInterval(codeTimer);
  $("#logins").replaceChildren(); $("#notes").replaceChildren(); $("#codes").replaceChildren();
  $("#health").replaceChildren();
}

// same order the engine itself uses: move the store's key first (it can roll back), then the token
async function setVaultPassword(oldPw, newPw) {
  const t = token();
  const { ProfileKekPassword } = ChromeUtils.importESModule("resource://gre/modules/ProfileKekPassword.sys.mjs");
  let kekMoved = false;
  try {
    if (!t.hasPassword || (await ProfileKekPassword.exists())) { await ProfileKekPassword.update(oldPw, newPw); kekMoved = true; }
    await t.changePassword(oldPw, newPw);
    return true;
  } catch (err) {
    if (kekMoved) await ProfileKekPassword.update(newPw, oldPw).catch(() => {});
    return false;
  }
}

$("#gate").addEventListener("submit", async e => {
  e.preventDefault();
  const form = e.target, out = $("#gate-out"), pw = $("#gate-pw").value;
  const minutes = parseInt($("#gate-remember").value, 10);
  const fail = msg => { out.textContent = msg; $("#gate-pw").select(); tear(form); };
  if (!token().hasPassword) {
    if (pw.length < 12) return fail("Use at least 12 characters. A few random words work well.");
    if (pw !== $("#gate-pw2").value) return fail("The two passwords do not match.");
    if (!(await setVaultPassword("", pw))) return fail("Could not set the master password.");
  }
  if (!(await Vault.unlock(pw, minutes))) return fail("That is not the master password.");
  Services.prefs.setIntPref("soot.vault.remember", minutes);
  form.reset(); out.textContent = "";
  await loadLogins();
  tear();
});

$("#lock-set").addEventListener("click", () => { $("#lock-form").hidden = false; ($("#l-old-row").hidden ? $("#l-new") : $("#l-old")).focus(); tear($("#lock-form")); });
$("#lock-cancel").addEventListener("click", () => { $("#lock-form").hidden = true; $("#lock-form").reset(); $("#lock-out").textContent = ""; });
$("#lock-form").addEventListener("submit", async e => {
  e.preventDefault();
  const form = e.target, out = $("#lock-out");
  const pw = $("#l-new").value;
  const fail = msg => { out.textContent = msg; tear(form); };
  if (pw.length < 12) return fail("Use at least 12 characters. A few random words work well.");
  if (pw !== $("#l-again").value) return fail("The two passwords do not match.");
  if (!(await setVaultPassword($("#l-old").value, pw))) return fail("The current master password is not right.");
  form.reset();
  out.textContent = "";
  form.hidden = true;
  renderLock();
  tear();
});

$("#lock-now").addEventListener("click", () => { Vault.lock(); });

// locked from here, from another vault tab, or because the remembered time ran out
const lockObserver = () => { shownOpen = false; emptyVault(); renderLock(); tear(); };
Services.obs.addObserver(lockObserver, "soot-vault-locked");
window.addEventListener("unload", () => {
  Services.obs.removeObserver(lockObserver, "soot-vault-locked");
  if (shownOpen && Vault.visitOnly) Vault.lock();      // "this visit only": closing the page locks it
});

async function loadLogins() {
  if (!vaultOpen()) { emptyVault(); renderLock(); return; }
  try {
    logins = (await Services.logins.getAllLogins()).filter(l => !l.origin.startsWith("chrome://"));
  } catch (e) { logins = []; }
  try {
    // sites known to have leaked accounts since the password was last changed (the engine keeps this list up to date)
    const { LoginBreaches } = ChromeUtils.importESModule("moz-src:///browser/components/aboutlogins/LoginBreaches.sys.mjs");
    siteBreached = await LoginBreaches.getPotentialBreachesByLoginGUID(logins);
  } catch (e) { siteBreached = null; }
  renderLock();
  renderLogins();
}

function fact(k, v, cls) {
  return el("div", {}, el("div", { class: "k", text: k }), el("div", { class: "v " + (cls || ""), text: String(v) }));
}

// SITE, USERNAME, LAST USED, LAST CHANGED, PROBLEMS FIRST; the choice is remembered
const SORTS = {
  site: (a, b) => siteOf(a.origin).localeCompare(siteOf(b.origin)) || a.username.localeCompare(b.username),
  user: (a, b) => a.username.toLowerCase().localeCompare(b.username.toLowerCase()) || siteOf(a.origin).localeCompare(siteOf(b.origin)),
  used: (a, b) => (b.timeLastUsed || 0) - (a.timeLastUsed || 0),
  changed: (a, b) => (b.timePasswordChanged || 0) - (a.timePasswordChanged || 0),
};

function renderLogins() {
  const counts = new Map();
  for (const l of logins) counts.set(l.password, (counts.get(l.password) || 0) + 1);
  const twins = new Map();
  for (const l of logins) { const k = accountKey(l.origin, l.username); twins.set(k, (twins.get(k) || 0) + 1); }
  const weak = logins.filter(l => isWeak(l.password)).length;
  const reused = logins.filter(l => counts.get(l.password) > 1).length;
  const bad = new Set();
  for (const l of logins) if (flags(l, counts).some(f => f[0] !== "OLD")) bad.add(l.guid);
  const copies = removableCopies().length;
  const dedupe = $("#dedupe");
  dedupe.hidden = !copies;
  dedupe.textContent = "REMOVE " + copies + " EXACT " + (copies === 1 ? "COPY" : "COPIES");
  const score = logins.length ? Math.round(100 * (logins.length - bad.size) / logins.length) : 100;
  $("#health").replaceChildren(
    fact("LOGINS", logins.length),
    fact("WEAK", weak, weak ? "bad" : "ok"),
    fact("REUSED", reused, reused ? "mid" : "ok"),
    fact("BREACHED", breached ? breached.size : "—", breached && breached.size ? "bad" : breached ? "ok" : ""),
    fact("HEALTH", score + "%", score >= 80 ? "ok" : score >= 50 ? "mid" : "bad"));

  const q = $("#search").value.trim().toLowerCase();
  const list = $("#logins");
  list.replaceChildren();
  const order = $("#sort").value;
  const problems = l => flags(l, counts, twins).filter(f => f[0] !== "OLD").length;
  const shown = logins.filter(l => !q || l.origin.toLowerCase().includes(q) || l.username.toLowerCase().includes(q))
    .sort(order === "issues" ? (a, b) => problems(b) - problems(a) || SORTS.site(a, b) : (a, b) => (SORTS[order] || SORTS.site)(a, b) || SORTS.site(a, b));
  if (!shown.length) {
    list.append(el("div", { class: "empty", text: logins.length ? "Nothing matches that search." : "No logins yet. Sign in to a site and Soot will offer to save it, or add one here." }));
    return;
  }
  for (const l of shown) {
    const secret = el("span", { class: "sub", text: "•".repeat(10) });
    const chips = el("div", {});
    for (const [name, cls] of flags(l, counts, twins)) chips.append(el("span", { class: "chip " + cls, text: name }));
    list.append(el("div", { class: "item" },
      el("div", { class: "main" },
        el("div", { class: "site", text: l.origin.replace(/^https?:\/\//, "") }),
        el("div", { class: "sub", text: l.username || "(no username)" }),
        secret, chips),
      el("button", { class: "btn small", text: "COPY USER", onclick: e => copy(l.username, e.currentTarget) }),
      el("button", { class: "btn small", text: "COPY PASSWORD", onclick: e => copy(l.password, e.currentTarget) }),
      el("button", { class: "btn small", text: "SHOW", onclick: e => {
        const hidden = e.currentTarget.textContent === "SHOW";
        secret.textContent = hidden ? l.password : "•".repeat(10);
        e.currentTarget.textContent = hidden ? "HIDE" : "SHOW";
      } }),
      el("button", { class: "btn small", text: "EDIT", onclick: () => openEditor(l) }),
      el("button", { class: "btn small danger", text: "DELETE", onclick: async e => {
        if (e.currentTarget.textContent !== "SURE?") { e.currentTarget.textContent = "SURE?"; return; }
        await Services.logins.removeLoginAsync(l);
        await loadLogins();
        tear();
      } })));
  }
}

function openEditor(login) {
  editing = login || null;
  $("#f-site").value = login ? login.origin : "";
  $("#f-user").value = login ? login.username : "";
  $("#f-pass").value = login ? login.password : "";
  $("#login-form").hidden = false;
  $("#f-site").focus();
  tear($("#login-form"));
}

$("#search").addEventListener("input", renderLogins);
$("#sort").value = Services.prefs.getStringPref("soot.vault.sort", "site");
if ($("#sort").selectedIndex < 0) $("#sort").value = "site";
$("#sort").addEventListener("change", () => { Services.prefs.setStringPref("soot.vault.sort", $("#sort").value); renderLogins(); tear($("#logins")); });
$("#add-login").addEventListener("click", () => openEditor(null));
$("#f-cancel").addEventListener("click", () => { $("#login-form").hidden = true; });
$("#f-generate").addEventListener("click", () => { $("#f-pass").value = generate(20, true, true, true, true); });
$("#login-form").addEventListener("submit", async e => {
  e.preventDefault();
  let site = $("#f-site").value.trim();
  if (!/^[a-z]+:\/\//i.test(site)) site = "https://" + site;
  let origin;
  try { origin = new URL(site).origin; } catch (err) { $("#f-site").focus(); tear($("#login-form")); return; }
  const next = new LoginInfo(origin, "", null, $("#f-user").value, $("#f-pass").value, "", "");
  try {
    if (editing) await Services.logins.modifyLoginAsync(editing, next);
    else await Services.logins.addLoginAsync(next);
    $("#login-form").hidden = true;
    await loadLogins();
    tear();
  } catch (err) {
    $("#f-site").setCustomValidity(String(err.message || err));
    $("#f-site").reportValidity();
    $("#f-site").setCustomValidity("");
  }
});

async function sha1(text) {
  const buf = await crypto.subtle.digest("SHA-1", new TextEncoder().encode(text));
  return Array.from(new Uint8Array(buf), b => b.toString(16).padStart(2, "0")).join("").toUpperCase();
}

$("#check-breaches").addEventListener("click", async e => {
  const btn = e.currentTarget;
  btn.disabled = true;
  btn.textContent = "CHECKING";
  const found = new Set();
  const cache = new Map();
  try {
    for (const l of logins) {
      const hash = await sha1(l.password);
      const prefix = hash.slice(0, 5), suffix = hash.slice(5);
      if (!cache.has(prefix)) {
        const r = await fetch("https://api.pwnedpasswords.com/range/" + prefix, { headers: { "Add-Padding": "true" }, credentials: "omit" });
        cache.set(prefix, r.ok ? await r.text() : "");
      }
      for (const line of cache.get(prefix).split("\n")) {
        const [suf, count] = line.trim().split(":");
        if (suf === suffix && parseInt(count, 10) > 0) { found.add(l.guid); break; }
      }
    }
    breached = found;
    btn.textContent = "CHECK BREACHES";
  } catch (err) {
    btn.textContent = "CHECK FAILED";
  }
  btn.disabled = false;
  renderLogins();
  tear();
});

// ---------- import (Dashlane, Bitwarden, other CSVs) and export ----------
// Every format is read into the same shape first: logins { title, urls[], username, password, notes, totp }
// and notes { title, body }. Then one pass adds them, skipping what the vault already has and setting aside
// the logins that are already saved with another password, for you to choose.

function parseCSV(text) {
  const rows = [];
  let row = [], field = "", quoted = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c === '"') { if (text[i + 1] === '"') { field += '"'; i++; } else quoted = false; }
      else field += c;
    } else if (c === '"') quoted = true;
    else if (c === ",") { row.push(field); field = ""; }
    else if (c === "\n" || c === "\r") {
      if (c === "\r" && text[i + 1] === "\n") i++;
      row.push(field); field = "";
      if (row.length > 1 || row[0] !== "") rows.push(row);
      row = [];
    } else field += c;
  }
  row.push(field);
  if (row.length > 1 || row[0] !== "") rows.push(row);
  if (!rows.length) return [];
  const head = rows[0].map(h => h.trim().replace(/^﻿/, "").toLowerCase());
  return rows.slice(1).map(r => Object.fromEntries(head.map((h, i) => [h, (r[i] || "").trim()])));
}

const csvField = v => /[",\r\n]/.test(v) ? '"' + v.replace(/"/g, '""') + '"' : v;
const pick = (r, ...names) => { for (const n of names) if (r[n]) return r[n]; return ""; };

function zipEntries(path) {
  const { NetUtil } = ChromeUtils.importESModule("resource://gre/modules/NetUtil.sys.mjs");
  const zr = Cc["@mozilla.org/libjar/zip-reader;1"].createInstance(Ci.nsIZipReader);
  const f = Cc["@mozilla.org/file/local;1"].createInstance(Ci.nsIFile);
  f.initWithPath(path);
  zr.open(f);
  const out = {};
  try {
    for (const name of zr.findEntries("*")) {
      if (name.endsWith("/")) continue;
      const stream = zr.getInputStream(name);
      out[name.split("/").pop().toLowerCase()] = NetUtil.readInputStreamToString(stream, stream.available(), { charset: "UTF-8" });
    }
  } finally { zr.close(); }
  return out;
}

// the rest of a row, as "label: value" lines, so nothing in an export is lost
function restOf(r, used) {
  return Object.entries(r).filter(([k, v]) => v && !used.includes(k)).map(([k, v]) => k.replace(/_/g, " ") + ": " + v).join("\n");
}

// a row of any other Dashlane file (payments, IDs, personal info) becomes one note
function rowNote(kind, r) {
  const title = pick(r, "title", "name", "account_name", "item_name", "nickname", "type") || kind;
  const body = restOf(r, []);
  return body ? { title: kind + ": " + title, body } : null;
}

function fromDashlane(files) {
  const logins = [], notes = [];
  for (const r of parseCSV(files["credentials.csv"] || "")) {
    const users = [r.username, r.username2, r.username3].filter(Boolean);
    logins.push({
      title: r.title, urls: [r.url], username: users[0] || r.email || r.login || "",
      password: r.password, totp: r.otpurl || r.otpsecret || r.otp || "",
      notes: [r.note, users.length > 1 ? "other usernames: " + users.slice(1).join(", ") : ""].filter(Boolean).join("\n"),
    });
  }
  for (const r of parseCSV(files["securenotes.csv"] || "")) {
    if (r.note || r.title) notes.push({ title: r.title || "Note", body: r.note || "" });
  }
  for (const [file, kind] of [["payments.csv", "Payment"], ["ids.csv", "ID"], ["personalinfo.csv", "Personal info"]]) {
    for (const r of parseCSV(files[file] || "")) { const n = rowNote(kind, r); if (n) notes.push(n); }
  }
  return { logins, notes };
}

// Bitwarden CSV: folder,favorite,type,name,notes,fields,reprompt,login_uri,login_username,login_password,login_totp
function fromBitwardenCSV(rows) {
  const logins = [], notes = [];
  for (const r of rows) {
    const extra = [r.notes, r.fields].filter(Boolean).join("\n");
    if (r.type === "note" || (!r.login_password && !r.login_username && !r.login_uri)) {
      if (r.name || extra) notes.push({ title: r.name || "Note", body: extra });
      continue;
    }
    logins.push({ title: r.name, urls: r.login_uri.split(/[,\n]/), username: r.login_username, password: r.login_password, totp: r.login_totp, notes: extra });
  }
  return { logins, notes };
}

// Bitwarden JSON (not encrypted): items of type 1 login, 2 note, 3 card, 4 identity, 5 SSH key
function fromBitwardenJSON(data) {
  const logins = [], notes = [];
  const fields = item => (item.fields || []).filter(f => f && f.value).map(f => (f.name || "field") + ": " + f.value);
  const lines = (obj, skip = []) => Object.entries(obj || {})
    .filter(([k, v]) => v != null && v !== "" && typeof v !== "object" && !skip.includes(k)).map(([k, v]) => k + ": " + v);
  for (const item of data.items || []) {
    const extra = [item.notes || "", ...fields(item)].filter(Boolean).join("\n");
    if (item.type === 1 && item.login) {
      const l = item.login;
      logins.push({ title: item.name, urls: (l.uris || []).map(u => u && u.uri).filter(Boolean), username: l.username || "", password: l.password || "", totp: l.totp || "", notes: extra });
    } else if (item.type === 3) {
      notes.push({ title: "Card: " + (item.name || "card"), body: [...lines(item.card), extra].filter(Boolean).join("\n") });
    } else if (item.type === 4) {
      notes.push({ title: "ID: " + (item.name || "identity"), body: [...lines(item.identity), extra].filter(Boolean).join("\n") });
    } else if (item.type === 5) {
      notes.push({ title: "SSH key: " + (item.name || "key"), body: [...lines(item.sshKey), extra].filter(Boolean).join("\n") });
    } else if (item.name || extra) {
      notes.push({ title: item.name || "Note", body: extra });
    }
  }
  return { logins, notes };
}

// Any other password manager's CSV (Chrome, Edge, Firefox, 1Password, KeePass, Proton, ...)
function fromOtherCSV(rows) {
  const logins = [], notes = [];
  const used = ["url", "login_uri", "website", "web site", "origin", "hostname", "uri", "urls", "username", "login_username", "login",
    "user name", "user", "email", "password", "login_password", "name", "title", "note", "notes", "extra", "comments", "totp", "otp",
    "otpauth", "login_totp", "one-time password", "httprealm", "formactionorigin", "guid", "timecreated", "timelastused", "timepasswordchanged"];
  for (const r of rows) {
    const password = pick(r, "password", "login_password");
    const title = pick(r, "name", "title");
    const rest = [pick(r, "note", "notes", "extra", "comments"), restOf(r, used)].filter(Boolean).join("\n");
    if (!password) { if (title || rest) notes.push({ title: title || "Note", body: rest }); continue; }
    logins.push({
      title, password, notes: rest,
      urls: pick(r, "url", "login_uri", "website", "web site", "origin", "hostname", "uri", "urls").split(/[,\n]/),
      username: pick(r, "username", "login_username", "login", "user name", "user", "email"),
      totp: pick(r, "totp", "otp", "otpauth", "login_totp", "one-time password"),
    });
  }
  return { logins, notes };
}

// ---- Bitwarden's password-protected JSON export: PBKDF2-SHA256, then HKDF-Expand to an AES-256-CBC and an HMAC key ----

async function hkdfExpand(prk, info) {
  const key = await crypto.subtle.importKey("raw", prk, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const msg = new Uint8Array([...new TextEncoder().encode(info), 1]);
  return new Uint8Array(await crypto.subtle.sign("HMAC", key, msg));
}

async function bitwardenKeys(password, data) {
  if (data.kdfType !== 0 && data.kdfType != null) throw new Error("argon2");
  const base = await crypto.subtle.importKey("raw", new TextEncoder().encode(password), "PBKDF2", false, ["deriveBits"]);
  const master = new Uint8Array(await crypto.subtle.deriveBits(
    { name: "PBKDF2", salt: new TextEncoder().encode(data.salt), iterations: data.kdfIterations || 600000, hash: "SHA-256" }, base, 256));
  return { enc: await hkdfExpand(master, "enc"), mac: await hkdfExpand(master, "mac") };
}

async function bitwardenDecrypt(encString, keys) {
  const m = /^2\.([^|]+)\|([^|]+)\|([^|]+)$/.exec(String(encString || ""));
  if (!m) throw new Error("format");
  const [iv, ct, mac] = m.slice(1).map(fromB64);
  const macKey = await crypto.subtle.importKey("raw", keys.mac, { name: "HMAC", hash: "SHA-256" }, false, ["verify"]);
  if (!(await crypto.subtle.verify("HMAC", macKey, mac, new Uint8Array([...iv, ...ct])))) throw new Error("password");
  const encKey = await crypto.subtle.importKey("raw", keys.enc, "AES-CBC", false, ["decrypt"]);
  return new TextDecoder().decode(await crypto.subtle.decrypt({ name: "AES-CBC", iv }, encKey, ct));
}

async function askPassword(title, text) {
  const result = await Services.prompt.asyncPromptPassword(topWin.browsingContext, Services.prompt.MODAL_TYPE_WINDOW, title, text, "");
  return result.getProperty("ok") ? result.getProperty("pass") : null;
}

async function openBitwardenJSON(data) {
  if (!data.encrypted) return data;
  if (!data.passwordProtected) throw new Error("This Bitwarden export is locked to your Bitwarden account. Export again and pick .json, or .json (Encrypted) with File password protected.");
  for (let tries = 0; tries < 5; tries++) {
    const pw = await askPassword("Bitwarden export", tries ? "That is not the file password. Try again:" : "This Bitwarden export has a file password. Type it to import:");
    if (pw == null) throw new Error("CANCELLED");
    let keys;
    try { keys = await bitwardenKeys(pw, data); } catch (e) {
      throw new Error("This export uses Argon2id, which Soot can't open yet. In Bitwarden, export as .json (not encrypted), import it, then delete the file.");
    }
    try {
      await bitwardenDecrypt(data.encKeyValidation_DO_NOT_EDIT, keys);
    } catch (e) { if (e.message === "password") continue; throw e; }
    return JSON.parse(await bitwardenDecrypt(data.data, keys));
  }
  throw new Error("Wrong file password five times.");
}

async function readExport(path) {
  if (/\.zip$/i.test(path)) {
    const files = zipEntries(path);
    if (files["credentials.csv"] || files["securenotes.csv"]) return { from: "DASHLANE", ...fromDashlane(files) };
    const json = Object.keys(files).find(n => n.endsWith(".json"));
    if (json) return { from: "BITWARDEN", ...fromBitwardenJSON(await openBitwardenJSON(JSON.parse(files[json]))) };
    const csv = Object.keys(files).find(n => n.endsWith(".csv"));
    if (csv) return readText(files[csv], csv);
    throw new Error("There is no CSV or JSON file in that .zip.");
  }
  return readText(await IOUtils.readUTF8(path), PathUtils.filename(path).toLowerCase());
}

async function readText(text, name) {
  const trimmed = text.replace(/^﻿/, "").trim();
  if (trimmed.startsWith("{")) {
    const data = JSON.parse(trimmed);
    if (Array.isArray(data.items) || data.encrypted) return { from: "BITWARDEN", ...fromBitwardenJSON(await openBitwardenJSON(data)) };
    throw new Error("Soot doesn't know this JSON layout. Export as CSV instead.");
  }
  const rows = parseCSV(trimmed);
  if (!rows.length) throw new Error("That file has no rows.");
  const cols = rows[0];
  if ("login_password" in cols || "login_uri" in cols) return { from: "BITWARDEN", ...fromBitwardenCSV(rows) };
  if ("username2" in cols || "otpurl" in cols || "otpsecret" in cols || ("title" in cols && "category" in cols))
    return { from: "DASHLANE", ...fromDashlane({ "credentials.csv": trimmed }) };
  if (name === "securenotes.csv") return { from: "DASHLANE", ...fromDashlane({ "securenotes.csv": trimmed }) };
  return { from: "CSV", ...fromOtherCSV(rows) };
}

// ---- one key per account: the site without www and scheme, and the username ----

const siteOf = origin => { try { return new URL(origin).host.replace(/^www\./, "").toLowerCase(); } catch (e) { return String(origin).toLowerCase(); } };
const accountKey = (origin, username) => siteOf(origin) + "\n" + String(username || "").trim().toLowerCase();

function toOrigin(url) {
  let site = String(url || "").trim();
  if (!site) return null;
  if (!/^[a-z][a-z0-9+.-]*:\/\//i.test(site)) site = "https://" + site;
  try {
    const u = new URL(site);
    return /^https?:$/.test(u.protocol) && u.host ? u.origin : null;    // android apps, ssh:// and the like are kept in notes
  } catch (e) { return null; }
}

function totpSecret(otp) {
  const m = /[?&]secret=([^&]+)/i.exec(otp || "");
  return m ? decodeURIComponent(m[1]) : String(otp || "").trim();
}

let conflicts = [];      // [{ existing, imported }] waiting for KEEP CURRENT or USE IMPORTED

async function importParsed(parsed) {
  await loadStore();
  const have = new Map();
  for (const l of await Services.logins.getAllLogins()) if (!l.origin.startsWith("chrome://")) have.set(accountKey(l.origin, l.username), l);
  const noteKeys = new Set(store.notes.map(n => n.title + "\n" + n.body));
  const codeKeys = new Set(store.codes.map(c => base32(c.secret).join(",")));
  const n = { added: 0, same: 0, conflicts: 0, notes: 0, codes: 0, skipped: 0 };
  let storeChanged = false;
  const addNote = (title, body) => {
    const k = title + "\n" + body;
    if (!body.trim() && !title.trim()) return;
    if (noteKeys.has(k)) return;
    noteKeys.add(k);
    store.notes.push({ title, body });
    n.notes++; storeChanged = true;
  };

  for (const r of parsed.logins) {
    const password = r.password || "";
    const origins = [...new Set((r.urls || []).map(toOrigin).filter(Boolean))];
    const label = r.title || (origins[0] ? siteOf(origins[0]) : "") || "Login";
    if (!password && !r.username) { if (r.notes) addNote(label, r.notes); else n.skipped++; continue; }
    if (!origins.length) {
      // no web address to attach it to: keep it as a note so nothing is lost
      const where = (r.urls || []).filter(Boolean).join(", ");
      addNote(label, ["username: " + (r.username || ""), "password: " + password, where ? "address: " + where : "", r.notes || ""].filter(Boolean).join("\n"));
      continue;
    }
    for (const origin of origins) {
      const key = accountKey(origin, r.username);
      const existing = have.get(key);
      if (existing) {
        if (existing.password === password) n.same++;
        else if (!conflicts.some(c => c.existing.guid === existing.guid && c.imported.password === password)) {
          conflicts.push({ existing, imported: { origin, username: r.username || "", password } });
          n.conflicts++;
        }
        continue;
      }
      try {
        const added = await Services.logins.addLoginAsync(new LoginInfo(origin, "", null, r.username || "", password, "", ""));
        have.set(key, added || { origin, username: r.username || "", password });
        n.added++;
      } catch (e) { n.same++; }      // the engine already has it under another form address
    }
    const secret = totpSecret(r.totp);
    if (secret && base32(secret).length) {
      const k = base32(secret).join(",");
      if (!codeKeys.has(k)) {
        codeKeys.add(k);
        store.codes.push({ name: label, secret, site: siteOf(origins[0]) });
        n.codes++; storeChanged = true;
      }
    }
    if (r.notes) addNote(label, r.notes);
  }
  for (const note of parsed.notes) addNote(note.title || "Note", note.body || "");
  if (storeChanged) await saveStore();
  return n;
}

async function importVaultFile(path) {
  let summary;
  try {
    const parsed = await readExport(path);
    const n = await importParsed(parsed);
    const parts = [n.added + " ADDED"];
    if (n.same) parts.push(n.same + " ALREADY THERE");
    if (n.conflicts) parts.push(n.conflicts + " TO CHECK");
    if (n.codes) parts.push(n.codes + " CODES");
    if (n.notes) parts.push(n.notes + " NOTES");
    if (n.skipped) parts.push(n.skipped + " EMPTY");
    summary = parsed.from + ": " + parts.join(", ");
  } catch (err) {
    if (err.message === "CANCELLED") summary = "IMPORT CANCELLED";
    else { console.error(err); summary = err instanceof SyntaxError || !err.message ? "COULD NOT READ THAT FILE" : err.message; }
  }
  await loadLogins();
  renderConflicts();
  return summary;
}

// ---- same account, different password: you choose ----

async function useImported(c) {
  const e = c.existing;
  const next = new LoginInfo(e.origin, e.formActionOrigin ?? (e.httpRealm == null ? "" : null), e.httpRealm, e.username, c.imported.password, e.usernameField || "", e.passwordField || "");
  await Services.logins.modifyLoginAsync(e, next);
}

function renderConflicts() {
  const box = $("#import-review");
  box.replaceChildren();
  // drop the ones whose login is gone or already has the imported password
  const live = new Map(logins.map(l => [l.guid, l]));
  conflicts = conflicts.filter(c => live.has(c.existing.guid) && live.get(c.existing.guid).password !== c.imported.password)
    .map(c => ({ ...c, existing: live.get(c.existing.guid) }));
  box.hidden = !conflicts.length;
  if (!conflicts.length) return;
  const done = async () => { await loadLogins(); renderConflicts(); tear(box); };
  box.append(
    el("div", { class: "k", text: conflicts.length + " SAVED WITH ANOTHER PASSWORD" }),
    el("p", { class: "note", text: "These accounts are already in the vault with a different password. Keep the one you have, or replace it with the imported one." }),
    el("div", { class: "row" },
      el("button", { class: "btn small", text: "KEEP ALL CURRENT", onclick: () => { conflicts = []; renderConflicts(); tear(); } }),
      el("button", { class: "btn small", text: "USE ALL IMPORTED", onclick: async () => {
        for (const c of conflicts) { try { await useImported(c); } catch (err) { console.error(err); } }
        conflicts = []; await done();
      } })));
  for (const c of conflicts) {
    const pwRow = el("div", { class: "sub", text: "current " + "•".repeat(8) + "   imported " + "•".repeat(8) });
    box.append(el("div", { class: "item" },
      el("div", { class: "main" },
        el("div", { class: "site", text: c.existing.origin.replace(/^https?:\/\//, "") }),
        el("div", { class: "sub", text: c.existing.username || "(no username)" }), pwRow),
      el("button", { class: "btn small", text: "SHOW", onclick: e => {
        const hidden = e.currentTarget.textContent === "SHOW";
        pwRow.textContent = hidden ? "current " + c.existing.password + "   imported " + c.imported.password : "current " + "•".repeat(8) + "   imported " + "•".repeat(8);
        e.currentTarget.textContent = hidden ? "HIDE" : "SHOW";
      } }),
      el("button", { class: "btn small", text: "KEEP CURRENT", onclick: () => { conflicts = conflicts.filter(x => x !== c); renderConflicts(); tear(); } }),
      el("button", { class: "btn small", text: "USE IMPORTED", onclick: async () => {
        try { await useImported(c); } catch (err) { console.error(err); }
        conflicts = conflicts.filter(x => x !== c); await done();
      } })));
  }
}

// ---- duplicates already in the vault: same site and username saved more than once ----

function duplicateGroups() {
  const groups = new Map();
  for (const l of logins) {
    const k = accountKey(l.origin, l.username);
    if (!groups.has(k)) groups.set(k, []);
    groups.get(k).push(l);
  }
  return [...groups.values()].filter(g => g.length > 1);
}

// copies that also have the same password: all but the most recently used one can go without losing anything
function removableCopies() {
  const out = [];
  for (const g of duplicateGroups()) {
    const byPw = new Map();
    for (const l of g) { if (!byPw.has(l.password)) byPw.set(l.password, []); byPw.get(l.password).push(l); }
    for (const same of byPw.values()) {
      if (same.length < 2) continue;
      same.sort((a, b) => (b.timeLastUsed || 0) - (a.timeLastUsed || 0) || (b.timePasswordChanged || 0) - (a.timePasswordChanged || 0));
      out.push(...same.slice(1));
    }
  }
  return out;
}

$("#dedupe").addEventListener("click", async e => {
  const btn = e.currentTarget;
  const extra = removableCopies();
  if (!extra.length) return;
  if (btn.textContent.startsWith("REMOVE")) { btn.textContent = "SURE? " + extra.length + (extra.length === 1 ? " COPY" : " COPIES"); return; }
  for (const l of extra) { try { await Services.logins.removeLoginAsync(l); } catch (err) { console.error(err); } }
  await loadLogins();
  tear();
});

$("#import-csv").addEventListener("click", async e => {
  const btn = e.currentTarget;
  const path = await pickFile("Import a Dashlane export (.zip or .csv) or a Bitwarden export (.json or .csv), or another password manager's CSV", false, "Password manager export", "*.zip; *.csv; *.json");
  if (!path) return;
  btn.textContent = "IMPORTING";
  btn.disabled = true;
  const summary = await importVaultFile(path);
  btn.disabled = false;
  btn.textContent = "IMPORT";
  say($("#import-result"), summary, /COULD NOT|CANCELLED|can't|doesn't|Wrong|no rows|no CSV|locked/.test(summary));
});

async function exportDashlane(path) {
  await loadStore();
  const lines = ["username,username2,username3,title,password,note,url,category,otpUrl"];
  for (const l of logins) {
    const host = l.origin.replace(/^https?:\/\/(www\.)?/, "");
    const code = store.codes.find(c => c.site === host || c.name.toLowerCase() === host.toLowerCase());
    const otp = code ? "otpauth://totp/" + encodeURIComponent(host) + "?secret=" + code.secret.replace(/\s/g, "") : "";
    lines.push([l.username, "", "", host, l.password, "", l.origin, "", otp].map(csvField).join(","));
  }
  await IOUtils.writeUTF8(path, lines.join("\r\n") + "\r\n");
  return logins.length;
}

$("#export-csv").addEventListener("click", async e => {
  const btn = e.currentTarget;
  const path = await pickFile("Export the vault as a CSV (Dashlane's layout; Bitwarden and others read it too)", true, "CSV", "*.csv", "credentials.csv");
  if (!path) return;
  btn.textContent = (await exportDashlane(path)) + " EXPORTED";
  setTimeout(() => { btn.textContent = "EXPORT CSV"; }, 6000);
  tear();
});

// ---------- vault: generator ----------

function generate(length, upper, lower, digits, symbols) {
  const sets = [];
  if (upper) sets.push("ABCDEFGHJKLMNPQRSTUVWXYZ");
  if (lower) sets.push("abcdefghijkmnopqrstuvwxyz");
  if (digits) sets.push("23456789");
  if (symbols) sets.push("!#$%&*+-=?@^_");
  if (!sets.length) sets.push("abcdefghijkmnopqrstuvwxyz");
  const all = sets.join("");
  const pick = chars => {
    const limit = Math.floor(0x100000000 / chars.length) * chars.length;
    const buf = new Uint32Array(1);
    do { crypto.getRandomValues(buf); } while (buf[0] >= limit);
    return chars[buf[0] % chars.length];
  };
  const out = sets.map(pick);                       // at least one from each chosen set
  while (out.length < length) out.push(pick(all));
  for (let i = out.length - 1; i > 0; i--) {        // shuffle
    const buf = new Uint32Array(1);
    crypto.getRandomValues(buf);
    const j = buf[0] % (i + 1);
    [out[i], out[j]] = [out[j], out[i]];
  }
  return out.slice(0, length).join("");
}

function newPassword() {
  $("#g-len-v").textContent = $("#g-len").value;
  $("#g-out").textContent = generate(parseInt($("#g-len").value, 10), $("#g-upper").checked, $("#g-lower").checked, $("#g-digits").checked, $("#g-symbols").checked);
}
for (const id of ["g-len", "g-upper", "g-lower", "g-digits", "g-symbols"]) $("#" + id).addEventListener("input", newPassword);
$("#g-new").addEventListener("click", () => { newPassword(); tear(); });
$("#g-copy").addEventListener("click", e => copy($("#g-out").textContent, e.currentTarget));

// ---------- vault: notes and sign-in codes (kept in one encrypted file in the profile) ----------

const storePath = PathUtils.join(PathUtils.profileDir, "soot-vault.json");
const sdr = () => Cc["@mozilla.org/login-manager/crypto/SDR;1"].getService(Ci.nsILoginManagerCrypto);
let store = null;

async function loadStore() {
  if (store) return store;
  store = { notes: [], codes: [] };
  try {
    if (await IOUtils.exists(storePath)) store = Object.assign(store, JSON.parse(sdr().decrypt(await IOUtils.readUTF8(storePath))));
  } catch (e) { console.error(e); }
  return store;
}

async function saveStore() {
  await IOUtils.writeUTF8(storePath, sdr().encrypt(JSON.stringify(store)), { tmpPath: storePath + ".tmp" });
}

async function renderNotes() {
  await loadStore();
  const list = $("#notes");
  list.replaceChildren();
  if (!store.notes.length) { list.append(el("div", { class: "empty", text: "No notes yet." })); return; }
  const byTitle = (a, b) => (a.title || "").localeCompare(b.title || "", undefined, { sensitivity: "base" });
  [...store.notes].sort(byTitle).forEach(n => {
    list.append(el("div", { class: "item" },
      el("div", { class: "main" }, el("div", { class: "site", text: n.title }), el("div", { class: "body", text: n.body })),
      el("button", { class: "btn small", text: "COPY", onclick: e => copy(n.body, e.currentTarget) }),
      el("button", { class: "btn small danger", text: "DELETE", onclick: async e => {
        if (e.currentTarget.textContent !== "SURE?") { e.currentTarget.textContent = "SURE?"; return; }
        store.notes.splice(store.notes.indexOf(n), 1); await saveStore(); renderNotes(); tear();
      } })));
  });
}

$("#note-form").addEventListener("submit", async e => {
  e.preventDefault();
  await loadStore();
  store.notes.unshift({ title: $("#n-title").value.trim(), body: $("#n-body").value });
  await saveStore();
  e.target.reset();
  renderNotes();
  tear();
});

function base32(text) {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  const clean = text.toUpperCase().replace(/[^A-Z2-7]/g, "");
  const bytes = [];
  let bits = 0, value = 0;
  for (const ch of clean) {
    value = (value << 5) | alphabet.indexOf(ch);
    bits += 5;
    if (bits >= 8) { bytes.push((value >>> (bits - 8)) & 255); bits -= 8; }
  }
  return new Uint8Array(bytes);
}

async function totp(secret, when = Date.now()) {
  const keyBytes = base32(secret);
  if (!keyBytes.length) return "------";
  const counter = Math.floor(when / 30000);
  const msg = new Uint8Array(8);
  new DataView(msg.buffer).setUint32(4, counter);
  const key = await crypto.subtle.importKey("raw", keyBytes, { name: "HMAC", hash: "SHA-1" }, false, ["sign"]);
  const mac = new Uint8Array(await crypto.subtle.sign("HMAC", key, msg));
  const o = mac[mac.length - 1] & 15;
  const num = ((mac[o] & 127) << 24) | (mac[o + 1] << 16) | (mac[o + 2] << 8) | mac[o + 3];
  return String(num % 1000000).padStart(6, "0");
}

let codeTimer = 0;
async function renderCodes() {
  await loadStore();
  clearInterval(codeTimer);
  const list = $("#codes");
  list.replaceChildren();
  if (!store.codes.length) { list.append(el("div", { class: "empty", text: "No sign-in codes yet." })); return; }
  const rows = [];
  [...store.codes].sort((a, b) => (a.name || "").localeCompare(b.name || "", undefined, { sensitivity: "base" })).forEach(c => {
    const code = el("div", { class: "code", text: "------" });
    const left = el("div", { class: "sub" });
    rows.push({ c, code, left });
    list.append(el("div", { class: "item" },
      el("div", { class: "main" }, el("div", { class: "site", text: c.name }), code, left),
      el("button", { class: "btn small", text: "COPY", onclick: e => copy(code.textContent, e.currentTarget) }),
      el("button", { class: "btn small danger", text: "DELETE", onclick: async e => {
        if (e.currentTarget.textContent !== "SURE?") { e.currentTarget.textContent = "SURE?"; return; }
        store.codes.splice(store.codes.indexOf(c), 1); await saveStore(); renderCodes(); tear();
      } })));
  });
  const tick = async () => {
    const secs = 30 - Math.floor(Date.now() / 1000) % 30;
    for (const r of rows) { r.code.textContent = await totp(r.c.secret); r.left.textContent = "new code in " + secs + " s"; }
  };
  tick();
  codeTimer = setInterval(tick, 1000);
}

$("#code-form").addEventListener("submit", async e => {
  e.preventDefault();
  await loadStore();
  let secret = $("#c-secret").value.trim();
  const m = /[?&]secret=([^&]+)/i.exec(secret);
  if (m) secret = decodeURIComponent(m[1]);
  if (!base32(secret).length) { $("#c-secret").focus(); tear($("#code-form")); return; }
  store.codes.push({ name: $("#c-name").value.trim(), secret });
  await saveStore();
  e.target.reset();
  renderCodes();
  tear();
});

// ---------- vault: cards and addresses, sharing ----------

$("#open-cards").addEventListener("click", () => topWin.openTrustedLinkIn("about:preferences#privacy-form-autofill", "tab"));

const toB64 = bytes => btoa(String.fromCharCode(...bytes));
const fromB64 = text => Uint8Array.from(atob(text), c => c.charCodeAt(0));

async function shareKey(passphrase, salt) {
  const base = await crypto.subtle.importKey("raw", new TextEncoder().encode(passphrase), "PBKDF2", false, ["deriveKey"]);
  return crypto.subtle.deriveKey({ name: "PBKDF2", salt, iterations: 600000, hash: "SHA-256" }, base, { name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]);
}

function fillShareList() {
  const sel = $("#sh-login");
  sel.replaceChildren();
  logins.forEach((l, i) => sel.append(el("option", { value: String(i), text: l.origin.replace(/^https?:\/\//, "") + "  /  " + (l.username || "(no username)") })));
}

$("#share-form").addEventListener("submit", async e => {
  e.preventDefault();
  const l = logins[parseInt($("#sh-login").value, 10)];
  if (!l) return;
  const salt = crypto.getRandomValues(new Uint8Array(16)), iv = crypto.getRandomValues(new Uint8Array(12));
  const key = await shareKey($("#sh-pass").value, salt);
  const data = new TextEncoder().encode(JSON.stringify({ o: l.origin, u: l.username, p: l.password }));
  const sealed = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, data));
  $("#sh-out").value = "soot1." + toB64(salt) + "." + toB64(iv) + "." + toB64(sealed);
  $("#sh-out").hidden = false; $("#sh-copy").hidden = false;
  $("#sh-pass").value = "";
  tear();
});
$("#sh-copy").addEventListener("click", e => copy($("#sh-out").value, e.currentTarget));

$("#receive-form").addEventListener("submit", async e => {
  e.preventDefault();
  const out = $("#rc-out");
  try {
    const parts = $("#rc-text").value.trim().split(".");
    if (parts.length !== 4 || parts[0] !== "soot1") throw new Error("format");
    const key = await shareKey($("#rc-pass").value, fromB64(parts[1]));
    const plain = await crypto.subtle.decrypt({ name: "AES-GCM", iv: fromB64(parts[2]) }, key, fromB64(parts[3]));
    const d = JSON.parse(new TextDecoder().decode(plain));
    await Services.logins.addLoginAsync(new LoginInfo(new URL(d.o).origin, "", null, String(d.u || ""), String(d.p || ""), "", ""));
    e.target.reset();
    await loadLogins();
    say(out, "Added " + d.o.replace(/^https?:\/\//, "") + " to your vault.");
  } catch (err) {
    say(out, /exist|duplicate/i.test(String(err.message)) ? "That login is already in your vault." : "Could not open it. Check the text and the passphrase.", true);
  }
});

// ---------- vault: phone sync ----------

async function renderPhone() {
  const on = Phone.enabled;
  Phone.load();
  $("#ph-toggle").textContent = on ? "TURN OFF PHONE SYNC" : "TURN ON PHONE SYNC";
  $("#ph-toggle").className = "btn" + (on ? "" : " primary");
  const addrs = on ? await Phone.addresses() : [];
  const status = $("#ph-status");
  status.style.gridTemplateColumns = "1fr 2fr 1fr";
  status.replaceChildren(
    fact("PHONE SYNC", on ? "ON" : "OFF", on ? "ok" : ""),
    fact("ADDRESS TO TYPE ON THE PHONE", on ? (addrs.length ? addrs.map(a => a + ":" + Phone.PORT).join("  or  ") : "no network found") : "—"),
    fact("PHONES", Phone.devices.filter(d => d.ok).length));
  const list = $("#ph-devices");
  list.replaceChildren();
  for (const d of Phone.devices) {
    list.append(el("div", { class: "item" },
      el("div", { class: "main" },
        el("div", { class: "site", text: d.name }),
        d.ok ? el("div", { class: "sub", text: d.last ? "last synced " + new Date(d.last).toLocaleString() : "paired, not synced yet" })
             : el("div", { class: "code", text: d.sas })),
      d.ok ? el("span", { class: "chip ok", text: "PAIRED" }) : el("span", { class: "chip mid", text: "CHECK THE CODE ON THE PHONE" }),
      d.ok ? null : el("button", { class: "btn small primary", text: "THE CODES MATCH", onclick: () => { Phone.confirm(d.id); tear(); } }),
      el("button", { class: "btn small danger", text: d.ok ? "UNPAIR" : "REJECT", onclick: e => {
        if (d.ok && e.currentTarget.textContent !== "SURE?") { e.currentTarget.textContent = "SURE?"; return; }
        Phone.remove(d.id); tear();
      } })));
  }
}

$("#ph-toggle").addEventListener("click", e => {
  const btn = e.currentTarget;
  if (Phone.enabled) { Phone.setEnabled(false); renderPhone(); tear(); return; }
  // Turning it on needs one firewall opening. Soot's launcher asks the system for it under Soot's own name.
  btn.textContent = WIN ? "WAITING FOR WINDOWS..." : "WAITING FOR THE FIREWALL...";
  try {
    const exe = Services.dirsvc.get("GreD", Ci.nsIFile).parent;
    exe.append(WIN ? "Soot.exe" : "soot");
    const proc = Cc["@mozilla.org/process/util;1"].createInstance(Ci.nsIProcess);
    proc.init(exe);
    proc.runAsync(["--allow-phone-sync"], 1, {
      observe() {
        if (proc.exitValue === 0) { try { Phone.setEnabled(true); } catch (err) { console.error(err); } renderPhone(); tear(); }
        else { renderPhone().then(() => { btn.textContent = (WIN ? "WINDOWS" : "THE FIREWALL") + " SAID NO. PRESS TO TRY AGAIN"; }); tear(); }
      },
    });
  } catch (err) { console.error(err); renderPhone(); }
});

const phoneObserver = () => { if (!$("#sub-phone").hidden) renderPhone(); };
Services.obs.addObserver(phoneObserver, "soot-phone-changed");
window.addEventListener("unload", () => Services.obs.removeObserver(phoneObserver, "soot-phone-changed"));

// ---------- clear and import ----------

$("#clear-now").addEventListener("click", async () => {
  const out = $("#clear-out");
  try {
    const { Sanitizer } = ChromeUtils.importESModule("resource:///modules/Sanitizer.sys.mjs");
    const items = Object.keys(Sanitizer.items).filter(n => /^(cache|cookies|cookiesAndStorage|offlineApps)$/.test(n));
    await Sanitizer.sanitize(items, { ignoreTimespan: true });
    say(out, "Cache and cookies cleared at " + new Date().toLocaleTimeString() + ".");
  } catch (err) {
    say(out, "Could not clear: " + (err.message || err), true);
  }
});

$("#clear-more").addEventListener("click", () => {
  const { Sanitizer } = ChromeUtils.importESModule("resource:///modules/Sanitizer.sys.mjs");
  Sanitizer.showUI(topWin);
});

$("#import-browser").addEventListener("click", () => {
  const { MigrationUtils } = ChromeUtils.importESModule("moz-src:///browser/components/migration/MigrationUtils.sys.mjs");
  MigrationUtils.showMigrationWizard(topWin, { entrypoint: MigrationUtils.MIGRATION_ENTRYPOINTS.UNKNOWN });
});

$("#import-html").addEventListener("click", async () => {
  const out = $("#import-out");
  const path = await pickFile("Import favourites from a file", false, "Favourites file", "*.html; *.htm");
  if (!path) return;
  try {
    const { BookmarkHTMLUtils } = ChromeUtils.importESModule("resource://gre/modules/BookmarkHTMLUtils.sys.mjs");
    await BookmarkHTMLUtils.importFromFile(path, { replace: false });
    say(out, "Favourites imported. Press Ctrl+Shift+O to see them.");
  } catch (err) {
    say(out, "Could not import that file: " + (err.message || err), true);
  }
});

$("#export-html").addEventListener("click", async () => {
  const out = $("#import-out");
  const path = await pickFile("Export favourites", true, "Favourites file", "*.html", "soot-favourites.html");
  if (!path) return;
  try {
    const { BookmarkHTMLUtils } = ChromeUtils.importESModule("resource://gre/modules/BookmarkHTMLUtils.sys.mjs");
    await BookmarkHTMLUtils.exportToFile(path);
    say(out, "Favourites saved to " + path);
  } catch (err) {
    say(out, "Could not export: " + (err.message || err), true);
  }
});

// ---------- start ----------

showSub("logins");
show(fromUrl());
