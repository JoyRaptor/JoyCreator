 $adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$src = "tasks/fade_8b_verify.json"
$id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeee0001"
$dstDir = "files/faditor/projects/$id"
$dst = "$dstDir/project.json"
& $adb -s <note9-serial> shell "run-as com.fadcam.beta mkdir -p $dstDir"
Get-Content $src -Raw | & $adb -s <note9-serial> shell "run-as com.fadcam.beta sh -c 'cat > $dst'"
if ($LASTEXITCODE -eq 0) { Write-Output "pushed $id" } else { Write-Output "failed $LASTEXITCODE" }
& $adb -s <note9-serial> shell "run-as com.fadcam.beta cat $dst" | Select-String "imageFade"
