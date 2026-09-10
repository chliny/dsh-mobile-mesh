#include <jni.h>
#include "ZeroTierSockets.h"

extern "C" JNIEXPORT jint JNICALL
Java_dev_dsh_mobile_mesh_connection_ZeroTierNative_safeNodeStop(JNIEnv*, jclass) {
    // libzt's Java wrapper must not detach ART's Java-owned calling thread.
    return zts_node_stop();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_dsh_mobile_mesh_connection_ZeroTierNative_isServiceOffline(JNIEnv*, jclass) {
    // initFromStorage is legal only once libzt's global service is no longer running.
    return zts_node_is_online() == ZTS_ERR_SERVICE;
}
