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

$defaultPhoneIp = '192.168.31.6'
$phoneWifiIp = $defaultPhoneIp

# Check currently attached ADB devices
$devs = @(& $adb devices | Where-Object { $_ -match "\bdevice\b" -and $_ -notmatch "List of" -and $_ -notmatch "192\.168\.31\.124" })

# Check if there is a physical USB device (serial has no colon or dot)
$usbDev = $devs | Where-Object { $_ -notmatch ":" } | ForEach-Object { ($_ -split '\s+')[0] } | Select-Object -First 1

if ($usbDev) {
    $serial = $usbDev
    $isUsbMode = $true
    Write-Host "[+] USB podklyuchenie obnaruzheno: $serial" -ForegroundColor Green

    # Enable wireless ADB port 5555 so Wi-Fi ADB is primed if cable is unplugged
    & $adb -s $serial tcpip 5555 2>$null | Out-Null

    # Forward ports for USB low latency & 0 packet loss
    Write-Host '[1/5] Probros USB portov (6004, 9550, 9552)...' -ForegroundColor Yellow
    & $adb -s $serial forward tcp:6004 tcp:6004
    & $adb -s $serial forward tcp:9550 tcp:9550
    & $adb -s $serial forward tcp:9552 tcp:9552

    # Query real phone Wi-Fi IP dynamically from device
    try {
        $ipOut = & $adb -s $serial shell "ip -4 addr show wlan0"
        if ($ipOut -match 'inet\s+([0-9]+\.[0-9]+\.[0-9]+\.[0-9]+)') {
            $phoneWifiIp = $matches[1]
        }
    } catch {}
    Write-Host "Wi-Fi IP telefona: $phoneWifiIp" -ForegroundColor Cyan

    # Wake up, unlock and launch app on phone
    Write-Host '[2/5] Zapusk prilozheniya VRFusion na telefone (USB)...' -ForegroundColor Yellow
    & $adb -s $serial shell 'input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; am start -n com.vrfusion.tracker/.MainActivity; am start-foreground-service -a com.vrfusion.tracker.START -n com.vrfusion.tracker/.VRFusionService --es extra_ip 192.168.31.2'

    $target = 'tcp://127.0.0.1:6004'
    Write-Host "[OK] Rezhim svyazi: USB (127.0.0.1:6004) - 0 poter, 120 FPS!" -ForegroundColor Green
} else {
    $isUsbMode = $false
    Write-Host "[!] USB kabel ne obnaruzhen. Rabotaem v besprovodnom rezhime WI-FI!" -ForegroundColor Yellow

    # Attempt to connect Wireless ADB to phone
    Write-Host "Podklyuchenie Wireless ADB k ${phoneWifiIp}:5555..." -ForegroundColor Cyan
    & $adb connect "${phoneWifiIp}:5555" 2>$null | Out-Null
    Start-Sleep -Milliseconds 400

    $wifiDev = @(& $adb devices | Where-Object { $_ -match [regex]::Escape("${phoneWifiIp}:5555") -and $_ -match "\bdevice\b" })
    if ($wifiDev.Count -gt 0) {
        $serial = "${phoneWifiIp}:5555"
        Write-Host "[+] Wireless ADB uspeshno podklyuchen: $serial" -ForegroundColor Green

        # Forward command port 9552
        & $adb -s $serial forward tcp:9552 tcp:9552 2>$null

        # Wake up, dismiss lockscreen and launch VRFusion app over Wi-Fi
        Write-Host '[2/5] Zapusk prilozheniya VRFusion na telefone (Wi-Fi ADB)...' -ForegroundColor Yellow
        & $adb -s $serial shell 'input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; am start -n com.vrfusion.tracker/.MainActivity; am start-foreground-service -a com.vrfusion.tracker.START -n com.vrfusion.tracker/.VRFusionService --es extra_ip 192.168.31.2'
    } else {
        Write-Host "[i] Wireless ADB ne otvetil. Ozhidanie pryamogo Wi-Fi soedineniya..." -ForegroundColor DarkYellow
    }

    $target = "tcp://${phoneWifiIp}:6004"
    Write-Host "[OK] Rezhim svyazi: WI-FI DIRECT ($target) - 120 FPS NVENC Stereo!" -ForegroundColor Green
}

