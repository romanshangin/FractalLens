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
    Copy-Item $temporaryLog target/ci-test.log -Force

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
