# Runs a Gradle command under the shared jb-gradle.lock, correctly.
#   .\with-gradle-lock.ps1 -Dir <worktree> -Task ":core:jvmTest" [-ExtraArgs @("--rerun-tasks")]
#
# WHY THIS EXISTS. On 2026-10-02 an earlier inline attempt of mine called File.WriteAllText on the lock
# path OUTSIDE its acquisition guard. When a second agent already held the lock, CreateNew threw, the
# script carried on regardless, and the WriteAllText then OVERWROTE another agent's lock token before
# deleting it. Two Gradle invocations then ran against the shared cache, which is exactly what the lock
# exists to prevent. Never delete or overwrite a lock you did not create.
param(
    [Parameter(Mandatory = $true)][string]$Dir,
    [Parameter(Mandatory = $true)][string]$Task,
    [string[]]$ExtraArgs = @(),
    # Gradle project directory, relative to $Dir. The joybrush modules live under joybrush/; the
    # standalone brush test app is its own build under tools/brushlab/testapp.
    [string]$Project = 'joybrush'
)

$ErrorActionPreference = 'Stop'
$lock = Join-Path $env:TEMP 'jb-gradle.lock'
$token = "opencode-pencil-$PID-$([DateTime]::Now.ToString('HHmmss'))"

# Acquire. If this throws we did NOT get the lock, and we must not build.
$stream = [System.IO.File]::Open($lock, 'CreateNew', 'Write', 'None')
$stream.Close()
[System.IO.File]::WriteAllText($lock, $token)
$mine = $true
Write-Output "ACQUIRED $lock = $token"

try {
    $env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=C:/Temp'
    Push-Location $Dir
    $all = @('--no-daemon', '--no-watch-fs', '-p', $Project, $Task, '--console=plain') + $ExtraArgs
    # Gradle writes its JAVA_TOOL_OPTIONS banner to stderr, and $ErrorActionPreference='Stop' would turn
    # that banner into a terminating error before the build even starts. Relax it for the call only;
    # the build's real failures arrive as a non-zero $LASTEXITCODE, which is what we act on.
    $ErrorActionPreference = 'Continue'
    & '.\gradlew.bat' @all *>&1 | Tee-Object -FilePath (Join-Path $Dir 'gradle-under-lock.log') | Select-Object -Last 25
    $code = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    Pop-Location
    Write-Output "GRADLE_EXIT=$code"
}
finally {
    # Release only a lock that is still ours, byte for byte.
    if ($mine -and (Test-Path -LiteralPath $lock)) {
        if ((Get-Content -LiteralPath $lock -Raw).Trim() -eq $token) {
            Remove-Item -LiteralPath $lock -Force
            Write-Output "RELEASED own token $token"
        }
        else {
            Write-Output "NOT OURS ANY MORE - left intact"
        }
    }
}