# 3. Stop standalone server to ensure driver_vrfusion.dll has exclusive Kinect 360 & Port 9550 access
Write-Host '[3/5] Podgotovka drayvera VRFusion (Kinect 360 FBT + 120Hz IMU)...' -ForegroundColor Yellow
Stop-Process -Name 'VRFusion_Server' -Force -ErrorAction SilentlyContinue

# 4. Start SteamVR
Write-Host '[4/5] Zapusk SteamVR...' -ForegroundColor Yellow
$steamConfigDir = 'C:\Program Files (x86)\Steam\config'
$chapFile = Join-Path $steamConfigDir 'chaperone_info.vrchap'
$srcChap = Join-Path $vrDir 'chaperone_info.vrchap'
if ((Test-Path $steamConfigDir) -and (Test-Path $srcChap) -and !(Test-Path $chapFile)) {
    Copy-Item $srcChap $chapFile -Force -ErrorAction SilentlyContinue
}
$vrProc = Get-Process -Name 'vrcompositor' -ErrorAction SilentlyContinue
if (!$vrProc) {
    Start-Process -FilePath 'C:\Program Files (x86)\Steam\steam.exe' -ArgumentList '-applaunch 250820'
    Write-Host 'Ozhidanie inicializacii SteamVR (6 sek)...' -ForegroundColor Gray
    Start-Sleep -Seconds 6
}

# 5. Connect to SteamVR Stereo Named Pipe (\\.\pipe\vrfusion_stream)
Write-Host '[5/5] Podklyuchenie k pryamomu stereo-kanalu SteamVR (Named Pipe)...' -ForegroundColor Yellow
$pipePath = '\\.\pipe\vrfusion_stream'

# Wait for phone video receiver port 6004 to be ready (prevents ffmpeg connection drops)
$checkHost = if ($target -match '//([^:]+):') { $matches[1] } else { '127.0.0.1' }
Write-Host "Proverka gotovnosti videopriyomnika ($checkHost : 6004)..." -ForegroundColor Gray
$portReady = $false
for ($i = 0; $i -lt 16; $i++) {
    try {
        $sock = New-Object System.Net.Sockets.TcpClient
        $iar = $sock.BeginConnect($checkHost, 6004, $null, $null)
        if ($iar.AsyncWaitHandle.WaitOne(400, $false) -and $sock.Connected) {
            $sock.EndConnect($iar)
            $sock.Close()
            $portReady = $true
            break
        }
        $sock.Close()
    } catch {}
    Start-Sleep -Milliseconds 400
}

if ($portReady) {
    Write-Host "[OK] Videopriyomnik telefona gotov k priyomu 120 FPS potoka!" -ForegroundColor Green
} else {
    Write-Host "[!] Port 6004 eshche ne otvechaet, popytka pryamogo podklyucheniya..." -ForegroundColor DarkYellow
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
        $errReader = $proc.StandardError

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

        if ($proc.ExitCode -ne 0) {
            $errContent = $errReader.ReadToEnd()
            if ($errContent) {
                $errLines = $errContent -split "`r?`n" | Where-Object { $_ -match "(error|failed|refused|cannot|unable)" }
                if ($errLines) {
                    Write-Host "[FFmpeg Error] $($errLines[-1])" -ForegroundColor Red
                }
            }
        }

        Write-Host 'Translyaciya perervana. Perezapusk cherez 1 sek...' -ForegroundColor DarkYellow
        Start-Sleep -Seconds 1
    }
} finally {
    Write-Host 'Ostanovka translyacii i FFmpeg...' -ForegroundColor Yellow
    Stop-Process -Name ffmpeg -Force -ErrorAction SilentlyContinue
}
