param(
    [string]$JsonJarPath,
    [switch]$KeepArtifacts
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$tempRoot = [IO.Path]::GetFullPath('C:\Temp').TrimEnd('\')
$out = [IO.Path]::GetFullPath((Join-Path $tempRoot 'timeline-marks-final-check'))
$expectedOut = [IO.Path]::GetFullPath('C:\Temp\timeline-marks-final-check')
if ($out -ne $expectedOut -or (Split-Path -Parent $out) -ne $tempRoot) {
    throw "Refusing unexpected scratch output path: $out"
}
$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$androidJars = Get-ChildItem -LiteralPath (Join-Path $sdkRoot 'platforms') -Filter 'android.jar' -Recurse -File -ErrorAction SilentlyContinue
$androidJar = $androidJars | Sort-Object { [version](($($_.Directory.Name -replace '^android-', '') + '.0')) } -Descending |
    Select-Object -First 1 -ExpandProperty FullName
$source = Join-Path $repo 'app\src\main\java\com\fadcam\ui\faditor\markers'

if (-not $androidJar) { throw "Missing Android SDK android.jar under $sdkRoot\platforms" }
if (Test-Path -LiteralPath $out) {
    $existing = (Resolve-Path -LiteralPath $out).Path
    if ($existing -ne $expectedOut) { throw "Refusing to remove unexpected path: $existing" }
    Remove-Item -LiteralPath $existing -Recurse -Force
}
New-Item -ItemType Directory -Path $out | Out-Null

try {
    if ($JsonJarPath) {
        $jsonJar = (Resolve-Path -LiteralPath $JsonJarPath).Path
    } else {
        $cachePath = Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1\org.json\json'
        $cachedJar = Get-ChildItem -LiteralPath $cachePath -Filter '*.jar' -Recurse -File -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending | Select-Object -First 1
        if ($cachedJar) {
            $jsonJar = $cachedJar.FullName
        } else {
            $jsonJar = Join-Path $out 'json-20240303.jar'
            $jsonUrl = 'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar'
            Invoke-WebRequest -Uri $jsonUrl -OutFile $jsonJar
        }
    }
    if (-not (Test-Path -LiteralPath $jsonJar -PathType Leaf)) { throw "Missing org.json jar: $jsonJar" }
    & jar tf $jsonJar | Select-String -Quiet '^org/json/JSONObject.class$' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Jar does not contain org.json.JSONObject: $jsonJar" }

    $sources = @(
        (Join-Path $PSScriptRoot 'stubs\android\util\AtomicFile.java'),
        (Join-Path $PSScriptRoot 'stubs\com\fadcam\FLog.java'),
        (Join-Path $PSScriptRoot 'MarkerWorkspaceChecks.java'),
        (Join-Path $source 'MarkerWorkspace.java'),
        (Join-Path $source 'MarkerWorkspaceStore.java'),
        (Join-Path $source 'MarkerLoopPolicy.java'),
        (Join-Path $source 'RegionExportPlan.java')
    )
    javac -encoding UTF-8 -cp "$androidJar;$jsonJar" -d $out $sources
    if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }
    java -cp "$out;$jsonJar" com.fadcam.ui.faditor.markers.MarkerWorkspaceChecks (Join-Path $out 'projects')
    if ($LASTEXITCODE -ne 0) { throw "marker checks failed with exit code $LASTEXITCODE" }
}
finally {
    if (-not $KeepArtifacts -and (Test-Path -LiteralPath $out)) {
        $existing = (Resolve-Path -LiteralPath $out).Path
        if ($existing -eq $expectedOut -and (Split-Path -Parent $existing) -eq $tempRoot) {
            Remove-Item -LiteralPath $existing -Recurse -Force
        } else {
            Write-Warning "Keeping unexpected scratch path for safety: $existing"
        }
    }
}
