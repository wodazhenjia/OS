@echo off
chcp 65001 >nul
rem ============================================================
rem  SimOS 一键运行脚本（Windows）
rem  用法：
rem    run.bat                编译并启动图形界面
rem    run.bat selftest [N]   编译并运行无界面自检（N 默认 600）
rem    run.bat build          只编译打包，不运行
rem    run.bat test           运行要求回归、健壮性回归和目录树回归测试
rem  需要完整 JDK 21：JAVA_HOME 指向低版本时会自动回退到
rem  %USERPROFILE%\.jdks\ms-21.0.11，再回退到 PATH 上的 javac。
rem ============================================================
setlocal EnableDelayedExpansion
cd /d "%~dp0"

if not exist "src\main\java" (
    echo [错误] 请在 SimOS 项目根目录运行本脚本。
    exit /b 1
)

rem ---------- 选择 JDK 21+ ----------
set "JDK_HOME="
set "JAVAC=javac"
set "JAVA=java"
set "JAR=jar"
set "FOUND_VERSION="

call :probe_jdk "%JAVA_HOME%"
if not defined JDK_HOME call :probe_jdk "%USERPROFILE%\.jdks\ms-21.0.11"
if not defined JDK_HOME call :probe_path

if not defined JDK_HOME (
    echo [错误] 未找到可用的 JDK 21 或更高版本。
    if defined FOUND_VERSION echo        检测到的 javac 版本：%FOUND_VERSION%
    if defined JAVA_HOME echo        JAVA_HOME = %JAVA_HOME%
    echo        请安装 JDK 21，或把 JAVA_HOME 指向 JDK 21 后重试。
    exit /b 1
)
echo [环境] JDK %FOUND_VERSION%   %JDK_HOME%
echo.

if not exist "target\classes" mkdir "target\classes"
del /s /q "target\classes\*.class" >nul 2>nul

echo [1/3] 编译源码...
> "target\sources.txt" (
    for /r "src\main\java" %%f in (*.java) do (
        set "SOURCE=%%f"
        set "REL=!SOURCE:%CD%\=!"
        echo "!REL:\=/!"
    )
)
"%JAVAC%" --release 21 -encoding UTF-8 -d "target\classes" "@target\sources.txt"
if errorlevel 1 (
    echo [错误] 编译失败。
    exit /b 1
)

echo [2/3] 打包 target\simos.jar ...
> "target\MANIFEST.MF" echo Manifest-Version: 1.0
>> "target\MANIFEST.MF" echo Main-Class: cn.edu.scau.os.app.SimOsApp
"%JAR%" --create --file "target\simos.jar" --manifest "target\MANIFEST.MF" -C "target\classes" .
if errorlevel 1 (
    echo [错误] 打包失败，请确认使用的是完整 JDK 21 或更高版本。
    exit /b 1
)

if /i "%~1"=="build" (
    echo 构建完成：target\simos.jar
    exit /b 0
)

if /i "%~1"=="selftest" (
    set "TICKS=%~2"
    if not defined TICKS set "TICKS=600"
    echo [3/3] 运行自检（!TICKS! 个时间单位）...
    "%JAVA%" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "target\classes" cn.edu.scau.os.app.SimOsApp --selftest !TICKS!
    if errorlevel 1 exit /b 1
    exit /b 0
)

if /i "%~1"=="test" (
    if not exist "target\test-classes" mkdir "target\test-classes"
    del /s /q "target\test-classes\*.class" >nul 2>nul
    > "target\test-sources.txt" (
        for /r "src\test\java" %%f in (*.java) do (
            set "SOURCE=%%f"
            set "REL=!SOURCE:%CD%\=!"
            echo "!REL:\=/!"
        )
    )
    "%JAVAC%" --release 21 -encoding UTF-8 -cp "target\classes" -d "target\test-classes" "@target\test-sources.txt"
    if errorlevel 1 exit /b 1
    "%JAVA%" -Dstdout.encoding=UTF-8 -Djava.awt.headless=true -cp "target\classes;target\test-classes" cn.edu.scau.os.RequirementsRegressionTest
    if errorlevel 1 exit /b 1
    "%JAVA%" -Dstdout.encoding=UTF-8 -Djava.awt.headless=true -cp "target\classes;target\test-classes" cn.edu.scau.os.ui.DirectoryTreeRegressionTest
    if errorlevel 1 exit /b 1
    exit /b 0
)

echo [3/3] 启动图形界面...
start "" "%JAVA%" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "target\classes" cn.edu.scau.os.app.SimOsApp
endlocal
exit /b 0

rem ============================================================
rem  子程序：探测一个 JDK 目录，合格（>=21）则设为当前 JDK
rem ============================================================
:probe_jdk
set "CAND=%~1"
if "%CAND%"=="" goto :eof
if not exist "%CAND%\bin\javac.exe" goto :eof
call :read_version "%CAND%\bin\javac.exe"
if not defined MAJOR goto :eof
if %MAJOR% LSS 21 goto :eof
set "JDK_HOME=%CAND%"
set "JAVAC=%CAND%\bin\javac.exe"
set "JAVA=%CAND%\bin\java.exe"
set "JAR=%CAND%\bin\jar.exe"
goto :eof

:probe_path
where javac >nul 2>nul || goto :eof
call :read_version "javac"
if not defined MAJOR goto :eof
if %MAJOR% LSS 21 goto :eof
set "JDK_HOME=(PATH)"
set "JAVAC=javac"
set "JAVA=java"
set "JAR=jar"
goto :eof

:read_version
set "VER="
set "MAJOR="
for /f "tokens=2" %%v in ('"%~1" -version 2^>^&1') do set "VER=%%v"
if not defined VER goto :eof
set "FOUND_VERSION=%VER%"
for /f "tokens=1 delims=." %%m in ("%VER%") do set "MAJOR=%%m"
if not defined MAJOR goto :eof
echo %MAJOR%| findstr /r "^[0-9][0-9]*$" >nul || set "MAJOR="
goto :eof
