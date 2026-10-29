param(
    [Parameter(Mandatory=$true)][string]$BatchRoot,
    [Parameter(Mandatory=$true)][string]$MaterialId,
    [Parameter(Mandatory=$true)][string]$OutputRoot
)
$ErrorActionPreference='Stop'
$rowRoot=(Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$batch=(Resolve-Path -LiteralPath $BatchRoot).Path
$job=Get-Content -LiteralPath (Join-Path $batch 'jobs.json') -Raw | ConvertFrom-Json | Where-Object { $_.id -eq $MaterialId }
if(!$job){throw 'Material id not in verified selection manifest'}
$tile=Join-Path $job.directory 'tile.png'
if(!(Test-Path -LiteralPath $tile)){throw 'Expanded source is not ready'}
$lockPath=Join-Path $env:TEMP 'jb-gradle.lock'
$owner='CodexPaperBake-'+[guid]::NewGuid().ToString()
$python='C:/Users/JoyRaptor/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
$tool=Join-Path $PSScriptRoot 'build_approved_evaluation.py'
New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null
$out=(Resolve-Path -LiteralPath $OutputRoot).Path
$assetDir=Join-Path $out 'assets'
$startFree=[math]::Floor((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024)
if($startFree -lt 2200){Write-Output "NOT_ADMITTED: freeRAM=$startFree MiB";exit 75}
try{$stream=[IO.File]::Open($lockPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)}catch{Write-Output 'NOT_ADMITTED: shared lock held';exit 75}
$writer=[IO.StreamWriter]::new($stream);$writer.WriteLine($owner);$writer.WriteLine((Get-Date).ToString('o'));$writer.Dispose()
$child=$null;$peak=0L;$lowestFree=$startFree;$stopReason=$null;$code=1
$watch=[Diagnostics.Stopwatch]::StartNew()
try{
    $fresh=[math]::Floor((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024)
    if($fresh -lt 2200){Write-Output "NOT_ADMITTED after claim: freeRAM=$fresh MiB";exit 75}
    # Record watcher presence; never pause/kill a watcher. Conservatively defer if
    # any Java/Python process is already alive; no independent queue or retries.
    $others=@(Get-Process -Name java,python,pythonw -ErrorAction SilentlyContinue)
    $watchers=@(Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'watch-build\.ps1' } | Select-Object ProcessId,Name)
    if($others.Count){Write-Output 'NOT_ADMITTED: existing Java/Python processes require coordinator review';exit 75}
    $stdout=Join-Path $out ($MaterialId+'-stdout.log');$stderr=Join-Path $out ($MaterialId+'-stderr.log')
    $arguments=@('-B',('"'+$tool+'"'),('"'+$batch+'"'),('"'+$assetDir+'"'),'--only',$MaterialId)
    $child=Start-Process -FilePath $python -ArgumentList $arguments -WorkingDirectory $rowRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $lastFreeCheck=[DateTime]::UtcNow
    while(!$child.HasExited){
        $child.Refresh();$peak=[math]::Max($peak,$child.PeakWorkingSet64)
        if(([DateTime]::UtcNow-$lastFreeCheck).TotalMilliseconds -ge 500){
            $free=[math]::Floor((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024)
            $lowestFree=[math]::Min($lowestFree,$free);$lastFreeCheck=[DateTime]::UtcNow
            if($free -lt 700){$stopReason='free RAM below700MiB'}
        }
        if($peak -gt 650MB){$stopReason='owned child exceeded650MiB peak'}
        if($stopReason){Stop-Process -Id $child.Id -ErrorAction SilentlyContinue;break}
        Start-Sleep -Milliseconds 100
    }
    $child.WaitForExit();$code=$child.ExitCode
    $watch.Stop()
    $inputs=@($tile,$tool,(Join-Path $PSScriptRoot 'pack.py'),(Join-Path $PSScriptRoot 'photo2paper.py'),(Join-Path $PSScriptRoot 'felt2paper.py')) | ForEach-Object { @{path=$_;sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash} }
    $receipt=@{material=$MaterialId;owner=$owner;source=(git -C $rowRoot rev-parse HEAD);startedFreeMiB=$startFree;lowestObservedFreeMiB=$lowestFree;peakChildMiB=[math]::Round($peak/1MB,2);seconds=$watch.Elapsed.TotalSeconds;exitCode=$code;stopReason=$stopReason;inputs=$inputs;watchersObserved=$watchers;output=$assetDir}
    $receipt | ConvertTo-Json -Depth 6 | Set-Content -Encoding utf8 (Join-Path $out ($MaterialId+'-resource-receipt.json'))
    Get-Content -LiteralPath $stdout -Tail 5;Get-Content -LiteralPath $stderr -Tail 8
    $receipt | ConvertTo-Json -Depth 6
    if($stopReason){exit 76}
}finally{
    if($child -and !$child.HasExited){$child.WaitForExit()}
    if((Test-Path -LiteralPath $lockPath) -and ((Get-Content -LiteralPath $lockPath -TotalCount 1) -eq $owner)){Remove-Item -LiteralPath $lockPath}
}
exit $code
