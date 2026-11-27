$ErrorActionPreference='Stop'
if(Test-Path "$env:TEMP/jb-gradle.lock") {Write-Output 'BUILD_LOCK_BUSY'; exit 75}
& C:/Users/JoyRaptor/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe mutate_library_assets.py
try {
    & ./build_library.ps1
    $mutationCode=$LASTEXITCODE
    if($mutationCode -eq 75) {throw 'Build lock was claimed; mutation not run'}
    Copy-Item joybrush/core/build/test-results/jvmTest/TEST-cc.joycreator.joybrush.core.paper.LaunchMaterialsTest.xml mutation-library.xml
} finally {
    foreach($file in @('look_canvas_linen','surface_crumpled_flattened','surface_cement','surface_sugarcane_pulp')) {
        Move-Item -LiteralPath "joybrush/assets/paper/$file.original" -Destination "joybrush/assets/paper/$file.png" -Force
    }
    Move-Item -LiteralPath "joybrush/assets/paper/catalogue.original" -Destination "joybrush/assets/paper/catalogue.json" -Force
}
Write-Output "MUTATION_EXIT=$mutationCode"
if($mutationCode -eq 0) {throw 'Mutation unexpectedly passed'}
& ./build_library.ps1
exit $LASTEXITCODE
