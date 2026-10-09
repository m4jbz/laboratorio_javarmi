@echo off
rem Inicia el cliente JavaFX.
rem Uso: client.bat [ip del servidor] [puerto]
rem   ip del servidor  IP de la computadora donde corre el servidor (por omisión 127.0.0.1).
rem   puerto           Puerto del servidor (por omisión 1099).
rem Ambos valores solo son los iniciales del diálogo de conexión y ahí se pueden cambiar.
rem El servidor envía los cambios de vuelta a este equipo: cuando el Firewall de Windows lo pregunte, permite java en redes privadas.
rem Requiere exactamente Java 25. El JDK se toma del archivo jdk.path (una linea con la ruta del JDK 25,
rem opcional y local a cada computadora), o de JAVA_HOME, o del java del sistema, en ese orden.

setlocal
set "IP=%~1"
if "%IP%"=="" set "IP=127.0.0.1"
set "PORT=%~2"
if "%PORT%"=="" set "PORT=1099"

cd /d "%~dp0"

rem JDK: el de jdk.path si existe, si no JAVA_HOME, si no el java del sistema. Debe ser Java 25.
if exist jdk.path set /p JAVA_HOME=<jdk.path
if defined JAVA_HOME (set "JAVA=%JAVA_HOME%\bin\java") else (set "JAVA=java")
set "MAJOR="
for /f "tokens=3" %%v in ('"%JAVA%" -version 2^>^&1') do if not defined MAJOR for /f "delims=." %%m in (%%v) do set "MAJOR=%%~m"
if not "%MAJOR%"=="25" (
    echo Se requiere Java 25 y se encontro: %MAJOR% ^(%JAVA%^).
    echo Escribe la ruta de un JDK 25 en el archivo jdk.path o define JAVA_HOME.
    exit /b 1
)
call mvnw.cmd -q install -DskipTests
if errorlevel 1 exit /b 1
call mvnw.cmd -q -f client\pom.xml javafx:run "-Djavafx.args=%IP% %PORT%"
