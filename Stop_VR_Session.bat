@echo off
echo Ostanovka vseh processov VRFusion, FFmpeg i SteamVR...
taskkill /F /IM ffmpeg.exe 2>nul
taskkill /F /IM vrcompositor.exe 2>nul
taskkill /F /IM vrmonitor.exe 2>nul
taskkill /F /IM vrserver.exe 2>nul
taskkill /F /IM VRFusion_Server.exe 2>nul
echo [OK] Vse processy translyacii i VR uspeshno ostanovleny!
ping 127.0.0.1 -n 2 >nul
