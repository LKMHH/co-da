@echo off
setlocal
set "PROJECT_ROOT=%~dp0.."
set "GRADLE_USER_HOME=%PROJECT_ROOT%\.gradle-test-home"
set "ANDROID_USER_HOME=%PROJECT_ROOT%\.android-user"
set "ANDROID_SDK_HOME="

echo ============================================
echo   CODA release 打包脚本
echo   发版前请先过一遍 RELEASE.md 检查清单
echo ============================================
echo.

REM ---- 1. 检查 gradlew ----
if not exist "%PROJECT_ROOT%\gradlew.bat" (
    echo [错误] 找不到 gradlew.bat，请在项目根目录运行本脚本
    exit /b 1
)

REM ---- 2. 检查签名文件 ----
if exist "%PROJECT_ROOT%\debug-signing.jks" (
    echo [提示] 已找到签名文件 debug-signing.jks
    echo         请确认它已在项目外备份（U盘/网盘），且与上一个发布版本是同一把
) else (
    echo [警告] 未找到 debug-signing.jks 签名文件！
    echo         没有签名无法覆盖安装升级，请先找回 keystore
)
echo.

REM ---- 3. 显示当前版本号 ----
echo [提示] 当前 build.gradle.kts 中的版本配置：
findstr /C:"versionName" /C:"versionCode" "%PROJECT_ROOT%\app\build.gradle.kts"
echo.
echo [提示] 请确认版本号已按 VERSIONING.md 决策表更新（versionCode 只增不减）
echo         CHANGELOG.md 已同步更新
echo.

REM ---- 4. 确认 ----
choice /C YN /M "版本号确认无误，开始打包"
if errorlevel 2 (
    echo 已取消，先更新版本号再来
    exit /b 0
)

REM ---- 5. 执行打包 ----
call "%PROJECT_ROOT%\gradlew.bat" :app:assembleRelease --no-daemon --no-watch-fs
if errorlevel 1 (
    echo [错误] 构建失败，查看上方错误信息
    exit /b 1
)

REM ---- 6. 输出结果 ----
set "APK=%PROJECT_ROOT%\app\build\outputs\apk\release\app-release.apk"
if exist "%APK%" (
    for %%F in ("%APK%") do echo [成功] APK: %APK% ^(%%~zF bytes^)
    echo.
    echo [提醒] 如果 release 未配置签名，此 APK 无法直接安装。
    echo         请检查 app/build.gradle.kts 的 signingConfig，或参考 RELEASE.md。
    echo [下一步] 装到真机验证 -^> 打 git tag -^> 微信群发布
) else (
    echo [错误] 未找到 APK 产物：%APK%
    exit /b 1
)

endlocal
