@echo off
subst V: /d 2>nul
subst V: "%~dp0"
call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"
cl.exe /nologo /LD /O2 /EHsc /std:c++17 /IV:\driver_vrfusion\openvr /I"C:\Program Files\Microsoft SDKs\Kinect\v1.8\inc" /Fo"V:\driver_vrfusion\bin\win64\driver_vrfusion.obj" V:\driver_vrfusion\src\driver_vrfusion.cpp /link /LIBPATH:"C:\Program Files\Microsoft SDKs\Kinect\v1.8\lib\amd64" ws2_32.lib winmm.lib xinput.lib Kinect10.lib d3d11.lib dxgi.lib user32.lib gdi32.lib /OUT:V:\driver_vrfusion\bin\win64\driver_vrfusion.dll /IMPLIB:V:\driver_vrfusion\bin\win64\driver_vrfusion.lib
if %ERRORLEVEL% EQU 0 (
    echo [OK] Driver compiled successfully!
    copy /Y "V:\driver_vrfusion\bin\win64\driver_vrfusion.dll" "C:\Program Files (x86)\Steam\steamapps\common\SteamVR\drivers\vrfusion\bin\win64\driver_vrfusion.dll"
    echo [OK] Copied driver_vrfusion.dll to SteamVR!
) else (
    echo [ERROR] Driver compilation failed!
)
subst V: /d 2>nul
