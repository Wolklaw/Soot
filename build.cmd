@echo off
rem Builds Soot.exe (the launcher) with the C# compiler that ships with Windows, and redraws the icons.
setlocal
set FW=%WINDIR%\Microsoft.NET\Framework64\v4.0.30319
if not exist "%FW%\csc.exe" (echo csc.exe not found in %FW% & exit /b 1)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0launcher\make-icon.ps1"
"%FW%\csc.exe" /nologo /target:winexe /optimize+ /platform:anycpu /out:"%~dp0Soot.exe" /win32icon:"%~dp0launcher\soot.ico" ^
  /r:System.dll /r:System.Windows.Forms.dll "%~dp0launcher\Soot.cs"
if errorlevel 1 exit /b 1
echo Built Soot.exe
