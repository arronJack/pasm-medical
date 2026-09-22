@echo off
setlocal EnableExtensions
REM ============================================================
REM  PASM Medical - local demo launcher (Windows)
REM
REM  Starts the three processes and opens the workbench:
REM    1/3  cognition service  :8090   Python, pasm_medical
REM    2/3  backend layer      :8081   Spring Boot 3, profile=dev, H2 in-memory
REM    3/3  web workbench      :5173   Vue 3 + Vite
REM
REM  Usage:
REM    start-demo.bat             start; ports already listening are skipped
REM    start-demo.bat --check     preflight only, start nothing
REM    start-demo.bat --no-open   do not open the browser
REM  Stop:
REM    stop-demo.bat
REM  Demo accounts (dev profile only):
REM    staff   / 123456    back office   /#/admin
REM    patient / 123456    consultation  /#/consult
REM
REM  Optional environment overrides:
REM    PASM_MEDICAL_PYTHON   python.exe to use, must be able to import pasm_medical
REM    PASM_MEDICAL_JAVA     java.exe to use, must be JDK 17+
REM    PASM_MEDICAL_NODE     node.exe to use
REM    PASM_MEDICAL_TOKEN    shared token for cognition <-> backend
REM
REM  ------------------------------------------------------------
REM  FILE FORMAT CONTRACT - read before editing this file
REM
REM    * Keep it ASCII-only with CRLF line endings.
REM      Do NOT save it as UTF-8, and do NOT let it end up with LF-only ends.
REM    * Do NOT use multi-line "( ... )" blocks. Use "goto" labels instead.
REM
REM  Why this matters: cmd.exe parses a .bat with the console code page and is
REM  fragile about line ends. A non-ASCII comment saved as UTF-8 shows up as
REM  mojibake under code page 936 and half of it is then executed as a command
REM  ("'...' is not recognized as an internal or external command"); LF-only
REM  ends break multi-line blocks so every later line shifts and errors cascade.
REM  Messages are English on purpose: this is a public repo and the console code
REM  page differs by locale (936 / 950 / 932 / 1252 / 65001).
REM  .gitattributes pins "*.bat text eol=crlf" as a second guard, and
REM  tools/check_demo_launchers.py can lint and normalize these files.
REM ============================================================

REM ---- resolve repo root = the parent of the directory holding this file ----
pushd "%~dp0..\.."
set "REPO=%CD%"
popd
REM A writable directory OUTSIDE this repository: used as the cwd for the
REM import check and for scratch files. Do NOT use %SystemRoot% here - writing
REM into C:\Windows needs administrator rights, and the java version probe below
REM would then fail with "cannot read the java version" (we hit exactly that).
set "NEUTRAL=%TEMP%"
if not defined NEUTRAL set "NEUTRAL=%USERPROFILE%"
if not defined NEUTRAL set "NEUTRAL=%CD%"
set "RUNTIME=%REPO%\.demo"
set "DATADIR=%RUNTIME%\data"
set "JAR=%REPO%\backend\target\pasm-medical-backend-0.1.0.jar"

set "CHECKONLY="
set "NOOPEN="
if /i "%~1"=="--check" set "CHECKONLY=1"
if /i "%~1"=="--no-open" set "NOOPEN=1"
if /i "%~2"=="--no-open" set "NOOPEN=1"

echo.
echo =============== PASM Medical - local demo ===============
echo repo: %REPO%
echo.

REM ---------- preflight: repository layout ----------
if not exist "%REPO%\pasm_medical\service.py" goto miss_repo
if not exist "%JAR%" goto miss_jar

REM ---------- preflight: python that can import pasm_medical ----------
set "PY="
if defined PASM_MEDICAL_PYTHON if exist "%PASM_MEDICAL_PYTHON%" set "PY=%PASM_MEDICAL_PYTHON%"
if not defined PY if exist "%REPO%\.venv\Scripts\python.exe" set "PY=%REPO%\.venv\Scripts\python.exe"
if not defined PY if exist "%REPO%\venv\Scripts\python.exe" set "PY=%REPO%\venv\Scripts\python.exe"
if not defined PY for /f "delims=" %%P in ('where python 2^>nul') do if not defined PY set "PY=%%P"
if not defined PY goto miss_python
REM Run the import check from OUTSIDE the repo. "python -c" puts the current
REM directory on sys.path, so with cwd inside the repo this would "pass" off the
REM working tree even when nothing is installed - a false green we actually hit.
pushd "%NEUTRAL%"
"%PY%" -c "import pasm_medical" >nul 2>&1
set "PYRC=%errorlevel%"
popd
if not "%PYRC%"=="0" goto miss_pkg
echo [OK] python  %PY%

