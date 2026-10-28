#include <jni.h>
#include <android/log.h>
#include <arpa/inet.h>
#include <cctype>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <netinet/in.h>
#include <set>
#include <string>
#include <sys/socket.h>
#include <vector>

#include "RakPeerInterface.h"
#include "MessageIdentifiers.h"
#include "RakNetTypes.h"
#include "RakNetSocket2.h"
#include "BitStream.h"
#include "GetTime.h"
#include "PluginInterface2.h"

#define LOG_TAG "RakNetJNI"

namespace {

/* ---------------------------------------------------------------- logging UI
 * Además de logcat, los mensajes nativos llegan al panel de registro de la app
 * llamando a AppLog.nativeLog(int, String, String) en el lado Kotlin.
 */
JavaVM *g_vm = nullptr;
jclass g_logClass = nullptr;
jmethodID g_logMethod = nullptr;

void initLogging(JNIEnv *env) {
    if (env == nullptr) return;
    if (g_vm == nullptr) env->GetJavaVM(&g_vm);
    if (g_logClass != nullptr) return;

    jclass local = env->FindClass("com/bedrockproxy/util/AppLog");
    if (local == nullptr) {
        env->ExceptionClear();
        return;
    }
    g_logClass = static_cast<jclass>(env->NewGlobalRef(local));
    g_logMethod = env->GetStaticMethodID(
            local, "nativeLog", "(ILjava/lang/String;Ljava/lang/String;)V");
    if (g_logMethod == nullptr) env->ExceptionClear();
    env->DeleteLocalRef(local);
}

void emitLog(int priority, const char *message) {
    if (message == nullptr || g_vm == nullptr || g_logClass == nullptr || g_logMethod == nullptr) {
        return;
    }
    JNIEnv *env = nullptr;
    bool attached = false;
    jint status = g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    } else if (status != JNI_OK) {
        return;
    }
    if (env == nullptr) {
        if (attached) g_vm->DetachCurrentThread();
        return;
    }

    jstring jtag = env->NewStringUTF(LOG_TAG);
    jstring jmsg = env->NewStringUTF(message);
    if (jtag != nullptr && jmsg != nullptr) {
        env->CallStaticVoidMethod(g_logClass, g_logMethod, priority, jtag, jmsg);
    }
    if (jtag != nullptr) env->DeleteLocalRef(jtag);
    if (jmsg != nullptr) env->DeleteLocalRef(jmsg);
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (attached) g_vm->DetachCurrentThread();
}

}  // namespace

#define LOGD(...) do { char _b[512]; snprintf(_b, sizeof(_b), __VA_ARGS__); \
    __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "%s", _b); \
    emitLog(ANDROID_LOG_DEBUG, _b); } while (0)
#define LOGI(...) do { char _b[512]; snprintf(_b, sizeof(_b), __VA_ARGS__); \
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s", _b); \
    emitLog(ANDROID_LOG_INFO, _b); } while (0)
#define LOGW(...) do { char _b[512]; snprintf(_b, sizeof(_b), __VA_ARGS__); \
    __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "%s", _b); \
    emitLog(ANDROID_LOG_WARN, _b); } while (0)
#define LOGE(...) do { char _b[512]; snprintf(_b, sizeof(_b), __VA_ARGS__); \
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s", _b); \
    emitLog(ANDROID_LOG_ERROR, _b); } while (0)

namespace {

/**
 * RakNet no es thread-safe: Send() y Receive() sobre el mismo peer no pueden
 * ejecutarse a la vez. Cada peer tiene su propio mutex que serializa todas las
 * llamadas nativas.
 */
struct PeerHandle {
    RakNet::RakPeerInterface *peer = nullptr;
    std::mutex mutex;

