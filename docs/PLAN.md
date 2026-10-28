# Plan de Migración: Python/RakNet → Kotlin/Android

> Documento vivo. Última actualización: 2026-10-26.

## Objetivo

Proxy transparente de Minecraft Bedrock (**RakNet + NetherNet**) empaquetado como APK Android:

- ARM64 (`arm64-v8a`) como objetivo principal; también `armeabi-v7a`, `x86_64`, `x86`.
- Android 6.0 (API 23) → Android 15/17.
- Una **pantalla principal de configuración** y otra de **registro**, accesibles
  desde un menú lateral de tres líneas (hamburguesa); sin consola ni Termux.

## Decisión permanente: RakNet no se elimina

**El soporte RakNet se mantiene indefinidamente.** NetherNet es una vía
**adicional**, no un reemplazo:

- La app debe seguir funcionando con clientes y servidores Bedrock antiguos
  (anteriores a 26.50), que solo hablan RakNet.
- La app debe seguir pudiendo conectarse a remotos que ofrezcan RakNet, aunque
  el cliente local use NetherNet (puente NetherNet → RakNet).
- No se eliminarán el listener `19132`, `RakNetPeer`, `raknet_jni.cpp` ni
  RakNet 4.081 del proyecto, salvo decisión explícita del usuario.
- Si en el futuro se añade un puente NetherNet ↔ NetherNet para remotos
  NetherNet-only, se añadirá **junto a** RakNet, no en su lugar.
- La UI y los logs deben dejar claro qué vía se está usando para cada cliente.

Esto da un rango de compatibilidad amplio: un mismo APK sirve para clientes
antiguos y nuevos, y para remotos RakNet, mixtos (`transport: both`) o
NetherNet-only cuando se implemente esa vía.

## Estado actual

| Fase | Estado |
|------|--------|
| Estructura Gradle + version catalog | ✅ Hecho |
| RakNet 4.081 vendorizado + CMake | ✅ Hecho y **validado en Linux** (handshake real) |
| JNI bridge (`raknet_jni.cpp`) | ✅ Hecho |
| Capa Kotlin (`RakNetPeer`, `BedrockProxy`) | ✅ Hecho |
| UI Compose + ViewModel + DataStore | ✅ Hecho |
| Tema oscuro + logger en vivo | ✅ Hecho (UI) |
| Descubrimiento LAN (multicast + pong remoto) | ✅ Hecho — **S24 Ultra + Xbox Series X OK**; Redmi 13C + Switch 2 pendiente |
| Puente NetherNet (UDP 7551 + WebRTC ↔ RakNet) | ✅ Implementado en 1.5.0 — pendiente de probar con Switch 2 |
| Soporte RakNet permanente | ✅ Se mantiene para clientes/remotos antiguos; NetherNet es adicional |
| Selector de interfaz LAN | ✅ Hecho (automática o interfaz concreta; se guarda en DataStore) |
| Foreground Service + notificación | ✅ Hecho |
| Compilación con NDK/Android SDK | ✅ Hecho — `assembleDebug` OK (4 ABIs, 2026-10-25) |
| Pruebas en dispositivo Android | 🟡 S24 Ultra + Xbox OK; Redmi 13C + Switch 2 pendiente |

---

## Arquitectura

```
┌──────────────────────────────────────────────────────────┐
│ MainActivity (Compose)                                   │
│   └─ ProxyViewModel ── DataStore (config persistente)    │
│        │ start/stop intents                              │
│        ▼                                                 │
│ ProxyForegroundService (notificación + WakeLock)         │
│        ▼                                                 │
│ BedrockProxy (Kotlin, hilos)                             │
│   ├─ RakPeer "listener"  :19132  ← clientes MCPE         │
│   ├─ RakPeer "cliente"   → servidor remoto (1 por cliente)│
│   └─ NetherNetServer     :7551   ← clientes 26.50+       │
│        ├─ descubrimiento UDP 7551 (ServerData v7)        │
│        └─ WebRTC PeerConnection ↔ RakNet remoto          │
│        ▼                                                 │
│ RakNetPeer.kt (JNI)  →  libraknet_jni.so                 │
│        ▼                                                 │
│ RakNet 4.081 (C++, vendorizado en cpp/RakNet/Source)     │
└──────────────────────────────────────────────────────────┘
```

### Flujo de un cliente NetherNet

