param([string]$BatchRoot,[string]$MaterialId,[string]$OutputRoot,[ValidateSet(512,1024)][int]$Size=1024)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'approved_bake_lease.ps1')
if(!$BatchRoot -or !$MaterialId -or !$OutputRoot){throw 'BatchRoot, MaterialId and OutputRoot are required'}
$rowRoot=(Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$batch=(Resolve-Path -LiteralPath $BatchRoot).Path
$job=Get-Content -LiteralPath (Join-Path $batch 'jobs.json') -Raw | ConvertFrom-Json | Where-Object { $_.id -eq $MaterialId }
if(!$job){throw 'Material id not in verified selection manifest'}
if($MaterialId -ne 'expanded-firm'){throw 'Only expanded-firm profiles are authorized'}
$tile=Join-Path $BatchRoot 'evaluation-pack/receipt_expanded-firm.json'
if(!(Test-Path -LiteralPath $tile)){throw 'Expanded source is not ready'}
$lockPath=Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'Temp/jb-gradle.lock'
$owner='CodexPaperBake-'+[guid]::NewGuid().ToString()
$python='C:/Users/JoyRaptor/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
$tool=Join-Path $PSScriptRoot 'verify_approved_phone_profile.py'
$startFree=[math]::Floor((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024)
if($startFree -lt 2200){Write-Output "NOT_ADMITTED: freeRAM=$startFree MiB";exit 75}
New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null
$out=(Resolve-Path -LiteralPath $OutputRoot).Path
$assetDir=$out
$init={param($stream,$who)
    # Initialization remains inside the lease try/finally. Keep the stream open
    # through the entire job, allowing readers but forbidding replacement/writes.
    $bytes=[Text.Encoding]::UTF8.GetBytes($who+[Environment]::NewLine+(Get-Date).ToString('o'))
    $stream.Write($bytes,0,$bytes.Length);$stream.Flush()
}
$body={param($s)
    $fresh=[math]::Floor((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024)
    $s.Data.startedFreeMiB=$fresh
    if($fresh -lt 2200){$s.Code=75;$s.Data.reason='RAM below admission after claim';return}
    $others=@(Get-Process -Name java,python,pythonw -ErrorAction SilentlyContinue)
    $watchers=@(Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match 'watch-build\.ps1' } | Select-Object ProcessId,Name)
    $s.Data.watchersObserved=$watchers
    if($others.Count -or $watchers.Count){$s.Code=75;$s.Data.reason='Existing heavy process or uncertain watcher requires root review';return}
    $stdout=Join-Path $out ($MaterialId+'-stdout.log');$stderr=Join-Path $out ($MaterialId+'-stderr.log')
    $s.Data.stdout=$stdout;$s.Data.stderr=$stderr
    $masterAssets=Join-Path $batch 'evaluation-pack/assets'
    $arguments=@('-B',('"'+$tool+'"'),('"'+$masterAssets+'"'),('"'+$tile+'"'),('"'+$assetDir+'"'),'--size',$Size)
    $watch=[Diagnostics.Stopwatch]::StartNew()
    $s.Child=Start-Process -FilePath $python -ArgumentList $arguments -WorkingDirectory $rowRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $s.TerminationConfirmed=$false;$s.Data.ownedChildId=$s.Child.Id
    $lastFreeCheck=[DateTime]::UtcNow;$s.Data.lowestObservedFreeMiB=$fresh
    while(!$s.Child.HasExited){
        $s.Child.Refresh()
        $sample=$s.Child.PeakWorkingSet64
        if($sample -gt 0){$s.ObservedPeakBytes=[math]::Max([long]$s.ObservedPeakBytes,$sample);$s.PeakObservation='maximum observed OS cumulative peak sample'}
        if(([DateTime]::UtcNow-$lastFreeCheck).TotalMilliseconds -ge 500){
            $free=[math]::Floor((Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory/1024)
            $s.Data.lowestObservedFreeMiB=[math]::Min($s.Data.lowestObservedFreeMiB,$free);$lastFreeCheck=[DateTime]::UtcNow
            if($free -lt 700){throw 'Observed free RAM below700MiB'}
        }
        if($s.ObservedPeakBytes -gt 650MB){throw 'Owned child exceeded650MiB observed peak'}
        Start-Sleep -Milliseconds 100
    }
    $s.Child.WaitForExit();$s.TerminationConfirmed=$true;$s.Code=$s.Child.ExitCode
    if($null -eq $s.ObservedPeakBytes){$s.PeakObservation='unobserved: child exited before first sample'}
    $watch.Stop();$s.Data.seconds=$watch.Elapsed.TotalSeconds
}
$stop={param($s)
    # Only the Process object returned by our Start-Process can be stopped.
    try {$s.Child.Kill()} catch {$s.Data.killMessage=$_.Exception.Message}
    try {$s.TerminationConfirmed=$s.Child.WaitForExit(5000)} catch {$s.TerminationConfirmed=$false;$s.Data.waitMessage=$_.Exception.Message}
}
$record={param($s)
    $inputs=@($tile,$tool,(Join-Path $PSScriptRoot 'approved_phone_profile.py'),(Join-Path $PSScriptRoot 'pack.py'),(Join-Path $PSScriptRoot 'photo2paper.py'),(Join-Path $PSScriptRoot 'felt2paper.py'),$PSCommandPath,(Join-Path $PSScriptRoot 'approved_bake_lease.ps1')) | ForEach-Object { @{path=$_;sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash} }
    $peakMiB=if($null -eq $s.ObservedPeakBytes){$null}else{[math]::Round($s.ObservedPeakBytes/1MB,2)}
    @{material=$MaterialId;owner=$owner;source=(git -C $rowRoot rev-parse HEAD);observedCumulativePeakMiB=$peakMiB;peakObservation=$s.PeakObservation;exitCode=$s.Code;failure=$s.Failure;claimRetained=$s.Retained;terminationConfirmed=$s.TerminationConfirmed;inputs=$inputs;data=$s.Data;output=$assetDir} |
        ConvertTo-Json -Depth 7 | Set-Content -Encoding utf8 (Join-Path $out ($MaterialId+'-resource-receipt.json'))
}
$result=Invoke-ApprovedBakeLease $lockPath $owner $init $body $stop $record
$result | Select-Object Owner,Acquired,TerminationConfirmed,Failure,Code,Retained,ObservedPeakBytes,PeakObservation,Data | ConvertTo-Json -Depth 5
if($result.Retained){exit 77}
exit $result.Code
