# Synchronizes only obsolete Python test files against an already extracted release ZIP.
# No source code, user configuration, .git history or other files are deleted.
param(
    [Parameter(Mandatory = $true)][string]$ArchivePath
)
$ErrorActionPreference = 'Stop'
$archive = (Resolve-Path -LiteralPath $ArchivePath).Path
$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
Add-Type -AssemblyName System.IO.Compression
$zip = [System.IO.Compression.ZipFile]::OpenRead($archive)
try {
    $expected = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($entry in $zip.Entries) {
        $relative = $entry.FullName.Replace('\', '/')
        if ($relative -match '^tests/test_[^/]+\.py$') {
            [void]$expected.Add($relative)
        }
    }
    if ($expected.Count -lt 10) { throw "ZIP looks incomplete: only $($expected.Count) tests found" }
    $existing = @(Get-ChildItem -LiteralPath (Join-Path $root 'tests') -Filter 'test_*.py' -File)
    $obsolete = @($existing | Where-Object { -not $expected.Contains("tests/$($_.Name)") })
    if ($obsolete.Count -eq 0) { Write-Host 'No obsolete tests; source is ready for verification.'; return }
    $backup = Join-Path $env:TEMP ('sonoff-obsolete-tests-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    New-Item -ItemType Directory -Path $backup -Force | Out-Null
    foreach ($file in $obsolete) {
        Move-Item -LiteralPath $file.FullName -Destination (Join-Path $backup $file.Name)
        Write-Host "Archived obsolete test: $($file.Name)"
    }
    Write-Host "Old test files were preserved at: $backup"
} finally {
    $zip.Dispose()
}
