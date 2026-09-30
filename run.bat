@echo off
rem ============================================================
rem  SimOS 一键运行脚本（Windows）
rem  用法：
rem    run.bat              编译并启动图形界面
rem    run.bat selftest     编译并运行无界面自检
rem    run.bat build        只编译打包，不运行
rem ============================================================
setlocal
cd /d "%~dp0"

rem 优先使用项目约定的 JDK 21；找不到则回退到 PATH 上的 java
set "JAVA_HOME_CANDIDATE=%USERPROFILE%\.jdks\ms-21.0.11"
if exist "%JAVA_HOME_CANDIDATE%\bin\javac.exe" (
    set "JAVA_HOME=%JAVA_HOME_CANDIDATE%"
) else (
    echo [警告] 未找到 %JAVA_HOME_CANDIDATE%，改用 PATH 上的 javac/java
)

set "JAVAC=javac"
set "JAVA=java"
if defined JAVA_HOME (
    set "JAVAC=%JAVA_HOME%\bin\javac.exe"
    set "JAVA=%JAVA_HOME%\bin\java.exe"
)

if not exist "src\main\java" (
    echo [错误] 请在 SimOS 项目根目录运行本脚本。
    exit /b 1
)

if not exist "target\classes" mkdir "target\classes"

echo [1/3] 编译源码...
dir /s /b "src\main\java\*.java" > "%TEMP%\simos-sources.txt"
"%JAVAC%" -encoding UTF-8 -d "target\classes" "@%TEMP%\simos-sources.txt"
if errorlevel 1 (
    echo [错误] 编译失败。
    exit /b 1
)

echo [2/3] 打包 target\simos.jar ...
> "target\MANIFEST.MF" echo Manifest-Version: 1.0
>> "target\MANIFEST.MF" echo Main-Class: cn.edu.scau.os.app.SimOsApp
"%JAVA_HOME%\bin\jar.exe" --create --file "target\simos.jar" --manifest "target\MANIFEST.MF" -C "target\classes" . 2>nul
if errorlevel 1 (
    echo [提示] jar 命令不可用，将直接用 class 目录运行。
)

if /i "%1"=="build" (
    echo 构建完成：target\simos.jar
    exit /b 0
)

if /i "%1"=="selftest" (
    echo [3/3] 运行自检...
    "%JAVA%" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "target\classes" cn.edu.scau.os.app.SimOsApp --selftest 600
    exit /b %errorlevel%
)

echo [3/3] 启动图形界面...
start "" "%JAVA%" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "target\classes" cn.edu.scau.os.app.SimOsApp
endlocal
