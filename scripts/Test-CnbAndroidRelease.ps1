#Requires -Version 7.0
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Publish-CnbAndroidRelease.ps1')

function Assert-Equal($Actual, $Expected) {
    if ($Actual -cne $Expected) { throw "Expected '$Expected', got '$Actual'." }
}

function Assert-Fails([scriptblock] $Action, [string] $Message) {
    try { & $Action } catch {
        if ($_.Exception.Message -notlike "*$Message*") { throw }
        return
    }
    throw "Expected failure containing '$Message'."
}

$tag = 'v1.0.22'
$names = @(
    "Mochi-$tag-arm64-v8a.apk",
    "Mochi-Mijia-Extension-$tag.apk",
    "Mochi-Termux-Extension-$tag.apk",
    "Mochi-WebDAV-Extension-$tag.apk",
    "Mochi-$tag-SHA256SUMS.txt"
)
$source = @{
    tag_name = $tag
    draft = $false
    prerelease = $false
    assets = @($names | ForEach-Object {
        @{
            name = $_
            state = 'uploaded'
            size = 4
            digest = 'sha256:' + ('a' * 64)
            browser_download_url = "https://github.com/gongpx20069/hi-mochi/releases/download/$tag/$_"
        }
    })
}

Assert-CnbRepository 'example/mobile/mochi'
foreach ($bad in @('', 'https://cnb.cool/example/mochi', 'example/../mochi', 'example/mochi?x', '/example/mochi')) {
    Assert-Fails { Assert-CnbRepository $bad } 'organization/repository'
}
$plan = @(Get-MirrorAssetPlan $source $tag)
Assert-Equal $plan.Count 5
Assert-Equal $plan[0].name "Mochi-$tag-arm64-v8a.apk"
$source.assets[0].browser_download_url = 'https://example.invalid/application.apk'
Assert-Fails { Get-MirrorAssetPlan $source $tag } 'Invalid GitHub release asset'
$source.assets[0].browser_download_url = "https://github.com/gongpx20069/hi-mochi/releases/download/$tag/$($names[0])"
$source.prerelease = $true
Assert-Fails { Get-MirrorAssetPlan $source $tag } 'stable Mochi'
$source.prerelease = $false
$source.assets += $source.assets[0]
Assert-Fails { Get-MirrorAssetPlan $source $tag } 'duplicate'
$source.assets = @($source.assets | Select-Object -First 5)
$source.assets[0].digest = ''
Assert-Fails { Get-MirrorAssetPlan $source $tag } 'Invalid GitHub release asset'
$source.assets[0].digest = 'sha256:' + ('a' * 64)

