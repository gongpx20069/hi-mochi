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

    $remoteTags = $null
    foreach ($attempt in 1..3) {
        $remoteTags = & git ls-remote --tags $Remote 'refs/tags/v1.0.*'
        if ($LASTEXITCODE -eq 0) {
            break
        }
        if ($attempt -lt 3) {
            Start-Sleep -Seconds (2 * $attempt)
        }
    }
    if ($LASTEXITCODE -ne 0 -or $null -eq $remoteTags) {
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
        $candidates = @(
            (Join-Path $sdkRoot 'cmdline-tools\latest\bin\apkanalyzer.bat'),
            (Join-Path $sdkRoot 'cmdline-tools/latest/bin/apkanalyzer')
        )
        $candidate = $candidates |
            Where-Object { Test-Path $_ } |
            Select-Object -First 1
        if ($candidate) {
            return $candidate
        }
    }

    if ($Name -eq 'apksigner') {
        $buildTools = Join-Path $sdkRoot 'build-tools'
        $candidate =
            Get-ChildItem $buildTools -Directory -ErrorAction SilentlyContinue |
                Sort-Object { [version] $_.Name } -Descending |
                ForEach-Object {
                    @(
                        (Join-Path $_.FullName 'apksigner.bat'),
                        (Join-Path $_.FullName 'apksigner')
                    )
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

function Get-AndroidApkApplicationId {
    param(
        [Parameter(Mandatory)]
        [string] $ApkPath
    )

    $analyzer = Resolve-AndroidSdkTool 'apkanalyzer'
    $applicationId = (& $analyzer manifest application-id $ApkPath).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $applicationId) {
        throw "Could not read the APK application ID from '$ApkPath'."
    }
    $applicationId
}

function Get-AndroidApkManifest {
    param(
        [Parameter(Mandatory)]
        [string] $ApkPath
    )

    $analyzer = Resolve-AndroidSdkTool 'apkanalyzer'
    $manifest = (& $analyzer manifest print $ApkPath) -join "`n"
    if ($LASTEXITCODE -ne 0 -or -not $manifest) {
        throw "Could not read the APK manifest from '$ApkPath'."
    }
    $manifest
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

function Get-AndroidApkSignerSha256 {
    param(
        [Parameter(Mandatory)]
        [string] $ApkPath
    )

    $signer = Resolve-AndroidSdkTool 'apksigner'
    $details = (& $signer verify --print-certs $ApkPath) -join "`n"
    if ($LASTEXITCODE -ne 0) {
        throw "APK signature verification failed for '$ApkPath'."
    }
    $digests = @(
        [regex]::Matches(
            $details,
            'Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]+)'
        ) |
            ForEach-Object {
                $_.Groups[1].Value.ToLowerInvariant()
            } |
            Sort-Object -Unique
    )
    if ($digests.Count -eq 0) {
        throw "Could not read the APK signer digest from '$ApkPath'."
    }
    $digests
}

function Assert-AndroidApkIdentity {
    param(
        [Parameter(Mandatory)]
        [string] $ApkPath,
        [Parameter(Mandatory)]
        [string] $ExpectedApplicationId,
        [Parameter(Mandatory)]
        [bool] $ShouldHaveLauncher
    )

    $applicationId = Get-AndroidApkApplicationId $ApkPath
    if ($applicationId -ne $ExpectedApplicationId) {
        throw (
            "APK '$ApkPath' application ID '$applicationId' does not " +
            "match '$ExpectedApplicationId'."
        )
    }
    $manifest = Get-AndroidApkManifest $ApkPath
    $hasMain = $manifest -match 'android\.intent\.action\.MAIN'
    $hasLauncher = $manifest -match 'android\.intent\.category\.LAUNCHER'
    $hasLauncherEntry = $hasMain -and $hasLauncher
    if ($hasLauncherEntry -ne $ShouldHaveLauncher) {
        $expectation = if ($ShouldHaveLauncher) {
            'must expose'
        } else {
            'must not expose'
        }
        throw "APK '$ApkPath' $expectation a launcher activity."
    }
}

function Get-MochiReleaseApkOutputs {
    param(
        [Parameter(Mandatory)]
        [string] $OutputDirectory
    )

    $metadataPath = Join-Path $OutputDirectory 'output-metadata.json'
    if (-not (Test-Path $metadataPath)) {
        throw "Android output metadata was not found at '$metadataPath'."
    }
    $metadata = Get-Content $metadataPath -Raw | ConvertFrom-Json
    $outputs = @(
        $metadata.elements |
            ForEach-Object {
                $abiFilters = @(
                    $_.filters |
                        Where-Object { $_.filterType -eq 'ABI' }
                )
                $abi = if ($abiFilters.Count -eq 0) {
                    'universal'
                } elseif ($abiFilters.Count -eq 1) {
                    "$($abiFilters[0].value)"
                } else {
                    throw "APK '$($_.outputFile)' has multiple ABI filters."
                }
                [pscustomobject]@{
                    abi = $abi
                    path = Join-Path $OutputDirectory $_.outputFile
                }
            }
    )
    $expectedAbis = @(
        'arm64-v8a',
        'armeabi-v7a',
        'x86',
        'x86_64',
        'universal'
    )
    $actualAbis = @($outputs.abi | Sort-Object)
    $missing = @($expectedAbis | Where-Object { $_ -notin $actualAbis })
    $unexpected = @($actualAbis | Where-Object { $_ -notin $expectedAbis })
    $duplicates = @(
        $outputs |
            Group-Object abi |
            Where-Object Count -ne 1 |
            ForEach-Object Name
    )
    if ($missing -or $unexpected -or $duplicates) {
        throw (
            "Unexpected release ABI outputs. " +
            "Missing: [$($missing -join ', ')]. " +
            "Unexpected: [$($unexpected -join ', ')]. " +
            "Duplicates: [$($duplicates -join ', ')]."
        )
    }
    $outputs |
        Sort-Object {
            [array]::IndexOf($expectedAbis, $_.abi)
        }
}

function New-MochiReleaseAssets {
    param(
        [Parameter(Mandatory)]
        [string] $Version,
        [Parameter(Mandatory)]
        [string] $Commit,
        [Parameter(Mandatory)]
        [string] $SourceOutputDirectory,
        [Parameter(Mandatory)]
        [string] $ExtensionSourceOutputDirectory,
        [Parameter(Mandatory)]
        [string] $DestinationDirectory
    )

    Assert-MochiVersion $Version
    if ($Commit -notmatch '^[0-9a-f]{40}$') {
        throw "Release commit '$Commit' is not a full Git SHA."
    }
    if (Test-Path $DestinationDirectory) {
        throw "Release destination '$DestinationDirectory' already exists."
    }

    $tag = "v$Version"
    $artifacts = @()
    New-Item -ItemType Directory -Path $DestinationDirectory | Out-Null
    try {
        $baseSignerDigests = $null
        foreach (
            $output in Get-MochiReleaseApkOutputs $SourceOutputDirectory
        ) {
            if (-not (Test-Path $output.path)) {
                throw "Release APK '$($output.path)' does not exist."
            }
            $embeddedVersion = Get-AndroidApkVersion $output.path
            if ($embeddedVersion -ne $Version) {
                throw (
                    "APK '$($output.path)' version '$embeddedVersion' " +
                    "does not match '$Version'."
                )
            }
            Assert-AndroidApkSignature $output.path
            Assert-AndroidApkIdentity `
                -ApkPath $output.path `
                -ExpectedApplicationId 'com.example.mochi_pet' `
                -ShouldHaveLauncher $true
            $signerDigests = @(Get-AndroidApkSignerSha256 $output.path)
            if ($null -eq $baseSignerDigests) {
                $baseSignerDigests = $signerDigests
            } elseif (Compare-Object $baseSignerDigests $signerDigests) {
                throw "Base APK '$($output.path)' uses a different signer."
            }

            $fileName = "Mochi-$tag-$($output.abi).apk"
            $destination = Join-Path $DestinationDirectory $fileName
            Copy-Item $output.path $destination
            $hash = (
                Get-FileHash -Algorithm SHA256 $destination
            ).Hash.ToLowerInvariant()
            $artifacts += [ordered]@{
                kind = 'base'
                abi = $output.abi
                file = $fileName
                sha256 = $hash
            }
        }

        $extensionOutputs = @(
            Get-MochiReleaseApkOutputsSingle $ExtensionSourceOutputDirectory
        )
        if ($extensionOutputs.Count -ne 1) {
            throw "Expected exactly one Mi Home extension APK."
        }
        $extensionPath = $extensionOutputs[0]
        $embeddedVersion = Get-AndroidApkVersion $extensionPath
        if ($embeddedVersion -ne $Version) {
            throw (
                "Extension APK '$extensionPath' version '$embeddedVersion' " +
                "does not match '$Version'."
            )
        }
        Assert-AndroidApkSignature $extensionPath
        Assert-AndroidApkIdentity `
            -ApkPath $extensionPath `
            -ExpectedApplicationId 'com.example.mochi_pet.extension.mijia' `
            -ShouldHaveLauncher $false
        $extensionSignerDigests = @(Get-AndroidApkSignerSha256 $extensionPath)
        if (Compare-Object $baseSignerDigests $extensionSignerDigests) {
            throw "The Mi Home extension APK must use the base APK signer."
        }
        $extensionFileName = "Mochi-Mijia-Extension-$tag.apk"
        $extensionDestination = Join-Path (
            $DestinationDirectory
        ) $extensionFileName
        Copy-Item $extensionPath $extensionDestination
        $extensionHash = (
            Get-FileHash -Algorithm SHA256 $extensionDestination
        ).Hash.ToLowerInvariant()
        $artifacts += [ordered]@{
            kind = 'extension'
            abi = 'universal'
            file = $extensionFileName
            sha256 = $extensionHash
        }

        $checksumName = "Mochi-$tag-SHA256SUMS.txt"
        $checksumPath = Join-Path $DestinationDirectory $checksumName
        $artifacts |
            ForEach-Object {
                "$($_.sha256)  $($_.file)"
            } |
            Set-Content -Encoding ascii $checksumPath

        [ordered]@{
            version = $Version
            commit = $Commit
            checksum_file = $checksumName
            artifacts = $artifacts
        } |
            ConvertTo-Json -Depth 4 |
            Set-Content -Encoding utf8 (
                Join-Path $DestinationDirectory 'release.json'
            )
    } catch {
        Remove-Item $DestinationDirectory -Recurse -Force `
            -ErrorAction SilentlyContinue
        throw
    }
}

function Get-MochiReleaseApkOutputsSingle {
    param(
        [Parameter(Mandatory)]
        [string] $OutputDirectory
    )

    $metadataPath = Join-Path $OutputDirectory 'output-metadata.json'
    if (-not (Test-Path $metadataPath)) {
        throw "Android output metadata was not found at '$metadataPath'."
    }
    $metadata = Get-Content $metadataPath -Raw | ConvertFrom-Json
    $outputs = @(
        $metadata.elements |
            ForEach-Object {
                Join-Path $OutputDirectory $_.outputFile
            }
    )
    if ($outputs.Count -ne 1 -or -not (Test-Path $outputs[0])) {
        throw "Expected exactly one APK output in '$OutputDirectory'."
    }
    $outputs
}
