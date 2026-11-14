#Requires -Version 7.0
[CmdletBinding()]
param(
    [string] $Tag,
    [string] $CnbRepository = $env:CNB_REPOSITORY
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'AndroidReleaseTools.ps1')

function Assert-CnbRepository {
    param([string] $Repository)

    if ($Repository -cnotmatch '^[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)+$') {
        throw 'CNB_REPOSITORY must be an organization/repository path, not a URL.'
    }
}

function Invoke-MirrorHttp {
    param(
        [string] $Uri,
        [string] $Method = 'GET',
        [hashtable] $Headers = @{},
        [string] $Body,
        [string] $InFile,
        [string] $OutFile,
        [switch] $AllowMissing
    )

    $parameters = @{
        Uri = $Uri
        Method = $Method
        Headers = $Headers
        TimeoutSec = 600
        MaximumRedirection = 0
        SkipHttpErrorCheck = $true
        ErrorAction = 'Stop'
    }
    if ($Body) {
        $parameters.Body = [Text.Encoding]::UTF8.GetBytes($Body)
        $parameters.ContentType = 'application/json'
    }
    if ($InFile) {
        $parameters.InFile = $InFile
        $parameters.ContentType = 'application/octet-stream'
    }
    if ($OutFile) {
        $parameters.OutFile = $OutFile
        $parameters.PassThru = $true
        $parameters.MaximumRedirection = 5
    }
    try {
        $response = Invoke-WebRequest @parameters
    } catch {
        # Signed upload URLs and service responses must never enter CI logs.
        throw "Mirror HTTP $Method failed before a response was received."
    }
    if ($AllowMissing -and $response.StatusCode -eq 404) { return $null }
    if ($response.StatusCode -lt 200 -or $response.StatusCode -ge 300) {
        throw "Mirror HTTP $Method failed (HTTP $($response.StatusCode))."
    }
    if ($OutFile -or -not $response.Content) { return $null }
    if ($response.RawContentLength -gt 1MB) {
        throw 'Mirror metadata exceeds the 1 MiB limit.'
    }
    try {
        return ($response.Content | ConvertFrom-Json -AsHashtable)
    } catch {
        throw 'Mirror metadata is not valid JSON.'
    }
}

function Invoke-CnbApi {
    param(
        [string] $Repository,
        [string] $Resource = '',
        [string] $Method = 'GET',
        [hashtable] $Body,
        [switch] $AllowMissing
    )

    $parameters = @{
        Uri = "https://api.cnb.cool/$Repository$Resource"
        Method = $Method
        Headers = @{
            Authorization = "Bearer $env:CNB_TOKEN"
            Accept = 'application/vnd.cnb.api+json'
        }
        AllowMissing = $AllowMissing
    }
    if ($Body) { $parameters.Body = $Body | ConvertTo-Json -Depth 10 -Compress }
    $result = Invoke-MirrorHttp @parameters
    if ($null -ne $result -and $result -is [System.Collections.IDictionary] -and $result['errcode']) {
        throw "CNB API rejected the request (code $($result['errcode']))."
    }
    return $result
}

function Get-MirrorSourceRelease {
    param([string] $ReleaseTag)

    $json = & gh api "repos/gongpx20069/hi-mochi/releases/tags/$ReleaseTag"
    if ($LASTEXITCODE -ne 0) { throw 'Could not read the published GitHub release.' }
    return ($json -join "`n" | ConvertFrom-Json -AsHashtable)
}

