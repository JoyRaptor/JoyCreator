# cloud-build.ps1 - build the app on GitHub's free runners instead of this PC (.github/workflows/build.yml).
#
# Usage (from your own worktree):
#   .\tools\cloud-build.ps1 start <ref> [variant]   ref = a PUSHED branch or commit; variant defaultDebug (default) or playRelease
#   .\tools\cloud-build.ps1 wait  [runId]           polls every 30 s until the run ends (run it in the background)
#   .\tools\cloud-build.ps1 fetch [runId]           downloads the APK/AAB into build\cloud\<artifact>\ of this worktree
#   .\tools\cloud-build.ps1 sign  [aab]             release only: signs the bundle with the Play upload key, on this PC
#   .\tools\cloud-build.ps1 run   <ref> [variant]   start + wait + fetch in one go
# runId defaults to the last run started from this worktree (build\cloud\last-run.json).
#
# Then:  .\tools\phone.ps1 install build\cloud\<artifact>\app-default-arm64-v8a-debug.apk   (under the phone lock)
#
# The GitHub login is the one `git push` already uses, read with `git credential fill`. It stays in memory:
# never printed, never written. Nothing is ever signed on GitHub; no key or password goes there.
# Signing reads JOY_KEYSTORE_PASSWORD / JOY_KEY_PASSWORD (and optional JOY_KEYSTORE_FILE / JOY_KEY_ALIAS) from
# the environment, else the main folder's git-ignored local.properties; jarsigner reads them by name (:env),
# so they never appear on a command line or in output.

param(
    [Parameter(Position = 0)] [string] $Cmd = "help",
    [Parameter(Position = 1, ValueFromRemainingArguments = $true)] [string[]] $Rest = @()
)

$ErrorActionPreference = "Stop"
$Repo = if ($env:CLOUD_BUILD_REPO) { $env:CLOUD_BUILD_REPO } else { "JoyRaptor/JoyCreator" }
$Workflow = "build.yml"
$WorkflowRef = "joy-creator"   # the branch the workflow file is taken from; the BUILT ref is an input

$root = (& git rev-parse --show-toplevel 2>$null)
if (-not $root) { Write-Host "run this from inside a Joy Creator worktree"; exit 1 }
$outDir = Join-Path $root "build\cloud"
$stateFile = Join-Path $outDir "last-run.json"

Add-Type -AssemblyName System.Net.Http
Add-Type -AssemblyName System.IO.Compression.FileSystem
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Get-GitHubToken {
    # Never prompt (a hidden dialog would hang a background lane); never print the result.
    # A PowerShell pipe can prepend a byte-order mark that git rejects, so talk to git directly.
    $env:GCM_INTERACTIVE = "Never"
    $env:GIT_TERMINAL_PROMPT = "0"
    $psi = New-Object System.Diagnostics.ProcessStartInfo("git", "credential fill")
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $p = [System.Diagnostics.Process]::Start($psi)
    $p.StandardInput.Write("protocol=https`nhost=github.com`n`n")
    $p.StandardInput.Close()
    $text = $p.StandardOutput.ReadToEnd()
    $why = $p.StandardError.ReadToEnd().Trim()
    $p.WaitForExit()
    foreach ($l in ($text -split "`r?`n")) { if ($l -like "password=*") { return $l.Substring(9) } }
    throw "no GitHub login found through git credential fill ($why)"
}

function New-Client([string] $token) {
    $h = New-Object System.Net.Http.HttpClientHandler
    $h.AllowAutoRedirect = $false   # artifact downloads redirect to storage that must NOT get our login
    $c = New-Object System.Net.Http.HttpClient($h)
    $c.Timeout = [TimeSpan]::FromMinutes(30)
    $c.DefaultRequestHeaders.UserAgent.ParseAdd("joycreator-cloud-build")
    if ($token) {
        $c.DefaultRequestHeaders.Accept.ParseAdd("application/vnd.github+json")
        $c.DefaultRequestHeaders.Add("X-GitHub-Api-Version", "2022-11-28")
        $c.DefaultRequestHeaders.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", $token)
    }
    return $c
}

$script:api = $null
function Api([string] $method, [string] $path, $body = $null) {
    if ($null -eq $script:api) { $script:api = New-Client (Get-GitHubToken) }
    $req = New-Object System.Net.Http.HttpRequestMessage((New-Object System.Net.Http.HttpMethod($method)), "https://api.github.com/repos/$Repo$path")
    if ($null -ne $body) {
        $json = $body | ConvertTo-Json -Depth 6 -Compress
        $req.Content = New-Object System.Net.Http.StringContent($json, [Text.Encoding]::UTF8, "application/json")
    }
    $res = $script:api.SendAsync($req).GetAwaiter().GetResult()
    if ([int]$res.StatusCode -ge 300 -and [int]$res.StatusCode -lt 400) { return $res }   # caller follows it
    $text = $res.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    if (-not $res.IsSuccessStatusCode) {
        $short = if ($text.Length -gt 300) { $text.Substring(0, 300) } else { $text }
        throw "GitHub answered $([int]$res.StatusCode) to $method $path : $short"
    }
    if ($text) { return ($text | ConvertFrom-Json) }
}

