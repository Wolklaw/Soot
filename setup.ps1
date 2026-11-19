# Downloads the Firefox engine from Mozilla and unpacks it into .\engine. Nothing is installed system-wide.
# Run this only if the engine folder is missing, or to start again from a clean engine.
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$engine = Join-Path $root 'engine'
$tmp = Join-Path $env:TEMP ("soot-setup-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $tmp | Out-Null
try {
    $installer = Join-Path $tmp 'FirefoxSetup.exe'
    Write-Host 'Downloading the engine from download.mozilla.org (about 90 MB)...'
    $ProgressPreference = 'SilentlyContinue'
    Invoke-WebRequest -UseBasicParsing 'https://download.mozilla.org/?product=firefox-latest-ssl&os=win64&lang=en-US' -OutFile $installer
    $sig = Get-AuthenticodeSignature $installer
    if ($sig.Status -ne 'Valid' -or $sig.SignerCertificate.Subject -notmatch 'O=Mozilla Corporation') {
        throw "The download is not signed by Mozilla (status: $($sig.Status)). Stopping."
    }
    $unpack = Join-Path $tmp 'unpacked'
    Write-Host 'Unpacking...'
    Start-Process -FilePath $installer -ArgumentList "/ExtractDir=$unpack" -Wait
    if (-not (Test-Path (Join-Path $unpack 'core\firefox.exe'))) { throw 'Unpacking failed.' }
    if (Test-Path $engine) { Remove-Item $engine -Recurse -Force }
    Move-Item (Join-Path $unpack 'core') $engine
    Write-Host ("Engine ready: " + (Get-Item (Join-Path $engine 'firefox.exe')).VersionInfo.ProductVersion)
}
finally {
    Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
}
