# Reservas de laboratorio con Java RMI

Aplicación para reservar las 20 computadoras de un laboratorio desde varias computadoras al mismo tiempo. Un **servidor** guarda los datos en PostgreSQL y decide quién se queda con cada computadora; cada alumno usa un **cliente** con ventana gráfica que se actualiza en tiempo real.

- Cada computadora se reserva por horario y puede tener varias reservaciones que no se encimen.
- Cada usuario entra con contraseña. El usuario `admin` es el único que puede mandar computadoras a mantenimiento.
- Las reservaciones se liberan solas al terminar su horario.

## Requisitos

| Programa | Versión | Para qué |
|---|---|---|
| JDK (Java) | **25** exactamente | Compilar y ejecutar el servidor y el cliente. |
| Docker | Cualquiera reciente | Ejecutar PostgreSQL. Solo en la computadora del servidor. |
| Git | Cualquiera | Descargar el proyecto. |

No hace falta instalar Maven: el proyecto trae `mvnw` (Linux) y `mvnw.cmd` (Windows), que lo descargan solos la primera vez. Esa primera vez se necesita internet y tarda un poco más.

## Instalación

### 1. Descargar el proyecto

```sh
git clone https://github.com/m4jbz/laboratorio_javarmi.git
cd laboratorio_javarmi
```

### 2. Indicar dónde está el JDK 25

Revisa tu versión de Java:

```sh
java -version
```

Si dice 25, no tienes que hacer nada. Si dice otra versión, crea un archivo llamado `jdk.path` en la carpeta del proyecto con **una sola línea**: la ruta de tu JDK 25.

- Linux, por ejemplo: `/usr/lib/jvm/java-25-openjdk`
- Windows, por ejemplo: `C:\Program Files\Java\jdk-25`

Los scripts leen ese archivo solos. Cada computadora tiene el suyo y no se sube a git.

### 3. Levantar PostgreSQL (solo en la computadora del servidor)

Con Docker encendido (en Windows, abre Docker Desktop), ejecuta una sola vez:

```sh
docker run -d --name postgres18 -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=2005 -e POSTGRES_DB=postgres -p 5432:5432 postgres:18
```

Y crea la base de datos del proyecto:

```sh
docker exec -it postgres18 psql -U postgres -c "CREATE DATABASE laboratorio"
```

Las tablas no hay que crearlas: el servidor las crea al arrancar. Las siguientes veces solo enciende el contenedor:

```sh
docker start postgres18
```

## Uso

### Iniciar el servidor

| Linux | Windows |
|---|---|
| `./server.sh` | `server.bat` |

Si todo está bien verás `Servidor listo en 127.0.0.1:1099`. Para detenerlo, presiona `Ctrl+C`.

La primera vez se crea el usuario **`admin`** con la contraseña **`admin123`**. Para usar otra, define la variable `ADMIN_PASSWORD` antes del primer arranque.

### Iniciar el cliente

| Linux | Windows |
|---|---|
| `./client.sh` | `client.bat` |

Se abre el diálogo «Conectar al laboratorio». Escribe tu usuario y una contraseña de al menos 4 caracteres. **La primera vez que entras con un usuario, esa contraseña queda registrada**; las siguientes veces tendrás que usar la misma.

Puedes abrir varios clientes a la vez, en la misma computadora o en otras.

### Usar la ventana

- **Colores:** verde es disponible, rojo es reservado y gris es en mantenimiento. Cada tarjeta muestra quién la tiene reservada, de qué hora a qué hora, y su siguiente reservación («Luego»).
- **Seleccionar:** haz clic en una o varias tarjetas. Las seleccionadas se marcan con un marco azul y una «✓».
- **Reservar:** selecciona las computadoras, pulsa **Reservar seleccionadas…** y escribe la hora de entrada y de salida (formato `HH:mm`, por ejemplo `09:30`). Recibirás un comprobante por cada computadora.
- **Cancelar:** selecciona tus computadoras y pulsa **Cancelar mis reservaciones…**, o en la pestaña **Reservaciones** elige filas con `Ctrl+clic` y pulsa **Cancelar las reservaciones seleccionadas**. Solo puedes cancelar las tuyas.
- **Mantenimiento (solo `admin`):** el administrador ve los botones **Enviar a mantenimiento** y **Habilitar**. Solo puede haber un administrador conectado a la vez.
- **Bitácora:** la pestaña **Bitácora** muestra las últimas operaciones de todos los usuarios.

## Usar varias computadoras

1. En la computadora del servidor, averigua su IP en la red (Linux: `ip addr`, Windows: `ipconfig`), por ejemplo `192.168.1.10`.
2. Inicia el servidor con esa IP:

   | Linux | Windows |
   |---|---|
   | `./server.sh 192.168.1.10` | `server.bat 192.168.1.10` |

3. Permite el puerto **1099** en el cortafuegos de esa computadora.
4. En cada computadora de alumno, inicia el cliente con la IP del servidor:

   | Linux | Windows |
   |---|---|
   | `./client.sh 192.168.1.10` | `client.bat 192.168.1.10` |

5. Si Windows pregunta si permite que Java use la red, acepta al menos en **redes privadas**. El servidor necesita conectarse de vuelta a cada cliente para avisarle de los cambios.

## Configuración opcional

Variables de entorno que lee el servidor:

| Variable | Valor por omisión | Uso |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/laboratorio` | Dirección de la base de datos. |
| `DB_USER` | `postgres` | Usuario de PostgreSQL. |
| `DB_PASSWORD` | `2005` | Contraseña de PostgreSQL. |
| `ADMIN_PASSWORD` | `admin123` | Contraseña con la que se crea `admin` la primera vez. |

Para usar un puerto distinto de 1099, pásalo como segundo argumento al servidor y al cliente, por ejemplo `./server.sh 127.0.0.1 2099`.

## Pruebas

Con PostgreSQL encendido:

| Linux | Windows |
|---|---|
| `./mvnw verify` | `mvnw.cmd verify` |

Las pruebas usan una base aparte (`lab_test`) y no tocan `laboratorio`. Si tu `java` no es la versión 25, define antes `JAVA_HOME` con la ruta del JDK 25.

## Problemas comunes

| Mensaje | Solución |
|---|---|
| `Se requiere Java 25 y se encontró: …` | Crea el archivo `jdk.path` con la ruta de tu JDK 25 (ver Instalación). |
| `No se pudo conectar a la base de datos` | Enciende Docker y el contenedor: `docker start postgres18`. |
| `la base de datos indicada no existe` | Crea la base: `docker exec -it postgres18 psql -U postgres -c "CREATE DATABASE laboratorio"`. |
| `El puerto 1099 ya está en uso` | Ya hay otro servidor abierto. Ciérralo o usa otro puerto. |
| `No hay ningún servidor escuchando en esa dirección y puerto` | Revisa que el servidor esté encendido, la IP y el puerto, y el cortafuegos del servidor. |
| `Usuario o contraseña incorrectos` | Ese usuario ya existe con otra contraseña. Usa la correcta u otro usuario. |
| `El administrador ya tiene una sesión abierta` | Cierra la otra ventana del administrador. |
| Las ventanas no se actualizan con los cambios de otros | El cortafuegos del cliente bloquea a Java. Permítelo en redes privadas. |

