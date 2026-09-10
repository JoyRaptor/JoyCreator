param(
  # Sandbox phone serial. Real serials are not stored in this repo - pass one in,
  # or set JOY_SANDBOX_SERIAL, or leave it blank when only one device is attached.
  [string]$Serial = $env:JOY_SANDBOX_SERIAL
)

$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$src = "tasks/fade_8b_verify.json"
$id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeee0001"
$dstDir = "files/faditor/projects/$id"
$dst = "$dstDir/project.json"

# Build the device selector once. An explicit serial is safer: a bare `adb shell`
# silently targets the wrong phone (or nothing at all, when a stale offline entry
# is in the list) and turns a verification into a no-op.
$sel = @()
if ($Serial) {
  $sel = @("-s", $Serial)
} else {
  $attached = (& $adb devices | Select-String "\tdevice$").Count
  if ($attached -ne 1) {
    Write-Output "REFUSING: $attached devices attached and no serial given."
    Write-Output "  Pass -Serial <serial>, or set JOY_SANDBOX_SERIAL, or attach only the sandbox phone."
    exit 1
  }
}

& $adb @sel shell "run-as com.fadcam.beta mkdir -p $dstDir"
Get-Content $src -Raw | & $adb @sel shell "run-as com.fadcam.beta sh -c 'cat > $dst'"
if ($LASTEXITCODE -eq 0) { Write-Output "pushed $id" } else { Write-Output "failed $LASTEXITCODE" }
& $adb @sel shell "run-as com.fadcam.beta cat $dst" | Select-String "imageFade"
