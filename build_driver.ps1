$vcvars = "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"
$kinectInc = "C:\Program Files\Microsoft SDKs\Kinect\v1.8\inc"
$kinectLib = "C:\Program Files\Microsoft SDKs\Kinect\v1.8\lib\amd64"
$openvrInc = "Z:\NETtrash\Миша\VRFusion\driver_vrfusion\openvr"
$src = "Z:\NETtrash\Миша\VRFusion\driver_vrfusion\src\driver_vrfusion.cpp"
$outDir = "Z:\NETtrash\Миша\VRFusion\driver_vrfusion\bin\win64"
$steamvrDir = "C:\Program Files (x86)\Steam\steamapps\common\SteamVR\drivers\vrfusion\bin\win64"

if (!(Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir -Force | Out-Null }
if (!(Test-Path $steamvrDir)) { New-Item -ItemType Directory -Path $steamvrDir -Force | Out-Null }

$cmd = "call `"$vcvars`" && cl.exe /LD /O2 /EHsc /std:c++17 /I`"$openvrInc`" /I`"$kinectInc`" `"$src`" /link /LIBPATH:`"$kinectLib`" ws2_32.lib winmm.lib xinput.lib Kinect10.lib /OUT:`"$outDir\driver_vrfusion.dll`""

cmd.exe /c $cmd
if ($LASTEXITCODE -eq 0) {
    Write-Host "Driver compiled successfully! Copying to SteamVR..." -ForegroundColor Green
    Copy-Item "$outDir\driver_vrfusion.dll" "$steamvrDir\driver_vrfusion.dll" -Force
    Write-Host "Copied to SteamVR drivers\vrfusion\bin\win64\driver_vrfusion.dll" -ForegroundColor Green
} else {
    Write-Host "Driver compilation FAILED with code $LASTEXITCODE" -ForegroundColor Red
}