function Save-State($o) {
    New-Item -ItemType Directory -Force $outDir | Out-Null
    ($o | ConvertTo-Json) | Set-Content -Encoding utf8 $stateFile
}
function Get-RunId {
    if ($Rest.Count -ge 1 -and $Rest[0] -match '^\d+$') { return $Rest[0] }
    if (Test-Path $stateFile) { return (Get-Content $stateFile -Raw | ConvertFrom-Json).run_id }
    throw "no run id given and no build\cloud\last-run.json here: start one first"
}

function Start-Build([string] $ref, [string] $variant) {
    if (-not $ref) { throw "usage: start <ref> [variant]" }
    if (-not $variant) { $variant = "defaultDebug" }
    # The runner can only see what is on GitHub: warn if this branch has unpushed commits.
    $localSha = (& git rev-parse --verify --quiet "$ref^{commit}" 2>$null)
    $remoteSha = (& git rev-parse --verify --quiet "origin/$ref^{commit}" 2>$null)
    if ($localSha -and $remoteSha -and $localSha -ne $remoteSha) {
        Write-Host "WARNING: your $ref is not the same as origin/$ref; the cloud builds origin's. Push first if you meant yours."
    }
    $rid = "cb-" + [Guid]::NewGuid().ToString("N").Substring(0, 8)
    Api POST "/actions/workflows/$Workflow/dispatches" @{ ref = $WorkflowRef; inputs = @{ ref = $ref; variant = $variant; request_id = $rid } } | Out-Null
    # Find our run by the tag in its name (it takes a few seconds to appear).
    for ($i = 0; $i -lt 24; $i++) {
        Start-Sleep -Seconds 5
        $runs = Api GET "/actions/workflows/$Workflow/runs?event=workflow_dispatch&per_page=30"
        $run = $runs.workflow_runs | Where-Object { $_.display_title -like "*[$rid]*" } | Select-Object -First 1
        if ($run) {
            Save-State ([ordered]@{ run_id = $run.id; ref = $ref; variant = $variant; request_id = $rid; url = $run.html_url; started = (Get-Date).ToString("s") })
            Write-Host "started run $($run.id): $variant @ $ref"
            Write-Host "  $($run.html_url)"
            return $run.id
        }
    }
    throw "the build was requested but its run did not show up within 2 minutes; look at the Actions tab"
}

function Wait-Build([string] $id) {
    $last = ""
    while ($true) {
        $r = Api GET "/actions/runs/$id"
        $now = "$($r.status)"
        if ($now -ne $last) { Write-Host ("{0:HH:mm:ss} run {1}: {2}" -f (Get-Date), $id, $now); $last = $now }
        if ($r.status -eq "completed") {
            $mins = [Math]::Round(([DateTime]$r.updated_at - [DateTime]$r.run_started_at).TotalMinutes, 1)
            Write-Host "finished: $($r.conclusion) after $mins min ($($r.html_url))"
            if ($r.conclusion -ne "success") {
                $jobs = Api GET "/actions/runs/$id/jobs"
                foreach ($j in $jobs.jobs) {
                    foreach ($s in $j.steps) { if ($s.conclusion -eq "failure") { Write-Host "  failed step: $($s.name)" } }
                }
                return $false
            }
            return $true
        }
        Start-Sleep -Seconds 30
    }
}

