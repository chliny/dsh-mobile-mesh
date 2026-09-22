cmake_minimum_required(VERSION 3.22)
project(dsh_mobile_native)

# libzt supplies a userspace TCP stack. It never creates a TUN device or asks
# Android to become the device VPN; DSH only exposes it through a loopback relay.
set(ZTS_ENABLE_JAVA ON CACHE BOOL "Build libzt Java bindings" FORCE)
add_subdirectory(
    "${CMAKE_CURRENT_LIST_DIR}/../third_party/libzt"
    "${CMAKE_CURRENT_BINARY_DIR}/libzt"
)

add_library(dsh_tsnet SHARED IMPORTED)
set_target_properties(dsh_tsnet PROPERTIES
    IMPORTED_LOCATION "${TSNET_LIB_DIR}/${ANDROID_ABI}/libdsh_tsnet.so"
    IMPORTED_NO_SONAME TRUE
)
add_library(dsh_tsnet_jni SHARED "${CMAKE_CURRENT_LIST_DIR}/src/main/cpp/TailscaleNative.cpp")
target_link_libraries(dsh_tsnet_jni dsh_tsnet log)
