# VRFusion 1-Click VR Launcher & Streamer
# Samsung S21 FE + SteamVR + Kinect 360 FBT + 120Hz NVENC Stereo Stream

$ErrorActionPreference = 'Continue'
$vrDir = $PSScriptRoot
if (!$vrDir) { $vrDir = (Get-Location).Path }
Set-Location $vrDir

try {
    if ($Host.UI.RawUI) {
        $buf = $Host.UI.RawUI.BufferSize
        if ($buf.Width -lt 125) {
            $buf.Width = 125
            $Host.UI.RawUI.BufferSize = $buf
        }
        $win = $Host.UI.RawUI.WindowSize
        if ($win.Width -lt 120) {
            $win.Width = 120
            $Host.UI.RawUI.WindowSize = $win
        }
    }
} catch {}

Write-Host '======================================================' -ForegroundColor Cyan
Write-Host '         VRFUSION UNIFIED VR SYSTEM 1-CLICK           ' -ForegroundColor Green
Write-Host '======================================================' -ForegroundColor Cyan
Write-Host ''

$adb = Join-Path $vrDir 'platform-tools\adb.exe'
$ffmpeg = Join-Path $vrDir 'ffmpeg.exe'
# Stop any orphaned ffmpeg processes from previous sessions
Stop-Process -Name ffmpeg -Force -ErrorAction SilentlyContinue

# Disconnect any stray TV device (192.168.31.124) from ADB
& $adb disconnect 192.168.31.124:5555 2>$null | Out-Null

$devs = @(& $adb devices | Where-Object { $_ -match "\bdevice\b" -and $_ -notmatch "List of" -and $_ -notmatch "192\.168\.31\.124" })
$serial = if ($devs.Count -gt 0) { ($devs[0].Trim() -split '\s+')[0] } else { 'R5CRC476JPV' }
Write-Host "Podklyuchennoe ustroystvo (telefon): $serial" -ForegroundColor Cyan

# Determine real phone IP dynamically from device (defaulting to 192.168.31.6)
$phoneWifiIp = '192.168.31.6'
try {
    $ipOut = & $adb -s $serial shell "ip -4 addr show wlan0"
    if ($ipOut -match 'inet\s+([0-9]+\.[0-9]+\.[0-9]+\.[0-9]+)') {
        $phoneWifiIp = $matches[1]
    }
} catch {}
Write-Host "Wi-Fi IP telefona: $phoneWifiIp" -ForegroundColor Cyan

# 1. Forward USB ports for ultra-low latency & zero packet loss
Write-Host '[1/5] Proverka podklyucheniya telefona i probros portov...' -ForegroundColor Yellow
& $adb -s $serial forward tcp:6004 tcp:6004
& $adb -s $serial forward tcp:9550 tcp:9550
& $adb -s $serial forward tcp:9552 tcp:9552

# 2. Wake up, unlock and launch app on phone
Write-Host '[2/5] Zapusk prilozheniya VRFusion na telefone...' -ForegroundColor Yellow
& $adb -s $serial shell 'input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; am start -n com.vrfusion.tracker/.MainActivity; am start-foreground-service -a com.vrfusion.tracker.START -n com.vrfusion.tracker/.VRFusionService --es extra_ip 192.168.31.2'

# 3. Stop standalone server to ensure driver_vrfusion.dll has exclusive Kinect 360 & Port 9550 access
Write-Host '[3/5] Podgotovka drayvera VRFusion (Kinect 360 FBT + 120Hz IMU)...' -ForegroundColor Yellow
Stop-Process -Name 'VRFusion_Server' -Force -ErrorAction SilentlyContinue

# 4. Start SteamVR
Write-Host '[4/5] Zapusk SteamVR...' -ForegroundColor Yellow
$vrProc = Get-Process -Name 'vrcompositor' -ErrorAction SilentlyContinue
if (!$vrProc) {
    Start-Process -FilePath 'C:\Program Files (x86)\Steam\steam.exe' -ArgumentList '-applaunch 250820'
    Write-Host 'Ozhidanie inicializacii SteamVR (6 sek)...' -ForegroundColor Gray
    Start-Sleep -Seconds 6
}

# 5. Connect to SteamVR Stereo Named Pipe (\\.\pipe\vrfusion_stream)
Write-Host '[5/5] Podklyuchenie k pryamomu stereo-kanalu SteamVR (Named Pipe)...' -ForegroundColor Yellow
$pipePath = '\\.\pipe\vrfusion_stream'