function Get-Build([string] $id) {
    $arts = Api GET "/actions/runs/$id/artifacts"
    $a = $arts.artifacts | Select-Object -First 1
    if (-not $a) { throw "run $id has no artifact (did it succeed? artifacts are kept 3 days)" }
    $res = Api GET "/actions/artifacts/$($a.id)/zip"
    if ($res -isnot [System.Net.Http.HttpResponseMessage]) { throw "GitHub did not hand out a download link" }
    $link = $res.Headers.Location
    New-Item -ItemType Directory -Force $outDir | Out-Null
    $zip = Join-Path $outDir "$($a.name).zip"
    $dest = Join-Path $outDir $a.name
    $plain = New-Client $null   # the storage link is pre-signed: no login goes with it
    $dl = $plain.GetAsync($link, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
    if (-not $dl.IsSuccessStatusCode) { throw "download failed: $([int]$dl.StatusCode)" }
    $in = $dl.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
    $fs = [IO.File]::Create($zip)
    try { $in.CopyTo($fs) } finally { $fs.Dispose(); $in.Dispose() }
    if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
    [IO.Compression.ZipFile]::ExtractToDirectory($zip, $dest)
    Remove-Item $zip
    Write-Host "fetched $($a.name) into build\cloud\$($a.name):"
    Get-ChildItem $dest | ForEach-Object { Write-Host ("  {0}  {1:N1} MB" -f $_.Name, ($_.Length / 1MB)) }
    $apk = Get-ChildItem $dest -Filter *.apk | Select-Object -First 1
    if ($apk) { Write-Host "install: .\tools\phone.ps1 install build\cloud\$($a.name)\$($apk.Name)" }
    $aab = Get-ChildItem $dest -Filter *.aab | Select-Object -First 1
    if ($aab) { Write-Host "unsigned bundle; sign it here: .\tools\cloud-build.ps1 sign build\cloud\$($a.name)\$($aab.Name)" }
}

function Find-Tool([string] $exe) {
    $c = @()
    if ($env:JAVA_HOME) { $c += (Join-Path $env:JAVA_HOME "bin\$exe") }
    $c += "C:\Program Files\Android\Android Studio\jbr\bin\$exe"
    foreach ($p in $c) { if (Test-Path $p) { return $p } }
    $g = Get-Command $exe -ErrorAction SilentlyContinue
    if ($g) { return $g.Source }
    throw "$exe not found (set JAVA_HOME to a JDK 17)"
}

function Sign-Bundle([string] $aab) {
    if (-not $aab) {
        $aab = Get-ChildItem $outDir -Recurse -Filter *.aab -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notlike "*-signed.aab" } | Sort-Object LastWriteTime | Select-Object -Last 1 -ExpandProperty FullName
    }
    if (-not $aab -or -not (Test-Path $aab)) { throw "no .aab to sign: fetch a playRelease build first" }
    # Settings: environment first, else the main folder's local.properties (the Play lane's setup). Kept in this
    # process's environment only, where jarsigner reads them by name.
    $mainLp = Join-Path (Split-Path (& git rev-parse --path-format=absolute --git-common-dir)) "local.properties"
    $lp = @{}
    if (Test-Path $mainLp) {
        foreach ($l in Get-Content $mainLp) { if ($l -match '^\s*([A-Z_]+)\s*=\s*(.*)$') { $lp[$Matches[1]] = $Matches[2].Trim() } }
    }
    foreach ($k in "KEYSTORE_FILE", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD") {
        $e = "JOY_$k"
        if (-not [Environment]::GetEnvironmentVariable($e) -and $lp.ContainsKey($k)) { [Environment]::SetEnvironmentVariable($e, $lp[$k], "Process") }
    }
    if (-not $env:JOY_KEYSTORE_FILE) { $env:JOY_KEYSTORE_FILE = "D:\JoyCreator-keys\joycreator-upload.jks" }
    if (-not $env:JOY_KEY_ALIAS) { $env:JOY_KEY_ALIAS = "upload" }
    if (-not $env:JOY_KEY_PASSWORD) { $env:JOY_KEY_PASSWORD = $env:JOY_KEYSTORE_PASSWORD }
    if (-not (Test-Path $env:JOY_KEYSTORE_FILE)) { throw "upload key not found (see tasks/launch/PLAY_UPLOAD_KEY.md)" }
    if (-not $env:JOY_KEYSTORE_PASSWORD) { throw "set JOY_KEYSTORE_PASSWORD (or KEYSTORE_PASSWORD in the main folder's local.properties)" }
    $signed = $aab -replace '\.aab$', '-signed.aab'
    $js = Find-Tool "jarsigner.exe"
    & $js -keystore $env:JOY_KEYSTORE_FILE -storepass:env JOY_KEYSTORE_PASSWORD -keypass:env JOY_KEY_PASSWORD `
        -sigalg SHA256withRSA -digestalg SHA-256 -signedjar $signed $aab $env:JOY_KEY_ALIAS | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "jarsigner failed (wrong password or alias?)" }
    & $js -verify $signed | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "the signed bundle did not verify" }
    Write-Host ("signed: {0} ({1:N1} MB); upload this one to Play" -f $signed, ((Get-Item $signed).Length / 1MB))
}

switch ($Cmd) {
    "start" { Start-Build $Rest[0] $(if ($Rest.Count -ge 2) { $Rest[1] } else { "" }) | Out-Null }
    "wait"  { if (-not (Wait-Build (Get-RunId))) { exit 1 } }
    "fetch" { Get-Build (Get-RunId) }
    "sign"  { Sign-Bundle $(if ($Rest.Count -ge 1) { $Rest[0] } else { "" }) }
    "run" {
        $id = Start-Build $Rest[0] $(if ($Rest.Count -ge 2) { $Rest[1] } else { "" })
        if (-not (Wait-Build $id)) { exit 1 }
        Get-Build $id
    }
    default { Get-Content $PSCommandPath -TotalCount 12 | ForEach-Object { $_ -replace '^# ?', '' } }
}
