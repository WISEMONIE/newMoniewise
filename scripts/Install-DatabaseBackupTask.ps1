param(
    [string]$TaskName = 'Moniewise Database Backup',
    [string]$BackupScriptPath = (Join-Path $PSScriptRoot 'Backup-Database.ps1'),
    [string]$At = '02:30',
    [string]$OutputDirectory = (Join-Path ([Environment]::GetFolderPath('UserProfile')) 'Downloads\MoniewiseDbBackups'),
    [string]$EnvFile = (Join-Path $PSScriptRoot '..\src\main\resources\.env')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (-not (Test-Path -LiteralPath $BackupScriptPath)) {
    throw "Backup script not found: $BackupScriptPath"
}

$scheduleTime = [DateTime]::ParseExact($At, 'HH:mm', [Globalization.CultureInfo]::InvariantCulture)
$resolvedBackupScriptPath = (Resolve-Path -LiteralPath $BackupScriptPath).Path

$arguments = @(
    '-NoProfile',
    '-ExecutionPolicy', 'Bypass',
    '-File', "`"$resolvedBackupScriptPath`"",
    '-OutputDirectory', "`"$OutputDirectory`"",
    '-EnvFile', "`"$EnvFile`""
)

$action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument ($arguments -join ' ')
$trigger = New-ScheduledTaskTrigger -Daily -At $scheduleTime
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew

Register-ScheduledTask `
    -TaskName $TaskName `
    -Action $action `
    -Trigger $trigger `
    -Settings $settings `
    -Description 'Creates a daily local pg_dump backup for the Moniewise PostgreSQL database.' `
    -Force | Out-Null

Write-Host "Scheduled task '$TaskName' installed. It will run daily at $At."
