@echo off
rem ============================================================
rem  run_client.bat - launch a development Minecraft client with
rem  Super Factory Manager and this addon loaded.
rem
rem  The first launch downloads and decompiles Minecraft, which
rem  takes several minutes. Later launches start in seconds.
rem ============================================================
setlocal
cd /d "%~dp0"

if defined JAVA_HOME (
  if exist "%JAVA_HOME%\bin\javac.exe" goto :jdk_ok
)
set "JAVA_HOME=%APPDATA%\.minecraft\runtime\java-runtime-delta"
if exist "%JAVA_HOME%\bin\javac.exe" goto :jdk_ok

echo [ERROR] No JDK 21 found. Install one, or set JAVA_HOME to a JDK 21.
exit /b 1

:jdk_ok
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo [run] starting development client with JAVA_HOME=%JAVA_HOME%
echo [run] first launch takes a while: Minecraft is downloaded and decompiled.
echo.
call gradlew.bat runClient --console=plain
exit /b %errorlevel%
