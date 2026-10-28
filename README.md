# Bedrock Proxy (Android)

Proxy transparente de **Minecraft Bedrock** para Android. Se instala como APK y se
controla con un único botón: no requiere Termux, ni consola, ni permisos de root.

```
Cliente Minecraft  ──▶  [ Teléfono Android: Proxy ]  ──▶  Servidor remoto
                        escucha en :19132                (IP:puerto)
```

## Características

- Soporta **varias conexiones simultáneas** (cada cliente obtiene su propio puente).
- Responde al **descubrimiento LAN** de Minecraft con el MOTD configurado.
- Anuncia el mundo por **multicast `224.0.2.60`** y, como alternativa, por
  **broadcast de la subred** (`192.168.x.255:19132`) para hotspots que filtran
  multicast.
- **Puente NetherNet (1.5.0)**: anuncia el mundo por **UDP 7551**, negocia
  WebRTC con clientes Minecraft 26.50+ y reenvía los paquetes Bedrock al
  servidor RakNet remoto. El mundo aparece en la lista LAN del cliente sin
  depender de `224.0.2.60:19132`.
- **Selector de interfaz LAN**: automática (todas) o la interfaz concreta
  (Wi-Fi, hotspot, Ethernet, VPN), con su IP visible. Imprescindible cuando el
  teléfono comparte datos por hotspot.
- Funciona con la pantalla apagada (servicio en primer plano + WakeLock).
- Compatible con **Android 6.0 (API 23) en adelante**, incluido Android 15/17.
- ABIs: `arm64-v8a` (principal), `armeabi-v7a`, `x86_64`, `x86`.
- **Registro en vivo** en su propia pestaña (menú lateral de tres líneas):
  muestra cada paso de la app y del motor RakNet (niveles D/I/W/E, botones
  Copiar y Limpiar). La configuración queda como pantalla principal y con más
  espacio en móviles pequeños.
- Interfaz **siempre en tema oscuro** con paleta esmeralda/cian.

## Uso

1. Instala el APK en el teléfono.
2. Abre **Bedrock Proxy** y acepta el permiso de notificaciones (Android 13+).
3. Rellena:
   - **IP remota** y **Puerto** del servidor al que quieres conectarte.
   - **Puerto local**: el puerto donde escuchará el proxy (por defecto `19132`).
   - **Interfaz LAN**: `Automática (todas)` o la interfaz concreta. Si usas el
     hotspot del teléfono, activa el hotspot **antes** de abrir la app y elige
     la entrada `Hotspot (ap0/swlan0) · 10.x.x.x/24` o `192.168.x.x`
     (pulsa **Actualizar** si no aparece). La app guarda el nombre de la
     interfaz y resuelve su IP al arrancar.
4. Pulsa **Iniciar**.
5. En Minecraft, abre la pestaña **Amigos**: el proxy aparece como un mundo
   LAN con el MOTD configurado (misma red Wi-Fi/hotspot y puerto local `19132`).
   También puedes añadirlo a mano con la **IP local** que muestra la app
   (por ejemplo `192.168.1.50:19132`). **Switch no tiene "Añadir servidor"**:
   depende del descubrimiento LAN.
   - Si el cliente es **Minecraft 26.50 o superior**, la app responde por
     **UDP 7551** y crea el puente WebRTC. En el registro verás
     `Solicitud NetherNet`, `Oferta NetherNet`, `Answer NetherNet`,
     `Canal fiable abierto` y `Puente NetherNet listo`. Espera 10–20 s tras
     abrir **Amigos**; si el mundo no aparece, reinicia Minecraft para que
     vuelva a escanear la LAN.
6. Pulsa **Detener** (o la acción de la notificación) para cerrar el proxy.
7. Usa el **menú de tres líneas** (arriba a la izquierda) para cambiar entre
   **Configuración** (principal) y **Registro** (log a pantalla completa).
   También puedes deslizar desde el borde izquierdo.

