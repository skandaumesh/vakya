@echo off
rem Start the Vakya server for the phone app. Keep this window open; Ctrl+C stops it.
cd /d "%~dp0"
call "%~dp0setup.bat" needkey || exit /b 1
echo Server running at http://localhost:8000  (check: http://localhost:8000/health)
.venv\Scripts\python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