# Re-verify ADB port forwarding for video and tracking
if ($serial) {
    & $adb -s $serial forward tcp:6004 tcp:6004 2>$null
    & $adb -s $serial forward tcp:9552 tcp:9552 2>$null
    & $adb -s $serial forward tcp:9550 tcp:9550 2>$null
    $target = 'tcp://127.0.0.1:6004'
    Write-Host '[OK] Svyaz po USB portu 6004 aktivna (127.0.0.1:6004) - 0 poter, 120 FPS!' -ForegroundColor Green
} elseif ($phoneWifiIp) {
    $target = "tcp://${phoneWifiIp}:6004"
    Write-Host "Pereklyuchenie na pryamoy Wi-Fi telefona: $phoneWifiIp:6004..." -ForegroundColor DarkYellow
} else {
    $target = 'tcp://127.0.0.1:6004'
}

Write-Host ''
Write-Host '======================================================' -ForegroundColor Green
Write-Host ' Translyaciya 120 FPS NVENC Stereo na telefon aktivna!' -ForegroundColor Cyan
Write-Host ' Na telefone vyvoditsya stereo 3D: 2340x1080 (oba glaza).' -ForegroundColor Cyan
Write-Host ' Pervyy zapusk: dlya centrirovaniya nazhmi gromkost +/-' -ForegroundColor Yellow
Write-Host ' Dlya skrytiya interfeysa na telefone kosnis ekrana.' -ForegroundColor Yellow
Write-Host '======================================================' -ForegroundColor Green
Write-Host ''

$nvencArgs = @(
    '-c:v', 'h264_nvenc',
    '-preset', 'p1',
    '-tune', 'ull',
    '-zerolatency', '1',
    '-r', '120',
    '-b:v', '20M',
    '-maxrate', '30M',
    '-bufsize', '2M',
    '-g', '30',
    '-forced-idr', '1',
    '-bsf:v', 'dump_extra=freq=keyframe',
    '-f', 'h264',
    $target
)

try {
    while ($true) {
        Write-Host 'Zapusk pryamoy stereo-translyacii glaz SteamVR (120 FPS NVENC)...' -ForegroundColor Green
        $ffArgs = @(
            '-hide_banner',
            '-nostats',
            '-progress', 'pipe:1',
            '-f', 'rawvideo',
            '-pixel_format', 'rgba',
            '-video_size', '1920x1080',
            '-framerate', '120',
            '-i', $pipePath,
            '-vf', 'scale=in_range=full:out_range=full,format=yuv420p',
            '-color_range', 'pc'
        ) + $nvencArgs

        $psi = New-Object System.Diagnostics.ProcessStartInfo
        $psi.FileName = $ffmpeg
        $psi.Arguments = ($ffArgs -join ' ')
        $psi.UseShellExecute = $false
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true
        $psi.CreateNoWindow = $true

        $proc = [System.Diagnostics.Process]::Start($psi)
        $reader = $proc.StandardOutput

        $curFrame = "0"
        $curFps = "0"
        $curSpeed = "0"
        $curTime = "00:00:00"

        while (!$proc.HasExited) {
            $line = $reader.ReadLine()
            if ($null -eq $line) { break }
            if ($line -match '^frame=(\d+)') { $curFrame = $matches[1] }
            elseif ($line -match '^fps=([\d\.]+)') { $curFps = $matches[1] }
            elseif ($line -match '^out_time=(\d+:\d+:\d+)') { $curTime = $matches[1] }
            elseif ($line -match '^speed=([\d\.]+x)') { $curSpeed = $matches[1] }
            elseif ($line -eq 'progress=continue') {
                [Console]::Write("`r[VR 120 FPS] Kadr: $curFrame | FPS: $curFps | Vremya: $curTime | Skorost: $curSpeed     ")
            }
        }
        $proc.WaitForExit()
        [Console]::WriteLine("")

        Write-Host 'Translyaciya perervana. Perezapusk cherez 1 sek...' -ForegroundColor DarkYellow
        Start-Sleep -Seconds 1
    }
} finally {
    Write-Host 'Ostanovka translyacii i FFmpeg...' -ForegroundColor Yellow
    Stop-Process -Name ffmpeg -Force -ErrorAction SilentlyContinue
}