> El descubrimiento LAN usa el multicast `224.0.2.60:19132`; por eso el puerto
> local debe ser `19132`. La app se une al grupo en todas las IPv4 locales por
> defecto; si la red tiene aislamiento de clientes o una VPN se queda con la
> ruta por defecto, elige la interfaz correcta en **Interfaz LAN**.

> En modo hotspot, algunos Android no reenvían multicast a sus propios
> clientes. La app también envía el pong por broadcast de la subred cada 5 s.
> Si aun así el cliente no aparece, revisa **Registro**:
> - `Interfaces: ... bcast=192.168.x.255 ...`: la app calculó bien el broadcast.
> - `Broadcast LAN: ap0→192.168.x.255:19132 (1/1 ok)`: el envío salió.
> - `Ping LAN recibido de <IP>:<puerto>`: el ping del cliente llegó al proxy.
>   Si no aparece ninguno, el AP no está entregando el tráfico del cliente al
>   teléfono; usa un router de viaje, USB tethering o una Wi-Fi compartida.

> Si un dispositivo no ve la IP local, usa la IP que aparece en
> *Ajustes → Wi-Fi → Red conectada*.

### NetherNet: el cambio de Minecraft 26.50+

Minecraft Bedrock 26.50 introdujo **NetherNet** y 26.60 (previsto para el
27/10/2026) retira RakNet del cliente. **La app conserva RakNet de forma
permanente**: NetherNet es una vía adicional para clientes nuevos, no un
reemplazo. Los clientes antiguos y los remotos RakNet siguen funcionando igual.
NetherNet no usa `224.0.2.60:19132` para el descubrimiento LAN: el cliente
difunde por **UDP 7551** y espera una tarjeta de mundo (ServerData versión 7).
Por eso el `Broadcast LAN` de 1.4.0 en 19132 no puede hacer aparecer un mundo en
un cliente que ya solo use NetherNet.

La **1.5.0** implementa el lado servidor de NetherNet:

1. Responde a la petición de descubrimiento con una tarjeta `ServerData v7`
   (nombre, versión/protocolo del remoto, jugadores, nonce y tipo LAN).
2. Negocia un `PeerConnection` WebRTC con el cliente y recibe los canales
   `ReliableDataChannel` / `UnreliableDataChannel`.
3. Reensambla los mensajes (`[segmentos][payload]`) y los envía por RakNet al
   servidor remoto. El cliente NetherNet manda el **batch de Bedrock sin el
   frame ID `0xFE`**; el puente lo añade al salir y lo quita al volver, porque
   RakNet sí espera ese byte. En sentido contrario, divide los paquetes del
   remoto en segmentos de 9 999 bytes y los escribe en el canal fiable.
4. Firma la respuesta SDP con una identidad **ES384** (clave EC P-384
   persistente, token autocontenido y JWS sobre los fingerprints DTLS). Sin
   `a=identity`, algunos clientes responden `CONNECTERROR 37`.
5. Reescribe los candidatos ICE a la IP del hotspot/Wi-Fi y los envía con
   `CANDIDATEADD`; `enableIceGatheringOnAnyAddressPorts` permite que el socket
   comodín reciba en `ap0` aunque Android no lo exponga como red WebRTC.

El puente es **de transporte**: no descifra el Login ni los paquetes Bedrock.
La clave de sesión Bedrock sigue siendo extremo a extremo entre el cliente y el
remoto; el proxy solo reenvía los bytes. Por eso el remoto debe seguir hablando
**RakNet** en `IP:puerto`. Si el remoto ya es NetherNet-only (sin RakNet), esta
versión todavía no puede conectarse a él; hace falta un puente NetherNet ↔
NetherNet o cambiar el remoto a `transport: both` en Geyser.

> El APK crece a ~33 MB porque incluye WebRTC (`libjingle_peerconnection_so`)
> para las cuatro ABIs. Es normal.

