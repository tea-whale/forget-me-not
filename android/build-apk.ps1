# Builds the WebView APK with the Android SDK build-tools only (no Gradle, no network).
$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
$Project = Join-Path $Root 'android'
$Build = Join-Path $Project 'build'

$Jdk = if ($env:APP_JDK) { $env:APP_JDK } else { 'C:\Users\Windows\.jdks\ms-17.0.19' }
$Sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'D:\Android\Sdk' }
$BuildToolsVersion = '35.0.0'
$Platform = 'android-35'

$BuildTools = Join-Path $Sdk "build-tools\$BuildToolsVersion"
$AndroidJar = Join-Path $Sdk "platforms\$Platform\android.jar"
$Keystore = [IO.Path]::GetFullPath((Join-Path $Sdk '..\debug.keystore'))
$VersionName = '1.1.4'
$VersionCode = '6'
$MinSdk = '26'
$TargetSdk = '34'

foreach ($tool in @('aapt2.exe', 'zipalign.exe', 'apksigner.bat')) {
  if (-not (Test-Path (Join-Path $BuildTools $tool))) { throw "Missing $tool in $BuildTools" }
}
if (-not (Test-Path $AndroidJar)) { throw "Missing android.jar: $AndroidJar" }
if (-not (Test-Path (Join-Path $Jdk 'bin\javac.exe'))) { throw "Missing JDK: $Jdk" }
if (-not (Test-Path $Keystore)) { throw "Missing debug keystore: $Keystore" }

$env:JAVA_HOME = $Jdk
$env:Path = "$Jdk\bin;$BuildTools;$env:Path"

function Invoke-Step([string]$Name, [scriptblock]$Action) {
  Write-Host "==> $Name"
  & $Action
  if ($LASTEXITCODE -ne 0) { throw "$Name failed with exit code $LASTEXITCODE" }
}

# Fresh output and copied web assets.
if (Test-Path $Build) { Remove-Item -LiteralPath $Build -Recurse -Force }
New-Item -ItemType Directory -Path $Build | Out-Null
$AssetsApp = Join-Path $Project 'assets\app'
if (Test-Path (Join-Path $Project 'assets')) {
  Remove-Item -LiteralPath (Join-Path $Project 'assets') -Recurse -Force
}
New-Item -ItemType Directory -Path $AssetsApp -Force | Out-Null
Copy-Item -Path (Join-Path $Root 'dist\*') -Destination $AssetsApp -Recurse -Force
Write-Host "==> Copied web assets ($((Get-ChildItem $AssetsApp -Recurse -File | Measure-Object).Count) files)"

Invoke-Step 'Compile resources (aapt2 compile)' {
  & aapt2 compile --dir (Join-Path $Project 'res') -o (Join-Path $Build 'res.zip')
}

Invoke-Step 'Link resources (aapt2 link)' {
  & aapt2 link -o (Join-Path $Build 'base.apk') `
    -I $AndroidJar `
    --manifest (Join-Path $Project 'AndroidManifest.xml') `
    --java (Join-Path $Build 'gen') `
    --min-sdk-version $MinSdk `
    --target-sdk-version $TargetSdk `
    --version-code $VersionCode `
    --version-name $VersionName `
    --debug-mode `
    (Join-Path $Build 'res.zip')
}

$GenJava = Get-ChildItem (Join-Path $Build 'gen') -Recurse -Filter *.java | ForEach-Object FullName
$AppJava = Get-ChildItem (Join-Path $Project 'src') -Recurse -Filter *.java | ForEach-Object FullName
$Sources = @($GenJava) + @($AppJava)

Invoke-Step 'Compile Java (javac)' {
  New-Item -ItemType Directory -Path (Join-Path $Build 'classes') -Force | Out-Null
  & javac -encoding UTF-8 -source 11 -target 11 -nowarn `
    -classpath $AndroidJar `
    -d (Join-Path $Build 'classes') `
    $Sources
}