REM ---------- preflight: java, must be 17+ (Spring Boot 3) ----------
set "JAVA="
if defined PASM_MEDICAL_JAVA if exist "%PASM_MEDICAL_JAVA%" set "JAVA=%PASM_MEDICAL_JAVA%"
if not defined JAVA if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
if not defined JAVA for /f "delims=" %%J in ('where java 2^>nul') do if not defined JAVA set "JAVA=%%J"
if not defined JAVA goto miss_java
REM java -version writes to stderr. Do NOT embed a possibly-quoted path inside
REM "for /f ('...')": cmd strips the wrong quotes there and tries to run e.g.
REM 'D:/Program' (we hit exactly that). Redirect to a file first, then parse it.
set "JV="
set "JVFILE=%NEUTRAL%\pasm-medical-java-version.txt"
"%JAVA%" -version > "%JVFILE%" 2>&1
for /f "tokens=3" %%V in ('findstr /i "version" "%JVFILE%"') do if not defined JV set "JV=%%~V"
if not defined JV goto java_odd
set "MAJ=%JV:~0,2%"
if "%MAJ%"=="1." set "MAJ=%JV:~2,1%"
if not defined MAJ goto java_odd
if %MAJ% LSS 17 goto java_old
echo [OK] java    %JAVA%  version %JV%

REM ---------- preflight: node and web dependencies ----------
set "NODE="
if defined PASM_MEDICAL_NODE if exist "%PASM_MEDICAL_NODE%" set "NODE=%PASM_MEDICAL_NODE%"
if not defined NODE for /f "delims=" %%N in ('where node 2^>nul') do if not defined NODE set "NODE=%%N"
if not defined NODE goto miss_node
if not exist "%REPO%\web\node_modules\vite\bin\vite.js" goto miss_vite
echo [OK] node    %NODE%

REM ---------- preflight: cognition token (local demo only) ----------
if defined PASM_MEDICAL_TOKEN goto token_from_env
set "PASM_MEDICAL_TOKEN=pasm-medical-local-demo"
set "TOKEN_SRC=built-in dev default"
goto token_done
:token_from_env
set "TOKEN_SRC=from environment"
:token_done
echo [OK] token   %TOKEN_SRC%

if not exist "%DATADIR%" mkdir "%DATADIR%" >nul 2>&1

REM ---------- port scan ----------
echo.
echo -- port status --
call :portstate 8090
call :portstate 8081
call :portstate 5173
echo.

if defined CHECKONLY goto preflight_ok

REM ---------- start the three services ----------
set "PASM_COGNITION_URL=http://127.0.0.1:8090"
set "PASM_COGNITION_TOKEN=%PASM_MEDICAL_TOKEN%"

if defined P8090 goto skip8090
echo [1/3] starting cognition service :8090 ...
start "pasm-medical 1of3 cognition 8090" /D "%REPO%" "%PY%" -u -m pasm_medical.service --tenant demo-hospital --host 127.0.0.1 --port 8090 --token %PASM_MEDICAL_TOKEN% --dir "%DATADIR%"
goto after8090
:skip8090
echo [1/3] cognition service :8090 already listening - skipped
:after8090

if defined P8081 goto skip8081
echo [2/3] starting backend layer :8081 ...
start "pasm-medical 2of3 backend 8081" /D "%REPO%\backend" "%JAVA%" -Dfile.encoding=UTF-8 -jar "%JAR%" --spring.profiles.active=dev
goto after8081
:skip8081
echo [2/3] backend layer :8081 already listening - skipped
:after8081

if defined P5173 goto skip5173
echo [3/3] starting web workbench :5173 ...
start "pasm-medical 3of3 web 5173" /D "%REPO%\web" "%NODE%" node_modules\vite\bin\vite.js --port 5173 --host 127.0.0.1 --strictPort
goto after5173
:skip5173
echo [3/3] web workbench :5173 already listening - skipped
:after5173

REM ---------- wait for the web workbench ----------
REM each round costs about 1-3 s: netstat scan plus a 1 s ping
echo.
echo waiting for the web workbench, polling :5173 - up to about 2 minutes ...
set /a TRIES=0
:waitloop
set /a TRIES+=1
call :portstate 5173 >nul
if defined P5173 goto ready
if %TRIES% GEQ 60 goto ready
ping -n 2 127.0.0.1 >nul
goto waitloop