En el registro, el flujo correcto es:

- `Solicitud NetherNet de …` → el cliente usa 7551.
- `Oferta NetherNet …` → llegó el SDP del cliente.
- `Answer NetherNet enviado …` → el teléfono respondió.
- `DataChannel … ReliableDataChannel` → SCTP/DTLS establecidos.
- `Puente NetherNet listo: … ↔ …` → el RakNet remoto aceptó.
- `Sin ping de …` → el remoto no responde; el mundo puede aparecer, pero al
  entrar fallará. Revisa Tailscale/firewall/servidor.

Si solo aparece `Ping LAN recibido` en 19132, el cliente sigue usando RakNet.
Si no aparece ninguno de los dos, el AP no entrega broadcast al teléfono o el
cliente no está escaneando LAN.

Fuentes: [NetherNet (Minecraft Wiki)](https://minecraft.wiki/w/NetherNet),
[Bedrock 26.60](https://minecraft.wiki/w/Bedrock_Edition_26.60),
[nethernet (bedrock-v)](https://github.com/bedrock-v/nethernet),
[Geyser y NetherNet](https://geysermc.org/blog/preparing-for-nethernet/).

## Compilar

Requisitos:

- JDK 17 o 21 y Android SDK con **platform 34**, **build-tools 34.0.0**,
  **CMake 3.22.1** y **NDK 26.1.10909125**. Con Android Studio basta con
  instalarlos desde el SDK Manager; también funciona un SDK manual indicando
  `sdk.dir` en `local.properties`.
- Gradle 8.10 (el wrapper ya apunta a esa versión).
- Conexión a internet la primera vez (Gradle descarga dependencias).

```bash
# APK de depuración
./gradlew :app:assembleDebug

# Instalar en el dispositivo conectado por USB
./gradlew :app:installDebug

# APK de release (firmado con la clave de debug por ahora)
./gradlew :app:assembleRelease
```

Si compilas sin Android Studio, el SDK queda estandarizado así:

```text
/opt/android-sdk/
├── cmdline-tools/latest/    # sdkmanager, avdmanager y android CLI
├── platforms/android-34/
├── build-tools/34.0.0/
├── cmake/3.22.1/            # incluye ninja
├── ndk/26.1.10909125/
├── platform-tools/
└── licenses/android-sdk-license
```

`local.properties` debe apuntar a ese SDK:

```properties
sdk.dir=/opt/android-sdk
```

Y el JDK usado es `/opt/javac/x64/21`; si no está en el entorno:

```bash
JAVA_HOME=/opt/javac/x64/21 ./gradlew :app:assembleDebug
```

> El código nativo se compila en **C++14**: RakNet 4.081 usa `register`
> (`Rand.cpp`), que es error en C++17. No subas el estándar sin parchear RakNet.

El APK queda en la ruta estándar de Gradle:

- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`

No se copia a `$HOME` ni a ninguna ruta externa al proyecto.

### Compilar solo la parte nativa

```bash
./gradlew :app:externalNativeBuildDebug
```

## Estructura

| Ruta | Contenido |
|------|-----------|
| `app/src/main/cpp/RakNet/Source` | RakNet 4.081 vendorizado (solo núcleo) |
| `app/src/main/cpp/jni/raknet_jni.cpp` | Puente JNI |
| `app/src/main/java/com/bedrockproxy/core` | Lógica del proxy |
| `app/src/main/java/com/bedrockproxy/native` | Wrapper Kotlin de RakNet |
| `app/src/main/java/com/bedrockproxy/ui` | Interfaz Compose (incluye `LogPanel`) |
| `app/src/main/java/com/bedrockproxy/ui/theme` | Paleta oscura |
| `app/src/main/java/com/bedrockproxy/util/AppLog.kt` | Logger global (UI + JNI) |
| `app/src/main/java/com/bedrockproxy/service` | Servicio en primer plano |
| `docs/PLAN.md` | Plan, decisiones y estado del proyecto |
| `bedrock_proxy.py` | Implementación Python original (referencia) |

## Solución de problemas

| Síntoma | Causa / solución |
|---------|------------------|
| "No se pudo abrir el puerto 19132" | Otro proceso/servidor usa ese puerto. Cambia el puerto local. |
| El cliente no ve el servidor en la lista LAN | Misma red Wi-Fi/hotspot; revisa que el router no aísle clientes (AP isolation). En hotspot elige la interfaz `Hotspot` en **Interfaz LAN**. |
| El log dice `Interfaz LAN: ap0 (10.x.x.x/24), broadcast 10.x.x.255` pero el cliente no ve el mundo | Revisa las líneas `Broadcast LAN` y `Ping LAN recibido`. Si el broadcast sale pero no llega ningún ping del cliente, el AP no reenvía el tráfico; prueba router de viaje, USB tethering o Wi-Fi compartida. |
| `Solicitud NetherNet de …` y luego `Oferta NetherNet`, pero no `Puente NetherNet listo` | El cliente usa 26.50+ y la negociación WebRTC empezó, pero ICE/DTLS o el RakNet remoto fallaron. Revisa `Oferta SDP`, `Answer NetherNet`, `Candidato ICE`, `ICE/… estado`, `DataChannel …` y `Sin ping de …`. |
| `Answer NetherNet` con `identidad=false` o `CONNECTERROR 37` | No se pudo firmar la respuesta SDP. Revisa que la app tenga espacio para guardar `nethernet_identity.*` y que el SDP del cliente traiga `a=fingerprint`. |
| `Oferta NetherNet` pero ningún `Candidato ICE` y `ICE … FAILED` | El AP no entrega UDP entre cliente y teléfono, o los candidatos quedaron en una interfaz equivocada. Usa `Automática (todas)` o la interfaz Wi-Fi/hotspot correcta y desactiva AP isolation. |
| `Puente NetherNet listo` pero Minecraft se queda en “Conectando” | El remoto no habla RakNet, la versión/protocolo no coincide o el servidor rechaza el Login. Comprueba que el remoto sea 26.52 y que RakNet siga abierto. |
| No aparece ni `Ping LAN recibido` en 19132 ni `Solicitud NetherNet` en 7551 | El AP no entrega multicast/broadcast al teléfono o el cliente no está escaneando LAN. Prueba Wi-Fi normal (sin aislamiento), router de viaje o USB tethering. |
| `Broadcast LAN` sale y `Ping LAN recibido` aparece, pero el Switch no muestra el mundo | El ping llega y el proxy responde (`Pong LAN enviado`). Compara la versión/protocolo del Switch con el remoto (`Anuncio LAN`). |
| El Switch no aparece solo en hotspot del Redmi, pero sí en Wi-Fi o con otro móvil | Limitación del AP del Redmi (multicast/broadcast/aislamiento). Usa una red Wi-Fi, un router de viaje o USB tethering. |
| Conecta pero se queda en "Localizando servidor" | El servidor remoto no acepta la conexión o la IP/puerto es incorrecto. |
| Se detiene al bloquear el teléfono | Desactiva la optimización de batería para la app en Ajustes. |
| Android 12+ no inicia el servicio | Inicia siempre desde la app (no desde segundo plano). |

> Ante cualquier fallo, revisa primero **Registro** en el menú lateral: indica
> el paso exacto (puerto, conexión al servidor, handshake, errores de RakNet).
> También puedes usar `adb logcat -s BedrockProxy RakNetJNI`.

## Licencia / créditos

- RakNet 4.081 — Oculus VR, licencia BSD (ver `app/src/main/cpp/RakNet/LICENSE`).
- RakNet está parcheado a **protocolo 11** (el que usa Minecraft Bedrock moderno).
- El resto del proyecto se distribuye bajo **GNU GPL v3**; consulta `LICENSE`.
