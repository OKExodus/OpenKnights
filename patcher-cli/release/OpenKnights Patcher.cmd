@echo off
rem OpenKnights Patcher for Windows: patches the game in the "original" folder next to this file.
setlocal
set "HERE=%~dp0"
set "JAVA=%HERE%runtime\bin\java.exe"
if not exist "%JAVA%" set "JAVA=java"
"%JAVA%" -Xmx2g -cp "%HERE%app\*" io.github.okexodus.openknights.patcher.cli.MainKt %*
set "CODE=%ERRORLEVEL%"
echo.
if "%~1"=="" pause
exit /b %CODE%
