// Soot launcher: starts the bundled engine with Soot's own profile, settings and interface.
// Everything lives in this folder; nothing is installed system-wide.
using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;
using System.Text;
using System.Windows.Forms;

static class Soot
{
    const string ConfigVersion = "2";
    const string AppId = "Soot.Browser";   // must match APP_ID in app\boot.sys.mjs

    [STAThread]
    static int Main(string[] args)
    {
        string root = AppDomain.CurrentDomain.BaseDirectory.TrimEnd('\\');
        string engineDir = Path.Combine(root, "engine");
        string engine = Path.Combine(engineDir, "firefox.exe");
        // Phone sync needs one firewall opening. Soot asks for it itself, under its own name, so Windows never
        // shows its own prompt worded after the engine. Called by the Phone page; returns 0 when the opening exists.
        if (args.Length > 0 && args[0] == "--allow-phone-sync") return AllowPhoneSync(engine, false);
        if (args.Length > 0 && args[0] == "--add-firewall-rule") return AllowPhoneSync(engine, true);
        string config = Path.Combine(root, "config");
        string profile = Path.Combine(root, "profile");
        if (!File.Exists(engine))
        {
            // First run from a fresh download: the engine is not shipped with Soot, it comes straight from Mozilla.
            var answer = MessageBox.Show("Soot needs its browser engine, which is not included in the download.\n\n" +
                "Download it now from Mozilla (about 90 MB)? It is unpacked into this folder and nothing is installed on the PC.",
                "Soot", MessageBoxButtons.YesNo, MessageBoxIcon.Question);
            if (answer != DialogResult.Yes) return 1;
            try
            {
                var setup = new ProcessStartInfo("powershell.exe", "-NoProfile -ExecutionPolicy Bypass -File \"" + Path.Combine(root, "setup.ps1") + "\"");
                setup.UseShellExecute = false; setup.WorkingDirectory = root;
                using (Process p = Process.Start(setup)) { p.WaitForExit(); }
            }
            catch (Exception e)
            {
                MessageBox.Show("Could not run setup.ps1:\n\n" + e.Message, "Soot", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return 1;
            }
            if (!File.Exists(engine))
            {
                MessageBox.Show("The engine download did not finish.\n\nRun setup.cmd in " + root + " to try again.", "Soot", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return 1;
            }
        }
        try
        {
            Directory.CreateDirectory(profile);
            // Policies: telemetry off, sponsored content off, ad blocker installed.
            CopyIfChanged(Path.Combine(config, "policies.json"), Path.Combine(engineDir, "distribution", "policies.json"));
            // Startup hook that loads Soot's own code (app\boot.sys.mjs) into the engine.
            CopyIfChanged(Path.Combine(config, "autoconfig", "soot-autoconfig.js"), Path.Combine(engineDir, "defaults", "pref", "soot-autoconfig.js"));
            CopyIfChanged(Path.Combine(config, "autoconfig", "soot.cfg"), Path.Combine(engineDir, "soot.cfg"));
            // Interface styles are refreshed on every start.
            string chromeSrc = Path.Combine(config, "chrome");
            if (Directory.Exists(chromeSrc))
                foreach (string f in Directory.GetFiles(chromeSrc))
                    CopyIfChanged(f, Path.Combine(profile, "chrome", Path.GetFileName(f)));
            // Default settings are applied once per config version, so later changes in Settings stick.
            string stamp = Path.Combine(profile, "soot-config-version");
            string userJs = Path.Combine(profile, "user.js");
            string have = File.Exists(stamp) ? File.ReadAllText(stamp).Trim() : "";
            string want = ConfigVersion + ":" + File.GetLastWriteTimeUtc(Path.Combine(config, "user.js")).Ticks;
            if (have != want)
            {
                File.Copy(Path.Combine(config, "user.js"), userJs, true);
                File.WriteAllText(stamp, want);
                // the engine caches interface code; drop the cache when Soot's own files change
                string cache = Path.Combine(profile, "startupCache");
                if (Directory.Exists(cache)) Directory.Delete(cache, true);
            }
            else if (File.Exists(userJs)) File.Delete(userJs);
            // Soot's own code is cached by the engine too; drop that cache when boot.sys.mjs or the manifest changes.
            long appTicks = 0;
            foreach (string n in new[] { "boot.sys.mjs", "chrome.manifest" })
            {
                string f = Path.Combine(root, "app", n);
                if (File.Exists(f)) appTicks += File.GetLastWriteTimeUtc(f).Ticks;
            }
            string appStamp = Path.Combine(profile, "soot-app-version");
            string haveApp = File.Exists(appStamp) ? File.ReadAllText(appStamp).Trim() : "";
            if (haveApp != appTicks.ToString())
            {
                string cache2 = Path.Combine(profile, "startupCache");
                if (Directory.Exists(cache2)) Directory.Delete(cache2, true);
                File.WriteAllText(appStamp, appTicks.ToString());
            }
        }
        catch (Exception e)
        {
            MessageBox.Show("Could not prepare the Soot profile:\n\n" + e.Message, "Soot", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
        try { EnsureShortcut(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "Soot.lnk"), Application.ExecutablePath, root); }
        catch { }
        // Windows identity: the engine looks its taskbar id up here by its own folder. Giving it Soot's id makes
        // Windows take the name and icon from the Soot Start menu entry for every window, jump list and pin request.
        try
        {
            using (var key = Microsoft.Win32.Registry.CurrentUser.CreateSubKey(@"Software\Mozilla\Firefox\TaskBarIDs"))
                key.SetValue(engineDir, AppId);
        }
        catch { }
        // The engine adds Start menu entries named after itself. Remove them, but only the ones that point into this folder.
        try
        {
            string programs = Environment.GetFolderPath(Environment.SpecialFolder.Programs);
            foreach (string name in new[] { "Firefox.lnk", "Firefox Private Browsing.lnk" })
            {
                string lnk = Path.Combine(programs, name);
                if (!File.Exists(lnk)) continue;
                string target = LinkTarget(lnk);
                if (target != null && target.StartsWith(engineDir + "\\", StringComparison.OrdinalIgnoreCase)) File.Delete(lnk);
            }
        }
        catch { }

        var sb = new StringBuilder();
        sb.Append("-profile \"").Append(profile).Append('"');
        foreach (string a in args)
        {
            if (a == "--private") { sb.Append(" -private-window"); continue; }
            sb.Append(' ').Append(Quote(a));
        }
        var psi = new ProcessStartInfo(engine, sb.ToString());
        psi.UseShellExecute = false;
        psi.WorkingDirectory = engineDir;
        psi.EnvironmentVariables["MOZ_CRASHREPORTER_DISABLE"] = "1";
        psi.EnvironmentVariables["SOOT_LAN_IPS"] = LanAddresses();
        // Vault password stretching: 600,000 rounds of PBKDF2-SHA256 instead of the engine's 10,000.
        psi.EnvironmentVariables["NSS_MIN_MP_PBE_ITERATION_COUNT"] = "600000";
        psi.EnvironmentVariables["NSS_MAX_MP_PBE_ITERATION_COUNT"] = "600000";
        try { Process.Start(psi); }
        catch (Exception e)
        {
            MessageBox.Show("Could not start the engine:\n\n" + e.Message, "Soot", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
        return 0;
    }

    // Reachable from the home network and from a Tailscale network (100.64.0.0/10), not from the open internet.
    const string RuleName = "Soot phone sync (anywhere)";
    const string OldRuleName = "Soot phone sync";

    static int Netsh(string arguments)
    {
        var p = new ProcessStartInfo("netsh.exe", arguments);
        p.UseShellExecute = false; p.CreateNoWindow = true; p.RedirectStandardOutput = true;
        using (Process proc = Process.Start(p)) { proc.StandardOutput.ReadToEnd(); proc.WaitForExit(); return proc.ExitCode; }
    }

    static int AllowPhoneSync(string engine, bool elevated)
    {
        try
        {
            if (!elevated)
            {
                if (Netsh("advfirewall firewall show rule name=\"" + RuleName + "\"") == 0) return 0;
                // not there yet: ask Windows for permission once (this is the "Do you want to allow Soot..." prompt)
                var up = new ProcessStartInfo(Application.ExecutablePath, "--add-firewall-rule");
                up.UseShellExecute = true; up.Verb = "runas";
                using (Process proc = Process.Start(up)) { proc.WaitForExit(); }
                return Netsh("advfirewall firewall show rule name=\"" + RuleName + "\"") == 0 ? 0 : 2;
            }
            // Elevated: one narrow opening (this engine, TCP 47600, private networks only), and remove any
            // rules Windows made by itself for this engine under the engine's name, which may be block rules.
            Netsh("advfirewall firewall delete rule name=\"Firefox\" program=\"" + engine + "\"");
            Netsh("advfirewall firewall delete rule name=\"" + OldRuleName + "\"");
            Netsh("advfirewall firewall delete rule name=\"" + RuleName + "\"");
            // Any network profile (Tailscale's adapter is often not "private"), but only from the local subnet
            // and Tailscale addresses. The phone still needs the pairing code and gets only ciphertext.
            return Netsh("advfirewall firewall add rule name=\"" + RuleName + "\" dir=in action=allow program=\"" + engine +
                         "\" protocol=TCP localport=47600 profile=any remoteip=LocalSubnet,100.64.0.0/10");
        }
        catch { return 3; }       // includes the user saying No to the permission prompt
    }

    // IPv4 addresses of this PC on networks that have a gateway (real Wi-Fi or Ethernet, not virtual adapters)
    static string LanAddresses()
    {
        var list = new System.Collections.Generic.List<string>();
        try
        {
            foreach (var ni in System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != System.Net.NetworkInformation.OperationalStatus.Up) continue;
                var props = ni.GetIPProperties();
                bool hasGateway = props.GatewayAddresses.Count > 0;
                foreach (var a in props.UnicastAddresses)
                {
                    if (a.Address.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork || System.Net.IPAddress.IsLoopback(a.Address)) continue;
                    var b = a.Address.GetAddressBytes();
                    bool tailscale = b[0] == 100 && b[1] >= 64 && b[1] <= 127;   // 100.64.0.0/10, works away from home
                    if (hasGateway || tailscale) list.Add(a.Address.ToString());
                }
            }
        }
        catch { }
        return string.Join(",", list.ToArray());
    }

    static void CopyIfChanged(string src, string dst)
    {
        if (!File.Exists(src)) return;
        Directory.CreateDirectory(Path.GetDirectoryName(dst));
        if (File.Exists(dst) && File.GetLastWriteTimeUtc(dst) == File.GetLastWriteTimeUtc(src) && new FileInfo(dst).Length == new FileInfo(src).Length) return;
        File.Copy(src, dst, true);
        File.SetLastWriteTimeUtc(dst, File.GetLastWriteTimeUtc(src));
    }

    static string Quote(string a)
    {
        if (a.Length > 0 && a.IndexOfAny(new[] { ' ', '\t', '"' }) < 0) return a;
        return "\"" + a.Replace("\\\"", "\\\\\"").Replace("\"", "\\\"") + "\"";
    }

    // ---- Start menu shortcut carrying Soot's taskbar identity, so pinned and running windows share one Soot icon ----

    [ComImport, Guid("00021401-0000-0000-C000-000000000046")]
    class CShellLink { }

    [ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("000214F9-0000-0000-C000-000000000046")]
    interface IShellLinkW
    {
        void GetPath([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder f, int cch, IntPtr pfd, uint flags);
        void GetIDList(out IntPtr ppidl);
        void SetIDList(IntPtr pidl);
        void GetDescription([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder s, int cch);
        void SetDescription([MarshalAs(UnmanagedType.LPWStr)] string s);
        void GetWorkingDirectory([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder s, int cch);
        void SetWorkingDirectory([MarshalAs(UnmanagedType.LPWStr)] string s);
        void GetArguments([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder s, int cch);
        void SetArguments([MarshalAs(UnmanagedType.LPWStr)] string s);
        void GetHotkey(out short w);
        void SetHotkey(short w);
        void GetShowCmd(out int i);
        void SetShowCmd(int i);
        void GetIconLocation([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder s, int cch, out int icon);
        void SetIconLocation([MarshalAs(UnmanagedType.LPWStr)] string s, int icon);
        void SetRelativePath([MarshalAs(UnmanagedType.LPWStr)] string s, uint reserved);
        void Resolve(IntPtr hwnd, uint flags);
        void SetPath([MarshalAs(UnmanagedType.LPWStr)] string s);
    }

    [StructLayout(LayoutKind.Sequential, Pack = 4)]
    struct PROPERTYKEY { public Guid fmtid; public uint pid; }

    [StructLayout(LayoutKind.Explicit)]
    struct PROPVARIANT
    {
        [FieldOffset(0)] public ushort vt;
        [FieldOffset(8)] public IntPtr p;
        [FieldOffset(16)] public IntPtr pad;
    }

    [ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99")]
    interface IPropertyStore
    {
        void GetCount(out uint c);
        void GetAt(uint i, out PROPERTYKEY key);
        void GetValue(ref PROPERTYKEY key, out PROPVARIANT v);
        void SetValue(ref PROPERTYKEY key, ref PROPVARIANT v);
        void Commit();
    }

    static string LinkTarget(string lnk)
    {
        var link = (IShellLinkW)new CShellLink();
        ((IPersistFile)link).Load(lnk, 0);
        var sb = new StringBuilder(520);
        link.GetPath(sb, sb.Capacity, IntPtr.Zero, 0);
        return sb.Length > 0 ? sb.ToString() : null;
    }

    static void EnsureShortcut(string lnk, string target, string workDir)
    {
        if (File.Exists(lnk) && File.GetLastWriteTimeUtc(lnk) >= File.GetLastWriteTimeUtc(target)) return;
        var link = (IShellLinkW)new CShellLink();
        link.SetPath(target);
        link.SetWorkingDirectory(workDir);
        link.SetDescription("Soot browser");
        link.SetIconLocation(target, 0);
        var store = (IPropertyStore)link;
        var key = new PROPERTYKEY { fmtid = new Guid("9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3"), pid = 5 };   // System.AppUserModel.ID
        var pv = new PROPVARIANT { vt = 31, p = Marshal.StringToCoTaskMemUni(AppId) };                       // VT_LPWSTR
        try { store.SetValue(ref key, ref pv); store.Commit(); }
        finally { Marshal.FreeCoTaskMem(pv.p); }
        ((IPersistFile)link).Save(lnk, true);
    }
}
