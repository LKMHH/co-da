@echo off
set "PROJECT_ROOT=%~dp0.."
set "GRADLE_USER_HOME=%PROJECT_ROOT%\.gradle-test-home"
set "ANDROID_USER_HOME=%PROJECT_ROOT%\.android-user"
set "ANDROID_SDK_HOME="
call "%PROJECT_ROOT%\gradlew.bat" :app:testDebugUnitTest --no-daemon --no-watch-fs
exit /b %ERRORLEVEL%
