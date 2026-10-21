@echo off
REM Build openandroiddex-wmd into a single dex, no Gradle and no Android project.
REM The daemon is not an app: it is loaded by `app_process` at uid 2000, so it needs
REM nothing but class files run through d8.
REM
REM   build.cmd            compile + dex
REM   build.cmd push       ... and push to /data/local/tmp/wmd.dex

setlocal enabledelayedexpansion
set HERE=%~dp0
set SDK=%LOCALAPPDATA%\Android\Sdk
set JBR=C:\Program Files\Android\Android Studio\jbr\bin

if not exist "%SDK%\platforms\android-36\android.jar" (
  echo ERROR: android-36 platform not found under %SDK%
  exit /b 1
)

set BUILDTOOLS=
for %%v in (36.1.0 36.0.0 35.0.0 34.0.0) do (
  if not defined BUILDTOOLS if exist "%SDK%\build-tools\%%v\d8.bat" set BUILDTOOLS=%SDK%\build-tools\%%v
)
if not defined BUILDTOOLS (
  echo ERROR: no build-tools with d8 found
  exit /b 1
)

echo [1/3] javac
if exist "%HERE%build" rmdir /s /q "%HERE%build"
mkdir "%HERE%build\classes"
dir /s /b "%HERE%src\*.java" > "%HERE%build\sources.txt"
REM android.jar goes on the classpath, not the bootclasspath: JDK 17+ rejects
REM -bootclasspath alongside -target 17. Everything we touch outside android.*
REM is java.lang/util/io/net, which Android provides at runtime anyway.
"%JBR%\javac.exe" -nowarn -Xlint:-options -source 17 -target 17 ^
  -cp "%SDK%\platforms\android-36\android.jar" ^
  -d "%HERE%build\classes" "@%HERE%build\sources.txt"
if errorlevel 1 exit /b 1

echo [2/3] d8
mkdir "%HERE%build\dex"
dir /s /b "%HERE%build\classes\*.class" > "%HERE%build\classes.txt"
call "%BUILDTOOLS%\d8.bat" --min-api 26 --output "%HERE%build\dex" "@%HERE%build\classes.txt"
if errorlevel 1 exit /b 1
copy /y "%HERE%build\dex\classes.dex" "%HERE%openandroiddex-wmd.dex" >nul

echo [3/3] done -^> %HERE%openandroiddex-wmd.dex
if /i "%~1"=="push" (
  adb push "%HERE%openandroiddex-wmd.dex" /data/local/tmp/wmd.dex
)
endlocal
