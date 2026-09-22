@echo off
setlocal EnableExtensions
REM ============================================================
REM  PASM Medical - stop the local demo
REM
REM  Kills whatever is listening on the three demo ports.
REM
REM  Usage:
REM    stop-demo.bat             stop the three services
REM    stop-demo.bat --check     report port status only, kill nothing
REM
REM  FILE FORMAT CONTRACT - same as start-demo.bat:
REM  ASCII-only, CRLF line endings, no multi-line "( ... )" blocks.
REM  See the header of start-demo.bat for the full reason, and
REM  tools/check_demo_launchers.py to lint or normalize these files.
REM ============================================================

set "KILL=1"
if /i "%~1"=="--check" set "KILL="

echo.
echo ============ PASM Medical - stop demo ============
echo.
call :byport 8090 cognition
call :byport 8081 backend
call :byport 5173 web
echo.
echo runtime data is kept under ^<repo^>\.demo
echo.
if not "%NOPAUSE%"=="1" pause
endlocal
exit /b 0

:byport
set "KILLPID="
for /f "tokens=5" %%A in ('netstat -ano ^| findstr /c:":%1 " ^| findstr /i "LISTENING"') do set "KILLPID=%%A"
if not defined KILLPID goto none
if not defined KILL goto show
echo   port %1 %2 : killing PID %KILLPID%
taskkill /F /PID %KILLPID% >nul 2>&1
if errorlevel 1 echo   port %1 %2 : kill failed, try again from an elevated console
goto :eof
:show
echo   port %1 %2 : listening, PID %KILLPID% - --check mode, nothing killed
goto :eof
:none
echo   port %1 %2 : not running
goto :eof