$directory = Join-Path ([IO.Path]::GetTempPath()) ("mochi-cnb-test-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $directory | Out-Null
try {
    $path = Join-Path $directory 'fixture.apk'
    [IO.File]::WriteAllBytes($path, [byte[]] @(80, 75, 3, 4))
    $asset = @{
        name = 'fixture.apk'
        size = 4
        sha256 = (Get-FileHash $path -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    Assert-MirrorFile $path $asset
    [IO.File]::WriteAllBytes($path, [byte[]] @(80, 75, 3, 5))
    Assert-Fails { Assert-MirrorFile $path $asset } 'SHA-256 mismatch'

    $script:httpCalls = @()
    $script:status = 403
    function Invoke-WebRequest {
        $script:httpCalls += $args
        @{ StatusCode = $script:status; Content = '{}'; RawContentLength = 2 }
    }
    Assert-Fails { Invoke-MirrorHttp 'https://cnb.cool/example/mochi' } 'HTTP 403'
    $script:status = 404
    Assert-Equal (Invoke-MirrorHttp 'https://cnb.cool/example/mochi' -AllowMissing) $null
    $script:status = 200
    $result = Invoke-MirrorHttp 'https://cnb.cool/example/mochi'
    Assert-Equal ($result -is [hashtable]) $true
    Remove-Item Function:Invoke-WebRequest

    $script:confirmedUrl = $null
    function Invoke-MirrorHttp {
        param($Uri, $Method, $Headers, $Body, $InFile, $OutFile, [switch] $AllowMissing)
        Assert-Equal $Headers $null
        $script:confirmedUrl = $Uri
        [IO.File]::WriteAllBytes($OutFile, [byte[]] @(80, 75, 3, 4))
    }
    Confirm-CnbMirrorAsset 'example/mochi' $tag $asset $path | Out-Null
    Assert-Equal $script:confirmedUrl "https://cnb.cool/example/mochi/-/releases/download/$tag/fixture.apk"

    $script:calls = @()
    $script:upload = @{
        upload_url = 'https://bucket.cos.ap-shanghai.myqcloud.com/fixture'
        verify_url = 'https://api.cnb.cool/example/mochi/-/releases/release-id/asset-upload-confirmation/token/file'
    }
    function Invoke-CnbApi {
        param($Repository, $Resource, $Method, $Body, [switch] $AllowMissing)
        $script:calls += @{ resource = $Resource; method = $Method; body = $Body }
        if ($Resource.EndsWith('/asset-upload-url')) { return $script:upload }
    }
    function Invoke-MirrorHttp {
        param($Uri, $Method, $Headers, $Body, $InFile, $OutFile, [switch] $AllowMissing)
        Assert-Equal $Headers $null
        Assert-Equal $Method 'PUT'
        Assert-Equal $InFile $path
    }
    Send-CnbMirrorAsset 'example/mochi' 'release-id' $asset $path
    Assert-Equal $script:calls[0].body.overwrite $false
    Assert-Equal $script:calls[0].body.ttl 0
    Assert-Equal $script:calls[1].method 'POST'
    $script:upload.upload_url = 'https://example.invalid/upload'
    Assert-Fails { Send-CnbMirrorAsset 'example/mochi' 'release-id' $asset $path } 'untrusted upload'
    $script:upload.upload_url = 'https://asset.cnb.cool/upload'
    $script:upload.verify_url = 'https://api.cnb.cool/other/project/-/releases/id/asset-upload-confirmation/t/f'
    Assert-Fails { Send-CnbMirrorAsset 'example/mochi' 'release-id' $asset $path } 'untrusted confirmation'
    $script:upload.verify_url = 'https://api.cnb.cool/example/mochi/-/releases/release-id/asset-upload-confirmation/../../other'
    Assert-Fails { Send-CnbMirrorAsset 'example/mochi' 'release-id' $asset $path } 'untrusted confirmation'

    $environmentNames = @(
        'CNB_TOKEN', 'GIT_CONFIG_COUNT', 'GIT_CONFIG_KEY_0', 'GIT_CONFIG_VALUE_0', 'GIT_TERMINAL_PROMPT'
    )
    $savedEnvironment = @{}
    foreach ($name in $environmentNames) {
        $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    }
    $script:pushExit = 0
    function git {
        $global:LASTEXITCODE = 0
        if ($args[0] -eq 'rev-parse') {
            if ($args[1] -eq 'FETCH_HEAD') { return ('a' * 40) }
            return ('b' * 40)
        }
        if ($args -contains 'push') {
            Assert-Equal $env:GIT_CONFIG_KEY_0 'http.https://cnb.cool/example/mochi.extraheader'
            Assert-Equal ($args -contains '--force') $false
            Assert-Equal $args[-1] (('a' * 40) + ':refs/tags/v1.0.22')
            $global:LASTEXITCODE = $script:pushExit
        }
    }
    try {
        $env:CNB_TOKEN = 'synthetic-test-only'
        $env:GIT_CONFIG_COUNT = '0'
        $env:GIT_CONFIG_KEY_0 = 'test-original-key'
        $env:GIT_CONFIG_VALUE_0 = 'test-original-value'
        Assert-Equal (Sync-CnbSourceTag 'example/mochi' $tag) ('b' * 40)
        Assert-Equal $env:GIT_CONFIG_COUNT '0'
        Assert-Equal $env:GIT_CONFIG_VALUE_0 'test-original-value'
        $script:pushExit = 1
        Assert-Fails { Sync-CnbSourceTag 'example/mochi' $tag } 'source tag push failed'
        Assert-Equal $env:GIT_CONFIG_KEY_0 'test-original-key'
        Assert-Equal $env:GIT_CONFIG_VALUE_0 'test-original-value'
    } finally {
        Remove-Item Function:git
        foreach ($name in $environmentNames) {
            [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
        }
    }

    $script:mirrorRelease = @{
        id = 'release-id'
        tag_name = $tag
        draft = $true
        prerelease = $false
        assets = @()
    }
    $script:events = @()
    $script:failVerify = $false
    $script:failDraftVerify = $false
    $script:visibility = 'Public'
    $script:usage = 0L
    $script:releaseMissing = $true
    function Get-MirrorSourceRelease { return $source }
    function Get-CnbMirrorUsage { return $script:usage }
    function Save-MirrorSourceAssets {
        param($ReleaseTag, $Assets, $Directory)
        $script:workDirectory = $Directory
        $script:events += 'download'
    }
    function Sync-CnbSourceTag { $script:events += 'tag'; return ('b' * 40) }
    function Invoke-CnbApi {
        param($Repository, $Resource, $Method, $Body, [switch] $AllowMissing)
        if (-not $Resource) { return @{ visibility_level = $script:visibility; path = $Repository } }
        if ($Resource -like '/-/releases/tags/*') {
            if ($script:releaseMissing) { return $null }
            return $script:mirrorRelease
        }
        if ($Resource -eq '/-/releases' -and $Method -eq 'POST') {
            Assert-Equal $Body.draft $true
            Assert-Equal $Body.target_commitish ('b' * 40)
            Assert-Equal $Body.make_latest 'false'
            $script:events += 'create'
            return $script:mirrorRelease
        }
        if ($Method -eq 'PATCH') { $script:events += 'publish'; return }
        throw "Unexpected API call in test: $Method $Resource"
    }
    function Send-CnbMirrorAsset { $script:events += 'upload' }
    function Confirm-CnbMirrorAsset {
        param($Repository, $ReleaseTag, $Asset, $Path, [switch] $Authenticated)
        if ($Authenticated) {
            $script:events += 'verify-draft'
            if ($script:failDraftVerify) { throw 'Synthetic draft verification failure.' }
            return
        }
        $script:events += 'verify'
        if ($script:failVerify) { throw 'Synthetic anonymous verification failure.' }
        return 'https://cnb.cool/example/mochi/verified'
    }
    $savedToken = $env:CNB_TOKEN
    $savedSummary = $env:GITHUB_STEP_SUMMARY
    try {
        $env:CNB_TOKEN = 'synthetic-test-only'
        $env:GITHUB_STEP_SUMMARY = ''
        Publish-CnbAndroidRelease $tag 'example/mochi' | Out-Null
        Assert-Equal ($script:events -join ',') 'download,tag,create,upload,upload,upload,upload,upload,verify-draft,verify-draft,verify-draft,verify-draft,verify-draft,publish,verify,verify,verify,verify,verify'
        Assert-Equal (Test-Path $script:workDirectory) $false
        $script:releaseMissing = $false
        $script:visibility = 'Private'
        Assert-Fails { Publish-CnbAndroidRelease $tag 'example/mochi' } 'public repository'
        $script:visibility = 'Public'
        $script:usage = 20GB
        Assert-Fails { Publish-CnbAndroidRelease $tag 'example/mochi' } 'budget'
        $script:usage = 0L
        $script:events = @()
        $script:failDraftVerify = $true
        Assert-Fails { Publish-CnbAndroidRelease $tag 'example/mochi' } 'draft verification'
        Assert-Equal ($script:events -contains 'publish') $false
        Assert-Equal (Test-Path $script:workDirectory) $false
        $script:failDraftVerify = $false
        $script:failVerify = $true
        Assert-Fails { Publish-CnbAndroidRelease $tag 'example/mochi' } 'anonymous verification'
        Assert-Equal (Test-Path $script:workDirectory) $false
        $script:failVerify = $false
        $script:mirrorRelease.draft = $false
        Assert-Fails { Publish-CnbAndroidRelease $tag 'example/mochi' } 'incomplete'
        Assert-Equal (Test-Path $script:workDirectory) $false
        $script:mirrorRelease.assets = @($source.assets | ForEach-Object { @{ name = $_.name; size = $_.size } })
        $script:events = @()
        Publish-CnbAndroidRelease $tag 'example/mochi' | Out-Null
        Assert-Equal ($script:events -join ',') 'download,tag,verify,verify,verify,verify,verify'
        $script:mirrorRelease.assets[0].size = 5
        Assert-Fails { Publish-CnbAndroidRelease $tag 'example/mochi' } 'Conflicting CNB asset'
        Assert-Equal (Test-Path $script:workDirectory) $false
    } finally {
        $env:CNB_TOKEN = $savedToken
        $env:GITHUB_STEP_SUMMARY = $savedSummary
    }
} finally {
    Remove-Item -LiteralPath $directory -Recurse -Force
}
Write-Output 'CNB mirror checks passed: source validation, anonymous verification, upload contracts, failure handling, and cleanup.'
