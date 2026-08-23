@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"

REM  ==========================================================================
REM   Bofma Ventures - start the till.
REM
REM   Double-click this. It brings up the database and the application in
REM   Docker and opens the till in a browser. Safe to run again at any time:
REM   it will not touch the shop's data, and it will not replace the signing
REM   key once one exists.
REM  ==========================================================================

echo.
echo   Bofma Ventures
echo   --------------
echo.

REM --- Is Docker there and running? ------------------------------------------
where docker >nul 2>&1
if errorlevel 1 (
  echo   Docker Desktop is not installed on this machine.
  echo   Install it from https://www.docker.com/products/docker-desktop
  echo.
  pause
  exit /b 1
)

docker info >nul 2>&1
if errorlevel 1 (
  echo   Docker Desktop is installed but not running.
  echo   Start Docker Desktop, wait for the whale icon to stop animating,
  echo   then run this again.
  echo.
  pause
  exit /b 1
)

REM --- Secrets, written once and then left alone -----------------------------
REM
REM  The signing key must outlive the process: regenerating it signs every till
REM  out. So it is written to .env on first run and never rewritten. .env is
REM  ignored by git, and losing it means everybody signs in again.
if not exist ".env" (
  echo   First run - creating .env with a new signing key and database password.

  for /f "usebackq delims=" %%K in (`powershell -NoProfile -Command "$b=New-Object byte[] 48;[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b);[Convert]::ToBase64String($b)"`) do set "CW_SECRET=%%K"
  for /f "usebackq delims=" %%P in (`powershell -NoProfile -Command "$b=New-Object byte[] 24;[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b);[Convert]::ToBase64String($b) -replace '[^A-Za-z0-9]',''"`) do set "CW_DBPASS=%%P"

  if "!CW_SECRET!"=="" (
    echo   Could not generate a signing key. Is PowerShell available?
    pause
    exit /b 1
  )

  > ".env" echo # Bofma Ventures - secrets for this installation.
  >>".env" echo # Written once, on first run. Keep a copy somewhere safe and off this
  >>".env" echo # machine: replacing the signing key signs every till out, and losing the
  >>".env" echo # database password locks the shop out of its own records.
  >>".env" echo COUNTERWEIGHT_JWT_SECRET=!CW_SECRET!
  >>".env" echo POSTGRES_PASSWORD=!CW_DBPASS!

  echo   Written. Back it up - see the notes inside .env
  echo.
)

REM --- Build and start -------------------------------------------------------
echo   Starting. The first run builds the application and takes a few minutes.
echo.

docker compose -f docker-compose.prod.yml up -d --build
if errorlevel 1 (
  echo.
  echo   It did not start. The output above says why.
  echo.
  pause
  exit /b 1
)

REM --- Wait until it is actually answering -----------------------------------
REM
REM  "Started" from compose means the container exists, not that migrations have
REM  run. Telling somebody it is ready before it is means they meet an error on
REM  the first thing they click.
echo   Waiting for the shop to come up...
set /a TRIES=0
:wait
set /a TRIES+=1
powershell -NoProfile -Command "try{$r=Invoke-WebRequest -UseBasicParsing -Uri 'http://localhost:8080/actuator/health' -TimeoutSec 3; if($r.Content -match 'UP'){exit 0}else{exit 1}}catch{exit 1}" >nul 2>&1
if not errorlevel 1 goto ready
if !TRIES! GEQ 60 (
  echo.
  echo   It is taking longer than expected. Check what it is saying:
  echo       docker compose -f docker-compose.prod.yml logs app
  echo.
  pause
  exit /b 1
)
timeout /t 3 /nobreak >nul
goto wait

:ready
echo.
echo   Ready. The till is at http://localhost:8080
echo.
echo   Signing in for the first time:
echo     There are two accounts - 'owner' runs the shop, 'sysadmin' runs the
echo     system. Both must set their own password before they can do anything.
echo     Their first passwords were written to the log once:
echo         docker compose -f docker-compose.prod.yml logs app ^| findstr /C:"FIRST RUN" /C:"sysadmin" /C:"owner"
echo.
echo   To stop the shop:  docker compose -f docker-compose.prod.yml down
echo.

start "" "http://localhost:8080"
endlocal
