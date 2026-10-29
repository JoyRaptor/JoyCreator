# Small injectable lifecycle used by the real bake monitor and fault tests.
function Invoke-ApprovedBakeLease {
    param([string]$ClaimPath,[string]$Owner,[scriptblock]$InitializeClaim,
          [scriptblock]$Body,[scriptblock]$StopOwned,[scriptblock]$Record)
    $state=[pscustomobject]@{Owner=$Owner;Acquired=$false;Child=$null;TerminationConfirmed=$true;
        Failure=$null;Code=1;Retained=$false;ObservedPeakBytes=$null;PeakObservation='no child samples';Data=@{}}
    $stream=$null
    try {
        try {$stream=[IO.File]::Open($ClaimPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)}
        catch {$state.Code=75;return $state}
        $state.Acquired=$true
        & $InitializeClaim $stream $Owner
        & $Body $state
    } catch {
        $state.Failure=$_.Exception.Message;$state.Code=1
        if($state.Child -and !$state.TerminationConfirmed){
            try {& $StopOwned $state} catch {$state.Failure+='; owned-child stop failed: '+$_.Exception.Message}
        }
    } finally {
        $state.Retained=$state.Acquired -and ($state.Child -and !$state.TerminationConfirmed)
        if($state.Acquired){
            try {& $Record $state} catch {Write-Warning ('Receipt write failed: '+$_.Exception.Message)}
        }
        # The exclusive file handle prevents another actor replacing this claim
        # during initialization/run/receipt. Close only after termination handling.
        if($stream){$stream.Dispose()}
        if($state.Acquired -and !$state.Retained){Remove-Item -LiteralPath $ClaimPath -ErrorAction Stop}
        elseif($state.Retained){Write-Warning ('Owned child termination unresolved; retain claim '+$ClaimPath)}
    }
    return $state
}
