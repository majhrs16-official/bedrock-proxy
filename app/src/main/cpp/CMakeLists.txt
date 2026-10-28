cmake_minimum_required(VERSION 3.22.1)
project(raknet_jni LANGUAGES CXX)

# RakNet 4.081 usa 'register' (C++98), que es error en C++17; C++14 le basta al JNI.
set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(RAKNET_DIR ${CMAKE_CURRENT_SOURCE_DIR}/RakNet/Source)

set(RAKNET_SOURCES
    ${RAKNET_DIR}/BitStream.cpp
    ${RAKNET_DIR}/CCRakNetSlidingWindow.cpp
    ${RAKNET_DIR}/CCRakNetUDT.cpp
    ${RAKNET_DIR}/CheckSum.cpp
    ${RAKNET_DIR}/DR_SHA1.cpp
    ${RAKNET_DIR}/DS_BytePool.cpp
    ${RAKNET_DIR}/DS_ByteQueue.cpp
    ${RAKNET_DIR}/DS_HuffmanEncodingTree.cpp
    ${RAKNET_DIR}/DataCompressor.cpp
    ${RAKNET_DIR}/FormatString.cpp
    ${RAKNET_DIR}/GetTime.cpp
    ${RAKNET_DIR}/gettimeofday.cpp
    ${RAKNET_DIR}/Itoa.cpp
    ${RAKNET_DIR}/LinuxStrings.cpp
    ${RAKNET_DIR}/LocklessTypes.cpp
    ${RAKNET_DIR}/NetworkIDManager.cpp
    ${RAKNET_DIR}/NetworkIDObject.cpp
    ${RAKNET_DIR}/PluginInterface2.cpp
    ${RAKNET_DIR}/RakMemoryOverride.cpp
    ${RAKNET_DIR}/RakNetSocket.cpp
    ${RAKNET_DIR}/RakNetSocket2.cpp
    ${RAKNET_DIR}/RakNetStatistics.cpp
    ${RAKNET_DIR}/RakNetTypes.cpp
    ${RAKNET_DIR}/RakPeer.cpp
    ${RAKNET_DIR}/RakSleep.cpp
    ${RAKNET_DIR}/RakString.cpp
    ${RAKNET_DIR}/RakThread.cpp
    ${RAKNET_DIR}/RakWString.cpp
    ${RAKNET_DIR}/Rand.cpp
    ${RAKNET_DIR}/RandSync.cpp
    ${RAKNET_DIR}/ReliabilityLayer.cpp
    ${RAKNET_DIR}/SecureHandshake.cpp
    ${RAKNET_DIR}/SendToThread.cpp
    ${RAKNET_DIR}/SignaledEvent.cpp
    ${RAKNET_DIR}/SimpleMutex.cpp
    ${RAKNET_DIR}/SocketLayer.cpp
    ${RAKNET_DIR}/StringCompressor.cpp
    ${RAKNET_DIR}/StringTable.cpp
    ${RAKNET_DIR}/SuperFastHash.cpp
    ${RAKNET_DIR}/UDPForwarder.cpp
    ${RAKNET_DIR}/WSAStartupSingleton.cpp
)

find_library(log_lib log)

add_library(raknet_jni SHARED
    jni/raknet_jni.cpp
    ${RAKNET_SOURCES}
)

target_include_directories(raknet_jni PRIVATE
    ${RAKNET_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/jni
)

target_link_libraries(raknet_jni ${log_lib})

target_compile_definitions(raknet_jni PRIVATE
    RAKNET_SUPPORT_IPV6=0
    _RAKNET_LIB=1
)

target_compile_options(raknet_jni PRIVATE
    -w
    -fexceptions
    -frtti
    -D_GNU_SOURCE
)

# Optimización en release
target_compile_options(raknet_jni PRIVATE $<$<CONFIG:Release>:-O2>)
