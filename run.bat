@echo off
chcp 65001 >nul
rem ============================================================
rem  SimOS 一键运行脚本（Windows）
rem  用法：
rem    run.bat              编译并启动图形界面
rem    run.bat selftest     编译并运行无界面自检
rem    run.bat build        只编译打包，不运行
rem    run.bat test         运行要求回归测试和目录树回归测试
rem ============================================================
setlocal EnableDelayedExpansion
cd /d "%~dp0"

rem 优先使用项目约定的 JDK 21；找不到则回退到 PATH 上的 java
set "JAVA_HOME_CANDIDATE=%USERPROFILE%\.jdks\ms-21.0.11"
if not defined JAVA_HOME (
    if exist "%JAVA_HOME_CANDIDATE%\bin\javac.exe" (
        set "JAVA_HOME=%JAVA_HOME_CANDIDATE%"
    )
)

set "JAVAC=javac"
set "JAVA=java"
set "JAR=jar"
if defined JAVA_HOME (
    set "JAVAC=%JAVA_HOME%\bin\javac.exe"
    set "JAVA=%JAVA_HOME%\bin\java.exe"
    set "JAR=%JAVA_HOME%\bin\jar.exe"
)

if not exist "src\main\java" (
    echo [错误] 请在 SimOS 项目根目录运行本脚本。
    exit /b 1
)

if not exist "target\classes" mkdir "target\classes"
del /s /q "target\classes\*.class" >nul 2>nul

echo [1/3] 编译源码...
> "target\sources.txt" (
    for /r "src\main\java" %%f in (*.java) do (
        set "SOURCE=%%f"
        echo "!SOURCE:\=/!"
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
    echo [错误] 打包失败，请确认使用完整的 JDK 21 或更高版本。
    exit /b 1
)

if /i "%1"=="build" (
    echo 构建完成：target\simos.jar
    exit /b 0
)

if /i "%1"=="selftest" (
    echo [3/3] 运行自检...
    "%JAVA%" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "target\classes" cn.edu.scau.os.app.SimOsApp --selftest 600
    if errorlevel 1 exit /b 1
    exit /b 0
)

if /i "%1"=="test" (
    if not exist "target\test-classes" mkdir "target\test-classes"
    > "target\test-sources.txt" (
        for /r "src\test\java" %%f in (*.java) do (
            set "SOURCE=%%f"
            echo "!SOURCE:\=/!"
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
