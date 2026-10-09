#!/bin/sh
# Inicia el servidor RMI.
# Uso: ./server.sh [ip] [puerto]
#   ip      IP de ESTA computadora con la que los clientes llegan a ella (por omisión 127.0.0.1: solo este equipo).
#           Para aceptar clientes de otras computadoras, indica la IP de este equipo en la red (ip addr), por ejemplo 192.168.1.10.
#   puerto  Puerto del registro RMI y del servicio (por omisión 1099). Ábrelo en el cortafuegos.
# Requiere exactamente Java 25. El JDK se toma del archivo jdk.path (una línea con la ruta del JDK 25,
# opcional y local a cada computadora), o de JAVA_HOME, o del java del sistema, en ese orden.
# Base de datos: PostgreSQL en jdbc:postgresql://localhost:5432/laboratorio, usuario postgres, contraseña 2005.
# Se cambia con las variables de entorno DB_URL, DB_USER y DB_PASSWORD. Las tablas se crean al arrancar.
# La primera vez se crea el usuario «admin»; su contraseña es ADMIN_PASSWORD (por omisión admin123).

IP="${1:-127.0.0.1}"
PORT="${2:-1099}"

cd "$(dirname "$0")" || exit 1

# JDK: el de jdk.path si existe, si no JAVA_HOME, si no el java del sistema. Debe ser Java 25.
[ -f jdk.path ] && JAVA_HOME=$(head -n 1 jdk.path) && export JAVA_HOME
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"
VERSION=$("$JAVA" -version 2>&1 | sed -n '1s/^[^"]*"\([0-9]*\).*/\1/p')
if [ "$VERSION" != "25" ]; then
    echo "Se requiere Java 25 y se encontró: ${VERSION:-ninguno} ($JAVA)." >&2
    echo "Escribe la ruta de un JDK 25 en el archivo jdk.path o define JAVA_HOME." >&2
    exit 1
fi

./mvnw -q install -DskipTests || exit 1
exec ./mvnw -q -f server/pom.xml exec:java "-Dexec.args=$IP $PORT"