1. El cliente 26.50+ difunde una petición por **UDP 7551**.
2. `NetherNetServer` responde con `ServerData v7` (mundo, versión/protocolo,
   jugadores, nonce y `connection_type=4`).
3. El cliente envía `CONNECTREQUEST` con una oferta SDP.
4. El teléfono crea un `PeerConnection` WebRTC, responde con
   `CONNECTRESPONSE` (answer + `a=identity` ES384) y envía sus candidatos ICE
   con `CANDIDATEADD`.
5. El cliente abre `ReliableDataChannel` / `UnreliableDataChannel`.
6. Los mensajes del canal fiable (`[segmentos][payload]`) se reensamblan y se
   reenvían por un `RakPeer` nuevo al servidor remoto.
7. Los paquetes del remoto se dividen en segmentos de 9999 bytes y se envían
   por el canal fiable.

### Flujo de un cliente

1. El cliente MCPE envía un *unconnected ping* → RakNet responde con el MOTD configurado (`SetOfflinePingResponse`).
2. El cliente completa el handshake RakNet con el listener → llega `ID_NEW_INCOMING_CONNECTION (0x13)`.
3. Se crea un `RakPeer` nuevo y se conecta al servidor remoto (puerto efímero).
4. Al recibir `ID_CONNECTION_REQUEST_ACCEPTED (0x10)`, el puente queda activo.
5. Hilo listener: paquetes del cliente → servidor remoto.
6. Hilo por cliente: paquetes del servidor → cliente.
7. `ID_DISCONNECTION_NOTIFICATION` / `ID_CONNECTION_LOST` cierran y liberan ambos lados.

---

## Especificación UI

```
┌─────────────────────────────────────┐
│ ● Bedrock Proxy                     │  ← barra superior con estado
│   En ejecución · puerto 19132       │
├─────────────────────────────────────┤
│  SERVIDOR REMOTO                    │
│  ┌─────────────────┬─────────────┐  │
│  │ IP remota       │ Puerto      │  │
│  └─────────────────┴─────────────┘  │
│  SERVIDOR LOCAL (PROXY)             │
│  ┌────────────────────────────────┐ │
│  │ Puerto local                   │ │
│  └────────────────────────────────┘ │
│  ┌────────────────────────────────┐ │
│  │            INICIAR             │ │
│  └────────────────────────────────┘ │
│  Estado / clientes / IP local       │
├─────────────────────────────────────┤
│ REGISTRO (312)      Copiar  Limpiar │
│ 12:03:44 I Proxy: Nuevo cliente…    │
│ 12:03:44 D RakNet: Connect iniciado │
│ 12:03:45 I Proxy: Puente listo…     │
│ …                                   │
└─────────────────────────────────────┘
```

- El botón alterna `INICIAR` (primario) ↔ `DETENER` (rojo).
- Mientras se inicia/detiene: spinner + campos deshabilitados.
- Al estar activo muestra nº de clientes y la IP local donde conectarse.
- La configuración es la **pantalla principal**. El **registro** vive en una
  pestaña aparte, accesible desde el menú lateral (hamburguesa) o deslizando
  desde el borde izquierdo. Así el log ya no resta altura a los campos y
  botones en pantallas pequeñas.
- Tema **siempre oscuro**: fondo casi negro azulado, acentos esmeralda/cian,
  tipografía monoespaciada en el registro.

---

## Decisiones técnicas

### ¿Por qué hay C++ si la app es Kotlin/Java?
El APK es una app Android normal (Kotlin + Compose). El C++ es **solo el motor
RakNet**, empaquetado como `libraknet_jni.so` dentro del APK, igual que muchas
apps Android incluyen librerías nativas. No hay consola, ni Termux, ni comandos.

Motivo: no existe una implementación RakNet en Java que funcione en **Android 6
(API 23)**. La opción Java madura (CloudburstMC/Netty) exige API 26+ y añade
~3 MB de Netty. RakNet 4.081 en C++ compila para ARM64 y corre desde API 23.

- **Kotlin/Java:** UI, ViewModel, servicio, notificación, configuración, lógica
  del proxy, hilos, validación, persistencia (~1.500 líneas).
- **C++:** protocolo RakNet (handshake, ACK/NAK, fragmentación, fiabilidad) y el
  puente JNI (~400 líneas propias + 3.5 MB de RakNet vendorizado).

