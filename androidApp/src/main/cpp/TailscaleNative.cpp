#include <jni.h>
#include <errno.h>
#include <mutex>

namespace {
JavaVM* javaVm = nullptr;
jobject activeNetwork = nullptr;
jmethodID bindSocket = nullptr;
std::mutex networkMutex;
}

extern "C" jint JNI_OnLoad(JavaVM* vm, void*) {
    javaVm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass network = env->FindClass("android/net/Network");
    bindSocket = env->GetMethodID(network, "bindSocket", "(Ljava/io/FileDescriptor;)V");
    env->DeleteLocalRef(network);
    return bindSocket ? JNI_VERSION_1_6 : JNI_ERR;
}

extern "C" int TailscaleBindSocket(int fd) {
    if (!javaVm || !bindSocket) return ENODEV;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (javaVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (javaVm->AttachCurrentThread(&env, nullptr) != JNI_OK) return EIO;
        attached = true;
    }
    jobject network = nullptr;
    {
        std::lock_guard<std::mutex> lock(networkMutex);
        if (activeNetwork) network = env->NewLocalRef(activeNetwork);
    }
    if (!network) {
        if (attached) javaVm->DetachCurrentThread();
        return ENODEV;
    }
    jclass descriptorClass = env->FindClass("java/io/FileDescriptor");
    jmethodID constructor = env->GetMethodID(descriptorClass, "<init>", "()V");
    jfieldID descriptor = env->GetFieldID(descriptorClass, "descriptor", "I");
    jobject fileDescriptor = env->NewObject(descriptorClass, constructor);
    env->SetIntField(fileDescriptor, descriptor, fd);
    env->CallVoidMethod(network, bindSocket, fileDescriptor);
    const bool failed = env->ExceptionCheck();
    if (failed) env->ExceptionClear();
    env->DeleteLocalRef(fileDescriptor);
    env->DeleteLocalRef(descriptorClass);
    env->DeleteLocalRef(network);
    if (attached) javaVm->DetachCurrentThread();
    return failed ? EPERM : 0;
}

extern "C" {
char* TailscaleStart(const char*, const char*, const char*, int);
char* TailscaleRestartRelay(const char*, int);
char* TailscaleCancelStart();
char* TailscaleStop();
void TailscaleFree(char*);
}

static jstring result(JNIEnv* env, char* value) {
    jstring text = env->NewStringUTF(value);
    TailscaleFree(value);
    return text;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_dsh_mobile_mesh_connection_TailscaleNative_startNative(
    JNIEnv* env, jclass, jstring stateDir, jstring hostname, jstring remoteHost, jint remotePort) {
    const char* state = env->GetStringUTFChars(stateDir, nullptr);
    const char* name = env->GetStringUTFChars(hostname, nullptr);
    const char* host = env->GetStringUTFChars(remoteHost, nullptr);
    char* value = TailscaleStart(state, name, host, remotePort);
    env->ReleaseStringUTFChars(stateDir, state);
    env->ReleaseStringUTFChars(hostname, name);
    env->ReleaseStringUTFChars(remoteHost, host);
    return result(env, value);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_dsh_mobile_mesh_connection_TailscaleNative_restartRelayNative(
    JNIEnv* env, jclass, jstring remoteHost, jint remotePort) {
    const char* host = env->GetStringUTFChars(remoteHost, nullptr);
    char* value = TailscaleRestartRelay(host, remotePort);
    env->ReleaseStringUTFChars(remoteHost, host);
    return result(env, value);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_dsh_mobile_mesh_connection_TailscaleNative_cancelStartNative(JNIEnv* env, jclass) {
    return result(env, TailscaleCancelStart());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_dsh_mobile_mesh_connection_TailscaleNative_stopNative(JNIEnv* env, jclass) {
    return result(env, TailscaleStop());
}

extern "C" JNIEXPORT void JNICALL
Java_dev_dsh_mobile_mesh_connection_TailscaleNative_setNetworkNative(JNIEnv* env, jclass, jobject network) {
    std::lock_guard<std::mutex> lock(networkMutex);
    if (activeNetwork) env->DeleteGlobalRef(activeNetwork);
    activeNetwork = network ? env->NewGlobalRef(network) : nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_dsh_mobile_mesh_connection_TailscaleNative_setInterfacesNative(JNIEnv* env, jclass, jstring interfaces) {
    extern void TailscaleSetInterfaces(const char*);
    const char* value = env->GetStringUTFChars(interfaces, nullptr);
    TailscaleSetInterfaces(value);
    env->ReleaseStringUTFChars(interfaces, value);
}
