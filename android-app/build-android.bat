@echo off
chcp 65001 >nul
setlocal
rem Goal Android App: copy the project out of Google Drive, install, sync, and open Android Studio.
set "SRC=%~dp0"
set "DEST=%USERPROFILE%\goal-android"

echo [1/4] Copying project to %DEST%
robocopy "%SRC%." "%DEST%" /E /XD node_modules build .gradle .idea /XF build-android.bat /NFL /NDL /NJH /NJS /NP >nul

echo [2/4] Copying the latest Goal web app
copy /Y "%SRC%..\index.html" "%DEST%\www\index.html" >nul
copy /Y "%SRC%..\manifest.json" "%DEST%\www\manifest.json" >nul
copy /Y "%SRC%..\version.json" "%DEST%\www\version.json" >nul
robocopy "%SRC%..\icons" "%DEST%\www\icons" /E /NFL /NDL /NJH /NJS /NP >nul

cd /d "%DEST%"
if not exist node_modules (
  echo [3/4] Installing Capacitor ^(first time only^)
  call npm install --no-audit --no-fund
  if errorlevel 1 goto :fail
) else (
  echo [3/4] Capacitor already installed
)

echo [4/4] Syncing the web app into the Android project
call npx cap sync android
if errorlevel 1 goto :fail

echo.
echo Done. Opening Android Studio...
call npx cap open android
echo If Android Studio did not open, open this folder in Android Studio:
echo   %DEST%\android
pause
goto :eof

:fail
echo.
echo Something went wrong above. Copy the messages in this window and send them to Claude.
pause
