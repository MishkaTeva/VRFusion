@echo off
chcp 65001 >nul
title VRFusion 1-Click VR Launcher
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0Start_VR_Session.ps1"
pause
