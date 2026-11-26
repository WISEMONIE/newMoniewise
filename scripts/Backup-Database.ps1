param(
    [string]$DatabaseUrl = $env:DATABASE_URL,
    [string]$Username = $env:DATABASE_USERNAME,
    [string]$Password = $env:DATABASE_PASSWORD,
    [string]$OutputDirectory = (Join-Path ([Environment]::GetFolderPath('UserProfile')) 'Downloads\MoniewiseDbBackups'),
    [string]$EnvFile = (Join-Path $PSScriptRoot '..\src\main\resources\.env'),
    [string]$PgDumpPath = 'pg_dump',
    [ValidateSet('Custom', 'Plain')]
    [string]$Format = 'Custom',
    [int]$RetentionDays = 14
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Import-EnvFile {
    param([string]$Path)

    if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path -LiteralPath $Path)) {
        return
    }

    foreach ($line in Get-Content -LiteralPath $Path) {
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) {
            continue
        }

        if ($trimmed -notmatch '^\s*([^#=\s]+)\s*=\s*(.*)\s*$') {
            continue
        }

        $name = $matches[1]
        $value = $matches[2].Trim()
        if (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }

        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))) {
            [Environment]::SetEnvironmentVariable($name, $value, 'Process')
        }
    }
}

function Get-QueryMap {
    param([string]$Query)

    $map = @{}
    if ([string]::IsNullOrWhiteSpace($Query)) {
        return $map
    }

    foreach ($part in $Query.TrimStart('?').Split('&')) {
        if ([string]::IsNullOrWhiteSpace($part)) {
            continue
        }

        $pair = $part.Split('=', 2)
        $key = [System.Uri]::UnescapeDataString($pair[0]).ToLowerInvariant()
        $value = ''
        if ($pair.Count -eq 2) {
            $value = [System.Uri]::UnescapeDataString($pair[1])
        }

        $map[$key] = $value
    }

    return $map
}

function Get-PostgresDumpArgs {
    param(
        [string]$Url,
        [string]$User
    )

    if ([string]::IsNullOrWhiteSpace($Url)) {
        throw 'DATABASE_URL is missing. Set DATABASE_URL or pass -DatabaseUrl.'
    }

    $normalized = $Url.Trim()
    if ($normalized.StartsWith('jdbc:postgresql://', [StringComparison]::OrdinalIgnoreCase)) {
        $normalized = 'postgresql://' + $normalized.Substring('jdbc:postgresql://'.Length)
    } elseif ($normalized.StartsWith('postgres://', [StringComparison]::OrdinalIgnoreCase)) {
        $normalized = 'postgresql://' + $normalized.Substring('postgres://'.Length)
    }

    if (-not $normalized.StartsWith('postgresql://', [StringComparison]::OrdinalIgnoreCase)) {
        $args = @("--dbname=$normalized")
        if (-not [string]::IsNullOrWhiteSpace($User)) {
            $args += "--username=$User"
        }

        return @{
            Args = $args
            PasswordFromUrl = $null
            SslMode = $null
        }
    }

    $uri = [System.Uri]$normalized
    $databaseName = [System.Uri]::UnescapeDataString($uri.AbsolutePath.TrimStart('/'))
    if ([string]::IsNullOrWhiteSpace($databaseName)) {
        throw "DATABASE_URL does not include a database name: $Url"
    }

    $resolvedUser = $User
    $passwordFromUrl = $null
    if (-not [string]::IsNullOrWhiteSpace($uri.UserInfo)) {
        $userParts = $uri.UserInfo.Split(':', 2)
        if ([string]::IsNullOrWhiteSpace($resolvedUser)) {
            $resolvedUser = [System.Uri]::UnescapeDataString($userParts[0])
        }
        if ($userParts.Count -eq 2) {
            $passwordFromUrl = [System.Uri]::UnescapeDataString($userParts[1])
        }
    }

    $query = Get-QueryMap -Query $uri.Query
    $sslMode = $null
    if ($query.ContainsKey('sslmode')) {
        $sslMode = $query['sslmode']
    }

    $args = @(
        "--host=$($uri.Host)",
        "--dbname=$databaseName"
    )

    if ($uri.Port -gt 0) {
        $args += "--port=$($uri.Port)"
    }
    if (-not [string]::IsNullOrWhiteSpace($resolvedUser)) {
        $args += "--username=$resolvedUser"
    }

    return @{
        Args = $args
        PasswordFromUrl = $passwordFromUrl
        SslMode = $sslMode
    }
}

