$ErrorActionPreference = 'Stop'

$expectedExit = 37
$consoleLimit = 8000
$statusLine = 'Running test command; output is buffered and retained in target/ci-test.log.'
$consoleLog = [System.IO.Path]::GetTempFileName()
$fixture = [System.IO.Path]::Combine(
    [System.IO.Path]::GetTempPath(),
    "fractalui-ci-fixture-$([guid]::NewGuid().ToString('N')).ps1"
)
$currentPowerShell = (Get-Process -Id $PID).Path

try {
    @'
[Console]::Write("probe-start`n" + ('z' * 12000) + "`nprobe-end`n")
exit 37
'@ | Set-Content -Path $fixture -Encoding utf8NoBOM

    & $currentPowerShell -NoProfile -File scripts/run-ci-tests.ps1 `
        $currentPowerShell -NoProfile -File $fixture *> $consoleLog
    $actualExit = $LASTEXITCODE

    if ($actualExit -ne $expectedExit) {
        throw "Expected exit code $expectedExit, got $actualExit"
    }

    $console = [System.IO.File]::ReadAllText($consoleLog)
    if ($console.Length -gt $consoleLimit) {
        throw "Console output exceeded limit: $($console.Length) > $consoleLimit characters"
    }

    $fullLogPath = 'target/ci-test.log'
    if (-not (Test-Path $fullLogPath -PathType Leaf)) {
        throw "Missing full log: $fullLogPath"
    }
    $fullLog = [System.IO.File]::ReadAllText($fullLogPath)
    if ($fullLog -notmatch '\Aprobe-start\r?\n' -or $fullLog -notmatch '\r?\nprobe-end\r?\n\z') {
        throw 'Full log did not preserve the fixture output'
    }

    $statusPattern = '\A' + [regex]::Escape($statusLine) + '\r?\n'
    $statusMatch = [regex]::Match($console, $statusPattern)
    if (-not $statusMatch.Success -or $console -notmatch 'probe-start\r?\n' -or
            $console -notmatch '\r?\nprobe-end\r?\n\z') {
        throw 'Console output did not preserve both diagnostic edges'
    }

    $markerPattern = '(?:\r?\n)\.\.\. \[(\d+) characters omitted; full output: target/ci-test\.log\] \.\.\.(?:\r?\n)'
    $marker = [regex]::Match($console, $markerPattern)
    if (-not $marker.Success) {
        throw 'Missing truncation marker'
    }

    $reportedOmitted = [int]$marker.Groups[1].Value
    $displayedLogLength = $console.Length - $statusMatch.Length - $marker.Length
    $actualOmitted = $fullLog.Length - $displayedLogLength
    if ($reportedOmitted -ne $actualOmitted) {
        throw "Reported omission $reportedOmitted does not match actual omission $actualOmitted"
    }

    [Console]::WriteLine(
        "PowerShell CI wrapper contract passed: exit=$actualExit console=$($console.Length) " +
        "full=$($fullLog.Length) omitted=$actualOmitted"
    )
} finally {
    Remove-Item $consoleLog, $fixture -Force -ErrorAction SilentlyContinue
}

exit 0