function Get-MirrorAssetPlan {
    param([hashtable] $Release, [string] $ReleaseTag)

    Assert-MochiVersion ($ReleaseTag -creplace '^v', '')
    if (
        $ReleaseTag -cnotmatch '^v1\.0\.[1-9][0-9]*$' -or
        $Release.tag_name -cne $ReleaseTag -or
        $Release.draft -ne $false -or $Release.prerelease -ne $false
    ) {
        throw 'Only an already published stable Mochi release can be mirrored.'
    }
    $names = @(
        "Mochi-$ReleaseTag-arm64-v8a.apk",
        "Mochi-Mijia-Extension-$ReleaseTag.apk",
        "Mochi-Termux-Extension-$ReleaseTag.apk",
        "Mochi-WebDAV-Extension-$ReleaseTag.apk",
        "Mochi-$ReleaseTag-SHA256SUMS.txt"
    )
    foreach ($name in $names) {
        $matches = @($Release.assets | Where-Object { $_.name -ceq $name })
        if ($matches.Count -ne 1) { throw "Missing or duplicate GitHub asset: $name" }
        $asset = $matches[0]
        $expectedUrl = "https://github.com/gongpx20069/hi-mochi/releases/download/$ReleaseTag/$name"
        if (
            $asset.state -cne 'uploaded' -or $asset.size -le 0 -or $asset.size -gt 256MB -or
            $asset.browser_download_url -cne $expectedUrl -or
            $asset.digest -cnotmatch '^sha256:[0-9a-f]{64}$'
        ) {
            throw "Invalid GitHub release asset: $name"
        }
        @{
            name = $name
            size = [long] $asset.size
            sha256 = $asset.digest.Substring(7)
        }
    }
}

function Assert-MirrorFile {
    param([string] $Path, [hashtable] $Asset)

    if (
        (Get-Item -LiteralPath $Path).Length -ne $Asset.size -or
        (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() -cne $Asset.sha256
    ) {
        throw "Size or SHA-256 mismatch: $($Asset.name)"
    }
}

function Save-MirrorSourceAssets {
    param([string] $ReleaseTag, [array] $Assets, [string] $Directory)

    $arguments = @('release', 'download', $ReleaseTag, '--repo', 'gongpx20069/hi-mochi', '--dir', $Directory)
    foreach ($asset in $Assets) { $arguments += @('--pattern', $asset.name) }
    & gh @arguments
    if ($LASTEXITCODE -ne 0) { throw 'GitHub APK download failed; nothing has been uploaded to CNB.' }
    foreach ($asset in $Assets) {
        Assert-MirrorFile (Join-Path $Directory $asset.name) $asset
    }
    $manifest = Get-Content -LiteralPath (Join-Path $Directory "Mochi-$ReleaseTag-SHA256SUMS.txt")
    foreach ($asset in $Assets | Where-Object { $_.name.EndsWith('.apk') }) {
        $entries = @($manifest | Where-Object {
            $_ -cmatch ('^([0-9a-f]{64})  ' + [regex]::Escape($asset.name) + '$')
        })
        if ($entries.Count -ne 1 -or $entries[0].Substring(0, 64) -cne $asset.sha256) {
            throw "GitHub checksum manifest mismatch: $($asset.name)"
        }
    }
}

function Sync-CnbSourceTag {
    param([string] $Repository, [string] $ReleaseTag)

    & git fetch --no-tags https://github.com/gongpx20069/hi-mochi.git "refs/tags/$ReleaseTag" | Out-Host
    if ($LASTEXITCODE -ne 0) { throw 'Could not fetch the original GitHub release tag.' }
    $tagObject = (& git rev-parse FETCH_HEAD).Trim()
    $commit = (& git rev-parse 'FETCH_HEAD^{commit}').Trim()
    if ($LASTEXITCODE -ne 0 -or $tagObject -cnotmatch '^[0-9a-f]{40}$' -or $commit -cnotmatch '^[0-9a-f]{40}$') {
        throw 'Invalid GitHub release tag object.'
    }
    $names = @('GIT_CONFIG_COUNT', 'GIT_CONFIG_KEY_0', 'GIT_CONFIG_VALUE_0', 'GIT_TERMINAL_PROMPT')
    $saved = @{}
    foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
    try {
        $env:GIT_TERMINAL_PROMPT = '0'
        $env:GIT_CONFIG_COUNT = '1'
        $env:GIT_CONFIG_KEY_0 = "http.https://cnb.cool/$Repository.extraheader"
        $basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("cnb:$env:CNB_TOKEN"))
        if ($env:GITHUB_ACTIONS -eq 'true') { Write-Host "::add-mask::$basic" }
        $env:GIT_CONFIG_VALUE_0 = "Authorization: Basic $basic"
        & git -c credential.helper= -c http.followRedirects=false `
            -c http.lowSpeedLimit=1024 -c http.lowSpeedTime=60 `
            push "https://cnb.cool/$Repository" "$tagObject`:refs/tags/$ReleaseTag" | Out-Host
        if ($LASTEXITCODE -ne 0) {
            throw 'CNB source tag push failed. Existing tags are never force-updated.'
        }
    } finally {
        foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
        $basic = $null
    }
    return $commit
}

