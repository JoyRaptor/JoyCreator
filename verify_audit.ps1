$ErrorActionPreference='Stop'
$lockPath=Join-Path $env:TEMP 'jb-gradle.lock'
function Take-BuildLock {
    if (Test-Path -LiteralPath $lockPath) {
        $age=(Get-Date)-(Get-Item -LiteralPath $lockPath).LastWriteTime
        if ($age.TotalMinutes -lt 45) { Write-Output 'BUILD_LOCK_BUSY'; exit 75 }
        Remove-Item -LiteralPath $lockPath
    }
    try {$stream=[IO.File]::Open($lockPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)}
    catch {Write-Output 'BUILD_LOCK_BUSY'; exit 75}
    $writer=[IO.StreamWriter]::new($stream)
    $writer.WriteLine('JB-9.03b '+(Get-Date).ToString('o')); $writer.Dispose()
}
$env:JOYBRUSH_TESTDATA='C:/+Projects/Screenrecorder/FadCam/joybrush/testdata-local'
$argsCommon=@('--no-daemon','--no-watch-fs','--max-workers=1','-Dorg.gradle.jvmargs=-Xmx768m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=C:/Temp -Djdk.net.unixdomain.tmpdir=C:/Temp','-Pkotlin.compiler.execution.strategy=in-process','-p','joybrush')
$source='joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/paper/SurfaceMaps.kt'
Take-BuildLock
$original=[IO.File]::ReadAllText((Join-Path $PWD $source))
try {
    $mutated=$original.Replace('10.0 * (height[rowDown + x] - height[rowUp + x])','2.0 * (height[rowDown + x] - height[rowUp + x])')
    if($mutated -eq $original){throw 'Mutation target missing'}
    [IO.File]::WriteAllText((Join-Path $PWD $source),$mutated,[Text.UTF8Encoding]::new($false))
    & .\gradlew.bat @argsCommon :core:jvmTest --tests '*SurfaceMapsTest.aSineRampInYReadsAsTheCentralDifferenceAndHasNoHorizontalSlope' --rerun-tasks *> audit-mutation.log
    $mutationExit=$LASTEXITCODE
} finally {
    [IO.File]::WriteAllText((Join-Path $PWD $source),$original,[Text.UTF8Encoding]::new($false))
    Remove-Item -LiteralPath $lockPath
}
Get-Content audit-mutation.log -Tail 16
$xmlPath='joybrush/core/build/test-results/jvmTest/TEST-cc.joycreator.joybrush.core.paper.SurfaceMapsTest.xml'
if (!(Test-Path $xmlPath)) {throw 'Mutation did not reach the test'}
[xml]$mutationXml=Get-Content $xmlPath
if($mutationExit -eq 0 -or [int]$mutationXml.testsuite.failures -lt 1){throw 'Mutation did not fail the dy assertion'}
Write-Output ('DY_MUTATION_RED '+$mutationXml.testsuite.failures)
Take-BuildLock
try {
    & .\gradlew.bat @argsCommon :core:jvmTest :androidkit:test --rerun-tasks *> audit-tests.log
    $fullExit=$LASTEXITCODE
} finally {Remove-Item -LiteralPath $lockPath}
Get-Content audit-tests.log -Tail 20
exit $fullExit
