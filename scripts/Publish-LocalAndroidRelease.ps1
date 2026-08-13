[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string] $ReleaseDirectory
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'AndroidReleaseTools.ps1')

$resolvedReleaseDirectory = (Resolve-Path $ReleaseDirectory).Path
$resolvedMetadata = Join-Path $resolvedReleaseDirectory 'release.json'
$metadata = Get-Content $resolvedMetadata -Raw | ConvertFrom-Json
$version = "$($metadata.version)"
Assert-MochiVersion $version
$commit = "$($metadata.commit)".Trim()
if ($commit -notmatch '^[0-9a-f]{40}$') {
    throw "Release metadata does not contain a valid Git commit."
}
$expectedAbis = @(
    'arm64-v8a',
    'armeabi-v7a',
    'x86',
    'x86_64',
    'universal'
)
$artifacts = @($metadata.artifacts)
if (
    $artifacts.Count -ne $expectedAbis.Count -or
    @($expectedAbis | Where-Object { $_ -notin $artifacts.abi })
) {
    throw "Release metadata does not contain the expected ABI artifacts."
}
$releaseAssets = @()
foreach ($artifact in $artifacts) {
    $expectedFile = "Mochi-v$version-$($artifact.abi).apk"
    if ($artifact.file -ne $expectedFile) {
        throw (
            "ABI '$($artifact.abi)' must use release file " +
            "'$expectedFile'."
        )
    }
    $apkPath = Join-Path $resolvedReleaseDirectory $artifact.file
    $embeddedVersion = Get-AndroidApkVersion $apkPath
    if ($embeddedVersion -ne $version) {
        throw "APK '$($artifact.file)' has version '$embeddedVersion'."
    }
    Assert-AndroidApkSignature $apkPath
    $apkHash = (
        Get-FileHash -Algorithm SHA256 $apkPath
    ).Hash.ToLowerInvariant()
    if ($artifact.sha256 -ne $apkHash) {
        throw "APK '$($artifact.file)' failed SHA-256 validation."
    }
    $releaseAssets += $apkPath
}
$expectedChecksumName = "Mochi-v$version-SHA256SUMS.txt"
if ($metadata.checksum_file -ne $expectedChecksumName) {
    throw "Release metadata contains an unexpected checksum file name."
}
$checksumPath = Join-Path (
    $resolvedReleaseDirectory
) $metadata.checksum_file
if (-not (Test-Path $checksumPath)) {
    throw "Release checksum file is missing."
}
$expectedChecksumLines = @(
    $artifacts |
        ForEach-Object {
            "$($_.sha256)  $($_.file)"
        }
)
$actualChecksumLines = @(Get-Content $checksumPath)
if (
    $actualChecksumLines.Count -ne $expectedChecksumLines.Count -or
    (Compare-Object $expectedChecksumLines $actualChecksumLines)
) {
    throw "Release checksum file does not match release metadata."
}
$releaseAssets += $checksumPath

$nextVersion = Get-MochiNextVersion
if ($version -ne $nextVersion) {
    throw "Release version '$version' is not the next release '$nextVersion'."
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

$tag = "v$version"
& gh api --method POST `
    "repos/$repository/git/refs" `
    -f "ref=refs/tags/$tag" `
    -f "sha=$commit" |
    Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Release tag '$tag' was allocated by another publisher."
}

$releaseNotes = @'
## Choose your APK / 选择安装包

- **`arm64-v8a`**: most current Android phones and tablets / 目前绝大多数手机和平板（推荐）
- **`armeabi-v7a`**: older 32-bit ARM devices / 较老的 32 位 ARM 设备
- **`x86_64`**: 64-bit Android emulators or rare Intel devices / 64 位模拟器或少见 Intel 设备
- **`x86`**: 32-bit Android emulators or older Intel devices / 32 位模拟器或更早 Intel 设备
- **`universal`**: use only when the architecture is unknown; this is the largest download / 不确定架构时使用，体积最大

All variants provide the same Mochi features.
'@

& gh release create $tag @releaseAssets `
    --repo $repository `
    --target $commit `
    --title "Mochi $version" `
    --notes $releaseNotes `
    --generate-notes `
    --verify-tag
if ($LASTEXITCODE -ne 0) {
    throw "GitHub Release '$tag' could not be created. The reserved tag remains."
}