function Get-CnbMirrorUsage {
    param([string] $Repository)

    $bytes = 0L
    for ($page = 1; $page -le 100; $page++) {
        $releases = @(Invoke-CnbApi $Repository "/-/releases?page=$page&page_size=100")
        foreach ($release in $releases) {
            foreach ($asset in $release.assets) {
                if ($asset.size -lt 0) { throw 'Invalid CNB storage metadata.' }
                $bytes += [long] $asset.size
            }
        }
        if ($releases.Count -lt 100) { return $bytes }
    }
    throw 'CNB release listing exceeded the pagination safety limit.'
}

function Send-CnbMirrorAsset {
    param([string] $Repository, [string] $ReleaseId, [hashtable] $Asset, [string] $Path)

    $resource = "/-/releases/$ReleaseId"
    $upload = Invoke-CnbApi $Repository "$resource/asset-upload-url" POST @{
        asset_name = $Asset.name
        size = $Asset.size
        overwrite = $false
        ttl = 0
    }
    $uploadUri = $null
    if (
        -not [uri]::TryCreate($upload.upload_url, [UriKind]::Absolute, [ref] $uploadUri) -or
        $uploadUri.Scheme -cne 'https' -or $uploadUri.UserInfo -or -not $uploadUri.IsDefaultPort -or
        ($uploadUri.Host -cne 'asset.cnb.cool' -and $uploadUri.Host -cnotmatch '^[a-z0-9-]+\.cos\.[a-z0-9-]+\.myqcloud\.com$')
    ) {
        throw 'CNB returned an untrusted upload URL.'
    }
    $prefix = "https://api.cnb.cool/$Repository$resource/asset-upload-confirmation/"
    $verifyUri = $null
    if (
        -not [uri]::TryCreate($upload.verify_url, [UriKind]::Absolute, [ref] $verifyUri) -or
        -not $verifyUri.AbsoluteUri.StartsWith($prefix, [StringComparison]::Ordinal) -or
        $verifyUri.Fragment
    ) {
        throw 'CNB returned an untrusted confirmation URL.'
    }
    Invoke-MirrorHttp -Uri $upload.upload_url -Method PUT -InFile $Path | Out-Null
    $confirmation = $verifyUri.AbsoluteUri.Substring("https://api.cnb.cool/$Repository".Length)
    Invoke-CnbApi $Repository $confirmation POST | Out-Null
}

function Confirm-CnbMirrorAsset {
    param(
        [string] $Repository,
        [string] $ReleaseTag,
        [hashtable] $Asset,
        [string] $Path,
        [switch] $Authenticated
    )

    $url = "https://cnb.cool/$Repository/-/releases/download/$ReleaseTag/$($Asset.name)"
    if ($Authenticated) {
        $apiUrl = "https://api.cnb.cool/$Repository/-/releases/download/$ReleaseTag/$($Asset.name)"
        Invoke-MirrorHttp -Uri $apiUrl -OutFile $Path -Headers @{
            Authorization = "Bearer $env:CNB_TOKEN"
            Accept = 'application/vnd.cnb.api+json'
        } | Out-Null
    } else {
        # Use a fresh anonymous request, never the authenticated API download URL.
        Invoke-MirrorHttp -Uri $url -OutFile $Path | Out-Null
    }
    Assert-MirrorFile $Path $Asset
    return $url
}

