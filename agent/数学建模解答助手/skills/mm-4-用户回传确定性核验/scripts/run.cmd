@echo off
rem ============================================================================
rem  run.cmd - single entry point for running this skill's Python scripts.
rem
rem  WHY: the platform shell is a restricted cmd.exe whose PATH is pinned to
rem  macOS/Linux locations, so `python` is NOT on PATH; and a machine often has
rem  several interpreters with different packages installed. This wrapper picks
rem  an interpreter in this order:
rem    1. python_path.txt next to this script (one line = absolute path)  <-- override
rem    2. the candidate list below, preferring one that can import all deps
rem    3. plain `python` / `py -3` from PATH (usually absent here)
rem
rem  TO CHANGE THE INTERPRETER LATER:
rem    - preferred: write the absolute path into python_path.txt (create it if
rem      missing). No edit of this file needed; takes effect on the next run.
rem    - or add the path to the candidate list below.
rem    - or bypass this wrapper entirely and call "<python.exe> <script.py>".
rem
rem  IMPORTANT: keep this file PURE ASCII. cmd.exe reads .cmd/.bat using the OEM
rem  code page (GBK on Chinese Windows), so non-ASCII comments get mangled and
rem  their fragments are executed as commands. Keep CRLF line endings too.
rem
rem  USAGE:
rem    run.cmd probe_inputs.py --problem "<dir>" --out-dir "<session>/analysis"
rem    run.cmd render_animation.py --scene-map ... --problem ... --out-dir ...
rem    run.cmd install_deps.py
rem ============================================================================
setlocal enabledelayedexpansion
set "HERE=%~dp0"

if "%~1"=="" (
  echo [run.cmd] usage: run.cmd ^<script.py^> [args...]
  exit /b 2
)

set "SCRIPT=%~1"
rem Allow an absolute script path (e.g. a script from another skill).
set "SCRIPTFULL=%HERE%%SCRIPT%"
if "%SCRIPT:~1,1%"==":" set "SCRIPTFULL=%SCRIPT%"
if "%SCRIPT:~0,1%"=="\" set "SCRIPTFULL=%SCRIPT%"
shift

set "ARGS="
:collect_args
if "%~1"=="" goto pick_python
set "ARGS=%ARGS% "%~1""
shift
goto collect_args

:pick_python
set "RUN="
set "FULL="
set "ANY="

rem ---- 0) session-scoped override (from platform shell env) --------------
rem  The platform injects SMYY_PYTHON when the task wrote
rem  <session>/python_path.txt, and always injects SMYY_SESSION_DIR.
rem  Takes precedence over the skill-local python_path.txt below.
if defined SMYY_PYTHON if exist "%SMYY_PYTHON%" (
  "%SMYY_PYTHON%" -c "import sys" >nul 2>&1
  if not errorlevel 1 (
    set "RUN=%SMYY_PYTHON%"
    echo [run.cmd] interpreter from SMYY_PYTHON: %SMYY_PYTHON%
  ) else (
    echo [run.cmd] SMYY_PYTHON is broken; ignoring it.
  )
)
if defined RUN goto invoke

if defined SMYY_SESSION_DIR if exist "%SMYY_SESSION_DIR%\python_path.txt" (
  set "OVERRIDE="
  set /p OVERRIDE=<"%SMYY_SESSION_DIR%\python_path.txt"
  set "OVERRIDE=!OVERRIDE:"=!"
  if defined OVERRIDE if exist "!OVERRIDE!" (
    "!OVERRIDE!" -c "import sys" >nul 2>&1
    if not errorlevel 1 (
      set "RUN=!OVERRIDE!"
      echo [run.cmd] interpreter from session python_path.txt: !OVERRIDE!
    )
  )
)
if defined RUN goto invoke
rem ---- 1) explicit override file -------------------------------------------
if exist "%HERE%python_path.txt" (
  set "OVERRIDE="
  set /p OVERRIDE=<"%HERE%python_path.txt"
  set "OVERRIDE=!OVERRIDE:"=!"
  if defined OVERRIDE (
    if exist "!OVERRIDE!" (
      "!OVERRIDE!" -c "import sys" >nul 2>&1
      if not errorlevel 1 (
        set "RUN="!OVERRIDE!""
        echo [run.cmd] interpreter from python_path.txt: !OVERRIDE!
        "!OVERRIDE!" -c "import docx, openpyxl, pymupdf, latex2mathml" >nul 2>&1
        if errorlevel 1 echo [run.cmd] WARNING: it is missing rendering dependencies.
      ) else (
        echo [run.cmd] python_path.txt points to a broken interpreter; ignoring it.
      )
    ) else (
      echo [run.cmd] python_path.txt path does not exist; ignoring it.
    )
  )
)
if defined RUN goto invoke

