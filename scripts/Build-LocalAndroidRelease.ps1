[CmdletBinding()]
param(
    [string] $OutputDirectory = 'dist\android-release'
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'AndroidReleaseTools.ps1')

$repositoryRoot = Split-Path $PSScriptRoot -Parent
$androidRoot = Join-Path $repositoryRoot 'android'
$worktreeChanges = & git -C $repositoryRoot status --porcelain
if ($LASTEXITCODE -ne 0) {
    throw 'Could not inspect the Git worktree.'
}
if ($worktreeChanges) {
    throw 'Commit or remove local changes before building a release APK.'
}
$commit = (& git -C $repositoryRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or -not $commit) {
    throw 'Could not resolve the release source commit.'
}

$signingProperties = Join-Path $androidRoot 'signing.properties'
if (-not (Test-Path $signingProperties)) {
    throw "Create android\signing.properties before building a local release."
}

$version = Get-MochiNextVersion
Assert-MochiVersion $version

Push-Location $androidRoot
try {
    & .\gradlew.bat verifyNative verifyRelease `
        "-PmochiVersionName=$version" --no-daemon
    if ($LASTEXITCODE -ne 0) {
        throw 'Android release build failed.'
    }
} finally {
    Pop-Location
}

$finalWorktreeChanges = & git -C $repositoryRoot status --porcelain
$finalStatusExitCode = $LASTEXITCODE
$finalCommit = (& git -C $repositoryRoot rev-parse HEAD).Trim()
$finalCommitExitCode = $LASTEXITCODE
if (
    $finalStatusExitCode -ne 0 -or
    $finalCommitExitCode -ne 0 -or
    $finalWorktreeChanges -or
    $finalCommit -ne $commit
) {
    throw 'Source changed during the release build; discard the APK and rebuild.'
}

$sourceApk = Join-Path $androidRoot 'app\build\outputs\apk\release\app-release.apk'
$embeddedVersion = Get-AndroidApkVersion $sourceApk
if ($embeddedVersion -ne $version) {
    throw "Built APK version '$embeddedVersion' does not match '$version'."
}
Assert-AndroidApkSignature $sourceApk

$resolvedOutput = Join-Path $repositoryRoot $OutputDirectory
New-Item -ItemType Directory -Force -Path $resolvedOutput | Out-Null
$tag = "v$version"
$apkName = "Mochi-$tag.apk"
$apkPath = Join-Path $resolvedOutput $apkName
$shaPath = "$apkPath.sha256"
$metadataPath = "$apkPath.release.json"
if (
    (Test-Path $apkPath) -or
    (Test-Path $shaPath) -or
    (Test-Path $metadataPath)
) {
    throw "Release output for '$version' already exists in '$resolvedOutput'."
}
$reservation = [System.IO.File]::Open(
    $apkPath,
    [System.IO.FileMode]::CreateNew,
    [System.IO.FileAccess]::Write,
    [System.IO.FileShare]::None
)
$reservation.Dispose()
try {
    Copy-Item $sourceApk $apkPath -Force
    $hash = (Get-FileHash -Algorithm SHA256 $apkPath).Hash.ToLowerInvariant()
    "$hash  $apkName" | Set-Content -Encoding ascii $shaPath
    [ordered]@{
        version = $version
        commit = $commit
        sha256 = $hash
    } |
        ConvertTo-Json |
        Set-Content -Encoding utf8 $metadataPath
} catch {
    Remove-Item $apkPath, $shaPath, $metadataPath -Force `
        -ErrorAction SilentlyContinue
    throw
}

Write-Output "Version: $version"
Write-Output "APK: $apkPath"
Write-Output "SHA-256: $shaPath"
Write-Output "Metadata: $metadataPath"
