@echo off
rem Try Vakya in this window: type a message you received, get 3 replies.
rem Extra options pass through, e.g.  try.bat --rel client
rem                                   try.bat --export "WhatsApp Chat with Rahul.txt" --rel friend
cd /d "%~dp0"
call "%~dp0setup.bat" needkey || exit /b 1
.venv\Scripts\python scripts\try_reply.py %*