Decisión confirmada con el usuario: se mantiene el motor C++ actual.

- **RakNet vendorizado** (no dependencia Maven): funciona en ARM sin depender de
  librerías Java que usan `java.lang.invoke` (no disponible en API < 26).
- **Overrides de RakNet**: `NativeFeatureIncludesOverrides.h` desactiva plugins
  (Cloud, Autopatcher, RPC4, FileTransfer, Replica, NAT, etc.). Solo se compilan
  41 archivos `.cpp` del núcleo.
- **Hilos, no coroutines**, en la capa de red: `receive()` es bloqueante-por-sondeo
  y necesita un hilo dedicado por peer; evita agotar `Dispatchers.IO`.
- **`SystemAddress` = data class(host, port)**: evita errores de orden de bytes
  entre JNI y Kotlin.
- **Foreground Service tipo `connectedDevice`**: `dataSync` tiene límite de 6 h en
  Android 15; `connectedDevice` encaja (proxy de red) y no expira.
- **WakeLock parcial + MulticastLock**: mantiene el proxy vivo con pantalla apagada
  y permite responder al descubrimiento LAN.
- **minSdk 23**, `targetSdk 34`, NDK 26.1.

---

## Estructura real del proyecto

```
minecraft-server-connector/
├── bedrock_proxy.py                 # Implementación Python de referencia
├── docs/PLAN.md
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── cpp/
│       │   ├── CMakeLists.txt
│       │   ├── RakNet/Source/       # RakNet 4.081 (264 archivos)
│       │   └── jni/raknet_jni.cpp
│       ├── java/com/bedrockproxy/
│       │   ├── core/                # BedrockProxy, ProxyConfig, ProxyState
│       │   ├── native/              # RakNetPeer, Packet, SystemAddress
│       │   ├── service/             # ProxyForegroundService
│       │   ├── ui/                  # MainActivity, ProxyViewModel, ConfigScreen, LogPanel
│       │   ├── ui/theme/            # Paleta oscura + colores del registro
│       │   └── util/                # AppLog, ConfigStore, NetworkUtils
│       └── res/                     # strings, themes, colors, iconos
├── gradle/libs.versions.toml
├── gradle/wrapper/                  # Gradle 8.10
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

---

## Tareas

### Fase 0 — Preparación ✅
- [x] Estructura Gradle Kotlin DSL
- [x] `minSdk 23`, `targetSdk 34`, NDK/CMake
- [x] Version catalog (`gradle/libs.versions.toml`)
- [x] Gradle wrapper 8.7

### Fase 1 — RakNet nativo ✅
- [x] Vendorizar RakNet 4.081 en `cpp/RakNet/Source`
- [x] `NativeFeatureIncludesOverrides.h` con plugins desactivados
- [x] `CMakeLists.txt` con los 41 `.cpp` del núcleo
- [x] `jni/raknet_jni.cpp` (startup, connect, send, receive, ping, offline ping)
- [x] **Validación en Linux**: compilación de los 41 archivos + prueba de
      handshake cliente↔servidor y envío de payload (OK)

### Fase 2 — Proxy Kotlin ✅
- [x] `RakNetPeer.kt` (wrapper JNI)
- [x] `Packet.kt`, `SystemAddress.kt`
- [x] `BedrockProxy.kt` (listener + un puente por cliente, hilos dedicados)
- [x] Cierre y limpieza de recursos en desconexión
- [x] Límite de conexiones concurrentes

### Fase 3 — UI ✅
- [x] `MainActivity` + `ProxyViewModel`
- [x] `MainScreen` con menú lateral y `ConfigContent` (pantalla principal)
- [x] Validación de host/puertos
- [x] Persistencia con DataStore
- [x] Estado en vivo (clientes, IP local, errores)
- [x] Botón Iniciar/Detener con spinner
- [x] Tema oscuro fijo (paleta esmeralda/cian)
- [x] Registro en pestaña propia (`LogPanel`): auto-scroll, copiar, limpiar
- [x] `LogPanel` a pantalla completa, sin restar espacio a la configuración
- [x] Logger global `AppLog` conectado a UI, servicio, proxy y JNI

### Fase 4 — Servicio ✅
- [x] `ProxyForegroundService` con notificación y acción Detener
- [x] Tipo `connectedDevice` + permisos Android 14
- [x] WakeLock + MulticastLock
- [x] Permiso `POST_NOTIFICATIONS` en Android 13+

### Fase 5 — Compilación y pruebas ⬜
- [x] Instalar SDK + NDK sin Android Studio (SDK manual en `/opt/android-sdk`)
- [x] `./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL**
- [x] Corregir errores de compilación (catálogo TOML, licencias, `register` de C++17)
- [x] Verificar APK: 4 ABIs, `libraknet_jni.so` + `libc++_shared.so`, minSdk 23 / targetSdk 34
- [ ] Instalar APK en dispositivo ARM64
- [ ] Probar 1 cliente, luego 2+ simultáneos
- [ ] Verificar MOTD en la lista de servidores
- [ ] Probar con pantalla apagada > 10 min
- [ ] Probar Android 6/7 (API 23/25) si hay dispositivo

