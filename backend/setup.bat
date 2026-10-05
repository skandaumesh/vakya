@echo off
rem Setup: Python environment, and (with "needkey") the .env file for your API key.
cd /d "%~dp0"

if not exist .venv\Scripts\python.exe (
    echo Setting up Python environment, this takes a minute...
    python -m venv .venv || goto :fail
    .venv\Scripts\python -m pip install -q --upgrade pip
    .venv\Scripts\python -m pip install -q -r requirements-dev.txt || goto :fail
    echo Done.
)

if "%~1"=="needkey" if not exist .env (
    copy /y .env.example .env >nul
    echo.
    echo Created backend\.env and opened it in Notepad.
    echo Paste your Groq key after GROQ_API_KEY=  then save, close Notepad and run this again.
    echo Free key: https://console.groq.com/keys
    start notepad .env
    exit /b 1
)
exit /b 0

:fail
echo.
echo Setup failed. Is Python 3.10+ installed and on PATH?
exit /b 1
