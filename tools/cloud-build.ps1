# cloud-build.ps1 - build the app on GitHub's free runners instead of this PC (.github/workflows/build.yml).
#
# Usage (from your own worktree):
#   .\tools\cloud-build.ps1 start <ref> [variant]   ref = a PUSHED branch or commit; variant defaultDebug (default) or playRelease
#   .\tools\cloud-build.ps1 wait  [runId]           polls every 30 s until the run ends (run it in the background)
#   .\tools\cloud-build.ps1 fetch [runId]           downloads the APK/AAB into build\cloud\<artifact>\ of this worktree
#   .\tools\cloud-build.ps1 sign  [aab]             release only: signs the bundle with the Play upload key, on this PC
#   .\tools\cloud-build.ps1 run   <ref> [variant]   start + wait + fetch in one go
# runId defaults to the last run started from this worktree (build\cloud\last-run.json); "latest" = the newest run anyone started.
#
# Then:  .\tools\phone.ps1 install build\cloud\<artifact>\app-default-arm64-v8a-debug.apk   (under the phone lock)
#
# The GitHub login is the one `git push` already uses, read with `git credential fill`. It stays in memory:
# never printed, never written. Nothing is ever signed on GitHub; no key or password goes there.
# Signing reads JOY_KEYSTORE_PASSWORD / JOY_KEY_PASSWORD (and optional JOY_KEYSTORE_FILE / JOY_KEY_ALIAS) from
# the environment, else your worktree's (then the main folder's) git-ignored local.properties; jarsigner reads them by name (:env),
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
    # git rejects a byte-order mark in front of "protocol=". A PowerShell pipe adds one, and so does
    # Process.StandardInput when the console's input encoding is UTF-8 (as in Claude's PowerShell tool); .NET
    # Framework has no StandardInputEncoding, and Process.Start writes that BOM at once (its writer auto-flushes).
    # So switch the console input encoding to BOM-free UTF-8 just for the start, then write plain bytes to the raw
    # stream and close that stream itself.
    $env:GCM_INTERACTIVE = "Never"
    $env:GIT_TERMINAL_PROMPT = "0"
    $psi = New-Object System.Diagnostics.ProcessStartInfo("git", "credential fill")
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $oldIn = $null
    try { $oldIn = [Console]::InputEncoding; [Console]::InputEncoding = New-Object System.Text.UTF8Encoding($false) } catch { }
    try { $p = [System.Diagnostics.Process]::Start($psi) }
    finally { if ($oldIn) { try { [Console]::InputEncoding = $oldIn } catch { } } }
    $req = [Text.Encoding]::ASCII.GetBytes("protocol=https`nhost=github.com`n`n")
    $raw = $p.StandardInput.BaseStream
    $raw.Write($req, 0, $req.Length)
    $raw.Flush()
    $raw.Close()
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
    if ($Rest.Count -ge 1 -and $Rest[0] -eq "latest") {   # the newest cloud build anyone started
        $r = (Api GET "/actions/workflows/$Workflow/runs?per_page=1").workflow_runs | Select-Object -First 1
        if (-not $r) { throw "no cloud builds yet" }
        Write-Host "latest run $($r.id): $($r.display_title)"
        return $r.id
    }
    if (Test-Path $stateFile) { return (Get-Content $stateFile -Raw | ConvertFrom-Json).run_id }
    throw "no run id given and no build\cloud\last-run.json here: start one first"
}

