$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'AndroidReleaseTools.ps1')

function Assert-Equal($Actual, $Expected) {
    if ($Actual -cne $Expected) { throw "Expected '$Expected', got '$Actual'." }
}

function git {
    if ($args[0] -eq 'remote') {
        $global:LASTEXITCODE = 0
        return $script:RemoteUrl
    }
    $global:LASTEXITCODE = $script:GitExit
    return $script:GitTags
}

function gh {
    $script:ApiCalls++
    Assert-Equal $args[1] 'repos/example/project/git/matching-refs/tags/v1.0.'
    $global:LASTEXITCODE = $script:ApiExit
    return $script:ApiTags
}

function Start-Sleep {}

$script:ApiCalls = 0
$script:GitExit = 0
$script:GitTags = @("abc`trefs/tags/v1.0.9", "def`trefs/tags/v1.0.14", "ghi`trefs/tags/v1.0.99^{}")
$script:RemoteUrl = 'https://github.com/example/project.git'
$script:ApiExit = 0
$script:ApiTags = @('refs/tags/v1.0.9', 'refs/tags/v1.0.14', 'refs/tags/v1.0.2')
Assert-Equal (Get-MochiNextVersion) '1.0.15'
Assert-Equal $script:ApiCalls 0

$script:GitExit = 1
$script:GitTags = $null
Assert-Equal (Get-MochiNextVersion) '1.0.15'
$script:RemoteUrl = 'git@github.com:example/project.git'
Assert-Equal (Get-MochiNextVersion) '1.0.15'

$script:ApiTags = @()
Assert-Equal (Get-MochiNextVersion) '1.0.1'

$script:ApiExit = 1
$failed = $false
try { Get-MochiNextVersion | Out-Null } catch { $failed = $true }
Assert-Equal $failed $true

$callsBefore = $script:ApiCalls
$script:RemoteUrl = 'https://example.invalid/example/project.git'
$failed = $false
try { Get-MochiNextVersion | Out-Null } catch { $failed = $true }
Assert-Equal $failed $true
Assert-Equal $script:ApiCalls $callsBefore
Write-Output 'Release version checks passed: Git, HTTPS/SSH API fallback, numeric ordering, empty tags, and failure handling.'
