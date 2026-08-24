[CmdletBinding()]
param(
    [ValidateSet(
        "all",
        "outbox-restart",
        "worker-exit",
        "ack-gap",
        "redis-outage",
        "mysql-outage",
        "model-timeout",
        "duplicate-terminal",
        "invalid-message"
    )]
    [string]$Scenario = "all",
    [string]$MavenExecutable = "mvn",
    [string]$PythonExecutable = "python",
    [switch]$List
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$catalog = @(
    [pscustomobject]@{
        Name = "outbox-restart"
        Description = "Control plane exits after commit and before Outbox publication"
        Rto = "15s"
        ConsistencyTarget = "One persisted command, one Redis command, no database repair"
        Alerts = @("AutoSpecOutboxBacklog", "AutoSpecOutboxOldestMessageTooOld")
        BackendTests = @("ControlPlaneOutboxRestartIT")
        PythonTests = @()
    },
    [pscustomobject]@{
        Name = "worker-exit"
        Description = "Worker exits while a Redis Stream message is pending"
        Rto = "claim idle plus one poll; accelerated test budget 5s"
        ConsistencyTarget = "One reclaimed delivery and an empty final pending list"
        Alerts = @("AutoSpecWorkerTargetDown", "AutoSpecWorkerHeartbeatStale")
        BackendTests = @(
            "RedisWorkflowTransportIT#workerExitBeforeAckIsRecoveredByAnotherConsumerWithinRto"
        )
        PythonTests = @()
    },
    [pscustomobject]@{
        Name = "ack-gap"
        Description = "Worker publishes a terminal event and exits before command ACK"
        Rto = "claim idle plus one poll"
        ConsistencyTarget = "Deterministic event replay; terminal state applied once"
        Alerts = @("AutoSpecRedisPendingMessageTooOld", "AutoSpecWorkerHeartbeatStale")
        BackendTests = @("MySqlWorkflowEventConsumerConcurrencyIT")
        PythonTests = @(
            "tests/test_worker_protocol.py::test_worker_exit_after_terminal_publish_replays_same_event_before_ack"
        )
    },
    [pscustomobject]@{
        Name = "redis-outage"
        Description = "Redis connection is cut and restored through Toxiproxy"
        Rto = "detection <10s; recovery <10s"
        ConsistencyTarget = "Command stays PENDING; retries keep one stable event ID for idempotent handling"
        Alerts = @(
            "AutoSpecWorkflowBacklogMetricsCollectionFailed",
            "AutoSpecOutboxOldestMessageTooOld"
        )
        BackendTests = @("RedisOutboxRecoveryIT")
        PythonTests = @()
    },
    [pscustomobject]@{
        Name = "mysql-outage"
        Description = "MySQL connection is cut and restored through Toxiproxy"
        Rto = "detection <5s; recovery <10s"
        ConsistencyTarget = "Fast failed write, zero partial rows, automatic pool recovery"
        Alerts = @(
            "AutoSpecWorkflowBacklogMetricsCollectionFailed",
            "AutoSpecHttpFailureRateTooHigh"
        )
        BackendTests = @("MySqlFailureRecoveryIT")
        PythonTests = @()
    },
    [pscustomobject]@{
        Name = "model-timeout"
        Description = "Model handler exceeds the command timeout budget"
        Rto = "timeout handling, retry scheduling, and finalization each <5s"
        ConsistencyTarget = "Bounded retry followed by one terminal FAILED state"
        Alerts = @("AutoSpecWorkerFailureRateTooHigh")
        BackendTests = @("ModelTimeoutRecoveryIT")
        PythonTests = @(
            "tests/test_node_executor.py::test_executor_times_out_slow_model_within_runtime_budget"
        )
    },
    [pscustomobject]@{
        Name = "duplicate-terminal"
        Description = "Two consumers concurrently deliver the same terminal event"
        Rto = "within the event handling transaction"
        ConsistencyTarget = "One accepted event, one duplicate, one DAG transition"
        Alerts = @("AutoSpecWorkflowEventHandlerFailed")
        BackendTests = @("MySqlWorkflowEventConsumerConcurrencyIT")
        PythonTests = @()
    },
    [pscustomobject]@{
        Name = "invalid-message"
        Description = "Worker receives an invalid command payload"
        Rto = "within one worker batch"
        ConsistencyTarget = "Poison message goes to DLQ and the following command continues"
        Alerts = @("AutoSpecWorkerFailureRateTooHigh")
        BackendTests = @()
        PythonTests = @(
            "tests/test_worker_runner.py::test_runner_quarantines_invalid_command_and_continues_batch",
            "tests/test_worker_runner.py::test_runner_does_not_acknowledge_when_dead_letter_publication_fails"
        )
    }
)

if ($List) {
    $catalog |
        Select-Object Name, Rto, Description |
        Format-Table -AutoSize
    exit 0
}

$selectedDrills = if ($Scenario -eq "all") {
    $catalog
} else {
    @($catalog | Where-Object { $_.Name -eq $Scenario })
}

function Get-UniqueSelectors {
    param(
        [object[]]$Drills,
        [string]$PropertyName
    )

    $selectors = foreach ($drill in $Drills) {
        foreach ($selector in @($drill.$PropertyName)) {
            if (-not [string]::IsNullOrWhiteSpace($selector)) {
                $selector
            }
        }
    }
    return @($selectors | Sort-Object -Unique)
}

$backendSelectors = @(Get-UniqueSelectors $selectedDrills "BackendTests")
$pythonSelectors = @(Get-UniqueSelectors $selectedDrills "PythonTests")
$commandSpecs = @()

if ($backendSelectors.Count -gt 0) {
    $commandSpecs += [pscustomobject]@{
        Name = "backend-integration"
        WorkingDirectory = Join-Path $repoRoot "backend"
        Executable = $MavenExecutable
        Arguments = @(
            "-Pintegration-test",
            "-Dit.test=$($backendSelectors -join ',')",
            "verify"
        )
    }
}

if ($pythonSelectors.Count -gt 0) {
    $commandSpecs += [pscustomobject]@{
        Name = "agent-pytest"
        WorkingDirectory = Join-Path $repoRoot "agent-engine"
        Executable = $PythonExecutable
        Arguments = @("-m", "pytest", "-q", "-s", "--tb=short") + $pythonSelectors
    }
}

$runStartedAt = [DateTimeOffset]::UtcNow
$runId = Get-Date -Format "yyyyMMdd-HHmmssfff"
$reportDirectory = Join-Path $repoRoot "build/failure-drills/$runId"
New-Item -ItemType Directory -Path $reportDirectory -Force | Out-Null

function Invoke-LoggedCommand {
    param([pscustomobject]$Spec)

    $startedAt = [DateTimeOffset]::UtcNow
    $logPath = Join-Path $reportDirectory "$($Spec.Name).log"
    $exitCode = 0
    Write-Host ("Running {0}: {1} {2}" -f @(
        $Spec.Name,
        $Spec.Executable,
        ($Spec.Arguments -join " ")
    ))

    Push-Location $Spec.WorkingDirectory
    try {
        try {
            & $Spec.Executable @($Spec.Arguments) 2>&1 |
                Tee-Object -FilePath $logPath |
                Out-Host
            $exitCode = $LASTEXITCODE
        } catch {
            $exitCode = 127
            ($_ | Out-String) |
                Tee-Object -FilePath $logPath |
                Out-Host
        }
    } finally {
        Pop-Location
    }

    $completedAt = [DateTimeOffset]::UtcNow
    return [pscustomobject]@{
        name = $Spec.Name
        executable = $Spec.Executable
        arguments = @($Spec.Arguments)
        exitCode = $exitCode
        startedAt = $startedAt.ToString("o")
        completedAt = $completedAt.ToString("o")
        durationMs = [math]::Round(($completedAt - $startedAt).TotalMilliseconds)
        log = "build/failure-drills/$runId/$($Spec.Name).log"
    }
}

$commandResults = @()
foreach ($spec in $commandSpecs) {
    $commandResults += Invoke-LoggedCommand $spec
}

$observations = foreach ($result in $commandResults) {
    $absoluteLog = Join-Path $repoRoot $result.log
    if (Test-Path $absoluteLog) {
        Select-String -Path $absoluteLog -Pattern "failureDrill=" |
            ForEach-Object {
                if ($_.Line -match "(failureDrill=.*)$") {
                    $Matches[1].Trim()
                }
            }
    }
}

$drillResults = foreach ($drill in $selectedDrills) {
    $requiredCommands = @()
    if (@($drill.BackendTests).Count -gt 0) {
        $requiredCommands += "backend-integration"
    }
    if (@($drill.PythonTests).Count -gt 0) {
        $requiredCommands += "agent-pytest"
    }
    $requiredResults = @(
        $commandResults | Where-Object { $requiredCommands -contains $_.name }
    )
    $failedCommands = @($requiredResults | Where-Object { $_.exitCode -ne 0 })

    [pscustomobject]@{
        name = $drill.Name
        status = if ($failedCommands.Count -eq 0) { "passed" } else { "failed" }
        rto = $drill.Rto
        consistencyTarget = $drill.ConsistencyTarget
        alerts = @($drill.Alerts)
        backendTests = @($drill.BackendTests)
        pythonTests = @($drill.PythonTests)
    }
}

$failed = @($commandResults | Where-Object { $_.exitCode -ne 0 })
$runCompletedAt = [DateTimeOffset]::UtcNow
$report = [ordered]@{
    schemaVersion = 1
    scenario = $Scenario
    status = if ($failed.Count -eq 0) { "passed" } else { "failed" }
    startedAt = $runStartedAt.ToString("o")
    completedAt = $runCompletedAt.ToString("o")
    durationMs = [math]::Round(($runCompletedAt - $runStartedAt).TotalMilliseconds)
    manualDatabaseRepairs = 0
    commands = @($commandResults)
    drills = @($drillResults)
    observations = @($observations)
}
$reportPath = Join-Path $reportDirectory "summary.json"
$report | ConvertTo-Json -Depth 8 | Set-Content -Path $reportPath -Encoding UTF8

Write-Host "Failure drill report: $reportPath"
if ($failed.Count -gt 0) {
    exit 1
}
