$ErrorActionPreference='Stop'
if(Test-Path "$env:TEMP/jb-gradle.lock") {Write-Output 'BUILD_LOCK_BUSY'; exit 75}
$path='joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/paper/PaperPreviews.kt'
$original=[IO.File]::ReadAllBytes((Resolve-Path $path))
$source=Get-Content $path -Raw
try {
    [IO.File]::WriteAllText((Resolve-Path $path), $source.Replace('lookId = null, tint = null', 'lookId = null, tint = current.tint'))
    & ./build_colour.ps1
    $mutationCode=$LASTEXITCODE
    if($mutationCode -eq 75) {throw 'Build lock was claimed; mutation not run'}
    Copy-Item joybrush/core/build/test-results/jvmTest/TEST-cc.joycreator.joybrush.core.paper.PaperPreviewsTest.xml mutation-colour.xml
} finally { [IO.File]::WriteAllBytes((Resolve-Path $path), $original) }
Write-Output "MUTATION_EXIT=$mutationCode"
if($mutationCode -eq 0) {throw 'Mutation unexpectedly passed'}
& ./build_colour.ps1
exit $LASTEXITCODE