    /** Sockets del peer (se cachean: no cambian tras Startup). */
    std::vector<int> lanSocketFds;
    /** Interfaces donde ya se unió el multicast (para no repetir el log). */
    std::set<std::string> lanJoined;
    /** Interfaces donde falló (para no repetir el warning cada 5 s). */
    std::set<std::string> lanFailed;
    /** Destinos de broadcast ya registrados (para no repetir el log). */
    std::set<std::string> broadcastLogged;
    /** Plugin de diagnóstico para ver pings/pongs LAN en el registro. */
    std::unique_ptr<class LanDiscoveryLogger> lanLogger;
};

/**
 * Registra en el panel de la app los pings de descubrimiento que llegan al
 * listener y los pongs que RakNet responde. Es la diferencia entre:
 *   - "el hotspot no entrega el multicast" (no aparece ningún ping), y
 *   - "el cliente lo recibe pero lo rechaza" (ping recibido + pong enviado).
 */
class LanDiscoveryLogger : public RakNet::PluginInterface2 {
public:
    // Necesario para que RakNet llame a OnDirectSocketReceive/Send.
    bool UsesReliabilityLayer() const override { return true; }

    void OnDirectSocketReceive(const char *data, const RakNet::BitSize_t bitsUsed,
                               RakNet::SystemAddress remote) override {
        if (data == nullptr || bitsUsed == 0) return;
        const unsigned int length = static_cast<unsigned int>(BITS_TO_BYTES(bitsUsed));
        const unsigned char id = static_cast<unsigned char>(data[0]);
        if (id == ID_UNCONNECTED_PING || id == ID_UNCONNECTED_PING_OPEN_CONNECTIONS) {
            char guidHex[17] = "?";
            if (length >= 25 + 8) {
                for (int i = 0; i < 8; ++i) {
                    std::snprintf(guidHex + i * 2, 3, "%02x",
                                  static_cast<unsigned char>(data[25 + i]));
                }
            }
            LOGI("Ping LAN recibido de %s (%u bytes, guid %s)",
                 remote.ToString(true, ':'), length, guidHex);
        } else if (id == ID_OPEN_CONNECTION_REQUEST_1 ||
                   id == ID_OPEN_CONNECTION_REQUEST_2 ||
                   id == ID_CONNECTION_REQUEST) {
            LOGI("Paquete LAN 0x%02x de %s (%u bytes)",
                 static_cast<unsigned int>(id),
                 remote.ToString(true, ':'), length);
        }
    }

