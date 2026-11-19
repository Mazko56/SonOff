param(
    [Parameter(Mandatory=$true)][string]$Repo,
    [Parameter(Mandatory=$true)][string]$Source
)
$ErrorActionPreference = 'Stop'
$Repo = (Resolve-Path -LiteralPath $Repo).Path
$Source = (Resolve-Path -LiteralPath $Source).Path
if (-not (Test-Path -LiteralPath (Join-Path $Repo '.git'))) { throw 'Repo must be the root of a Git repository' }
if (-not (Test-Path -LiteralPath (Join-Path $Source 'app/build.gradle'))) { throw 'Source must be the extracted clean gateway project' }
$changes = @(git -C $Repo status --porcelain)
if ($LASTEXITCODE -ne 0 -or $changes.Count -gt 0) { throw 'Git working tree has uncommitted changes. Commit or stash them before replacement.' }
$backup = Join-Path (Split-Path -Parent $Repo) ("SonoffBackup_" + (Get-Date -Format 'yyyyMMdd_HHmmss'))
New-Item -ItemType Directory -Path $backup -Force | Out-Null
$folders = @('app', 'tests', 'docs', 'scripts', '.github')
$rootFiles = @('build.gradle', 'settings.gradle', 'gradle.properties', '.gitignore', 'README.md')
foreach ($entry in ($folders + $rootFiles)) {
    $p = Join-Path $Repo $entry
    if (Test-Path -LiteralPath $p) {
        Copy-Item -LiteralPath $p -Destination (Join-Path $backup $entry) -Recurse -Force
    }
}
foreach ($entry in $folders) {
    $target = Join-Path $Repo $entry
    if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
    Copy-Item -LiteralPath (Join-Path $Source $entry) -Destination $target -Recurse -Force
}
foreach ($entry in $rootFiles) {
    Copy-Item -LiteralPath (Join-Path $Source $entry) -Destination (Join-Path $Repo $entry) -Force
}
Write-Host "Clean project copied. Old files backed up to: $backup"
Write-Host "Review changes: git -C '$Repo' status --short"
Write-Host "Run: python -m unittest discover -s tests -v"
Write-Host "Then: git add -A; git commit; git push"
