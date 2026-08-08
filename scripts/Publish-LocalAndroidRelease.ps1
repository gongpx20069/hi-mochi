[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string] $ApkPath,
    [string] $MetadataPath
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'AndroidReleaseTools.ps1')

$resolvedApk = (Resolve-Path $ApkPath).Path
$resolvedMetadata = if ($MetadataPath) {
    (Resolve-Path $MetadataPath).Path
} else {
    (Resolve-Path "$resolvedApk.release.json").Path
}
$metadata = Get-Content $resolvedMetadata -Raw | ConvertFrom-Json
$embeddedVersion = Get-AndroidApkVersion $resolvedApk
Assert-MochiVersion $embeddedVersion
Assert-AndroidApkSignature $resolvedApk
if ($metadata.version -ne $embeddedVersion) {
    throw "Release metadata version does not match the APK."
}
$apkHash = (Get-FileHash -Algorithm SHA256 $resolvedApk).Hash.ToLowerInvariant()
if ($metadata.sha256 -ne $apkHash) {
    throw "Release metadata SHA-256 does not match the APK."
}
$commit = "$($metadata.commit)".Trim()
if ($commit -notmatch '^[0-9a-f]{40}$') {
    throw "Release metadata does not contain a valid Git commit."
}

$nextVersion = Get-MochiNextVersion
if ($embeddedVersion -ne $nextVersion) {
    throw "APK version '$embeddedVersion' is not the next release '$nextVersion'."
}

& gh auth status | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw 'Authenticate GitHub CLI with gh auth login before publishing.'
}

$repository = (& gh repo view --json nameWithOwner --jq '.nameWithOwner').Trim()
if ($LASTEXITCODE -ne 0 -or -not $repository) {
    throw 'Could not resolve the GitHub repository.'
}
& gh api "repos/$repository/commits/$commit" | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Target commit '$commit' is not available on GitHub."
}
& git cat-file -e "$commit`^{commit}" 2>$null
if ($LASTEXITCODE -ne 0) {
    & git fetch origin $commit
    if ($LASTEXITCODE -ne 0) {
        throw "Could not fetch target commit '$commit'."
    }
}

$tag = "v$embeddedVersion"
$temporaryDirectory = Join-Path (
    [System.IO.Path]::GetTempPath()
) "mochi-release-$([guid]::NewGuid())"
New-Item -ItemType Directory -Path $temporaryDirectory | Out-Null
try {
    $apkName = "Mochi-$tag.apk"
    $releaseApk = Join-Path $temporaryDirectory $apkName
    Copy-Item $resolvedApk $releaseApk
    $shaPath = "$releaseApk.sha256"
    $hash = (Get-FileHash -Algorithm SHA256 $releaseApk).Hash.ToLowerInvariant()
    "$hash  $apkName" | Set-Content -Encoding ascii $shaPath

    & gh api --method POST `
        "repos/$repository/git/refs" `
        -f "ref=refs/tags/$tag" `
        -f "sha=$commit" |
        Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Release tag '$tag' was allocated by another publisher."
    }

    & gh release create $tag $releaseApk $shaPath `
        --repo $repository `
        --target $commit `
        --title "Mochi $embeddedVersion" `
        --generate-notes `
        --verify-tag
    if ($LASTEXITCODE -ne 0) {
        throw "GitHub Release '$tag' could not be created. The reserved tag remains."
    }
} finally {
    Remove-Item $temporaryDirectory -Recurse -Force
}
