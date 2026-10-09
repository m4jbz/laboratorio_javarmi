#!/bin/sh
# Inicia el cliente JavaFX.
# Uso: ./client.sh [ip del servidor] [puerto]
#   ip del servidor  IP de la computadora donde corre el servidor (por omisión 127.0.0.1).
#   puerto           Puerto del servidor (por omisión 1099).
# Ambos valores solo son los iniciales del diálogo de conexión y ahí se pueden cambiar.
# El servidor envía los cambios de vuelta a este equipo, así que el cortafuegos debe permitir conexiones entrantes a java.
# Requiere exactamente Java 25. El JDK se toma del archivo jdk.path (una línea con la ruta del JDK 25,
# opcional y local a cada computadora), o de JAVA_HOME, o del java del sistema, en ese orden.

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
exec ./mvnw -q -f client/pom.xml javafx:run "-Djavafx.args=$IP $PORT"
