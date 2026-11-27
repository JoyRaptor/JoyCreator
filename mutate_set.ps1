$ErrorActionPreference='Stop'
if(Test-Path "$env:TEMP/jb-gradle.lock") {Write-Output 'BUILD_LOCK_BUSY'; exit 75}
& C:/Users/JoyRaptor/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe mutate_height.py
try {
    & ./build_set.ps1
    $mutationCode=$LASTEXITCODE; if($mutationCode -eq 75) {throw 'Build lock was claimed; mutation not run'}
    Copy-Item joybrush/core/build/test-results/jvmTest/TEST-cc.joycreator.joybrush.core.paper.PaperTestSetTest.xml mutation-surface.xml
} finally {
    Move-Item -LiteralPath joybrush/assets/paper/surface_canvas_linen.original -Destination joybrush/assets/paper/surface_canvas_linen.png -Force
}
Write-Output "MUTATION_EXIT=$mutationCode"
if($mutationCode -eq 0) {throw 'Mutation unexpectedly passed'}
& ./build_set.ps1
exit $LASTEXITCODE

