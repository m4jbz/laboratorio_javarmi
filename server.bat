@echo off
rem Inicia el servidor RMI.
rem Uso: server.bat [ip] [puerto]
rem   ip      IP de ESTA computadora con la que los clientes llegan a ella (por omisión 127.0.0.1: solo este equipo).
rem           Para aceptar clientes de otras computadoras, indica la IP de este equipo en la red (ipconfig), por ejemplo 192.168.1.10.
rem   puerto  Puerto del registro RMI y del servicio (por omisión 1099). Permítelo en el Firewall de Windows.
rem Requiere exactamente Java 25. El JDK se toma del archivo jdk.path (una linea con la ruta del JDK 25,
rem opcional y local a cada computadora), o de JAVA_HOME, o del java del sistema, en ese orden.
rem Base de datos: PostgreSQL en jdbc:postgresql://localhost:5432/laboratorio, usuario postgres, contraseña 2005.
rem Se cambia con las variables de entorno DB_URL, DB_USER y DB_PASSWORD. Las tablas se crean al arrancar.
rem La primera vez se crea el usuario «admin»; su contraseña es ADMIN_PASSWORD (por omisión admin123).

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
call mvnw.cmd -q -f server\pom.xml exec:java "-Dexec.args=%IP% %PORT%"
