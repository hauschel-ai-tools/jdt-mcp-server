@echo off
REM
REM JDT MCP Server - Standalone launcher (Windows)
REM Starts the Eclipse JDT MCP server for use with AI coding assistants.
REM
REM Usage:
REM   jdtls-mcp              # stdio mode (default, for Claude Code)
REM   jdtls-mcp --http       # HTTP/SSE mode (for debugging)
REM
REM Environment variables:
REM   JDTMCP_WORKSPACE  - Eclipse workspace directory (default: %TEMP%\jdtls-mcp-<pid>)
REM   JDTMCP_TRANSPORT  - Transport: stdio or http (default: stdio)
REM   JAVA_HOME         - Java installation to use
REM

setlocal enabledelayedexpansion

REM Resolve installation directory
set "BASEDIR=%~dp0.."

REM Parse arguments
if not defined JDTMCP_TRANSPORT set "TRANSPORT=stdio"
if defined JDTMCP_TRANSPORT set "TRANSPORT=%JDTMCP_TRANSPORT%"

set "EXTRA_ARGS="
for %%a in (%*) do (
    if "%%a"=="--version" (
        if exist "%BASEDIR%\.version" (
            set /p VERSION=<"%BASEDIR%\.version"
            echo JDT MCP Server !VERSION!
        ) else (
            echo JDT MCP Server ^(development^)
        )
        exit /b 0
    )
    if "%%a"=="--http" set "TRANSPORT=http"
)

REM Workspace directory
if not defined JDTMCP_WORKSPACE (
    set "WORKSPACE=%TEMP%\jdtls-mcp-%RANDOM%"
) else (
    set "WORKSPACE=%JDTMCP_WORKSPACE%"
)

REM Find Java
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" (
        set "JAVA=%JAVA_HOME%\bin\java.exe"
    ) else (
        set "JAVA=java"
    )
) else (
    set "JAVA=java"
)

REM Find Equinox launcher
set "LAUNCHER="
for %%f in ("%BASEDIR%\plugins\org.eclipse.equinox.launcher_*.jar") do (
    set "LAUNCHER=%%f"
)

if not defined LAUNCHER (
    echo ERROR: Equinox launcher not found in %BASEDIR%\plugins\ >&2
    exit /b 1
)

"%JAVA%" ^
    -Djdtmcp.headless=true ^
    -Djdtmcp.transport=%TRANSPORT% ^
    -Dosgi.bundles.defaultStartLevel=4 ^
    -Declipse.application=org.naturzukunft.jdt.mcp.headless ^
    -jar "%LAUNCHER%" ^
    -configuration "%BASEDIR%\configuration" ^
    -data "%WORKSPACE%" ^
    -nosplash ^
    %*
