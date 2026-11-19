# Builds phone\Soot.apk with the Android SDK's own tools. No Gradle, no libraries: the app uses only what Android ships.
# -NoSecure builds a copy that allows screenshots, for checking the look on an emulator. Never install that one on a real phone.
param([switch]$NoSecure)
$ErrorActionPreference = 'Continue'
$here = $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$bt = (Get-ChildItem "$sdk\build-tools" -Directory | Sort-Object Name | Select-Object -Last 1).FullName
$jar = (Get-ChildItem "$sdk\platforms" -Directory | Sort-Object Name | Select-Object -Last 1).FullName + '\android.jar'
$out = "$here\build"; if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force "$out\gen", "$out\classes", "$out\dex", "$out\src" | Out-Null

# launcher icon: Soot's mark, from the same script that draws the desktop icon
if (-not (Test-Path "$here\res\mipmap-xxhdpi\ic_launcher.png")) {
    New-Item -ItemType Directory -Force "$here\res\mipmap-xxhdpi" | Out-Null
    Copy-Item "$here\..\app\content\brand\about-logo.png" "$here\res\mipmap-xxhdpi\ic_launcher.png"
}

Copy-Item "$here\src\*" "$out\src" -Recurse
if ($NoSecure) {
    $f = "$out\src\dev\wolklaw\soot\MainActivity.java"
    [IO.File]::WriteAllText($f, [IO.File]::ReadAllText($f).Replace('getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);', ''))
}

& "$bt\aapt2.exe" compile --dir "$here\res" -o "$out\res.zip"; if ($LASTEXITCODE) { throw 'aapt2 compile failed' }
& "$bt\aapt2.exe" link -o "$out\base.apk" -I $jar --manifest "$here\AndroidManifest.xml" --java "$out\gen" `
    --min-sdk-version 30 --target-sdk-version 36 --version-code 1 --version-name 0.1 "$out\res.zip"; if ($LASTEXITCODE) { throw 'aapt2 link failed' }

$sources = Get-ChildItem "$out\src", "$out\gen" -Recurse -Filter *.java | ForEach-Object FullName
& javac -nowarn -encoding UTF-8 --release 11 -cp $jar -d "$out\classes" $sources; if ($LASTEXITCODE) { throw 'javac failed' }
$classes = Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object FullName
& "$bt\d8.bat" --lib $jar --min-api 30 --output "$out\dex" $classes; if ($LASTEXITCODE) { throw 'd8 failed' }

Push-Location "$out\dex"; & jar uf "$out\base.apk" classes.dex; Pop-Location; if ($LASTEXITCODE) { throw 'adding classes.dex failed' }
& "$bt\zipalign.exe" -f 4 "$out\base.apk" "$out\aligned.apk"; if ($LASTEXITCODE) { throw 'zipalign failed' }

# signing key: made once and kept. Updates to the app must be signed with the same key, so back these two files up.
$ks = "$here\soot.keystore"; $pwFile = "$here\soot.keystore.password.txt"
if (-not (Test-Path $ks)) {
    $pw = -join ((48..57) + (97..122) | Get-Random -Count 24 | ForEach-Object { [char]$_ })
    Set-Content $pwFile $pw -Encoding ascii -NoNewline
    & keytool -genkeypair -keystore $ks -alias soot -keyalg RSA -keysize 2048 -validity 10000 -storepass $pw -keypass $pw -dname 'CN=Soot' 2>$null
}
$pw = (Get-Content $pwFile -Raw).Trim()
$apk = if ($NoSecure) { "$out\Soot-screenshots-allowed.apk" } else { "$here\Soot.apk" }
& "$bt\apksigner.bat" sign --ks $ks --ks-key-alias soot --ks-pass "pass:$pw" --key-pass "pass:$pw" --out $apk "$out\aligned.apk"; if ($LASTEXITCODE) { throw 'signing failed' }
"built $apk ({0:N0} KB)" -f ((Get-Item $apk).Length / 1KB)
