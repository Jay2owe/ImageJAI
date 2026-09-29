@echo off
rem ImageJAI Console launcher — put this file (or a copy) anywhere on PATH.
rem It finds its own repository, so it works from any working directory.
setlocal
set "CONSOLE_HOME=%~dp0"
set "IMAGEJAI_AGENT_WORKSPACE=%CONSOLE_HOME%agent"
set "PYTHONPATH=%CONSOLE_HOME%;%PYTHONPATH%"
python -m agent.console %*
set "IMAGEJAI_EXIT=%ERRORLEVEL%"
endlocal & exit /b %IMAGEJAI_EXIT%
