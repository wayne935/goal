@echo off
chcp 65001 >nul
cd /d "%~dp0"
set PORT=8765

rem Open the browser one second after the server starts
start "" cmd /c "timeout /t 1 >nul & start http://localhost:%PORT%/"

where py >nul 2>nul
if %errorlevel%==0 (
  echo Goal is running at http://localhost:%PORT%/  -- close this window to stop.
  py serve.py
  goto :eof
)
where python >nul 2>nul
if %errorlevel%==0 (
  echo Goal is running at http://localhost:%PORT%/  -- close this window to stop.
  python serve.py
  goto :eof
)
echo.
echo Python was not found.
echo Install it from https://www.python.org/downloads/ and tick "Add python.exe to PATH",
echo then double-click start.bat again.
pause
