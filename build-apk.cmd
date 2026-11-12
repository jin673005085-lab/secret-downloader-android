@echo off
setlocal
if not defined GRADLE_USER_HOME set "GRADLE_USER_HOME=%USERPROFILE%\.gradle"
set "SECRET_BUILD_ROOT=%GRADLE_USER_HOME%\secret-downloader-build"
pushd "%~dp0"
call gradlew.bat "-PandroidBuildRoot=%SECRET_BUILD_ROOT%" --no-daemon --console=plain testDebugUnitTest testReleaseUnitTest assembleDebug lintDebug assembleRelease lintRelease
set "SECRET_BUILD_RESULT=%ERRORLEVEL%"
popd
if not "%SECRET_BUILD_RESULT%"=="0" exit /b %SECRET_BUILD_RESULT%
echo Release APK: %SECRET_BUILD_ROOT%\app\outputs\apk\release\app-release.apk
echo Debug APK: %SECRET_BUILD_ROOT%\app\outputs\apk\debug\app-debug.apk
exit /b 0
