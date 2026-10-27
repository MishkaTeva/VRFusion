# KinectTest.ps1 - VRFusion Kinect 360 Diagnostics (v3 - correct SDK v1.8 API)
# Run: powershell -ExecutionPolicy Bypass -File KinectTest.ps1

$OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

Write-Host ""
Write-Host "=== VRFusion - Kinect 360 Diagnostics ===" -ForegroundColor Cyan
Write-Host ""

# 1. PnP devices
Write-Host "1) PnP Kinect devices:" -ForegroundColor Yellow
$kinects = Get-PnpDevice | Where-Object { $_.FriendlyName -like '*Kinect*' }
if ($kinects) {
    $kinects | Format-Table Status, FriendlyName, InstanceId -AutoSize
} else {
    Write-Host "   ERROR: No Kinect found in PnP devices." -ForegroundColor Red
}

# 2. SDK v1.8 registry
Write-Host "2) Kinect SDK v1.8:" -ForegroundColor Yellow
$sdkKey = 'HKLM:\Software\Microsoft\Kinect'
if (Test-Path $sdkKey) {
    $sdkPath = (Get-ItemProperty $sdkKey).SDKInstallPath
    Write-Host "   SDK path: $sdkPath" -ForegroundColor Green
    
    $dll = Join-Path $sdkPath "Assemblies\Microsoft.Kinect.dll"
    if (Test-Path $dll) {
        Write-Host "   Microsoft.Kinect.dll: FOUND" -ForegroundColor Green
    } else {
        Write-Host "   Microsoft.Kinect.dll: NOT FOUND" -ForegroundColor Red
        $sdkPath = $null
    }
} else {
    Write-Host "   SDK v1.8: NOT INSTALLED" -ForegroundColor Red
    $sdkPath = $null
}

# 3. Try to initialize Kinect via .NET (SDK v1.8 uses Start()/Stop(), not Open()/Close())
Write-Host ""
Write-Host "3) Initializing Kinect via .NET (SDK v1.8 API):" -ForegroundColor Yellow
if ($sdkPath) {
    $dll = Join-Path $sdkPath "Assemblies\Microsoft.Kinect.dll"
    try {
        Add-Type -Path $dll -ErrorAction Stop
        $sensors = [Microsoft.Kinect.KinectSensor]::KinectSensors
        $count = $sensors.Count
        Write-Host "   Sensors found: $count" -ForegroundColor $(if ($count -gt 0) { 'Green' } else { 'Red' })
        
        if ($count -gt 0) {
            foreach ($s in $sensors) {
                Write-Host ("   Sensor: " + $s.UniqueKinectId + " | Status: " + $s.Status + " | IsRunning: " + $s.IsRunning) -ForegroundColor Green
                
                if ($s.Status -eq [Microsoft.Kinect.KinectStatus]::Connected) {
                    try {
                        # SDK v1.8: enable streams then Start()
                        $s.ColorStream.Enable([Microsoft.Kinect.ColorImageFormat]::RgbResolution640x480Fps30)
                        $s.DepthStream.Enable([Microsoft.Kinect.DepthImageFormat]::Resolution320x240Fps30)
                        $s.SkeletonStream.Enable()
                        $s.Start()
                        Write-Host "   -> Start() SUCCESS. Streams enabled." -ForegroundColor Green
                        Write-Host "   -> ElevationAngle: $($s.ElevationAngle) deg" -ForegroundColor Cyan
                        Write-Host "   -> Min/Max elevation: $($s.MinElevationAngle) / $($s.MaxElevationAngle)" -ForegroundColor Cyan
                        Start-Sleep -Milliseconds 1000
                        
                        # Skeleton test
                        Write-Host "   -> Waiting for skeleton frame..." -ForegroundColor DarkYellow
                        $skeletonFrame = $s.SkeletonStream
                        Write-Host "   -> SkeletonStream ready: $($skeletonFrame.IsEnabled)" -ForegroundColor Green
                        
                        $s.Stop()
                        Write-Host "   -> Stop() OK" -ForegroundColor Green
                    } catch {
                        Write-Host ("   -> Error: " + $_) -ForegroundColor Red
                    }
                } else {
                    Write-Host ("   -> Sensor not connected (Status=" + $s.Status + ")") -ForegroundColor Red
                }
            }
        } else {
            Write-Host "   No sensors available (check USB connection)" -ForegroundColor Red
        }
    } catch {
        Write-Host ("   DLL load/init error: " + $_) -ForegroundColor Red
    }
} else {
    Write-Host "   Skipped (SDK not found)" -ForegroundColor DarkYellow
}

# 4. USB VID/PID check
Write-Host ""
Write-Host "4) USB devices with Microsoft VID (045E):" -ForegroundColor Yellow
$usb = Get-PnpDevice | Where-Object { $_.InstanceId -like 'USB\VID_045E*' }
$usb | Format-Table Status, FriendlyName -AutoSize

# 5. driver4vr process
Write-Host "5) driver4vr process:" -ForegroundColor Yellow
$d4vr = Get-Process -Name "driver4vr" -ErrorAction SilentlyContinue
if ($d4vr) {
    Write-Host ("   driver4vr RUNNING (PID: " + $d4vr.Id + ")") -ForegroundColor Green
} else {
    Write-Host "   driver4vr NOT running" -ForegroundColor DarkYellow
}

# 6. Gamepad
Write-Host ""
Write-Host "6) Gamepad / Xbox devices:" -ForegroundColor Yellow
$gp = Get-PnpDevice | Where-Object { $_.FriendlyName -like '*Xbox*' -or $_.FriendlyName -like '*Controller*' -and $_.FriendlyName -notlike '*AMD*' -and $_.FriendlyName -notlike '*Audio*' -and $_.FriendlyName -notlike '*AHCI*' -and $_.FriendlyName -notlike '*PCI*' }
$gp | Select-Object -Property Status, FriendlyName | Sort-Object FriendlyName | Get-Unique -AsString | Format-Table -AutoSize

Write-Host ""
Write-Host "=== Diagnostics done ===" -ForegroundColor Cyan