Import-EnvFile -Path $EnvFile

if ([string]::IsNullOrWhiteSpace($DatabaseUrl)) {
    $DatabaseUrl = $env:DATABASE_URL
}
if ([string]::IsNullOrWhiteSpace($Username)) {
    $Username = $env:DATABASE_USERNAME
}
if ([string]::IsNullOrWhiteSpace($Password)) {
    $Password = $env:DATABASE_PASSWORD
}

$pgDumpCommand = Get-Command $PgDumpPath -ErrorAction SilentlyContinue
if (-not $pgDumpCommand) {
    throw "pg_dump was not found. Install PostgreSQL client tools, or pass -PgDumpPath with the full path to pg_dump.exe."
}

$connection = Get-PostgresDumpArgs -Url $DatabaseUrl -User $Username
if ([string]::IsNullOrWhiteSpace($Password) -and -not [string]::IsNullOrWhiteSpace($connection.PasswordFromUrl)) {
    $Password = $connection.PasswordFromUrl
}
if (-not [string]::IsNullOrWhiteSpace($connection.SslMode)) {
    $allowedSslModes = @('disable', 'allow', 'prefer', 'require', 'verify-ca', 'verify-full')
    if ($allowedSslModes -notcontains $connection.SslMode.ToLowerInvariant()) {
        throw 'DATABASE_URL has an invalid sslmode value. Use one of: disable, allow, prefer, require, verify-ca, verify-full.'
    }
}

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$extension = if ($Format -eq 'Plain') { 'sql' } else { 'dump' }
$backupPath = Join-Path $OutputDirectory "moniewise-db-$timestamp.$extension"

$dumpArgs = @()
if ($Format -eq 'Plain') {
    $dumpArgs += '--format=plain'
} else {
    $dumpArgs += '--format=custom'
}
$dumpArgs += '--no-owner'
$dumpArgs += '--no-privileges'
$dumpArgs += "--file=$backupPath"
$dumpArgs += $connection.Args

$previousPgPassword = $env:PGPASSWORD
$previousPgSslMode = $env:PGSSLMODE

try {
    if (-not [string]::IsNullOrWhiteSpace($Password)) {
        $env:PGPASSWORD = $Password
    }
    if (-not [string]::IsNullOrWhiteSpace($connection.SslMode)) {
        $env:PGSSLMODE = $connection.SslMode
    }

    Write-Host "Saving Moniewise database backup to: $backupPath"
    & $pgDumpCommand.Source @dumpArgs
    if ($LASTEXITCODE -ne 0) {
        if (Test-Path -LiteralPath $backupPath) {
            Remove-Item -LiteralPath $backupPath -Force -ErrorAction SilentlyContinue
        }
        throw "pg_dump failed with exit code $LASTEXITCODE."
    }

    if ($RetentionDays -gt 0) {
        $cutoff = (Get-Date).AddDays(-$RetentionDays)
        Get-ChildItem -LiteralPath $OutputDirectory -File |
            Where-Object { $_.Name -match '^moniewise-db-\d{8}-\d{6}\.(dump|sql)$' -and $_.LastWriteTime -lt $cutoff } |
            Remove-Item -Force
    }

    Write-Host 'Database backup completed.'
} finally {
    $env:PGPASSWORD = $previousPgPassword
    $env:PGSSLMODE = $previousPgSslMode
}