Invoke-Step 'Package classes (jar)' {
  & jar --create --file (Join-Path $Build 'classes.jar') -C (Join-Path $Build 'classes') .
}

Invoke-Step 'Dex (d8)' {
  New-Item -ItemType Directory -Path (Join-Path $Build 'dex') -Force | Out-Null
  & d8.bat --release --min-api $MinSdk --lib $AndroidJar `
    --output (Join-Path $Build 'dex') `
    (Join-Path $Build 'classes.jar')
}

# Add web assets and classes.dex with standard forward-slash entry names.
# (aapt2 on Windows would otherwise store asset paths with backslashes, which the
# Android AssetManager cannot resolve on device.)
$BaseApk = Join-Path $Build 'base.apk'
$SignedApk = Join-Path $Build 'app-unsigned.apk'
Copy-Item $BaseApk $SignedApk -Force
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$AssetsRoot = (Join-Path $Project 'assets').TrimEnd('\')
$zip = [System.IO.Compression.ZipFile]::Open($SignedApk, [System.IO.Compression.ZipArchiveMode]::Update)
try {
  Get-ChildItem (Join-Path $Project 'assets') -Recurse -File | ForEach-Object {
    $relative = $_.FullName.Substring($AssetsRoot.Length + 1).Replace('\', '/')
    $entry = $zip.CreateEntry("assets/$relative", [System.IO.Compression.CompressionLevel]::Optimal)
    $stream = $entry.Open()
    try {
      $bytes = [System.IO.File]::ReadAllBytes($_.FullName)
      $stream.Write($bytes, 0, $bytes.Length)
    } finally {
      $stream.Dispose()
    }
  }
  $entry = $zip.CreateEntry('classes.dex', [System.IO.Compression.CompressionLevel]::NoCompression)
  $stream = $entry.Open()
  try {
    $bytes = [System.IO.File]::ReadAllBytes((Join-Path $Build 'dex\classes.dex'))
    $stream.Write($bytes, 0, $bytes.Length)
  } finally {
    $stream.Dispose()
  }
} finally {
  $zip.Dispose()
}

# Fail fast if any entry uses a backslash or the entry point is missing.
$check = [System.IO.Compression.ZipFile]::OpenRead($SignedApk)
try {
  $bad = $check.Entries | Where-Object { $_.FullName.Contains('\') }
  if ($bad) { throw "APK contains invalid entry names: $($bad[0].FullName)" }
  if (-not ($check.Entries | Where-Object { $_.FullName -eq 'assets/app/index.html' })) {
    throw 'APK is missing assets/app/index.html'
  }
  Write-Host "==> Packed $($check.Entries.Count) entries (assets + dex)"
} finally {
  $check.Dispose()
}

Invoke-Step 'Align (zipalign)' {
  & zipalign.exe -f -p 4 (Join-Path $Build 'app-unsigned.apk') (Join-Path $Build 'app-aligned.apk')
}

$FinalApk = Join-Path $Build "ForgetMeNot-$VersionName.apk"
Invoke-Step 'Sign (apksigner)' {
  & apksigner.bat sign `
    --ks $Keystore `
    --ks-pass pass:android `
    --ks-key-alias androiddebugkey `
    --key-pass pass:android `
    --v1-signing-enabled true `
    --v2-signing-enabled true `
    --out $FinalApk `
    (Join-Path $Build 'app-aligned.apk')
}

Invoke-Step 'Verify signature (apksigner)' {
  & apksigner.bat verify --print-certs $FinalApk
}

Write-Host '==> Package info'
& aapt2 dump badging $FinalApk | Select-String -Pattern "^package:|^application-label:|^sdkVersion:|^targetSdkVersion:|^launchable-activity:"

$size = (Get-Item $FinalApk).Length / 1MB
Write-Host ("==> Built: {0} ({1:N1} MB)" -f $FinalApk, $size)
Write-Output $FinalApk
