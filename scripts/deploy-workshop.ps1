# Deploy a release into the workshop: bump the patch, build the signed APK, and
# put it in C:\workshop\quire-compose-<version>.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\deploy-workshop.ps1
#
# The workshop is the user's own drop zone: one folder per release, named
# <name>-<version> (aura-1.2.6, aw-qtui-0.1.36, quire-desktop-0.1.4), filled with
# what that build needs to run. This shell's folder is quire-compose-<version>
# and not a bare quire-<version>, for the reason the desktop shell's is
# quire-desktop-<version>: the drop zone holds one folder per release of several
# applications, and there are two Android shells in this family — a name that did
# not say which one put it there would be a name to guess at. The file inside
# keeps the name the GitHub release carries, so the local copy and the published
# one are the same name over the same bytes.
#
# `just release-publish` is this shell's *delivery* — the APK Obtainium installs —
# and this is the local one. They are two independent acts, and each advances the
# version, the way the desktop shell's deploy and its publish do.
#
# The steps are ordered so the folder holds an APK that says <version>:
#
#   1. `quire.version`'s patch +1 in gradle.properties. versionName and
#      versionCode are both derived from that one line by app/build.gradle.kts,
#      so the bump is what makes the folder's name true of the APK inside it —
#      and it is what makes a second deploy a *second* folder instead of the same
#      folder quietly holding a different build.
#   2. `gradlew :app:assembleRelease`, which also runs cargo-ndk for the bridge.
#   3. The APK is checked to be *signed* before it is copied. The keystore lives
#      in the user's own directory, outside this repository; when it is missing
#      the build produces an *unsigned* release APK, which installs nowhere —
#      caught here rather than assumed from the build's exit code, because the
#      workshop is where an APK gets picked up from.
#   4. The bump is committed (gradle.properties, nothing else) and pushed, so the
#      folder can still say what it was built from.
#   5. The copy, last: a build or a push that failed leaves no folder behind
#      claiming a version that never landed.
#
# One rule for the text below: every character inside a Write-Output or a throw
# is ASCII. Windows PowerShell 5.1 reads a BOM-less script as ANSI — this file is
# UTF-8 like the rest of the repository — so a non-ASCII character in a *string
# literal* reaches the console as mojibake (this machine's codepage is GBK).
# Comments are free; messages are not, because the one place they are read is a
# deploy that went wrong.

$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
$root = (Get-Location).Path

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
    throw ("the bump wrote '{0}', expected '{1}' - aborting before the build" -f `
        $check.Groups[1].Value, $version)
}
Write-Output "==> version -> $version"

# ── 2: the build ────────────────────────────────────────────────────────────
& (Join-Path $root 'gradlew.bat') :app:assembleRelease --console=plain
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$apk = Join-Path $root 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path $apk)) { throw "no APK at $apk - the build reported success but left nothing" }

# ── 2b: it has to be signed ─────────────────────────────────────────────────
# The signing identity is the keystore in the user's own directory, which is
# outside this repository; when it is missing the build produces an *unsigned*
# release APK, which installs nowhere. That is caught here rather than assumed
# from the build's exit code, because an unsigned APK in the workshop is a folder
# that cannot be installed from.
#
# No version check to go with it, unlike the desktop deploy's look at the exe's
# version resource: gradle.properties is a configuration input Gradle re-reads on
# every configure, and the read-back above already proved the value that lands in
# it. The signature is the failure that is *silent*.
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else {
    $line = Select-String -Path (Join-Path $root 'local.properties') -Pattern '^sdk\.dir=(.*)$' |
        Select-Object -First 1
    if ($line) { $line.Matches[0].Groups[1].Value -replace '\\:', ':' -replace '\\\\', '\' } else { $null }
}
if (-not $sdk) { throw "no ANDROID_HOME and no sdk.dir in local.properties - cannot verify the signature" }
$buildTools = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (-not $buildTools) { throw "no build-tools under $sdk - cannot verify the signature" }
& (Join-Path $buildTools.FullName 'apksigner.bat') verify --print-certs $apk
if ($LASTEXITCODE -ne 0) { throw "$apk is not signed - refusing to deploy it" }

# ── 3: the bump goes in as its own commit ───────────────────────────────────
git add gradle.properties
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
git commit -m "chore(release): $version"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
git push
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# ── 4: the deploy ───────────────────────────────────────────────────────────
$archive = Join-Path 'C:\workshop' "quire-compose-$version"
New-Item -ItemType Directory -Force -Path $archive | Out-Null
$named = Join-Path $archive ("quire-compose-{0}.apk" -f $version)
Copy-Item $apk $named -Force
Write-Output ("==> deployed v{0} ({1:N1} MiB) -> {2}" -f `
    $version, ((Get-Item $named).Length / 1MB), $named)
