$ErrorActionPreference='Stop'
Set-Location 'C:/Users/JOYRAP~1/AppData/Local/Temp/jb-9.06'
$lockPath=Join-Path $env:TEMP 'jb-gradle.lock'
if(Test-Path -LiteralPath $lockPath) {
    if(((Get-Date)-(Get-Item -LiteralPath $lockPath).LastWriteTime).TotalMinutes -lt 45) {Write-Output 'BUILD_LOCK_BUSY'; exit 75}
    Remove-Item -LiteralPath $lockPath
}
try {$stream=[IO.File]::Open($lockPath,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)} catch {exit 75}
$writer=[IO.StreamWriter]::new($stream); $writer.WriteLine('JB-9.06 APK '+(Get-Date).ToString('o')); $writer.Dispose()
try {
    & .\gradlew.bat --no-daemon --no-watch-fs --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=C:/Temp -Djdk.net.unixdomain.tmpdir=C:/Temp' '-Pkotlin.compiler.execution.strategy=in-process' :app:assembleDefaultDebug *> row-apk.log
    $code=$LASTEXITCODE
} finally {Remove-Item -LiteralPath $lockPath}
Get-Content row-apk.log -Tail 25
exit $code