### Entorno de compilación verificado (2026-10-25)

| Componente | Versión / ruta |
|------------|----------------|
| JDK | 21 (`/opt/javac/x64/21`) |
| Gradle | 8.10 (wrapper, ya cacheado) |
| AGP / Kotlin | 8.5.0 / 1.9.24 |
| SDK | `/opt/android-sdk` (platform 34, build-tools 34.0.0, platform-tools, CMake, NDK) |
| CMake / Ninja | 3.22.1 (paquete SDK) |
| NDK | 26.1.10909125 (r26b) |

`app/src/main/cpp/CMakeLists.txt` usa **C++14**, no C++17: RakNet 4.081 usa
`register` en `Rand.cpp`, que es error en C++17. El JNI solo necesita C++14
(`std::make_unique`). No subir el estándar sin parchear RakNet.

---

## Notas de implementación RakNet

### Versión de protocolo
`RakNet/Source/RakNetVersion.h` está parcheado:

```c
#define RAKNET_PROTOCOL_VERSION 11   // Minecraft Bedrock (1.16.220+)
```

RakNet 4.081 trae `6` por defecto; con `6` el cliente MCPE responde
`ID_INCOMPATIBLE_PROTOCOL_VERSION` y no conecta. Los formatos de los paquetes
offline (`OPEN_CONNECTION_REQUEST_1/2`, `REPLY_1/2`) son idénticos a los que usa
RakLib (PocketMine), por lo que solo cambia el número de versión.

> ⚠️ Pendiente de validar con un cliente Minecraft real.

### Descubrimiento LAN (pestaña Amigos)

Minecraft Bedrock descubre mundos LAN enviando `ID_UNCONNECTED_PING` al grupo
multicast `224.0.2.60:19132`. No basta con escuchar en el puerto: el socket
debe unirse al grupo (`IP_ADD_MEMBERSHIP`) y la app debe mantener un
`MulticastLock` de Wi-Fi.

Implementación:

- `nativeJoinLanDiscovery` (JNI) pide los sockets a `RakPeer::GetSockets()` y
  hace `setsockopt(IP_ADD_MEMBERSHIP)` sobre el socket IPv4 del listener.
  Acepta una lista de IPv4 separadas por comas y une el grupo en **cada** una;
  los sockets/FD se cachean y `EADDRINUSE` se trata como éxito (join repetido).
- El join se refresca cada 5 s desde el hilo de ping. Así se detectan
  interfaces que aparecen **después** de arrancar (por ejemplo, activar el
  hotspot con la proxy ya iniciada).
- **Selector de interfaz LAN** en la UI (sección superior): `Automática
  (todas)` o una interfaz concreta. Se guarda el **nombre** de la interfaz
  (p. ej. `ap0`, `wlan0`) en DataStore y se resuelve su IPv4 al arrancar, porque
  la IP puede cambiar por DHCP. Si la interfaz elegida no está disponible, el
  proxy arranca igualmente y reintenta el join cuando aparece.
- La lista de interfaces se refresca al abrir el desplegable, al volver a la
  Activity y al iniciar; muestra `Hotspot`, `Wi-Fi`, `Ethernet`, `VPN`,
  `Datos móviles` junto al nombre y la IP.
- `ServerPing` consulta cada 5 s el pong del servidor remoto (timeout 2,5 s)
  y `ProxyConfig.buildOfflinePingResponse(info)` construye el pong LAN con
  protocolo, versión, jugadores, MOTD y serverId reales, pero anunciando el
  puerto **local**. Así el cliente no marca el mundo como incompatible.
