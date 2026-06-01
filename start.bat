@echo off
setlocal

cd /d "%~dp0"

set "JAR=target\cosmic-cost-ai-0.1.0-SNAPSHOT.jar"
set "PORT=8080"

where mvn >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Maven not found in PATH.
  echo Please install Maven and make sure "mvn" is available.
  pause
  exit /b 1
)

where java >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Java not found in PATH.
  pause
  exit /b 1
)

if not "%~1"=="" set "OPENAI_API_KEY=%~1"
if not "%~2"=="" set "PORT=%~2"

if "%OPENAI_API_KEY%"=="" (
  echo [ERROR] OPENAI_API_KEY is empty.
  echo Option 1: set OPENAI_API_KEY in system/user env and reopen terminal.
  echo Option 2: run start.bat sk-xxxx [port]
  pause
  exit /b 1
)

if "%DEEPSEEK_API_KEY%"=="" (
  set "DEEPSEEK_API_KEY=%OPENAI_API_KEY%"
)

if not exist "storage\tmp" mkdir "storage\tmp"

echo [INFO] Stopping old cosmic-cost-ai process if it is running...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -like '*cosmic-cost-ai-0.1.0-SNAPSHOT.jar*' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"

echo [INFO] Building project (skip tests)...
call mvn -q clean package -DskipTests
if errorlevel 1 (
  echo [ERROR] Build failed.
  pause
  exit /b 1
)

if not exist "%JAR%" (
  echo [ERROR] Jar not found after build: %JAR%
  pause
  exit /b 1
)

echo [INFO] Starting cosmic-cost-ai on port %PORT% ...
echo [INFO] URL: http://localhost:%PORT%/index.html
echo.

java -Dserver.port=%PORT% -DOPENAI_API_KEY="%OPENAI_API_KEY%" -DDEEPSEEK_API_KEY="%DEEPSEEK_API_KEY%" -jar "%JAR%"

endlocal