function Start-Build([string] $ref, [string] $variant) {
    if (-not $ref) { throw "usage: start <ref> [variant]" }
    if (-not $variant) { $variant = "defaultDebug" }
    # A commit: the runner's checkout can only fetch a FULL 40-character commit id, and only one that is on GitHub.
    if ($ref -match '^[0-9a-fA-F]{7,40}$' -and -not (& git rev-parse --verify --quiet "refs/remotes/origin/$ref" 2>$null)) {
        $full = (& git rev-parse --verify --quiet "$ref^{commit}" 2>$null)
        if (-not $full) { throw "commit $ref is not known in this worktree (typo, or fetch first)" }
        try { Api GET "/commits/$full" | Out-Null }
        catch { throw "commit $ref is not on GitHub yet: push the branch that has it, then start again" }
        if ($full -ne $ref) { Write-Host "commit $ref = $full" }
        $ref = $full
    }
    # The runner can only see what is on GitHub: warn if this branch has unpushed commits.
    $localSha = (& git rev-parse --verify --quiet "$ref^{commit}" 2>$null)
    $remoteSha = (& git rev-parse --verify --quiet "origin/$ref^{commit}" 2>$null)
    if ($localSha -and $remoteSha -and $localSha -ne $remoteSha) { & git merge-base --is-ancestor $localSha $remoteSha 2>$null }
    if ($localSha -and $remoteSha -and $localSha -ne $remoteSha -and $LASTEXITCODE -ne 0) {
        Write-Host "WARNING: your $ref is not the same as origin/$ref; the cloud builds origin's. Push first if you meant yours."
    }
    $rid = "cb-" + [Guid]::NewGuid().ToString("N").Substring(0, 8)
    Api POST "/actions/workflows/$Workflow/dispatches" @{ ref = $WorkflowRef; inputs = @{ ref = $ref; variant = $variant; request_id = $rid } } | Out-Null
    # Find our run by the tag in its name (it takes a few seconds to appear).
    for ($i = 0; $i -lt 24; $i++) {
        Start-Sleep -Seconds 5
        $runs = Api GET "/actions/workflows/$Workflow/runs?event=workflow_dispatch&per_page=30"
        $run = $runs.workflow_runs | Where-Object { "$($_.display_title)".Contains("[$rid]") } | Select-Object -First 1
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
    New-Item -ItemType Directory -Force $outDir | Out-Null
    $zip = Join-Path $outDir "$($a.name).zip"
    $dest = Join-Path $outDir $a.name
    $plain = New-Client $null   # the storage link is pre-signed: no login goes with it
    # Big downloads get cut off ("connection was forcibly closed"), most of all when this PC's line is busy: keep
    # resuming where it stopped for as long as each try gets further; give up after 5 tries in a row that get nowhere.
    $done = $false
    $stuck = 0
    $try = 0
    $total = "{0:N0} MB" -f ($a.size_in_bytes / 1MB)
    while (-not $done -and $stuck -lt 5) {
        $try++
        $before = if (Test-Path $zip) { (Get-Item $zip).Length } else { 0 }
        try {
            $res = Api GET "/actions/artifacts/$($a.id)/zip"   # a fresh link each time: they expire after a minute
            if ($res -isnot [System.Net.Http.HttpResponseMessage]) { throw "GitHub did not hand out a download link" }
            $have = if (Test-Path $zip) { (Get-Item $zip).Length } else { 0 }
            $req = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Get, $res.Headers.Location)
            if ($have -gt 0) { $req.Headers.Range = New-Object System.Net.Http.Headers.RangeHeaderValue([long]$have, $null) }
            $dl = $plain.SendAsync($req, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
            if ([int]$dl.StatusCode -eq 416) { $done = $true; break }   # nothing left to send
            if (-not $dl.IsSuccessStatusCode) { throw "download answered $([int]$dl.StatusCode)" }
            $mode = if ([int]$dl.StatusCode -eq 206) { [IO.FileMode]::Append } else { [IO.FileMode]::Create }
            $in = $dl.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
            $fs = New-Object IO.FileStream($zip, $mode)
            # Copy by hand so a silent stall (no bytes for 60 s) counts as a cut-off and resumes.
            $buf = New-Object byte[] (1MB)
            $next = $have + 25MB
            try {
                while ($true) {
                    $t = $in.ReadAsync($buf, 0, $buf.Length)
                    if (-not $t.Wait(60000)) { throw "no data for 60 s" }
                    if ($t.Result -le 0) { break }
                    $fs.Write($buf, 0, $t.Result)
                    if ($fs.Length -ge $next) { Write-Host ("  {0:N0} MB of {1}" -f ($fs.Length / 1MB), $total); $next += 25MB }
                }
            } finally { $fs.Dispose(); $in.Dispose() }
            $done = $true
        } catch {
            $after = if (Test-Path $zip) { (Get-Item $zip).Length } else { 0 }
            if ($after -gt $before) { $stuck = 0 } else { $stuck++ }
            Write-Host ("download cut off at {0:N0} of {1} (try {2}), resuming: {3}" -f ($after / 1MB), $total, $try, $_.Exception.GetBaseException().Message)
            Start-Sleep -Seconds (5 + 5 * $stuck)
        }
    }
    if (-not $done) { throw "download got nowhere 5 times in a row; run fetch again later to resume" }
    if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
    try { [IO.Compression.ZipFile]::ExtractToDirectory($zip, $dest) }
    catch { Remove-Item $zip -ErrorAction SilentlyContinue; throw "the download was damaged and is deleted; run fetch again" }
    Remove-Item $zip
    Write-Host "fetched $($a.name) into build\cloud\$($a.name):"
    Get-ChildItem $dest | ForEach-Object { Write-Host ("  {0}  {1:N1} MB" -f $_.Name, ($_.Length / 1MB)) }
    $apk = Get-ChildItem $dest -Filter *.apk | Select-Object -First 1
    if ($apk -and $apk.Name -like "*debug*") { Resign-Debug $apk.FullName }
    if ($apk) { Write-Host "install: .\tools\phone.ps1 install build\cloud\$($a.name)\$($apk.Name)" }
    $aab = Get-ChildItem $dest -Filter *.aab | Select-Object -First 1
    if ($aab) { Write-Host "unsigned bundle; sign it here: .\tools\cloud-build.ps1 sign build\cloud\$($a.name)\$($aab.Name)" }
}

function Resign-Debug([string] $apk) {
    # Each GitHub runner makes its own throwaway debug key, and Android refuses to update an app signed with a
    # different key (the only way round is an uninstall, which wipes the owner's projects: never). So a debug APK
    # is re-signed here with this PC's debug key, the one every local build uses. Nothing goes to GitHub.
    $ks = if ($env:ANDROID_USER_HOME) { Join-Path $env:ANDROID_USER_HOME "debug.keystore" } else { Join-Path $env:USERPROFILE ".android\debug.keystore" }
    if (-not (Test-Path $ks)) { Write-Host "no debug key on this PC: this APK keeps GitHub's key and will not update an installed copy"; return }
    $bt = Get-ChildItem (Join-Path $env:LOCALAPPDATA "Android\Sdk\build-tools") -Directory |
        Sort-Object { [version](($_.Name -replace '[^0-9.].*$', '') + ".0.0" -replace '^(\d+\.\d+\.\d+).*$', '$1') } | Select-Object -Last 1
    $signer = Join-Path $bt.FullName "apksigner.bat"
    # The standard Android debug key and its well-known password "android": not a secret.
    & $signer sign --ks $ks --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android $apk | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "re-signing the debug APK failed" }
    Write-Host "re-signed with this PC's debug key, so it installs over the existing app (no uninstall)"
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
    # Settings: environment first, else this worktree's git-ignored local.properties, else the main folder's (the
    # same order the Gradle build uses). Kept in this process's environment only, where jarsigner reads them by name.
    $mainLp = Join-Path (Split-Path (& git rev-parse --path-format=absolute --git-common-dir)) "local.properties"
    $lp = @{}
    foreach ($file in @($mainLp, (Join-Path $root "local.properties"))) {   # later file wins: this worktree's
        if (Test-Path $file) {
            foreach ($l in Get-Content $file) { if ($l -match '^\s*([A-Z_]+)\s*=\s*(.*)$') { $lp[$Matches[1]] = $Matches[2].Trim() } }
        }
    }
    foreach ($k in "KEYSTORE_FILE", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD") {
        $e = "JOY_$k"
        if (-not [Environment]::GetEnvironmentVariable($e) -and $lp.ContainsKey($k)) { [Environment]::SetEnvironmentVariable($e, $lp[$k], "Process") }
    }
    if (-not $env:JOY_KEYSTORE_FILE) { $env:JOY_KEYSTORE_FILE = "D:\JoyCreator-keys\joycreator-upload.jks" }
    if (-not $env:JOY_KEY_ALIAS) { $env:JOY_KEY_ALIAS = "upload" }
    if (-not $env:JOY_KEY_PASSWORD) { $env:JOY_KEY_PASSWORD = $env:JOY_KEYSTORE_PASSWORD }
    if (-not (Test-Path $env:JOY_KEYSTORE_FILE)) { throw "upload key not found (see tasks/launch/PLAY_UPLOAD_KEY.md)" }
    if (-not $env:JOY_KEYSTORE_PASSWORD) { throw "set JOY_KEYSTORE_PASSWORD (or KEYSTORE_PASSWORD in this worktree's local.properties)" }
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