- El pong debe ser `uint16 BE (longitud) + "MCPE;…"`: RakNet escribe el buffer
  tal cual y el cliente lee primero la longitud. Sin esos 2 bytes el mundo no
  aparece (confirmado contra RakLib/Cloudburst y un servidor real).
- `ProxyForegroundService` mantiene el `MulticastLock` desde antes de arrancar
  el proxy.
- El puerto local debe ser `19132` (el del descubrimiento); con otro puerto
  solo funciona la conexión manual por IP.

**Diagnóstico del fallo en hotspot (Redmi 13C):** el log mostraba
`No hay IPv4 Wi-Fi` e `iface ANY`. El join estaba cayendo en la ruta por
defecto (datos móviles/Tailscale, `100.x`) en vez de la interfaz del hotspot
(`ap0`/`swlan0`, `172.31.x` o `10.x`). Por eso el Switch no veía el mundo.
Ahora se unen todas las IPv4 locales por defecto y, si hace falta, el usuario
puede fijar explícitamente la interfaz del hotspot en el selector.

**Validado en dispositivo:** Samsung S24 Ultra + Xbox Series X (Wi-Fi) — el
mundo aparece y se puede jugar. Redmi 13C + Switch 2 en hotspot móvil: **sigue
sin detectarse** probando `Automática` y la interfaz del hotspot. El selector
solo elige dónde unirse al multicast; no puede hacer que un hotspot que no
reenvía multicast lo reenvíe.

**Diagnóstico 1.3.0 (log del Redmi 13C):** el autodiagnóstico local daba
`Unicast OK` y `Multicast OK` en `ap0` (`10.x.x.x`), y el join multicast
aparecía correcto. Sin embargo, **no se registró ningún `Ping LAN recibido` del
Switch** (ni con interfaz automática ni con `ap0`). Eso descarta el listener y
el join local: el paquete del Switch no está llegando al teléfono o el Switch no
lo está enviando. El S24 + Xbox funcionando en una red equivalente apunta al AP
del Redmi, al comportamiento del Switch, o a ambos.

**1.4.0:**
- Se elimina el botón **Probar LAN** y `LanSelfTest`; el diagnóstico queda solo
  en el registro.
- Canal alternativo **broadcast**: cada 5 s se envía el pong MCPE a la
  dirección de broadcast de cada interfaz LAN (`192.168.x.255:19132`), además
  del multicast. Es experimental: solo ayuda si el cliente escucha en ese
  puerto; el registro indica `Broadcast LAN: ap0→… (1/1 ok)`.
- Más depuración:
  - interfaces con prefijo, broadcast y soporte multicast;
  - ruta por defecto (`/proc/net/route`) y vecinos ARP (`/proc/net/arp`);
  - `Ping LAN recibido` con IP, puerto y GUID;
  - `Pong LAN enviado` con IP y puerto;
  - cada minuto, clientes registrados y ARP (para ver la IP del Switch).
- `SO_BROADCAST` se activa en el socket del listener.

> ⚠️ Algunos hotspots Android no reenvían multicast ni broadcast a sus propios
> clientes. Si el log muestra `Broadcast LAN` correcto y el join multicast, pero
> no aparece ningún `Ping LAN recibido` del Switch, la limitación es del AP:
> usar una red Wi-Fi, un router de viaje o USB tethering. Si el ping llega, el
> siguiente paso es comparar versión/protocolo del cliente y del remoto.

**1.4.1 (2026-10-26) — investigación de NetherNet:**

La búsqueda web confirma un cambio de protocolo que explica que el Switch no
envíe nada a 19132:

- Minecraft Bedrock **26.50** introdujo **NetherNet**; **26.60** (27/10/2026)
  retira RakNet. El servidor dedicado 26.52 ya usa NetherNet por defecto.
- NetherNet descubre mundos LAN por **UDP 7551**, no por
  `224.0.2.60:19132`. El cliente difunde una petición y el servidor responde
  con `ServerData` versión 7; después la misma conexión transporta las señales
  WebRTC.
- Por eso el `Broadcast LAN` de 1.4.0 en 19132 es irrelevante para un cliente
  26.50+/26.60. El log de 1.4.0 no mostraba pings del Switch: compatible con
  que el Switch ya esté usando 7551 o con que el AP aísle clientes.
