#ifndef WORLDWINDJNI_UTIL_LOG_H
#define WORLDWINDJNI_UTIL_LOG_H

#include <android/log.h>

// 统一日志封装，TAG 与模块名一致，便于 logcat 过滤
#define WWJNI_LOG_TAG "worldwindjni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, WWJNI_LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, WWJNI_LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, WWJNI_LOG_TAG, __VA_ARGS__)

#endif // WORLDWINDJNI_UTIL_LOG_H
