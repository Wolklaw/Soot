#!/usr/bin/env python3
"""Headless smoke test for an installed Soot on Linux.

Starts `soot --headless` on a fresh profile, connects to the engine's Marionette port, and checks from
inside the browser that Soot's policies, default settings and interface code are loaded.

    python3 linux/smoke-test.py [--soot /usr/bin/soot] [--expect-pregen]
"""
import argparse, json, os, shutil, socket, subprocess, sys, tempfile, time

PORT = 2828

CHECKS = r"""
const done = arguments[arguments.length - 1];
(async () => {
  const out = {};
  const check = (name, fn) => { try { out[name] = fn(); } catch (e) { out[name] = "ERROR: " + e; } };
  check("os", () => Services.appinfo.OS);
  check("engine", () => Services.appinfo.version);
  // policies
  check("policies_active", () => Services.policies.status === Ci.nsIEnterprisePolicies.ACTIVE);
  check("policy_updater_off", () => Services.policies.getActivePolicies().DisableAppUpdate === true);
  check("policy_telemetry_off", () => Services.policies.getActivePolicies().DisableTelemetry === true);
  check("policy_adblock", () => !!Services.policies.getActivePolicies().ExtensionSettings["uBlock0@raymondhill.net"]);
  check("updates_not_allowed", () => !Services.policies.isAllowed("appUpdate"));
  // autoconfig and user.js
  check("autoconfig", () => Services.prefs.getStringPref("general.config.filename") === "soot.cfg");
  check("pref_userchrome", () => Services.prefs.getBoolPref("toolkit.legacyUserProfileCustomizations.stylesheets") === true);
  check("pref_density", () => Services.prefs.getIntPref("browser.uidensity") === 1);
  check("pref_telemetry", () => Services.prefs.getBoolPref("datareporting.healthreport.uploadEnabled") === false);
  // app/ is registered
  check("resource_soot", () => Services.io.getProtocolHandler("resource").QueryInterface(Ci.nsIResProtocolHandler).hasSubstitution("soot"));
  check("chrome_soot", () => Cc["@mozilla.org/chrome/chrome-registry;1"].getService(Ci.nsIChromeRegistry)
    .convertChromeURL(Services.io.newURI("chrome://soot/content/hub.html")).spec.endsWith("/app/content/hub.html"));
  check("boot_module", () => typeof ChromeUtils.importESModule("resource://soot/boot.sys.mjs").Vault.unlock === "function");
  check("about_vault", () => Components.manager.QueryInterface(Ci.nsIComponentRegistrar)
    .isContractIDRegistered("@mozilla.org/network/protocol/about;1?what=vault"));
  check("brand_name", () => Services.strings.createBundle("chrome://branding/locale/brand.properties").GetStringFromName("brandShortName"));
  check("toolbar_buttons", () => {
    const doc = Services.wm.getMostRecentWindow("navigator:browser").document;
    return !!doc.getElementById("soot-vault-button") && !!doc.getElementById("soot-clear-button");
  });
  check("userchrome_in_profile", () => {
    const f = Services.dirsvc.get("ProfD", Ci.nsIFile); f.append("chrome"); f.append("userChrome.css"); return f.exists();
  });
  // rewritten engine texts
  check("auto_dir", () => Services.io.getProtocolHandler("resource").QueryInterface(Ci.nsIResProtocolHandler)
    .getSubstitution("soot-auto").spec);
  try {
    const root = Services.dirsvc.get("GreD", Ci.nsIFile).parent.path;
    out.pregen = await IOUtils.exists(PathUtils.join(root, "app", "l10n-auto", "build.txt"));
  } catch (e) { out.pregen = "ERROR: " + e; }
  try {
    // a sentence the engine ships with its own name in it
    const l10n = new Localization(["browser/profile/default-bookmarks.ftl"], false);
    out.renamed_text = await l10n.formatValue("default-bookmarks-firefox-customize");
  } catch (e) { out.renamed_text = "ERROR: " + e; }
  done(out);
})();
"""


