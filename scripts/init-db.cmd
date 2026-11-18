@echo off
setlocal
set "PROJECT=%~dp0.."
set "MYSQL=mysql.exe"
where mysql.exe >nul 2>nul
if errorlevel 1 (
    set "MYSQL=%ProgramFiles%\MySQL\MySQL Server 8.4\bin\mysql.exe"
)
if not exist "%PROJECT%\sql\schema.sql" (
    echo Cannot find sql\schema.sql.
    exit /b 1
)
if not exist "%MYSQL%" (
    where mysql.exe >nul 2>nul
    if errorlevel 1 (
        echo MySQL client not found. Install MySQL or add mysql.exe to PATH.
        exit /b 1
    )
)
echo Enter your MySQL root password when prompted.
"%MYSQL%" --user=root --password < "%PROJECT%\sql\schema.sql"
if errorlevel 1 exit /b 1
echo AEGIS schema and Demo Agent are ready.
