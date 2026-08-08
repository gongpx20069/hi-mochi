Set-StrictMode -Version Latest

function Assert-MochiVersion {
    param(
        [Parameter(Mandatory)]
        [string] $Version
    )

    if ($Version -notmatch '^1\.0\.([1-9][0-9]*)$') {
        throw "Android release version must match 1.0.x with x greater than zero."
    }
}

function Get-MochiNextVersion {
    param(
        [string] $Remote = 'origin'
    )

    $remoteTags = & git ls-remote --tags $Remote 'refs/tags/v1.0.*'
    if ($LASTEXITCODE -ne 0) {
        throw "Could not read Android release tags from remote '$Remote'."
    }

    $patches = @(
        $remoteTags |
            ForEach-Object {
                if ($_ -match 'refs/tags/v1\.0\.([0-9]+)$') {
                    [int] $Matches[1]
                }
            }
    )
    $nextPatch = if ($patches.Count -eq 0) {
        1
    } else {
        ($patches | Measure-Object -Maximum).Maximum + 1
    }
    "1.0.$nextPatch"
}

function Resolve-AndroidSdkTool {
    param(
        [Parameter(Mandatory)]
        [string] $Name
    )

    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    $sdkRoot = @(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT
    ) | Where-Object { $_ } | Select-Object -First 1
    if (-not $sdkRoot) {
        throw "Set ANDROID_HOME or ANDROID_SDK_ROOT so '$Name' can be located."
    }

    if ($Name -eq 'apkanalyzer') {
        $candidate = Join-Path $sdkRoot 'cmdline-tools\latest\bin\apkanalyzer.bat'
        if (Test-Path $candidate) {
            return $candidate
        }
    }

    if ($Name -eq 'apksigner') {
        $buildTools = Join-Path $sdkRoot 'build-tools'
        $candidate = Get-ChildItem $buildTools -Directory -ErrorAction SilentlyContinue |
            Sort-Object { [version] $_.Name } -Descending |
            ForEach-Object {
                Join-Path $_.FullName 'apksigner.bat'
            } |
            Where-Object { Test-Path $_ } |
            Select-Object -First 1
        if ($candidate) {
            return $candidate
        }
    }

    throw "Android SDK tool '$Name' was not found."
}

function Get-AndroidApkVersion {
    param(
        [Parameter(Mandatory)]
        [string] $ApkPath
    )

    $analyzer = Resolve-AndroidSdkTool 'apkanalyzer'
    $version = (& $analyzer manifest version-name $ApkPath).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $version) {
        throw "Could not read the APK version from '$ApkPath'."
    }
    $version
}

function Assert-AndroidApkSignature {
    param(
        [Parameter(Mandatory)]
        [string] $ApkPath
    )

    $signer = Resolve-AndroidSdkTool 'apksigner'
    & $signer verify $ApkPath
    if ($LASTEXITCODE -ne 0) {
        throw "APK signature verification failed for '$ApkPath'."
    }
}
