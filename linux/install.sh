#!/bin/bash
# Installs Soot on Linux (x86_64). Non-interactive; needs no display.
#
#   sudo bash linux/install.sh                      # /usr/lib/soot, /usr/bin/soot, /usr/share
#   bash linux/install.sh --prefix "$HOME/soot" --bindir "$HOME/.local/bin" --datadir "$HOME/.local/share"
#
# It downloads the Firefox engine from Mozilla, checks it against Mozilla's SHA256SUMS, and lays out
# Soot's policies, startup hook, interface code and launcher around it, the way setup.ps1 and Soot.exe
# do on Windows. The engine's self-updater is switched off: install again (or rebuild the image) to update.
set -euo pipefail

prefix=/usr/lib/soot
bindir=/usr/bin
datadir=/usr/share
version=latest
pregen=auto        # auto: try, warn on failure. require: fail the install. skip: do not try.

usage() {
  sed -n '2,9p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  cat <<'EOF'

Options:
  --prefix DIR            where the browser goes (default /usr/lib/soot)
  --bindir DIR            where the "soot" command goes (default /usr/bin)
  --datadir DIR           where the menu entry and icons go (default /usr/share)
  --engine-version VER    a specific Firefox release, e.g. 160.0 (default: the latest release)
  --require-pregen        fail if the engine's texts cannot be renamed at install time
  --skip-pregen           do not start the engine during the install
EOF
}

die() { echo "install.sh: $*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case $1 in
    --prefix) prefix=${2:?}; shift 2 ;;
    --prefix=*) prefix=${1#*=}; shift ;;
    --bindir) bindir=${2:?}; shift 2 ;;
    --bindir=*) bindir=${1#*=}; shift ;;
    --datadir) datadir=${2:?}; shift 2 ;;
    --datadir=*) datadir=${1#*=}; shift ;;
    --engine-version) version=${2:?}; shift 2 ;;
    --engine-version=*) version=${1#*=}; shift ;;
    --require-pregen) pregen=require; shift ;;
    --skip-pregen) pregen=skip; shift ;;
    -h|--help) usage; exit 0 ;;
    *) die "unknown option: $1 (see --help)" ;;
  esac
done

case $prefix in /*) ;; *) die "--prefix must be an absolute path" ;; esac
case $prefix in /|/usr|/usr/lib|/usr/lib64|/opt|/home|"${HOME:-/root}") die "--prefix $prefix is not a folder of its own" ;; esac
[ "$(uname -m)" = x86_64 ] || die "only x86_64 is supported for now (this is $(uname -m))"
for tool in curl tar xz sha256sum sed install cmp; do
  command -v "$tool" >/dev/null 2>&1 || die "'$tool' is needed and was not found"
done

src=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
for need in app/boot.sys.mjs app/chrome.manifest config/policies.json config/user.js \
            config/autoconfig/soot.cfg config/autoconfig/soot-autoconfig.js linux/soot linux/dev.wolklaw.Soot.desktop; do
  [ -f "$src/$need" ] || die "$need is missing; run this script from a full copy of the Soot source"
done

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
umask 022

# ---- engine: download from Mozilla and verify ----

if [ "$version" = latest ]; then
  url=$(curl -fsSIL --retry 3 -o /dev/null -w '%{url_effective}' \
        'https://download.mozilla.org/?product=firefox-latest-ssl&os=linux64&lang=en-US')
  version=$(sed -nE 's#.*/releases/([^/]+)/.*#\1#p' <<<"$url")
fi
[[ $version =~ ^[0-9]+(\.[0-9A-Za-z]+)*$ ]] || die "could not work out the engine version (got '$version')"

base=https://archive.mozilla.org/pub/firefox/releases/$version
file=linux-x86_64/en-US/firefox-$version.tar.xz
echo "Downloading the engine, Firefox $version, from archive.mozilla.org (about 80 MB)..."
curl -fsSL --retry 3 -o "$tmp/SHA256SUMS" "$base/SHA256SUMS"
curl -fsSL --retry 3 -o "$tmp/engine.tar.xz" "$base/$file"
want=$(sed -nE "s|^([0-9a-f]{64})  ${file//./\\.}\$|\\1|p" "$tmp/SHA256SUMS")
got=$(sha256sum "$tmp/engine.tar.xz" | cut -d' ' -f1)
[ -n "$want" ] || die "Mozilla's SHA256SUMS has no entry for $file"
[ "$want" = "$got" ] || die "the download does not match Mozilla's SHA256SUMS (want $want, got $got). Stopping."
echo "Checksum matches Mozilla's SHA256SUMS."

echo "Unpacking..."
tar -xJf "$tmp/engine.tar.xz" -C "$tmp"
[ -f "$tmp/firefox/firefox" ] || die "unpacking failed"

