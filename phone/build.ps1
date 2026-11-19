# Builds Soot for Android. The app is plain Java plus Mozilla's GeckoView engine, put together by Gradle.
# The first run downloads Gradle, the Android build plugin and the engine (about 1 GB, kept in ~\.gradle).
#
#   phone\build.ps1                  phone\Soot.apk, for phones (arm64-v8a)
#   phone\build.ps1 -Abi x86_64      phone\build\Soot-x86_64.apk, for emulators
#   -NoSecure                        a copy that allows screenshots of the vault and private tabs, for checking
#                                    the look on an emulator. Never install that one on a real phone.
param(
    [switch]$NoSecure,
    [ValidateSet('arm64-v8a', 'x86_64', 'armeabi-v7a')][string]$Abi = 'arm64-v8a'
)
$ErrorActionPreference = 'Continue'
$here = $PSScriptRoot
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk" }
if (-not (Test-Path "$env:ANDROID_HOME\platforms")) { throw "Android SDK not found at $env:ANDROID_HOME. Install it or set ANDROID_HOME." }

# launcher icon: Soot's mark, from the same script that draws the desktop icon
if (-not (Test-Path "$here\res\mipmap-xxhdpi\ic_launcher.png")) {
    New-Item -ItemType Directory -Force "$here\res\mipmap-xxhdpi" | Out-Null
    Copy-Item "$here\..\app\content\brand\about-logo.png" "$here\res\mipmap-xxhdpi\ic_launcher.png"
}

# signing key: made once and kept. Updates to the app must be signed with the same key, so back these two files up.
$ks = "$here\soot.keystore"; $pwFile = "$here\soot.keystore.password.txt"
if (-not (Test-Path $ks)) {
    $pw = -join ((48..57) + (97..122) | Get-Random -Count 24 | ForEach-Object { [char]$_ })
    Set-Content $pwFile $pw -Encoding ascii -NoNewline
    & keytool -genkeypair -keystore $ks -alias soot -keyalg RSA -keysize 2048 -validity 10000 -storepass $pw -keypass $pw -dname 'CN=Soot' 2>$null
}
$env:SOOT_KEYSTORE_PASSWORD = (Get-Content $pwFile -Raw).Trim()      # passed in the environment, not on the command line

$secure = if ($NoSecure) { 'false' } else { 'true' }
& "$here\gradlew.bat" -p $here --console=plain assembleRelease "-Pabi=$Abi" "-Psecure=$secure" 2>&1 | ForEach-Object { "$_" }
$failed = $LASTEXITCODE
$env:SOOT_KEYSTORE_PASSWORD = $null
if ($failed) { throw 'the Gradle build failed' }

$built = "$here\build\outputs\apk\release\phone-release.apk"
if (-not (Test-Path $built)) { $built = (Get-ChildItem "$here\build\outputs\apk\release\*.apk" | Select-Object -First 1).FullName }
$apk = if ($NoSecure) { "$here\build\Soot-$Abi-screenshots-allowed.apk" }
       elseif ($Abi -eq 'arm64-v8a') { "$here\Soot.apk" }
       else { "$here\build\Soot-$Abi.apk" }
Copy-Item $built $apk -Force
"built $apk ({0:N1} MB)" -f ((Get-Item $apk).Length / 1MB)
