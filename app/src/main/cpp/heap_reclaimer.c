#include <android/api-level.h>
#include <jni.h>
#include <malloc.h>

/* Public allocator maintenance only. No cache/decay policy changes or live data access.
 * https://android.googlesource.com/platform/bionic/+/refs/heads/main/libc/include/malloc.h
 */
JNIEXPORT jint JNICALL
Java_com_example_version1_NativeHeapReclaimer_nativePurgeUnused(JNIEnv *env, jobject receiver) {
    (void)env;
    (void)receiver;
    if (android_get_device_api_level() >= 34 && mallopt(M_PURGE_ALL, 0) == 1) return 2;
    return mallopt(M_PURGE, 0) == 1 ? 1 : 0;
}
