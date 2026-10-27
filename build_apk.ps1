$ErrorActionPreference = "Stop"

$scriptDir = $PSScriptRoot
# Mount virtual drive X: purely during build to satisfy AAPT2 ASCII requirements
subst X: /d 2>$null
subst X: $scriptDir

try {
    $root = "X:"
    $javac = "C:\Program Files\Microsoft\jdk-25.0.2.10-hotspot\bin\javac.exe"
    $jar = "C:\Program Files\Microsoft\jdk-25.0.2.10-hotspot\bin\jar.exe"
    $buildTools = "$root\sdk\build-tools\34.0.0"
    $platform = "$root\sdk\platforms\android-34"
    $aapt2 = "$buildTools\aapt2.exe"
    $d8 = "$buildTools\d8.bat"
    $zipalign = "$buildTools\zipalign.exe"
    $apksigner = "$buildTools\apksigner.bat"
    $adb = "$root\platform-tools\adb.exe"

    Write-Host ">>> 1. Compiling Java sources..." -ForegroundColor Cyan
    $classesDir = "$root\bin\classes"
    if (Test-Path $classesDir) { Remove-Item -Path $classesDir -Recurse -Force }
    New-Item -ItemType Directory -Path $classesDir | Out-Null

    $srcDir = "$root\app_src\com\vrfusion\tracker"
    $javaFiles = Get-ChildItem -Path "$srcDir\*.java" | Select-Object -ExpandProperty FullName
    & $javac -g:none --release 8 -cp "$platform\android.jar" -d $classesDir $javaFiles

    Write-Host ">>> 2. Running D8 to create classes.dex..." -ForegroundColor Cyan
    $dexDir = "$root\bin\dex"
    if (Test-Path $dexDir) { Remove-Item -Path $dexDir -Recurse -Force }
    New-Item -ItemType Directory -Path $dexDir | Out-Null

    $classFiles = Get-ChildItem -Path $classesDir -Recurse -Filter "*.class" | Select-Object -ExpandProperty FullName
    & $d8 --lib "$platform\android.jar" --output $dexDir $classFiles

    Write-Host ">>> 3. Compiling and linking resources with AAPT2..." -ForegroundColor Cyan
    $resZip = "$root\bin\res.zip"
    if (Test-Path $resZip) { Remove-Item $resZip -Force }

    if (Test-Path "$root\app_src\res") {
        & $aapt2 compile --dir "$root\app_src\res" -o $resZip
    }

    $unalignedApk = "$root\bin\VRFusion.unaligned.apk"
    if (Test-Path $unalignedApk) { Remove-Item $unalignedApk -Force }

    if (Test-Path $resZip) {
        & $aapt2 link -I "$platform\android.jar" $resZip --manifest "$root\app_src\AndroidManifest.xml" -o $unalignedApk --auto-add-overlay
    } else {
        & $aapt2 link -I "$platform\android.jar" --manifest "$root\app_src\AndroidManifest.xml" -o $unalignedApk
    }

    Write-Host ">>> 4. Adding classes.dex to APK..." -ForegroundColor Cyan
    Push-Location $dexDir
    & $jar -uf $unalignedApk classes.dex
    Pop-Location

    Write-Host ">>> 5. Zipaligning APK..." -ForegroundColor Cyan
    $alignedApk = "$root\bin\VRFusion.aligned.apk"
    if (Test-Path $alignedApk) { Remove-Item $alignedApk -Force }
    & $zipalign -p -f -v 4 $unalignedApk $alignedApk | Out-Null

    Write-Host ">>> 6. Signing APK with debug.keystore..." -ForegroundColor Cyan
    $finalApk = "$root\VRFusion.apk"
    if (Test-Path $finalApk) { Remove-Item $finalApk -Force }
    Copy-Item $alignedApk $finalApk

    & $apksigner sign --ks "$root\debug.keystore" --ks-pass pass:android --key-pass pass:android $finalApk

    Write-Host ">>> 7. Installing to Samsung S21 FE..." -ForegroundColor Green
    $devs = @(& $adb devices | Where-Object { $_ -match "\bdevice\b" -and $_ -notmatch "List of" -and $_ -notmatch "192\.168\.31\.124" })
    $devSerial = if ($devs.Count -gt 0) { ($devs[0].Trim() -split '\s+')[0] } else { 'R5CRC476JPV' }
    Write-Host "Target ADB device: $devSerial" -ForegroundColor Cyan
    & $adb -s $devSerial install -r --user 0 $finalApk
    & $adb -s $devSerial shell "pm uninstall --user 95 com.vrfusion.tracker 2>/dev/null"

    Write-Host ">>> 8. Restarting VRFusion tracker app on phone..." -ForegroundColor Green
    & $adb -s $devSerial shell "am force-stop com.vrfusion.tracker; am start -n com.vrfusion.tracker/.MainActivity"

    Write-Host ">>> VRFusion.apk BUILD AND DEPLOY SUCCESSFUL! <<<" -ForegroundColor Green
}
finally {
    # Clean up virtual drive X: automatically
    subst X: /d 2>$null
}
