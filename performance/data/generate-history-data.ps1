[CmdletBinding()]
param(
    [string]$HostName = 'localhost',
    [ValidateRange(1, 65535)]
    [int]$Port = 3306,
    [string]$Database = '',
    [string]$UserName = '',
    [string]$OwnerUsername = 'owner',
    [string]$RunId = '',
    [ValidateRange(1, 1000000)]
    [int]$HistoryRows = 100000,
    [ValidateRange(1, 100000)]
    [int]$ProjectRows = 10000,
    [ValidateRange(100, 10000)]
    [int]$BatchSize = 5000,
    [ValidateSet('DISABLED', 'PREFERRED', 'REQUIRED', 'VERIFY_CA', 'VERIFY_IDENTITY')]
    [string]$SslMode = 'PREFERRED',
    [string]$MySqlClient = 'mysql',
    [switch]$Execute,
    [string]$Confirmation = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($Database)) {
    $Database = $env:MYSQL_DATABASE
}
if ([string]::IsNullOrWhiteSpace($UserName)) {
    $UserName = $env:MYSQL_USER
}
if ([string]::IsNullOrWhiteSpace($RunId)) {
    $RunId = 'history-' + [DateTimeOffset]::UtcNow.ToString('yyyyMMdd-HHmmss')
}

if ([string]::IsNullOrWhiteSpace($Database)) {
    throw 'Database is required. Pass -Database or set MYSQL_DATABASE.'
}
if ([string]::IsNullOrWhiteSpace($UserName)) {
    throw 'UserName is required. Pass -UserName or set MYSQL_USER.'
}
if ($Database -notmatch '^[A-Za-z0-9_]+$') {
    throw 'Database may contain only letters, digits, and underscore.'
}
if ($Database -notmatch '(?i)perf') {
    throw 'Safety check failed: the dedicated database name must contain "perf".'
}
if ($UserName -notmatch '^[A-Za-z0-9_.@-]+$') {
    throw 'UserName contains unsupported characters.'
}
if ($RunId -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$') {
    throw 'RunId must be 1-32 characters containing only letters, digits, underscore, or hyphen.'
}
if ($OwnerUsername -notmatch '^[A-Za-z0-9][A-Za-z0-9_.@-]{0,127}$') {
    throw 'OwnerUsername contains unsupported characters.'
}

$expectedConfirmation = "AUTOSPEC_PERFORMANCE_ONLY:$Database"
$plan = @"
AutoSpec synthetic performance dataset plan
  Target:          $UserName@$HostName`:$Port/$Database
  Owner username:  $OwnerUsername
  Run ID:          $RunId
  Project rows:    $ProjectRows
  Rows per history table: $HistoryRows
  History tables:  workflow_run, workflow_node_run, artifact, agent_event, model_invocation
  Batch size:      $BatchSize
  TLS mode:        $SslMode
"@
Write-Host $plan

if (-not $Execute) {
    Write-Host 'Dry run only: no database connection was opened and no rows were written.'
    Write-Host "To execute, add -Execute -Confirmation '$expectedConfirmation'."
    exit 0
}

if ($Confirmation -cne $expectedConfirmation) {
    throw "Confirmation must exactly equal '$expectedConfirmation'."
}
if ([string]::IsNullOrWhiteSpace($env:MYSQL_PWD)) {
    throw 'MYSQL_PWD must contain the password for the performance-schema database user.'
}

$mysqlCommand = Get-Command -Name $MySqlClient -CommandType Application -ErrorAction Stop
$sqlFile = Join-Path $PSScriptRoot 'generate-history-data.sql'
if (-not (Test-Path -LiteralPath $sqlFile -PathType Leaf)) {
    throw "SQL generator not found: $sqlFile"
}

$sessionSettings = @(
    "SET @autospec_perf_history_rows = $HistoryRows;",
    "SET @autospec_perf_project_rows = $ProjectRows;",
    "SET @autospec_perf_batch_size = $BatchSize;",
    "SET @autospec_perf_run_id = '$RunId';",
    "SET @autospec_perf_owner_username = '$OwnerUsername';"
) -join [Environment]::NewLine

$sql = $sessionSettings `
    + [Environment]::NewLine `
    + [System.IO.File]::ReadAllText($sqlFile, [System.Text.Encoding]::UTF8)

$arguments = @(
    "--host=$HostName",
    "--port=$Port",
    "--user=$UserName",
    "--database=$Database",
    '--protocol=tcp',
    '--connect-timeout=10',
    "--ssl-mode=$SslMode",
    '--default-character-set=utf8mb4',
    '--batch',
    '--raw'
)

Write-Host 'Executing against the confirmed performance schema. The password will not be printed.'
$sql | & $mysqlCommand.Source @arguments
if ($LASTEXITCODE -ne 0) {
    throw "mysql exited with code $LASTEXITCODE. Recreate the disposable schema before retrying."
}

Write-Host 'Synthetic dataset generation completed. Save the printed IDs and counts with the report.'