- 1.4.1 añade `NetherNetDiscovery.kt`: socket UDP 7551, descifra
  HMAC-SHA256 + AES-256-ECB con la clave constante `SHA-256(LE64(0xdeadbeef))`
  y registra peticiones, respuestas y señales. **Solo diagnóstico**; no
  responde ni implementa WebRTC.
- Si el log muestra `Solicitud NetherNet de …`, el siguiente paso es un puente
  NetherNet/WebRTC ↔ RakNet o el modo Amigos por Xbox Live. Si no muestra nada
  y tampoco hay ping en 19132, el problema es el AP/aislamiento o el cliente no
  está escaneando LAN.

**1.5.0 (2026-10-26) — puente NetherNet ↔ RakNet:**

- `NetherNetServer.kt` sustituye al sniffer. Responde al descubrimiento con
  `ServerData v7` y mantiene la misma clave HMAC/AES que usa el juego.
- WebRTC con `io.github.webrtc-sdk:android:125.6422.07` (4 ABIs). El APK pasa a
  ~33 MB.
- Se acepta la oferta SDP del cliente, se crea la respuesta y se envían los
  candidatos ICE. Los candidatos se filtran a la interfaz LAN y se **reescriben
  a la IP del hotspot/Wi-Fi** si WebRTC los reunió en datos móviles, Tailscale o
  `0.0.0.0`; `enableIceGatheringOnAnyAddressPorts` permite que el socket
  comodín reciba en `ap0`.
- El canal fiable se reensambla con el contador de segmentos y se reenvía a un
  `RakPeer` nuevo contra el remoto. NetherNet manda el **batch de Bedrock sin
  el frame ID `0xFE`**; el puente lo añade al ir al remoto y lo quita al
  volver. El puente es de transporte: no toca Login, cifrado ni compresión
  Bedrock. La clave Bedrock sigue siendo extremo a extremo entre cliente y
  remoto, así que el remoto debe seguir hablando RakNet.
- La respuesta SDP lleva `a=identity` ES384 (EC P-384 persistente, JWT
  autocontenido, JWS separado sobre los fingerprints DTLS) porque algunos
  clientes rechazan respuestas sin identidad con `CONNECTERROR 37`.
- El contador de clientes de la UI suma RakNet + NetherNet.
- La interfaz LAN seleccionada ahora se ignora para descubrimiento si es
  datos móviles/VPN (`ccmni*`, `tun*`) y se usa la LAN automática; esto corrige
  el caso en que el log de 1.4.1 mostraba `ccmni2` y no `ap0`.
- Limitación: el remoto debe seguir hablando **RakNet**. Un remoto
  NetherNet-only necesita otro puente o `transport: both` en Geyser.

Fuentes:
- <https://minecraft.wiki/w/NetherNet>
- <https://minecraft.wiki/w/Bedrock_Edition_26.52>
- <https://minecraft.wiki/w/Bedrock_Edition_26.60>
- <https://github.com/bedrock-v/nethernet>
- <https://kjetilv.com/minecraftlanproxy/>
- <https://geysermc.org/blog/preparing-for-nethernet/>
- <https://mojira.dev/MCPE-232507>

**Matiz sobre Switch:** hay reportes de que el cliente de Switch no muestra
una sección LAN estable en algunas versiones, mientras que otros usuarios
juegan Switch↔Switch en red local. La app debe basarse en el log: si el Switch
emite peticiones 7551/19132, el descubrimiento existe; si no emite nada, hay
que revisar la versión, la opción "Visible to LAN players", Nintendo Switch
Online y el aislamiento del AP.

### Thread safety
`RakPeer::Send()` y `RakPeer::Receive()` **no** son seguros entre hilos. El JNI
serializa todas las operaciones de cada peer con un `std::mutex` por handle.
Para no bloquear, `Receive()` nunca se queda esperando (sondeo con `Thread.sleep(1)`).

### Paquetes en cola
Mientras el puente al servidor remoto se está estableciendo (handshake ~0.3-1 s),
los paquetes del cliente se guardan (máx. 256) y se reenvían al completarse.

### Registro en vivo (logger)
`util/AppLog.kt` es un singleton con un buffer circular de 500 entradas expuesto
como `StateFlow`. Cada entrada lleva hora, nivel (`D/I/W/E`), etiqueta y mensaje.

