#include "net/HttpClient.h"

#include "util/Log.h"

#include <curl/curl.h>

#include <mutex>

namespace wwdjni {

std::string HttpClient::caBundlePath_;

namespace {

std::once_flag g_initOnce;
bool g_initOk = false;

// libcurl 写回调：把响应字节追加到 std::vector<uint8_t>
size_t writeCallback(char *ptr, size_t size, size_t nmemb, void *userdata) {
    auto *out = static_cast<std::vector<uint8_t> *>(userdata);
    const size_t bytes = size * nmemb;
    out->insert(out->end(), ptr, ptr + bytes);
    return bytes;
}

// 传输进度回调：返回非 0 则中断传输。用于关机时让正在进行的 GET 尽快返回，避免 join 阻塞。
int abortProgress(void *clientp, curl_off_t, curl_off_t, curl_off_t, curl_off_t) {
    const auto *flag = static_cast<const std::atomic<bool> *>(clientp);
    return (flag != nullptr && flag->load()) ? 1 : 0;
}

// Android 系统 CA 证书目录（OpenSSL 哈希命名的单个 PEM），供 CURLOPT_CAPATH 直接校验公有 CA
constexpr const char *const kAndroidSystemCaPath = "/system/etc/security/cacerts";

} // namespace

bool HttpClient::globalInit() {
    std::call_once(g_initOnce, []() {
        const CURLcode rc = curl_global_init(CURL_GLOBAL_DEFAULT);
        g_initOk = (rc == CURLE_OK);
        if (g_initOk) {
            LOGI("curl_global_init ok, version=%s", curl_version());
        } else {
            LOGE("curl_global_init failed rc=%d", static_cast<int>(rc));
        }
    });
    return g_initOk;
}

void HttpClient::globalCleanup() {
    curl_global_cleanup();
}

std::string HttpClient::version() {
    const char *v = curl_version();
    return v != nullptr ? std::string(v) : std::string();
}

void HttpClient::setCaBundle(const std::string &caInfoPath) {
    caBundlePath_ = caInfoPath;
}

bool HttpClient::get(const std::string &url, std::vector<uint8_t> &outBytes, long timeoutMs,
                     const std::atomic<bool> *abortFlag) {
    if (!globalInit()) return false;

    CURL *curl = curl_easy_init();
    if (curl == nullptr) {
        LOGE("curl_easy_init failed");
        return false;
    }

    outBytes.clear();
    curl_easy_setopt(curl, CURLOPT_URL, url.c_str());
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, writeCallback);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &outBytes);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT_MS, timeoutMs);
    curl_easy_setopt(curl, CURLOPT_NOSIGNAL, 1L);       // 多线程环境下必须，避免 alarm 信号
    curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, 1L); // 跟随 3xx
    curl_easy_setopt(curl, CURLOPT_USERAGENT, "worldwindjni/0.2.8.1");

    // 可中断传输：传入 abortFlag 时启用进度回调，标志置位则中断
    if (abortFlag != nullptr) {
        curl_easy_setopt(curl, CURLOPT_NOPROGRESS, 0L);
        curl_easy_setopt(curl, CURLOPT_XFERINFOFUNCTION, abortProgress);
        curl_easy_setopt(curl, CURLOPT_XFERINFODATA, abortFlag);
    }

    // HTTPS 证书校验：配置了 CA 包则用之；否则用 Android 系统证书目录（CAPATH）校验公有 CA
    if (!caBundlePath_.empty()) {
        curl_easy_setopt(curl, CURLOPT_CAINFO, caBundlePath_.c_str());
        curl_easy_setopt(curl, CURLOPT_SSL_VERIFYPEER, 1L);
        curl_easy_setopt(curl, CURLOPT_SSL_VERIFYHOST, 2L);
    } else {
        curl_easy_setopt(curl, CURLOPT_CAPATH, kAndroidSystemCaPath);
        curl_easy_setopt(curl, CURLOPT_SSL_VERIFYPEER, 1L);
        curl_easy_setopt(curl, CURLOPT_SSL_VERIFYHOST, 2L);
    }

    CURLcode rc = curl_easy_perform(curl);

    // 证书校验失败（部分设备系统证书目录不可用/不完整）时降级为不校验重试一次，保证瓦片可取
    if (rc == CURLE_PEER_FAILED_VERIFICATION || rc == CURLE_SSL_CACERT_BADFILE) {
        LOGW("curl TLS 校验失败(%s)，降级不校验重试 url=%s", curl_easy_strerror(rc), url.c_str());
        outBytes.clear();
        curl_easy_setopt(curl, CURLOPT_SSL_VERIFYPEER, 0L);
        curl_easy_setopt(curl, CURLOPT_SSL_VERIFYHOST, 0L);
        rc = curl_easy_perform(curl);
    }

    long httpCode = 0;
    curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &httpCode);
    curl_easy_cleanup(curl);

    if (rc != CURLE_OK) {
        // 主动中断（abort）属正常关机路径，降为 debug 级避免刷屏
        if (abortFlag != nullptr && abortFlag->load()) {
            outBytes.clear();
            return false;
        }
        LOGW("curl GET 失败 url=%s err=%s", url.c_str(), curl_easy_strerror(rc));
        outBytes.clear();
        return false;
    }
    if (httpCode < 200 || httpCode >= 300) {
        LOGW("curl GET 非 2xx（HTTP %ld）url=%s", httpCode, url.c_str());
        outBytes.clear();
        return false;
    }
    return true;
}

} // namespace wwdjni
