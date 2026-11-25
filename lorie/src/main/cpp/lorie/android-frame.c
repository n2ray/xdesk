#include <android/hardware_buffer_jni.h>
#include <jni.h>

JNIEXPORT jint JNICALL Java_com_termux_x11_AndroidFrameProbe_send(JNIEnv *env, jclass type, jobject buffer, jint fd) {
    AHardwareBuffer *native = AHardwareBuffer_fromHardwareBuffer(env, buffer);
    return native ? AHardwareBuffer_sendHandleToUnixSocket(native, fd) : -1;
}

JNIEXPORT jint JNICALL Java_com_termux_x11_CmdEntryPoint_sendXdesxBuffer(JNIEnv *env, jclass type, jobject buffer, jint fd) {
    return Java_com_termux_x11_AndroidFrameProbe_send(env, type, buffer, fd);
}
