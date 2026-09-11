#include <jni.h>
#include "ZeroTierSockets.h"

extern "C" JNIEXPORT jint JNICALL
Java_dev_dsh_mobile_mesh_connection_ZeroTierNativeBridge_safeNodeStop(JNIEnv*, jclass) {
    // libzt's Java wrapper must not detach ART's Java-owned calling thread.
    return zts_node_stop();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_dsh_mobile_mesh_connection_ZeroTierNativeBridge_isServiceOffline(JNIEnv*, jclass) {
    // libzt reports an offline service as 0; initFromStorage is then legal again.
    return zts_node_is_online() != 1;
}
