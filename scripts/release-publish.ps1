# Publish a release: bump the patch, build the APK, put it on GitHub.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\release-publish.ps1
#
# Obtainium installs this app by watching this repository's releases, so the
# publish *is* the delivery, and the steps are ordered so nothing ships without
# its bookkeeping:
#
#   1. `quire.version`'s patch +1 in gradle.properties. versionName and
#      versionCode are both derived from that one line by app/build.gradle.kts,
#      so this is what makes Android — and Obtainium — see the update at all.
#   2. `gradlew :app:assembleRelease`, which also runs cargo-ndk for the bridge.
#   3. The bump is committed (gradle.properties, nothing else) and pushed, so the
#      tag created next points at a commit carrying the version it names.
#   4. `gh release create v<version>` attaches quire-compose-<version>.apk.
#
# The signed artifact is app-release.apk, AGP's own name; the published asset is
# the copy whose file name carries the version. Re-running after a failed publish
# finds the tag already there and re-uploads over it rather than erroring out.

$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
$root = (Get-Location).Path

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    throw "gh is not on PATH — install the GitHub CLI and 'gh auth login' first"
}

# ── 1: the bump ─────────────────────────────────────────────────────────────
# `quire.version` is unique in gradle.properties, so there is no table to guess
# at — which is why the version lives there and not in a manifest with a
# [package] table to disambiguate against.
$props = Join-Path $root 'gradle.properties'
$text = [System.IO.File]::ReadAllText($props)
$m = [regex]::Match($text, '(?m)^quire\.version\s*=\s*(\d+)\.(\d+)\.(\d+)\s*$')
if (-not $m.Success) { throw "no quire.version=MAJOR.MINOR.PATCH line in gradle.properties" }
$version = "{0}.{1}.{2}" -f $m.Groups[1].Value, $m.Groups[2].Value, ([int]$m.Groups[3].Value + 1)
# splice exactly the three digits — group 1 starts at the major, group 3 ends at
# the patch — so every other byte of the file is untouched
$vStart = $m.Groups[1].Index
$vEnd = $m.Groups[3].Index + $m.Groups[3].Length
$text = $text.Substring(0, $vStart) + $version + $text.Substring($vEnd)
$utf8 = New-Object System.Text.UTF8Encoding $false
[System.IO.File]::WriteAllText($props, $text, $utf8)
# read it back before anything expensive happens on top of a bad edit
$check = [regex]::Match([System.IO.File]::ReadAllText($props), '(?m)^quire\.version\s*=\s*([^\r\n]+)')
if (-not $check.Success -or $check.Groups[1].Value -ne $version) {
    throw ("the bump wrote '{0}', expected '{1}' — aborting before the build" -f `
        $check.Groups[1].Value, $version)
}
Write-Output "==> version -> $version"

# ── 2: the build ────────────────────────────────────────────────────────────
& (Join-Path $root 'gradlew.bat') :app:assembleRelease --console=plain
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$apk = Join-Path $root 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path $apk)) { throw "no APK at $apk — the build reported success but left nothing" }

# ── 2b: it has to be signed ─────────────────────────────────────────────────
# The signing identity is the keystore in the user's own directory, which is
# outside this repository; when it is missing the build produces an *unsigned*
# release APK, which installs nowhere. That is caught here rather than assumed
# from the build's exit code, because an unsigned APK would otherwise be
# published, and the release *is* the delivery — Obtainium installs what is
# attached to it.
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else {
    $line = Select-String -Path (Join-Path $root 'local.properties') -Pattern '^sdk\.dir=(.*)$' |
        Select-Object -First 1
    if ($line) { $line.Matches[0].Groups[1].Value -replace '\\:', ':' -replace '\\\\', '\' } else { $null }
}
if (-not $sdk) { throw "no ANDROID_HOME and no sdk.dir in local.properties — cannot verify the signature" }
$buildTools = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (-not $buildTools) { throw "no build-tools under $sdk — cannot verify the signature" }
& (Join-Path $buildTools.FullName 'apksigner.bat') verify --print-certs $apk
if ($LASTEXITCODE -ne 0) { throw "$apk is not signed — refusing to publish it" }

$named = Join-Path $root ("app\build\outputs\apk\release\quire-compose-{0}.apk" -f $version)
Copy-Item $apk $named -Force

# ── 3: the bump goes in as its own commit ───────────────────────────────────
git add gradle.properties
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
git commit -m "chore(release): $version"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
git push
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# ── 4: the release ──────────────────────────────────────────────────────────
$repo = (& gh repo view --json nameWithOwner | ConvertFrom-Json).nameWithOwner
$tag = "v$version"
$existing = & gh release list --json tagName --limit 200 | ConvertFrom-Json
if (@($existing | Where-Object { $_.tagName -eq $tag }).Count -gt 0) {
    Write-Output "==> release $tag already exists; replacing its APK"
    & gh release upload $tag $named --clobber
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} else {
    $notes = @"
Quire for Android, the Compose shell — $version (arm64-v8a only).

- package: dev.quire.compose (minSdk 24, targetSdk 34)
- installs beside the Slint shell (dev.quire.android): different package, so
  both can be on one device
- signed with the stable key keystore.properties names, so an update installs
  over every earlier build carrying the same signature

Obtainium: add https://github.com/$repo and it picks up the APK attached to
every release.
"@
    & gh release create $tag $named --target main --title "Quire Compose $version" --notes $notes
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
Write-Output "==> published $tag ($named)"
