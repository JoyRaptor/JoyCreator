param(
    [Parameter(Mandatory)][string]$Job,
    [Parameter(Mandatory)][string]$Agent,
    [Parameter(Mandatory)][ValidateSet('frontier','mid')][string]$Tier,
    [Parameter(Mandatory)][ValidateSet('Doing','Review','Blocked','Ready','Integrated')][string]$State,
    [string]$Evidence = '',
    [string]$Document = 'C:/+Projects/Screenrecorder/FadCam/tasks/joybrush/CURRENT_HANDOFF.md',
    [switch]$LeadUpdate
)
$ErrorActionPreference = 'Stop'
if ($Agent -match '[|\r\n]' -or $Job -notmatch '^JB-NOW-\d+$' -or $Evidence -match '[|\r\n]') {
    throw 'Use a unique single-line agent ID and evidence without table separators.'
}
if ($State -in @('Ready','Integrated') -and -not $LeadUpdate) {
    throw 'Only the appointed lead may reopen or integrate a job.'
}
$Document = [IO.Path]::GetFullPath($Document)
# Persistent lock file; exclusivity lasts only while this handle is open.
# Never delete the lock file: deletion could let another process bypass a holder.
$guard = [IO.File]::Open("$Document.claim.lock", [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
try {
    $body = [IO.File]::ReadAllText($Document)
    $pattern = '(?m)^\| ' + [regex]::Escape($Job) + ' \| (frontier|mid) \| ([^|]+) \| ([^|]+) \| ([^\r\n]*)\|\r?$'
    $matchesFound = [regex]::Matches($body, $pattern)
    if ($matchesFound.Count -ne 1) { throw 'Expected exactly one existing queue row.' }
    $row = $matchesFound[0]
    $required = $row.Groups[1].Value
    $previous = $row.Groups[2].Value.Trim()
    $owner = $row.Groups[3].Value.Trim()
    if ($required -eq 'frontier' -and $Tier -ne 'frontier') { throw 'This job requires frontier ownership.' }
    if ($State -eq 'Doing' -and $previous -ne 'Ready') { throw "Job is $previous, not Ready; do not start." }
    if ($State -ne 'Doing' -and -not $LeadUpdate -and ($owner -ne $Agent -or $previous -ne 'Doing')) {
        throw 'Only the active owner may submit or block their Doing job.'
    }
    if ($State -in @('Review','Blocked','Integrated') -and [string]::IsNullOrWhiteSpace($Evidence)) {
        throw 'Evidence or a concrete blocker is required.'
    }
    if ([string]::IsNullOrWhiteSpace($Evidence)) { $Evidence = $row.Groups[4].Value.Trim() }
    $stamp = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
    $nextOwner = if ($State -eq 'Ready') { '—' } else { $Agent }
    $replacement = "| $Job | $required | $State | $nextOwner | $stamp; $Evidence |"
    $updated = $body.Substring(0, $row.Index) + $replacement + $body.Substring($row.Index + $row.Length)
    [IO.File]::WriteAllText($Document, $updated, [Text.UTF8Encoding]::new($false))
    Write-Output $replacement
} finally { $guard.Dispose() }
