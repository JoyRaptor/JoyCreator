$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'approved_bake_lease.ps1')
$testRoot=Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) ('Temp/jb-paper-lease-tests-'+[guid]::NewGuid())
New-Item -ItemType Directory -Path $testRoot | Out-Null
$passed=0
try {
    foreach($case in @('write-before','write-after','monitor','unresolved','quick-exit')){
        $script:recorded=$false;$script:stopped=$false;$path=Join-Path $testRoot ($case+'.lock')
        $init={param($stream,$owner)
            if($case -eq 'write-before'){throw 'injected before claim text'}
            $bytes=[Text.Encoding]::UTF8.GetBytes($owner);$stream.Write($bytes,0,$bytes.Length);$stream.Flush()
            if($case -eq 'write-after'){throw 'injected after claim text'}
        }
        $body={param($s)
            $s.Child=[pscustomobject]@{Id=-1};$s.TerminationConfirmed=($case -eq 'quick-exit')
            if($case -eq 'quick-exit'){$s.PeakObservation='unobserved: child exited before first sample';$s.Code=0;return}
            throw 'injected monitor/CIM/Refresh failure'
        }
        $stop={param($s) $script:stopped=$true;$s.TerminationConfirmed=($case -ne 'unresolved')}
        $record={param($s) $script:recorded=$true}
        $s=Invoke-ApprovedBakeLease $path ('test-'+$case) $init $body $stop $record
        if(!$script:recorded){throw "No failure/success receipt: $case"}
        if($case -eq 'unresolved'){
            if(!(Test-Path $path) -or !$s.Retained -or !$script:stopped){throw 'Unresolved termination lost claim'}
        }else{
            if(Test-Path $path){throw "Orphaned claim: $case"}
            if($case -eq 'monitor' -and (!$script:stopped -or !$s.Failure)){throw 'Monitor error did not stop owned child'}
            if($case -eq 'quick-exit' -and ($null -ne $s.ObservedPeakBytes -or $s.PeakObservation -notlike 'unobserved*')){throw 'Quick child reported a false measured zero peak'}
        }
        $passed++
    }
    Write-Output "Bake lease fault tests: $passed passed; no full-size child, no shared claim, no phone."
} finally {
    # Only this freshly created, absolute test directory is removed; never shared claims.
    $resolved=(Resolve-Path -LiteralPath $testRoot).Path
    $allowed=Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'Temp'
    if(!$resolved.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase)){throw 'Unexpected test cleanup path'}
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
