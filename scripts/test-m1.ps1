$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$env:GRADLE_USER_HOME = Join-Path $projectRoot ".gradle-test-home"
$env:ANDROID_USER_HOME = Join-Path $projectRoot ".android-user"
Remove-Item Env:ANDROID_SDK_HOME -ErrorAction SilentlyContinue

Push-Location $projectRoot
try {
    & .\gradlew.bat :app:testDebugUnitTest --no-daemon --no-watch-fs
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
} finally {
    Pop-Location
}