    void OnDirectSocketSend(const char *data, const RakNet::BitSize_t bitsUsed,
                            RakNet::SystemAddress remote) override {
        if (data == nullptr || bitsUsed == 0) return;
        const unsigned char id = static_cast<unsigned char>(data[0]);
        const unsigned int length = static_cast<unsigned int>(BITS_TO_BYTES(bitsUsed));
        if (id == ID_UNCONNECTED_PONG) {
            LOGI("Pong LAN enviado a %s (%u bytes)",
                 remote.ToString(true, ':'), length);
        } else if (id == ID_OPEN_CONNECTION_REPLY_1 ||
                   id == ID_OPEN_CONNECTION_REPLY_2) {
            LOGI("Handshake LAN respuesta 0x%02x a %s (%u bytes)",
                 static_cast<unsigned int>(id),
                 remote.ToString(true, ':'), length);
        }
    }
};

inline PeerHandle *H(jlong handle) { return reinterpret_cast<PeerHandle *>(handle); }

std::string JStr(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

bool ToSystemAddress(const std::string &host, jint port, RakNet::SystemAddress &out) {
    if (host.empty() || port <= 0 || port > 65535) return false;
    if (!out.SetBinaryAddress(host.c_str())) return false;
    out.SetPortHostOrder(static_cast<unsigned short>(port));
    return true;
}

std::unique_ptr<PeerHandle> createHandle() {
    auto h = std::make_unique<PeerHandle>();
    h->peer = RakNet::RakPeerInterface::GetInstance();
    if (!h->peer) return nullptr;
    return h;
}

/** Cachea los FDs IPv4 del peer; GetSockets() puede bloquear, se hace una vez. */
void collectSockets(PeerHandle *h) {
    if (!h->lanSocketFds.empty()) return;
    DataStructures::List<RakNet::RakNetSocket2 *> sockets;
    h->peer->GetSockets(sockets);
    for (unsigned int i = 0; i < sockets.Size(); ++i) {
        auto *berkley = dynamic_cast<RakNet::RNS2_Berkley *>(sockets[i]);
        if (berkley == nullptr) continue;
        const int fd = berkley->GetSocket();
        if (fd >= 0) h->lanSocketFds.push_back(fd);
    }
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_bedrockproxy_native_RakNetPeer_nativeCreate(JNIEnv *env, jclass) {
    initLogging(env);
    auto h = createHandle();
    if (!h) return 0;
    return reinterpret_cast<jlong>(h.release());
}

/* Alias defensivo: variante generada por @JvmStatic en un companion object. */
JNIEXPORT jlong JNICALL
Java_com_bedrockproxy_native_RakNetPeer_00024Companion_nativeCreate(JNIEnv *env, jobject) {
    initLogging(env);
    auto h = createHandle();
    if (!h) return 0;
    return reinterpret_cast<jlong>(h.release());
}

JNIEXPORT void JNICALL
Java_com_bedrockproxy_native_RakNetPeer_nativeDestroy(JNIEnv *, jclass, jlong handle) {
    auto *h = H(handle);
    if (!h) return;

    {
        std::lock_guard<std::mutex> lock(h->mutex);
        if (h->peer) {
            h->peer->Shutdown(300);
            if (h->lanLogger) {
                h->peer->DetachPlugin(h->lanLogger.get());
                h->lanLogger.reset();
            }
            RakNet::RakPeerInterface::DestroyInstance(h->peer);
            h->peer = nullptr;
        }
    }
    delete h;
    LOGD("Peer liberado");
}

JNIEXPORT jboolean JNICALL
Java_com_bedrockproxy_native_RakNetPeer_nativeStartup(JNIEnv *env, jclass, jlong handle,
                                                      jint port, jint maxConnections,
                                                      jbyteArray offlinePing) {
    auto *h = H(handle);
    if (!h) return JNI_FALSE;

    std::lock_guard<std::mutex> lock(h->mutex);
    if (!h->peer) return JNI_FALSE;

    RakNet::SocketDescriptor sd(static_cast<unsigned short>(port), nullptr);
    sd.socketFamily = AF_INET;

    const unsigned short maxIncoming =
            static_cast<unsigned short>(maxConnections > 0 ? maxConnections : 1);

    // El listener (el que lleva la respuesta offline) recibe los pings LAN.
    // AttachPlugin antes de Startup: con UsesReliabilityLayer()=true RakNet
    // no permite attach/detach con el peer activo.
    if (offlinePing != nullptr && !h->lanLogger) {
        h->lanLogger = std::make_unique<LanDiscoveryLogger>();
        h->peer->AttachPlugin(h->lanLogger.get());
    }

    RakNet::StartupResult result = h->peer->Startup(maxIncoming, &sd, 1);
    if (result != RakNet::RAKNET_STARTED) {
        LOGE("Startup falló en el puerto %d (código %d)", port, static_cast<int>(result));
        return JNI_FALSE;
    }

    h->peer->SetMaximumIncomingConnections(maxIncoming);

    if (offlinePing) {
        jsize len = env->GetArrayLength(offlinePing);
        if (len > 0) {
            std::vector<jbyte> buf(static_cast<size_t>(len));
            env->GetByteArrayRegion(offlinePing, 0, len, buf.data());
            h->peer->SetOfflinePingResponse(
                    reinterpret_cast<const char *>(buf.data()), static_cast<unsigned>(len));
        }

        // Preparar el socket del listener para el canal alternativo broadcast.
        collectSockets(h);
        int yes = 1;
        for (int fd : h->lanSocketFds) {
            setsockopt(fd, SOL_SOCKET, SO_BROADCAST, &yes, sizeof(yes));
        }
        LOGD("Broadcast LAN preparado en %u socket(s)",
             static_cast<unsigned int>(h->lanSocketFds.size()));
    }

    LOGI("RakPeer iniciado en puerto %d (max %d, bound %s)", port, maxIncoming,
         h->peer->GetMyBoundAddress().ToString(true, ':'));
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_bedrockproxy_native_RakNetPeer_nativeShutdown(JNIEnv *, jclass, jlong handle,
                                                       jdouble timeoutSec) {
    auto *h = H(handle);
    if (!h) return;

    std::lock_guard<std::mutex> lock(h->mutex);
    if (h->peer) {
        h->peer->Shutdown(static_cast<unsigned int>(timeoutSec * 1000.0));
        LOGD("Peer apagado (shutdown)");
    }
}

JNIEXPORT jboolean JNICALL
Java_com_bedrockproxy_native_RakNetPeer_nativeConnect(JNIEnv *env, jclass, jlong handle,
                                                      jstring host, jint port) {
    auto *h = H(handle);
    if (!h) return JNI_FALSE;

    std::string hostStr = JStr(env, host);
    if (hostStr.empty() || port <= 0 || port > 65535) return JNI_FALSE;

    std::lock_guard<std::mutex> lock(h->mutex);
    if (!h->peer) return JNI_FALSE;

    RakNet::ConnectionAttemptResult res =
            h->peer->Connect(hostStr.c_str(), static_cast<unsigned short>(port), nullptr, 0);

    if (res != RakNet::CONNECTION_ATTEMPT_STARTED) {
        LOGE("Connect a %s:%d falló (código %d)", hostStr.c_str(), port, static_cast<int>(res));
        return JNI_FALSE;
    }
    LOGI("Connect a %s:%d iniciado", hostStr.c_str(), port);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_bedrockproxy_native_RakNetPeer_nativeCloseConnection(JNIEnv *env, jclass, jlong handle,
                                                              jstring host, jint port,
                                                              jboolean sendNotification) {
    auto *h = H(handle);
    if (!h) return;

    RakNet::SystemAddress addr;
    if (!ToSystemAddress(JStr(env, host), port, addr)) return;

    std::lock_guard<std::mutex> lock(h->mutex);
    if (!h->peer) return;
    h->peer->CloseConnection(addr, sendNotification == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_com_bedrockproxy_native_RakNetPeer_nativeSend(JNIEnv *env, jclass, jlong handle,
                                                   jbyteArray data, jint priority, jint reliability,
                                                   jint orderingChannel, jstring host, jint port) {
    auto *h = H(handle);
    if (!h || !data) return JNI_FALSE;

    RakNet::SystemAddress addr;
    if (!ToSystemAddress(JStr(env, host), port, addr)) return JNI_FALSE;

    jsize len = env->GetArrayLength(data);
    if (len <= 0) return JNI_FALSE;

    std::vector<jbyte> buf(static_cast<size_t>(len));
    env->GetByteArrayRegion(data, 0, len, buf.data());

    std::lock_guard<std::mutex> lock(h->mutex);
    if (!h->peer) return JNI_FALSE;

    uint32_t result = h->peer->Send(
            reinterpret_cast<const char *>(buf.data()), len,
            static_cast<PacketPriority>(priority),
            static_cast<PacketReliability>(reliability),
            static_cast<char>(orderingChannel), addr, false);

    if (result == 0) {
        LOGW("Send de %d bytes falló (puerto destino %d)", static_cast<int>(len), port);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT jobject JNICALL
Java_com_bedrockproxy_native_RakNetPeer_nativeReceive(JNIEnv *env, jclass, jlong handle) {
    auto *h = H(handle);
    if (!h) return nullptr;

    RakNet::Packet *packet = nullptr;
    std::string host;
    jint port = 0;
    jlong ts = 0;

    {
        std::lock_guard<std::mutex> lock(h->mutex);
        if (!h->peer) return nullptr;

        packet = h->peer->Receive();
        if (!packet) return nullptr;

        host = packet->systemAddress.ToString(false);
        port = static_cast<jint>(packet->systemAddress.GetPort());
        ts = static_cast<jlong>(RakNet::GetTimeMS());

        jclass packetClass = env->FindClass("com/bedrockproxy/native/Packet");
        if (!packetClass) {
            h->peer->DeallocatePacket(packet);
            return nullptr;
        }

        jmethodID ctor = env->GetMethodID(packetClass, "<init>", "([BLjava/lang/String;IJ)V");
        if (!ctor) {
            h->peer->DeallocatePacket(packet);
            return nullptr;
        }

        jbyteArray dataArray = env->NewByteArray(static_cast<jsize>(packet->length));
        env->SetByteArrayRegion(dataArray, 0, static_cast<jsize>(packet->length),
                                reinterpret_cast<const jbyte *>(packet->data));

        jstring hostStr = env->NewStringUTF(host.c_str());

        jobject obj = env->NewObject(packetClass, ctor, dataArray, hostStr, port, ts);

        env->DeleteLocalRef(hostStr);
        env->DeleteLocalRef(dataArray);

        // El objeto Java ya tiene su propia copia de los datos.
        h->peer->DeallocatePacket(packet);
        return obj;
    }
}

JNIEXPORT void JNICALL
Java_com_bedrockproxy_native_RakNetPeer_nativeDeallocatePacket(JNIEnv *, jclass, jlong) {
    // Sin operación: los paquetes se liberan dentro de nativeReceive.
}

JNIEXPORT jint JNICALL
Java_com_bedrockproxy_native_RakNetPeer_nativeGetBoundPort(JNIEnv *, jclass, jlong handle) {
    auto *h = H(handle);
    if (!h) return 0;

    std::lock_guard<std::mutex> lock(h->mutex);
    if (!h->peer) return 0;
    return static_cast<jint>(h->peer->GetMyBoundAddress().GetPort());
}

JNIEXPORT jint JNICALL
Java_com_bedrockproxy_native_RakNetPeer_nativeGetConnectionCount(JNIEnv *, jclass, jlong handle) {
    auto *h = H(handle);
    if (!h) return 0;

    std::lock_guard<std::mutex> lock(h->mutex);
    if (!h->peer) return 0;
    return static_cast<jint>(h->peer->NumberOfConnections());
}

JNIEXPORT jboolean JNICALL
Java_com_bedrockproxy_native_RakNetPeer_nativeIsActive(JNIEnv *, jclass, jlong handle) {
    auto *h = H(handle);
    if (!h) return JNI_FALSE;

    std::lock_guard<std::mutex> lock(h->mutex);
    if (!h->peer) return JNI_FALSE;
    return h->peer->IsActive() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_bedrockproxy_native_RakNetPeer_nativeSetOfflinePingResponse(JNIEnv *env, jclass,
                                                                     jlong handle,
                                                                     jbyteArray data) {
    auto *h = H(handle);
    if (!h) return;

    std::lock_guard<std::mutex> lock(h->mutex);
    if (!h->peer) return;

    if (!data) {
        h->peer->SetOfflinePingResponse(nullptr, 0);
        return;
    }

    jsize len = env->GetArrayLength(data);
    if (len > 0) {
        std::vector<jbyte> buf(static_cast<size_t>(len));
        env->GetByteArrayRegion(data, 0, len, buf.data());
        h->peer->SetOfflinePingResponse(
                reinterpret_cast<const char *>(buf.data()), static_cast<unsigned>(len));
    }
}

/**
 * Une el socket IPv4 del peer al grupo multicast que usa Minecraft Bedrock
 * para el descubrimiento LAN (224.0.2.60:19132).
 *
 * Sin esto el cliente envía su ping de descubrimiento al grupo, pero el socket
 * de RakNet (bound a 0.0.0.0:19132) no lo recibe y el mundo nunca aparece en
 * la pestaña de amigos/LAN.
 *
 * @param ifaceIps IPv4 locales separadas por comas (Wi-Fi, hotspot, Ethernet…).
 *                 Se intentan todas; si está vacío se usa la interfaz por
 *                 defecto (INADDR_ANY).
 */
JNIEXPORT jboolean JNICALL
Java_com_bedrockproxy_native_RakNetPeer_nativeJoinLanDiscovery(JNIEnv *env, jclass,
                                                               jlong handle, jstring ifaceIps) {
    auto *h = H(handle);
    if (!h) return JNI_FALSE;

    std::lock_guard<std::mutex> lock(h->mutex);
    if (!h->peer) return JNI_FALSE;

    // Los sockets no cambian tras Startup: se cachean para no llamar a
    // GetSockets() en cada refresco (bloquea hasta ~1 s).
    collectSockets(h);
    if (h->lanSocketFds.empty()) {
        LOGE("Descubrimiento LAN: el peer no expone ningún socket");
        return JNI_FALSE;
    }

    std::vector<std::string> ips;
    const std::string raw = JStr(env, ifaceIps);
    size_t start = 0;
    while (start <= raw.size()) {
        const size_t comma = raw.find(',', start);
        std::string part = raw.substr(
                start, comma == std::string::npos ? std::string::npos : comma - start);
        while (!part.empty() && std::isspace(static_cast<unsigned char>(part.front()))) {
            part.erase(part.begin());
        }
        while (!part.empty() && std::isspace(static_cast<unsigned char>(part.back()))) {
            part.pop_back();
        }
        if (!part.empty()) {
            in_addr parsed{};
            if (inet_pton(AF_INET, part.c_str(), &parsed) == 1) ips.push_back(part);
        }
        if (comma == std::string::npos) break;
        start = comma + 1;
    }

    // Sin IPs: unirse en la interfaz por defecto.
    if (ips.empty()) ips.emplace_back();

    int ok = 0;
    int attempted = 0;
    for (int fd : h->lanSocketFds) {
        for (const std::string &ip : ips) {
            ip_mreq mreq{};
            mreq.imr_multiaddr.s_addr = inet_addr("224.0.2.60");
            mreq.imr_interface.s_addr = htonl(INADDR_ANY);
            if (!ip.empty()) {
                in_addr parsed{};
                if (inet_pton(AF_INET, ip.c_str(), &parsed) != 1) continue;
                mreq.imr_interface = parsed;
            }

            const std::string key = ip.empty() ? "ANY" : ip;
            attempted++;
            if (setsockopt(fd, IPPROTO_IP, IP_ADD_MEMBERSHIP, &mreq, sizeof(mreq)) == 0) {
                ok++;
                if (h->lanJoined.insert(key).second) {
                    LOGI("Multicast 224.0.2.60 unido en %s (fd %d)", key.c_str(), fd);
                }
            } else {
                const int err = errno;
                if (err == EADDRINUSE) {
                    // Ya estaba unido; el refresco periódico lo reintenta.
                    ok++;
                } else if (h->lanFailed.insert(key).second) {
                    LOGW("Multicast 224.0.2.60 falló en %s (fd %d): errno %d",
                         key.c_str(), fd, err);
                }
            }
        }
    }

    if (ok == 0) {
        LOGE("Descubrimiento LAN: no se pudo unir 224.0.2.60 en ninguna interfaz (%d intento/s)",
             attempted);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

/**
 * Envía un pong MCPE por broadcast usando el socket del listener.
 *
 * Es un canal alternativo al multicast 224.0.2.60 para hotspots que filtran
 * multicast. El cliente solo lo aceptará si su socket de descubrimiento está
 * escuchando en el puerto indicado (normalmente 19132); por eso es experimental.
 */
JNIEXPORT jboolean JNICALL
Java_com_bedrockproxy_native_RakNetPeer_nativeSendBroadcastPong(
        JNIEnv *env, jclass, jlong handle, jstring host, jint port,
        jbyteArray offlineResponse) {
    auto *h = H(handle);
    if (!h || !offlineResponse) return JNI_FALSE;

    const std::string target = JStr(env, host);
    if (target.empty() || port <= 0 || port > 65535) return JNI_FALSE;

    const jsize responseLen = env->GetArrayLength(offlineResponse);
    if (responseLen <= 0) return JNI_FALSE;
    std::vector<jbyte> response(static_cast<size_t>(responseLen));
    env->GetByteArrayRegion(offlineResponse, 0, responseLen, response.data());

    std::lock_guard<std::mutex> lock(h->mutex);
    if (!h->peer) return JNI_FALSE;
    collectSockets(h);
    if (h->lanSocketFds.empty()) {
        LOGW("Broadcast LAN: el listener no expone ningún socket");
        return JNI_FALSE;
    }

    // ID_UNCONNECTED_PONG + tiempo + GUID + magic + respuesta MCPE.
    // RakNet usa 8 bytes para Time y GUID (__GET_TIME_64BIT=1).
    const size_t headerLen = 1 + 8 + 8 + 16;
    std::vector<char> packet(headerLen + static_cast<size_t>(responseLen));
    packet[0] = static_cast<char>(ID_UNCONNECTED_PONG);

    const uint64_t pingTime = static_cast<uint64_t>(RakNet::GetTime());
    for (int i = 0; i < 8; ++i) {
        packet[1 + i] = static_cast<char>((pingTime >> (56 - i * 8)) & 0xFF);
    }

    const uint64_t guid = h->peer->GetMyGUID().g;
    for (int i = 0; i < 8; ++i) {
        packet[9 + i] = static_cast<char>((guid >> (56 - i * 8)) & 0xFF);
    }

    static const unsigned char magic[16] = {
            0x00, 0xFF, 0xFF, 0x00, 0xFE, 0xFE, 0xFE, 0xFE,
            0xFD, 0xFD, 0xFD, 0xFD, 0x12, 0x34, 0x56, 0x78
    };
    std::memcpy(packet.data() + 17, magic, sizeof(magic));
    std::memcpy(packet.data() + headerLen, response.data(), response.size());

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(static_cast<uint16_t>(port));
    if (inet_pton(AF_INET, target.c_str(), &addr.sin_addr) != 1) return JNI_FALSE;

    const int fd = h->lanSocketFds.front();
    int yes = 1;
    if (setsockopt(fd, SOL_SOCKET, SO_BROADCAST, &yes, sizeof(yes)) != 0) {
        const int err = errno;
        if (h->broadcastLogged.insert("sockopt:" + target).second) {
            LOGW("Broadcast LAN: SO_BROADCAST falló en fd %d: errno %d", fd, err);
        }
    }

    const ssize_t sent = sendto(
            fd, packet.data(), packet.size(), 0,
            reinterpret_cast<const sockaddr *>(&addr), sizeof(addr));
    if (sent < 0) {
        const int err = errno;
        const std::string key = "err:" + target + ":" + std::to_string(port);
        if (h->broadcastLogged.insert(key).second) {
            LOGW("Broadcast pong a %s:%d falló: errno %d", target.c_str(), port, err);
        }
        return JNI_FALSE;
    }

    const std::string key = target + ":" + std::to_string(port);
    if (h->broadcastLogged.insert(key).second) {
        LOGI("Broadcast pong a %s:%d (%zu bytes, fd %d)",
             target.c_str(), port, packet.size(), fd);
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_bedrockproxy_native_RakNetPeer_nativeSetMaxIncomingConnections(JNIEnv *, jclass,
                                                                        jlong handle, jint max) {
    auto *h = H(handle);
    if (!h) return;

    std::lock_guard<std::mutex> lock(h->mutex);
    if (!h->peer) return;
    h->peer->SetMaximumIncomingConnections(static_cast<unsigned short>(max));
}

JNIEXPORT jboolean JNICALL
Java_com_bedrockproxy_native_RakNetPeer_nativePing(JNIEnv *env, jclass, jlong handle,
                                                   jstring host, jint port) {
    auto *h = H(handle);
    if (!h) return JNI_FALSE;

    std::string hostStr = JStr(env, host);
    if (hostStr.empty() || port <= 0 || port > 65535) return JNI_FALSE;

    std::lock_guard<std::mutex> lock(h->mutex);
    if (!h->peer) return JNI_FALSE;
    return h->peer->Ping(hostStr.c_str(), static_cast<unsigned short>(port), false)
           ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