:ready
echo.
echo ==================== startup result ====================
call :probe 8090 cognition
call :probe 8081 backend
call :probe 5173 web
echo ========================================================
echo.
echo health anchor - custom_routes must be greater than 0
echo if it is 0 or missing, the medical routes never registered
set "SYS=%SystemRoot%"
if not defined SYS set "SYS=C:\Windows"
if not exist "%SYS%\System32\curl.exe" goto no_curl
"%SYS%\System32\curl.exe" -s --max-time 5 http://127.0.0.1:8090/healthz
echo.
goto after_anchor
:no_curl
echo   curl.exe not found. Open http://127.0.0.1:8090/healthz and look at custom_routes
:after_anchor
echo.
echo open      http://127.0.0.1:5173
echo   staff   / 123456   back office   /#/admin
echo   patient / 123456   consultation  /#/consult
echo.
echo each service runs in its own window, and that window is its live log.
echo closing a window stops that service.
echo stop everything: stop-demo.bat
echo.

if defined NOOPEN goto done
start "" http://127.0.0.1:5173
goto done

:preflight_ok
echo [OK] preflight passed - --check mode, nothing was started
goto done

REM ---------- subroutines ----------
:portstate
set "P%1="
netstat -ano | findstr /c:":%1 " | findstr /i "LISTENING" >nul
if errorlevel 1 goto :eof
set "P%1=1"
echo   port %1 is already in use
goto :eof

:probe
call :portstate %1 >nul
if not defined P%1 goto probe_down
echo   %2  port %1 : listening
goto :eof
:probe_down
echo   %2  port %1 : NOT ready - check that service window for errors
goto :eof

REM ---------- diagnostics ----------
:miss_repo
echo [X] this file is not inside the repository
echo     expected to find %REPO%\pasm_medical\service.py
echo     keep the script at ^<repo^>\tools\demo\start-demo.bat
goto die

:miss_jar
echo [X] backend jar not found: %JAR%
echo     build it first. JDK 17+ is required:
echo       cd /d "%REPO%\backend"
echo       set "JAVA_HOME=D:\Program Files\Java\jdk-17"
echo       mvn -DskipTests package
echo     see docs/GUIDE.md section 4.1 for the JDK pitfalls
goto die

:miss_python
echo [X] no python.exe found
echo     create a virtual environment and install this project:
echo       cd /d "%REPO%"
echo       python -m venv .venv
echo       .venv\Scripts\pip install -e .
echo     or point PASM_MEDICAL_PYTHON at an interpreter you already prepared
goto die

:miss_pkg
echo [X] this interpreter cannot import pasm_medical: %PY%
echo     install the project into it:
echo       "%PY%" -m pip install -e "%REPO%"
echo     that also pulls pasm-skills and pasm-framework 0.5.3 or newer
goto die

:miss_java
echo [X] no java.exe found
echo     install JDK 17 or newer, or set PASM_MEDICAL_JAVA to one
goto die

:java_old
echo [X] java %JV% is too old, Spring Boot 3 needs JDK 17 or newer
echo     java used: %JAVA%
echo     point JAVA_HOME or PASM_MEDICAL_JAVA at a JDK 17 install:
echo       set "JAVA_HOME=D:\Program Files\Java\jdk-17"
echo       set "PASM_MEDICAL_JAVA=D:\Program Files\Java\jdk-17\bin\java.exe"
echo     related trap: with JAVA_HOME unset, Maven silently ignores release=17
echo     and only the lines using new language features fail to compile
echo     ("record", "instanceof SomeType name"). Run "mvn -version" first.
goto die

:java_odd
echo [X] cannot read the java version of %JAVA%
echo     run "%JAVA%" -version yourself, or set PASM_MEDICAL_JAVA
goto die

:miss_node
echo [X] no node.exe found
echo     install Node.js 18 or newer, or set PASM_MEDICAL_NODE
goto die

:miss_vite
echo [X] web dependencies are missing: %REPO%\web\node_modules\vite
echo     cd /d "%REPO%\web"
echo     npm install
goto die

:done
echo.
if not "%NOPAUSE%"=="1" pause
endlocal
exit /b 0

:die
echo.
if not "%NOPAUSE%"=="1" pause
endlocal
exit /b 1
