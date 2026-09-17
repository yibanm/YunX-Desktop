@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

set "APPNAME=YunX-Desktop-Fork"
set "APPDIR=%LOCALAPPDATA%\Programs\%APPNAME%"
set "EXENAME=YunX-Desktop-Fork.exe"
set "AUMID=YunX-Desktop-fork"
set "SRCDIR=%~dp0%APPNAME%"

echo ============================================
echo   YunX Desktop Fork  -  Installer
echo ============================================
echo.

if not exist "%SRCDIR%\%EXENAME%" (
    echo [ERROR] Application files not found: %SRCDIR%
    pause
    exit /b 1
)

echo [1/3] Copying files to %APPDIR% ...
if not exist "%APPDIR%" mkdir "%APPDIR%"
xcopy "%SRCDIR%\*" "%APPDIR%\" /E /Y /Q >nul
if errorlevel 1 (
    echo [ERROR] Copy failed.
    pause
    exit /b 1
)

echo [2/3] Creating shortcuts ...
set "PS1=%APPDIR%\CreateShortcuts.ps1"
copy /Y "%~dp0CreateShortcuts.ps1" "%PS1%" >nul
powershell -NoProfile -ExecutionPolicy Bypass -File "%PS1%" -Target "%APPDIR%\%EXENAME%" -WorkDir "%APPDIR%" -Aumid "%AUMID%" -Name "YunX"
if errorlevel 1 (
    echo [WARN] Shortcut AUMID setup failed, falling back to basic shortcut.
    powershell -NoProfile -Command "$ws=New-Object -ComObject WScript.Shell;$d=[Environment]::GetFolderPath('Desktop');$s=$ws.CreateShortcut(\"$d\YunX.lnk\");$s.TargetPath=\"%APPDIR%\%EXENAME%\";$s.WorkingDirectory=\"%APPDIR%\";$s.IconLocation=\"%APPDIR%\%EXENAME%,0\";$s.Save()"
)

echo [3/3] Starting application ...
start "" "%APPDIR%\%EXENAME%"

echo.
echo Installation complete.
endlocal
