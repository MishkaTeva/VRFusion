@echo off
title VRFusion Control Center
cd /d "%~dp0"
python VRFusion_Control_Center.py
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Ne udalos zapustit python VRFusion_Control_Center.py
    pause
)
