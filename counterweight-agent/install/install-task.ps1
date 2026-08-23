<#
  Registers the print agent to start with the till and stay up.

  A scheduled task rather than a Windows service, for one reason that is not
  about elegance: a printer queue installed for one Windows user is invisible
  to a service running as SYSTEM, so the agent that prints perfectly when
  launched by hand goes silent the moment it becomes a service. Running as the
  till's own account at logon keeps it looking at the same printers the cashier
  can see.

  Run once, elevated, from the folder holding counterweight-agent.jar:

      powershell -ExecutionPolicy Bypass -File install-task.ps1

  A shop that would rather have a true service should install WinSW or NSSM and
  point it at the same command line — and then install the printer queue for
  all users, or this is exactly the trap above.
#>

param(
    [string]$JarPath = (Join-Path $PSScriptRoot "counterweight-agent.jar"),
    [string]$ConfigPath = "C:\ProgramData\Counterweight\agent.properties",
    [string]$JavaPath = "javaw.exe",
    [string]$TaskName = "Counterweight print agent"
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $JarPath)) {
    throw "No jar at $JarPath. Pass -JarPath, or run this from the folder the jar is in."
}

$dataDir = Split-Path $ConfigPath -Parent
if (-not (Test-Path $dataDir)) { New-Item -ItemType Directory -Path $dataDir | Out-Null }

if (-not (Test-Path $ConfigPath)) {
    $example = Join-Path $PSScriptRoot "agent.example.properties"
    if (Test-Path $example) {
        Copy-Item $example $ConfigPath
        Write-Host "Wrote a starter config to $ConfigPath - set `printer` and `allowedOrigins` before this is any use."
    }
}

$logDir = Join-Path $dataDir "logs"
if (-not (Test-Path $logDir)) { New-Item -ItemType Directory -Path $logDir | Out-Null }

# `javaw` rather than `java`: no console window on a machine where the cashier
# would eventually close it and wonder why receipts stopped.
$arguments = "-jar `"$JarPath`" --config `"$ConfigPath`""
$action = New-ScheduledTaskAction -Execute $JavaPath -Argument $arguments -WorkingDirectory (Split-Path $JarPath -Parent)

# At logon, as the person who is signed in at the till. Restart on failure,
# because a printer agent that died at 09:00 and stayed dead is a day of
# receipts nobody can hand over.
$trigger = New-ScheduledTaskTrigger -AtLogOn
$settings = New-ScheduledTaskSettingsSet -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -Force | Out-Null
Start-ScheduledTask -TaskName $TaskName

Write-Host "Registered '$TaskName' and started it."
Write-Host "Check it answers:  curl http://127.0.0.1:9110/v1/status"
Write-Host "Print a test page: java -jar `"$JarPath`" --config `"$ConfigPath`" --test-print"
