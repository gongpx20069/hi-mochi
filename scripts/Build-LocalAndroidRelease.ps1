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

$resolvedOutput = Join-Path $repositoryRoot $OutputDirectory
$tag = "v$version"
$releaseDirectory = Join-Path $resolvedOutput "Mochi-$tag"
New-MochiReleaseAssets `
    -Version $version `
    -Commit $commit `
    -SourceOutputDirectory (
        Join-Path $androidRoot 'app\build\outputs\apk\release'
    ) `
    -DestinationDirectory $releaseDirectory

Write-Output "Version: $version"
Write-Output "Release directory: $releaseDirectory"
Get-ChildItem $releaseDirectory -File |
    ForEach-Object {
        Write-Output "Asset: $($_.FullName)"
    }
