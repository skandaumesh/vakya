@echo off
rem Offline tests: no API key needed, costs nothing.
rem For the live quality check (uses ~60k of your daily free tokens) run:
rem     .venv\Scripts\python eval\run_eval.py
cd /d "%~dp0"
call "%~dp0setup.bat" || exit /b 1
.venv\Scripts\python -m pytest -q