# ---- lay out: engine/, app/, config/ and the launcher in one folder ----

mkdir -p "$prefix"
rm -rf "$prefix/engine" "$prefix/app" "$prefix/config"
mv "$tmp/firefox" "$prefix/engine"
engine=$prefix/engine

mkdir -p "$prefix/app" "$prefix/config"
cp -R "$src/app/." "$prefix/app/"
rm -rf "$prefix/app/l10n-auto"                     # built for another engine; made again below
cp -R "$src/config/chrome" "$prefix/config/chrome"
install -m 644 "$src/config/user.js" "$prefix/config/user.js"
install -m 755 "$src/linux/soot" "$prefix/soot"

# Policies: the Windows set, plus the engine's self-updater off. Add-on and Safe Browsing updates are untouched.
mkdir -p "$engine/distribution" "$engine/defaults/pref"
sed '0,/"policies": {/s//"policies": {\n    "DisableAppUpdate": true,/' "$src/config/policies.json" > "$engine/distribution/policies.json"
grep -q '"DisableAppUpdate": true' "$engine/distribution/policies.json" || die "could not add DisableAppUpdate to policies.json"
# Startup hook that loads Soot's own code (app/boot.sys.mjs) into the engine.
install -m 644 "$src/config/autoconfig/soot-autoconfig.js" "$engine/defaults/pref/soot-autoconfig.js"
install -m 644 "$src/config/autoconfig/soot.cfg" "$engine/soot.cfg"
# Window icons the engine hands to the desktop.
for size in 16 32 48 64 128; do
  icon=$src/app/content/brand/icon$size.png
  [ -f "$icon" ] || continue
  [ -d "$engine/browser/chrome/icons/default" ] && install -m 644 "$icon" "$engine/browser/chrome/icons/default/default$size.png"
  install -D -m 644 "$icon" "$datadir/icons/hicolor/${size}x${size}/apps/dev.wolklaw.Soot.png"
done

mkdir -p "$bindir" "$datadir/applications"
ln -sfn "$prefix/soot" "$bindir/soot"
sed "s|@BINDIR@|$bindir|g" "$src/linux/dev.wolklaw.Soot.desktop" > "$datadir/applications/dev.wolklaw.Soot.desktop"
chmod 644 "$datadir/applications/dev.wolklaw.Soot.desktop"

# ---- rename the engine's own texts now, so the first start already says Soot everywhere ----
# The engine runs once, headless, on a throwaway profile; app/boot.sys.mjs writes app/l10n-auto and quits.
# If the engine cannot start here (a container without GTK), each user's first start does it instead.

if [ "$pregen" != skip ]; then
  mkdir -p "$tmp/home" "$tmp/profile"
  HOME=$tmp/home XDG_RUNTIME_DIR=$tmp/home MOZ_CRASHREPORTER_DISABLE=1 SOOT_PREGEN=1 \
    timeout 180 "$engine/firefox" --headless --no-remote -profile "$tmp/profile" >"$tmp/pregen.log" 2>&1 || true
  if [ -f "$prefix/app/l10n-auto/build.txt" ]; then
    echo "Engine texts renamed ($(find "$prefix/app/l10n-auto" -type f | wc -l) files)."
  else
    sed 's/^/  engine: /' "$tmp/pregen.log" | tail -n 15 >&2
    [ "$pregen" = require ] && die "the engine could not run here to rename its texts (--require-pregen)"
    rm -rf "$prefix/app/l10n-auto"
    echo "Note: the engine could not run here, so its texts are renamed on each user's first start instead." >&2
  fi
fi

chmod -R u+rwX,go+rX,go-w "$prefix"
command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database -q "$datadir/applications" 2>/dev/null || true
command -v gtk-update-icon-cache >/dev/null 2>&1 && gtk-update-icon-cache -q -t -f "$datadir/icons/hicolor" 2>/dev/null || true

if [ -e /etc/firefox/policies/policies.json ]; then
  echo "Warning: /etc/firefox/policies/policies.json exists. The engine reads that file instead of Soot's" >&2
  echo "         policies, so the ad blocker and the updater switch may not apply. Remove it for Soot." >&2
fi

echo
echo "Soot is installed (engine: Firefox $version)."
echo "  $prefix/            engine/, app/, config/, soot"
echo "  $bindir/soot -> $prefix/soot"
echo "  $datadir/applications/dev.wolklaw.Soot.desktop"
echo "  $datadir/icons/hicolor/{16x16,32x32,48x48,64x64,128x128}/apps/dev.wolklaw.Soot.png"
echo "User data goes to ~/.local/share/soot. To remove Soot, delete the paths above."
