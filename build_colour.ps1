param([string[]]$Targets=@(':core:jvmTest'))
$ErrorActionPreference='Stop'
$rowRoot='C:/Users/JOYRAP~1/AppData/Local/Temp/jb-9.07'
Set-Location -LiteralPath $rowRoot
$lockPath=Join-Path $env:TEMP 'jb-gradle.lock'
if(Test-Path -LiteralPath $lockPath) {
    if(((Get-Date)-(Get-Item -LiteralPath $lockPath).LastWriteTime).TotalMinutes -lt 45) {Write-Output 'BUILD_LOCK_BUSY'; exit 75}
    Remove-Item -LiteralPath $lockPath
}
try {$stream=[IO.File]::Open($lockPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)} catch {Write-Output 'BUILD_LOCK_BUSY'; exit 75}
$writer=[IO.StreamWriter]::new($stream); $writer.WriteLine('JB-9.07 '+(Get-Date).ToString('o')); $writer.Dispose()
$env:JOYBRUSH_TESTDATA='C:/+Projects/Screenrecorder/FadCam/joybrush/testdata-local'
try {
    & .\gradlew.bat --no-daemon --no-watch-fs --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx768m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=C:/Temp -Djdk.net.unixdomain.tmpdir=C:/Temp' '-Pkotlin.compiler.execution.strategy=in-process' -I "$rowRoot/paper_test_heap.gradle" -p joybrush @Targets --tests '*PaperPreviewsTest' --tests '*PaperStateTest' *> row-tests.log
    $code=$LASTEXITCODE
} finally {Remove-Item -LiteralPath $lockPath}
Get-Content row-tests.log -Tail 35
exit $code