rem ---- 2) candidate scan ---------------------------------------------------
for %%P in (
  "D:\Anaconda3\python.exe"
  "C:\ProgramData\Anaconda3\python.exe"
  "%USERPROFILE%\anaconda3\python.exe"
  "%USERPROFILE%\miniconda3\python.exe"
  "%LOCALAPPDATA%\Programs\Python\Python313\python.exe"
  "%LOCALAPPDATA%\Programs\Python\Python312\python.exe"
  "%LOCALAPPDATA%\Programs\Python\Python311\python.exe"
  "%LOCALAPPDATA%\Programs\Python\Python310\python.exe"
  "%LOCALAPPDATA%\Programs\Python\Python39\python.exe"
  "C:\Python313\python.exe"
  "C:\Python312\python.exe"
  "C:\Python311\python.exe"
  "C:\Python310\python.exe"
  "C:\Python39\python.exe"
) do call :probe_candidate %%P

rem The execute environment defines ONLY PATH (inheritEnv=false), so
rem %LOCALAPPDATA% / %USERPROFILE% are NOT expanded -- scan C:\Users\* literally
rem to cover per-user Python installs on such machines.
for /d %%U in ("C:\Users\*") do call :scan_user_dir %%U

if defined FULL set "RUN="%FULL%""
if not defined RUN if defined ANY set "RUN="%ANY%""

rem ---- 3) PATH fallback ----------------------------------------------------
rem Do NOT use `where`: when PATH is the fake POSIX string, where.exe itself
rem cannot be resolved.
if not defined RUN (
  python -c "import sys" >nul 2>&1 && set "RUN=python"
)
if not defined RUN (
  py -3 -c "import sys" >nul 2>&1 && set "RUN=py -3"
)

if not defined RUN (
  echo [run.cmd] no usable Python interpreter found.
  echo [run.cmd] write an absolute path into python_path.txt next to this script,
  echo [run.cmd] or add it to the candidate list inside run.cmd.
  exit /b 3
)

if not defined FULL (
  echo [run.cmd] WARNING: %RUN% is missing rendering dependencies. Run first:
  echo [run.cmd]   run.cmd install_deps.py
)

:invoke
%RUN% "%SCRIPTFULL%" %ARGS%
exit /b %ERRORLEVEL%

rem ---------------------------------------------------------------------------
rem  :scan_user_dir <C:\Users\<name>>
rem  Probe the per-user Python locations under one user profile directory.
rem ---------------------------------------------------------------------------
:scan_user_dir
for %%V in (313 312 311 310 39) do call :probe_candidate "%~1\AppData\Local\Programs\Python\Python%%V\python.exe"
goto :eof

rem ---------------------------------------------------------------------------
rem  :probe_candidate <python-path>
rem  Skip missing paths; remember the first working interpreter; prefer one
rem  that can actually import the rendering dependencies.
rem ---------------------------------------------------------------------------
:probe_candidate
if not exist "%~1" goto :eof
if not defined ANY set "ANY=%~1"
if defined FULL goto :eof
"%~1" -c "import docx, openpyxl, pymupdf, latex2mathml" >nul 2>&1
if not errorlevel 1 set "FULL=%~1"
goto :eof
