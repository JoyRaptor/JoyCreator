$ErrorActionPreference='Stop'
Set-Location 'C:/Users/JOYRAP~1/AppData/Local/Temp/jb-9.06'
$lockPath=Join-Path $env:TEMP 'jb-gradle.lock'
if(Test-Path -LiteralPath $lockPath) {
    if(((Get-Date)-(Get-Item -LiteralPath $lockPath).LastWriteTime).TotalMinutes -lt 45) {Write-Output 'BUILD_LOCK_BUSY'; exit 75}
    Remove-Item -LiteralPath $lockPath
}
try {$stream=[IO.File]::Open($lockPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)} catch {exit 75}
$writer=[IO.StreamWriter]::new($stream); $writer.WriteLine('JB-9.06 mutation '+(Get-Date).ToString('o')); $writer.Dispose()
$source=Join-Path $PWD 'joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/paper/PaperState.kt'
$original=[IO.File]::ReadAllText($source)
try {
    $mutation=$original.Replace('tintSet = p.tint != null,','tintSet = false,')
    if($original -eq $mutation){throw 'Mutation target missing'}
    [IO.File]::WriteAllText($source,$mutation,[Text.UTF8Encoding]::new($false))
    & .\gradlew.bat --no-daemon --no-watch-fs --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx768m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=C:/Temp -Djdk.net.unixdomain.tmpdir=C:/Temp' '-Pkotlin.compiler.execution.strategy=in-process' -p joybrush :core:jvmTest --tests '*PaperRasterTest.constantLookTintUsesItsMeanIncludingExplicitBaseColourTint' --rerun-tasks *> mutation.log
    $code=$LASTEXITCODE
} finally {
    [IO.File]::WriteAllText($source,$original,[Text.UTF8Encoding]::new($false))
    Remove-Item -LiteralPath $lockPath
}
Get-Content mutation.log -Tail 22
[xml]$xml=Get-Content joybrush/core/build/test-results/jvmTest/TEST-cc.joycreator.joybrush.core.paper.PaperRasterTest.xml
if($code -eq 0 -or [int]$xml.testsuite.failures -ne 1){throw 'Tint mutation did not fail the expected test'}
Write-Output 'TINT_MUTATION_RED 1/1; source restored'