class Marionette:
    def __init__(self, port, timeout):
        deadline = time.time() + timeout
        while True:
            try:
                self.sock = socket.create_connection(("127.0.0.1", port), timeout=5)
                break
            except OSError:
                if time.time() > deadline:
                    raise SystemExit("smoke test: the engine never opened its Marionette port")
                time.sleep(0.5)
        self.sock.settimeout(120)
        self.buf = b""
        self.id = 0
        self.read()                                  # the server's hello

    def read(self):
        while b":" not in self.buf:
            self.buf += self._recv()
        length, _, rest = self.buf.partition(b":")
        need = int(length)
        while len(rest) < need:
            rest += self._recv()
        self.buf = rest[need:]
        return json.loads(rest[:need])

    def _recv(self):
        data = self.sock.recv(65536)
        if not data:
            raise SystemExit("smoke test: the engine closed the connection")
        return data

    def call(self, name, params=None):
        self.id += 1
        body = json.dumps([0, self.id, name, params or {}])
        self.sock.sendall(("%d:%s" % (len(body.encode()), body)).encode())
        _, _, error, result = self.read()
        if error:
            raise SystemExit("smoke test: %s failed: %s" % (name, json.dumps(error)[:600]))
        return result


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--soot", default=shutil.which("soot") or "/usr/bin/soot")
    ap.add_argument("--expect-pregen", action="store_true", help="fail unless install.sh renamed the engine texts")
    opts = ap.parse_args()

    work = tempfile.mkdtemp(prefix="soot-smoke-")
    env = dict(os.environ, SOOT_PROFILE=os.path.join(work, "profile"), HOME=os.path.join(work, "home"),
               XDG_RUNTIME_DIR=os.path.join(work, "home"), MOZ_REMOTE_ALLOW_SYSTEM_ACCESS="1")
    os.makedirs(env["HOME"])
    log = open(os.path.join(work, "engine.log"), "w+")
    proc = subprocess.Popen([opts.soot, "--headless", "--no-remote", "-marionette", "-remote-allow-system-access"],
                            env=env, stdout=log, stderr=subprocess.STDOUT)
    try:
        m = Marionette(PORT, timeout=90)
        m.call("WebDriver:NewSession", {"capabilities": {"alwaysMatch": {}}})
        m.call("Marionette:SetContext", {"value": "chrome"})
        result = m.call("WebDriver:ExecuteAsyncScript",
                        {"script": CHECKS, "args": [], "sandbox": "system", "newSandbox": True, "scriptTimeout": 60000})
        out = result["value"] if isinstance(result, dict) and "value" in result else result
        try:
            m.call("Marionette:Quit", {"flags": ["eForceQuit"]})
        except (SystemExit, OSError):
            pass
    finally:
        try:
            proc.wait(timeout=20)
        except subprocess.TimeoutExpired:
            proc.kill()
        log.seek(0)
        tail = log.read()[-3000:]
        log.close()

    info = {"os", "engine", "auto_dir", "pregen"}
    want = {"brand_name": "Soot", "renamed_text": "Customize Soot"}
    failed = []
    for name, value in out.items():
        ok = True
        if name in info:
            ok = not str(value).startswith("ERROR")
        elif name in want:
            ok = value == want[name]
        else:
            ok = value is True
        if name == "pregen" and opts.expect_pregen:
            ok = value is True
        print("%-4s %-24s %s" % ("ok" if ok else "FAIL", name, value))
        if not ok:
            failed.append(name)
    if out.get("os") != "Linux":
        failed.append("os")
    if failed:
        print("\nengine log (tail):\n" + tail)
        raise SystemExit("smoke test FAILED: " + ", ".join(failed))
    shutil.rmtree(work, ignore_errors=True)
    print("\nsmoke test passed")


if __name__ == "__main__":
    main()
