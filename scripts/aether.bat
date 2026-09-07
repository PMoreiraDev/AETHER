@echo off
rem
rem AETHER launch script (Windows).
rem Starts the AETHER JavaFX desktop application.
rem
rem Works in two layouts:
rem   1. Source tree (after `mvn -B -DskipTests package` or
rem      `mvn compile dependency:copy-dependencies`):
rem      classpath = target\classes;target\lib\*
rem   2. Extracted distribution zip:
rem      classpath = AETHER-*.jar;lib\* (+resources)
rem
rem Environment variables:
rem   JAVA_HOME            Force a specific JDK 21 installation.
rem   AETHER_PRISM_ORDER   Override the rendering pipeline (default: sw).
rem                        Use "hw" / "es2" for hardware acceleration.
rem
setlocal enabledelayedexpansion

rem --- Locate the application home (parent of this script's directory). ---
set "SCRIPT_DIR=%~dp0"
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"
for %%i in ("%SCRIPT_DIR%") do set "APP_HOME=%%~dpi"
if "%APP_HOME:~-1%"=="\" set "APP_HOME=%APP_HOME:~0,-1%"

rem --- Detect a Java 21 runtime. ---
set "JAVACMD="

if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" (
    "%JAVA_HOME%\bin\java.exe" -version 2>&1 | findstr /R /C:"version \"21\." >nul
    if not errorlevel 1 set "JAVACMD=%JAVA_HOME%\bin\java.exe"
)

if not defined JAVACMD (
    where java >nul 2>&1
    if not errorlevel 1 (
        java -version 2>&1 | findstr /R /C:"version \"21\." >nul
        if not errorlevel 1 set "JAVACMD=java"
    )
)

rem --- Fallback: common JDK 21 install locations. ---
if not defined JAVACMD (
    for %%P in (
        "%ProgramFiles%\Java\jdk-21\bin\java.exe"
        "%ProgramFiles%\Eclipse Adoptium\jdk-21*\bin\java.exe"
        "%ProgramFiles%\Microsoft\jdk-21*\bin\java.exe"
        "%ProgramFiles%\BellSoft\Liberica JRE 21\bin\java.exe"
        "C:\Program Files\Java\jdk-21\bin\java.exe"
    ) do (
        if not defined JAVACMD if exist %%P set "JAVACMD=%%~P"
    )
)

if not defined JAVACMD (
    echo ERROR: Java 21 ^(JDK^) was not found. >&2
    echo Install a JDK 21, ensure 'java' is on PATH, or set JAVA_HOME. >&2
    exit /b 1
)

rem --- Build the module path and classpath. ---
rem JavaFX modules must be resolved on the module path: LauncherApp extends
rem javafx.application.Application, so the JDK launcher takes the JavaFX launch
rem path, which requires the javafx.* modules to be resolvable. Putting the
rem JavaFX jars on -cp alone fails with "JavaFX runtime components are missing".
rem The application jar/classes stay on the (unnamed) classpath.
set "APP_CP="
set "MODULE_PATH="
if exist "%APP_HOME%\target\classes" (
    rem Source tree layout.
    set "APP_CP=%APP_HOME%\target\classes"
    set "MODULE_PATH=%APP_HOME%\target\lib"
) else (
    rem Distribution layout.
    set "JAR="
    for %%f in ("%APP_HOME%\AETHER-*.jar") do set "JAR=%%f"
    if not defined JAR (
        echo ERROR: AETHER jar not found under %APP_HOME%. >&2
        echo Build it first with: mvn -B -DskipTests package >&2
        exit /b 1
    )
    set "APP_CP=!JAR!"
    set "MODULE_PATH=%APP_HOME%\lib"
    if exist "%APP_HOME%\resources" set "APP_CP=!APP_CP!;%APP_HOME%\resources"
)

rem --- JVM options. ---
rem prism.order=sw selects the software rendering pipeline, which keeps AETHER
rem working on headless / remote-desktop machines without a GPU. Set
rem AETHER_PRISM_ORDER=hw to use hardware acceleration.
set "JVM_OPTS=-Dprism.order=sw"
if defined AETHER_PRISM_ORDER set "JVM_OPTS=-Dprism.order=!AETHER_PRISM_ORDER!"

set "JAVA_ARGS=%JVM_OPTS% -cp "%APP_CP%" app.LauncherApp"

rem Only add module-path flags when the directory exists and is non-empty.
if exist "%MODULE_PATH%\*" (
    set "JAVA_ARGS=--module-path "%MODULE_PATH%" --add-modules ALL-MODULE-PATH !JAVA_ARGS!"
)

echo Starting AETHER...
echo   JAVA: %JAVACMD%
echo   HOME: %APP_HOME%
"%JAVACMD%" %JAVA_ARGS% %*
endlocal
