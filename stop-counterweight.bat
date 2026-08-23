@echo off
setlocal EnableDelayedExpansion
cd /d "%~dp0"

REM  ==========================================================================
REM   Bofma Ventures - stop the till.
REM
REM   The counterpart to start-counterweight.bat. Shuts the application and the
REM   database down cleanly and leaves every record where it is.
REM
REM   This does NOT delete anything. Sales, stock, customers and backups live in
REM   Docker volumes that outlive the containers, so starting again brings the
REM   shop back exactly as it was.
REM  ==========================================================================

echo.
echo   Bofma Ventures
echo   --------------
echo.

REM --- Nothing to stop? Say so plainly rather than failing. ------------------
docker info >nul 2>&1
if errorlevel 1 (
  echo   Docker Desktop is not running, so neither is the till.
  echo   There is nothing to stop.
  echo.
  pause
  exit /b 0
)

set "RUNNING="
for /f "usebackq delims=" %%C in (`docker ps --filter "name=bofma-counterweight" --format "{{.Names}}" 2^>nul`) do set "RUNNING=%%C"

if not defined RUNNING (
  echo   The till is not running.
  echo.
  echo   To start it:  start-counterweight.bat
  echo.
  pause
  exit /b 0
)

REM --- Confirm, because this is a shop and somebody may be selling -----------
REM
REM  A sale in progress is held on the server, not in the browser, so it is a
REM  row in the database and it survives this. What does not survive is the
REM  cashier's next five minutes.
echo   The till is running.
echo.
echo   Stopping it now will interrupt anyone serving a customer. A sale already
echo   started is held on the server and will still be there afterwards, but
echo   nobody can ring anything up until it is started again.
echo.
set "ANSWER="
set /p "ANSWER=  Stop the till? [y/N] "
if /i not "!ANSWER!"=="y" (
  echo.
  echo   Left running.
  echo.
  pause
  exit /b 0
)

echo.
echo   Stopping...

docker compose -f docker-compose.prod.yml down
if errorlevel 1 (
  echo.
  echo   It did not stop cleanly. The output above says why.
  echo.
  pause
  exit /b 1
)

echo.
echo   Stopped.
echo.
echo   Nothing was deleted. Every sale, product, customer and backup is still
echo   there - start-counterweight.bat brings the shop back as it was.
echo.
pause
endlocal
