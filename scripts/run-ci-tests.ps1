param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$Executable,

    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$CommandArguments
)

$ErrorActionPreference = 'Stop'
$consoleLimit = 8000
$markerBudget = 160
$statusLine = 'Running test command; output is buffered and retained in target/ci-test.log.'
$status = $statusLine + [Environment]::NewLine
$temporaryLog = [System.IO.Path]::GetTempFileName()

try {
    [Console]::Write($status)
    & $Executable @CommandArguments *> $temporaryLog
    $exitCode = if ($null -eq $LASTEXITCODE) { 1 } else { $LASTEXITCODE }

    New-Item -ItemType Directory -Force -Path target | Out-Null
    $pythonCommand = Get-Command python -ErrorAction SilentlyContinue
    if ($null -eq $pythonCommand) { $pythonCommand = Get-Command python3 -ErrorAction Stop }
    & $pythonCommand.Source (Join-Path $PSScriptRoot 'privacy.py') $temporaryLog target/ci-test.log
    if ($LASTEXITCODE -ne 0) { throw 'Privacy filtering failed; output withheld.' }
    Copy-Item target/ci-test.log $temporaryLog -Force

    $output = [System.IO.File]::ReadAllText($temporaryLog)
    if ($exitCode -eq 0 -or ($status.Length + $output.Length) -le $consoleLimit) {
        [Console]::Write($output)
    } else {
        $excerptLimit = $consoleLimit - $status.Length - $markerBudget
        [int]$headLength = [Math]::Floor($excerptLimit / 2)
        $tailLength = $excerptLimit - $headLength
        [Console]::Write($output.Substring(0, $headLength))
        $omitted = $output.Length - $excerptLimit
        [Console]::Write("`n... [$omitted characters omitted; full output: target/ci-test.log] ...`n")
        [Console]::Write($output.Substring($output.Length - $tailLength))
    }

    exit $exitCode
} finally {
    Remove-Item $temporaryLog -Force -ErrorAction SilentlyContinue
}