function Publish-CnbAndroidRelease {
    param([string] $ReleaseTag, [string] $Repository)

    Assert-CnbRepository $Repository
    if ([string]::IsNullOrWhiteSpace($env:CNB_TOKEN)) { throw 'Set CNB_TOKEN using a GitHub Actions secret.' }
    Assert-MochiVersion ($ReleaseTag -creplace '^v', '')
    $source = Get-MirrorSourceRelease $ReleaseTag
    $assets = @(Get-MirrorAssetPlan $source $ReleaseTag)
    $repoInfo = Invoke-CnbApi $Repository
    if ($repoInfo.visibility_level -cne 'Public' -or $repoInfo.path -cne $Repository) {
        throw 'The CNB destination must be the configured public repository.'
    }
    $usage = Get-CnbMirrorUsage $Repository
    $incoming = ($assets | Measure-Object -Property size -Sum).Sum
    if ($usage + $incoming -gt 20GB) {
        throw 'CNB mirror exceeds the conservative 20 GiB attachment budget. No releases were deleted.'
    }
    $directory = Join-Path ([IO.Path]::GetTempPath()) ("mochi-cnb-" + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $directory | Out-Null
    try {
        Save-MirrorSourceAssets $ReleaseTag $assets $directory
        $commit = Sync-CnbSourceTag $Repository $ReleaseTag
        $release = Invoke-CnbApi $Repository "/-/releases/tags/$ReleaseTag" -AllowMissing
        if ($null -eq $release) {
            $release = Invoke-CnbApi $Repository '/-/releases' POST @{
                tag_name = $ReleaseTag
                target_commitish = $commit
                name = "Mochi $ReleaseTag"
                body = "Official mirror of https://github.com/gongpx20069/hi-mochi/releases/tag/$ReleaseTag`nSource commit: $commit`nARM64 app and optional extensions; other ABIs remain on GitHub."
                draft = $true
                prerelease = $false
                make_latest = 'false'
            }
        }
        if (
            $release.tag_name -cne $ReleaseTag -or $release.prerelease -ne $false -or
            $release.id -cnotmatch '^[A-Za-z0-9_-]+$'
        ) {
            throw 'CNB returned an unexpected release.'
        }
        $existingNames = @($release.assets | ForEach-Object { $_.name })
        if (@($existingNames | Where-Object { $_ -cnotin $assets.name }).Count -gt 0) {
            throw 'The CNB release contains unexpected assets; refusing to modify it.'
        }
        foreach ($asset in $assets) {
            $existing = @($release.assets | Where-Object { $_.name -ceq $asset.name })
            if ($existing.Count -gt 1) { throw "Duplicate CNB asset: $($asset.name)" }
            if ($existing.Count -eq 1) {
                if ($existing[0].size -ne $asset.size) { throw "Conflicting CNB asset: $($asset.name)" }
                continue
            }
            if (-not $release.draft) {
                throw 'Published CNB release is incomplete; refusing to change an existing published version.'
            }
            Send-CnbMirrorAsset $Repository $release.id $asset (Join-Path $directory $asset.name)
        }
        if ($release.draft) {
            foreach ($asset in $assets) {
                Confirm-CnbMirrorAsset $Repository $ReleaseTag $asset `
                    (Join-Path $directory "verify-$($asset.name)") -Authenticated | Out-Null
            }
            Invoke-CnbApi $Repository "/-/releases/$($release.id)" PATCH @{
                draft = $false
                make_latest = 'legacy'
            } | Out-Null
        }
        $urls = foreach ($asset in $assets) {
            Confirm-CnbMirrorAsset $Repository $ReleaseTag $asset (Join-Path $directory "verify-$($asset.name)")
        }
        $summary = @(
            "CNB mirror verified: $ReleaseTag",
            "Source commit: $commit",
            'All five files were downloaded anonymously and matched GitHub SHA-256 digests.',
            ($urls -join "`n")
        ) -join "`n`n"
        Write-Output $summary
        if ($env:GITHUB_STEP_SUMMARY) { Add-Content -LiteralPath $env:GITHUB_STEP_SUMMARY -Value $summary }
    } finally {
        Remove-Item -LiteralPath $directory -Recurse -Force
    }
}

if ($MyInvocation.InvocationName -ne '.') {
    Publish-CnbAndroidRelease -ReleaseTag $Tag -Repository $CnbRepository
}
