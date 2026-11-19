@echo off
rem Downloads the browser engine into .\engine (see setup.ps1).
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup.ps1"
pause
