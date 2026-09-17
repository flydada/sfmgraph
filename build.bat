@echo off
rem ============================================================
rem  build.bat - build the mod jar with the Gradle wrapper.
rem  Finds a JDK 21 automatically: JAVA_HOME first, then the JDK
rem  bundled with the Minecraft launcher.
rem ============================================================
setlocal
cd /d "%~dp0"

call :find_jdk
if errorlevel 1 exit /b 1

echo [build] using JAVA_HOME=%JAVA_HOME%
echo [build] gradle %*
echo.

call gradlew.bat %* --console=plain
exit /b %errorlevel%

:find_jdk
if defined JAVA_HOME (
  if exist "%JAVA_HOME%\bin\javac.exe" goto :jdk_ok
)
set "JAVA_HOME=%APPDATA%\.minecraft\runtime\java-runtime-delta"
if exist "%JAVA_HOME%\bin\javac.exe" goto :jdk_ok

echo [ERROR] No JDK 21 found. Install one, or set JAVA_HOME to a JDK 21.
echo         The Minecraft launcher's copy lives in:
echo         %%APPDATA%%\.minecraft\runtime\java-runtime-delta
exit /b 1

:jdk_ok
set "PATH=%JAVA_HOME%\bin;%PATH%"
exit /b 0