- La UI (`LogPanel`) se suscribe al `StateFlow` y se actualiza en tiempo real.
- El servicio, el proxy y la capa JNI escriben en el mismo logger.
- El JNI llama a `AppLog.nativeLog(...)` mediante una referencia global a la
  clase y `AttachCurrentThread` cuando el hilo nativo no está ligado a la JVM.
- Todo se sigue copiando a logcat (`adb logcat -s BedrockProxy RakNetJNI`).
- El registro no crece sin límite: al superar 500 líneas se descartan las más
  antiguas.

### Rendimiento
`RELIABLE_ORDERED` en el canal 0 es lo que usa Minecraft; el proxy no reinterpreta
los paquetes, solo los reenvía, así que el cifrado y la compresión del juego se
mantienen intactos.


### Fase 6 — Distribución ⬜
- [ ] Icono definitivo / splash
- [ ] Firma release propia
- [ ] `assembleRelease` y publicar APK
- [ ] README de uso final

---

## Riesgos conocidos

| Riesgo | Mitigación |
|--------|------------|
| NDK distinto al esperado | `ndkVersion = 26.1.10909125` en `app/build.gradle.kts` |
| RakNet no compila en una ABI | Probar `arm64-v8a` primero; el código ya compiló en Linux x86_64 |
| Android 14+ restringe FGS | Tipo `connectedDevice` + permiso `FOREGROUND_SERVICE_CONNECTED_DEVICE` |
| Batería | WakeLock solo mientras el proxy está activo |
| Mismo puerto que otro servidor local | Error mostrado en la UI; el usuario cambia el puerto |
| Cliente MCPE con versión distinta al servidor | El proxy es transparente: el servidor decide |

---

## Comandos

```bash
# Compilar nativo + APK debug
./gradlew :app:assembleDebug

# Instalar en dispositivo conectado
./gradlew :app:installDebug

# APK release
./gradlew :app:assembleRelease

# Logs (además del panel dentro de la app)
adb logcat -s BedrockProxy RakNetJNI
```

---

## Próximos pasos inmediatos

1. Instalar **1.5.0** (`/home/majhrs16/BedrockProxy-1.5.0.apk`) y probar primero
   en **Wi-Fi normal** (no hotspot), con el Switch y el teléfono en la misma red
   y sin aislamiento de clientes. El APK creció a ~33 MB por WebRTC.
2. Verificar que el servidor remoto responde en **RakNet** (`IP:19132`). El log
   no debe mostrar `Sin ping de …` antes de probar. Este puente no conecta a un
   remoto NetherNet-only.
3. Abrir Minecraft 26.50+ → **Amigos/Worlds** y esperar 10–20 s. En el registro
   debería aparecer:
   - `NetherNet 7551 escuchando`
   - `Solicitud NetherNet de …`
   - `Oferta NetherNet …`
   - `Answer NetherNet enviado … (identidad=true)`
   - `DataChannel … ReliableDataChannel`
   - `Candidato ICE …`
   - `ICE/… estado=CONNECTED`
   - `Puente NetherNet listo: … ↔ …`
4. Si aparece el mundo y al entrar se queda en “Conectando”:
   - `CONNECTERROR 37` → la identidad no fue aceptada; revisar el log
     `Answer NetherNet … identidad=`.
   - `ICE FAILED` / sin candidatos → revisar que el cliente y el teléfono estén
     en la misma subred y que el AP no aísle clientes.
   - `Puente NetherNet listo` pero sin Login → el remoto no habla RakNet o
     rechaza la versión/protocolo. Comparar con `26.52` / `2193`.
5. Probar 1 cliente, luego 2+ simultáneos, y después reconexión/cierre.
6. Si el Switch no emite ni 7551 ni 19132, el problema es el AP/aislamiento o
   la versión del cliente; usar una Wi-Fi normal, router de viaje o USB
   tethering.
7. Para 26.60 (27/10/2026): **no retirar RakNet**. El remoto puede actualizarse
   y, si deja de ofrecer RakNet, configurar Geyser con `transport: both` (TCP de
   signaling + UDP de WebRTC) o añadir el puente NetherNet ↔ NetherNet **sin
   quitar** el camino RakNet. La app debe seguir sirviendo a clientes antiguos y
   a remotos RakNet en paralelo.
