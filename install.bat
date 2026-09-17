@echo off
rem ============================================================
rem  install.bat - copy the built jar into a mods folder.
rem
rem  Usage: install.bat [mods folder]
rem         with no argument it uses %APPDATA%\.minecraft\mods
rem
rem  It refuses to run while Minecraft is open. That is not
rem  paranoia: Minecraft reads classes out of the mod jar lazily,
rem  so replacing the file under a running game leaves every class
rem  that has not been loaded yet unfindable. The result is a
rem  NoClassDefFoundError crash on whichever class happens to load
rem  next, which looks like a random bug in the mod.
rem ============================================================
setlocal
cd /d "%~dp0"

set "JAR="
for %%f in (build\libs\*.jar) do set "JAR=%%f"
if not defined JAR (
  echo [ERROR] No jar in build\libs. Run build.bat first.
  exit /b 1
)

set "DEST=%~1"
if "%DEST%"=="" set "DEST=%APPDATA%\.minecraft\mods"

tasklist /fi "imagename eq java.exe" 2>nul | find /i "java.exe" >nul
if not errorlevel 1 (
  echo [ERROR] Minecraft looks like it is running ^(java.exe^).
  echo         Close the game completely, then run this again.
  echo.
  echo         Replacing the jar under a running game causes
  echo         NoClassDefFoundError crashes when later classes load.
  exit /b 1
)
tasklist /fi "imagename eq javaw.exe" 2>nul | find /i "javaw.exe" >nul
if not errorlevel 1 (
  echo [ERROR] Minecraft looks like it is running ^(javaw.exe^).
  echo         Close the game completely, then run this again.
  exit /b 1
)

if not exist "%DEST%" (
  echo [ERROR] mods folder not found: %DEST%
  echo         Pass it explicitly, for example:
  echo         install.bat "D:\pcl\.minecraft\versions\1.21.1-NeoForge_21.1.250\mods"
  exit /b 1
)

copy /y "%JAR%" "%DEST%\" >nul
if errorlevel 1 (
  echo [ERROR] Copy failed.
  exit /b 1
)

echo [OK] Installed:
echo      %JAR%
echo   to %DEST%
echo.
echo      Start the game now.
exit /b 0
