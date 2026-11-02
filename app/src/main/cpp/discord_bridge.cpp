// JNI bridge between cc.yetracker.mobile.Discord (Kotlin) and the Discord Social SDK.
// Only compiled when app/libs/discord_partner_sdk.aar exists (see app/build.gradle).
#include <jni.h>
#include <memory>
#include <string>
#include <android/log.h>

#define DISCORDPP_IMPLEMENTATION // exactly one file
#include "discordpp.h"

#define TAG "YeDiscord"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static JavaVM* g_vm = nullptr;
static jobject g_obj = nullptr;
static jmethodID g_onStatus = nullptr;
static jmethodID g_onToken = nullptr;
static std::shared_ptr<discordpp::Client> g_client;
static uint64_t g_appId = 0;

static JNIEnv* env_for_thread() {
    JNIEnv* env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
        g_vm->AttachCurrentThread(&env, nullptr);
    }
    return env;
}

static void send_status(const std::string& s) {
    JNIEnv* env = env_for_thread();
    if (!env || !g_obj) return;
    jstring js = env->NewStringUTF(s.c_str());
    env->CallVoidMethod(g_obj, g_onStatus, js);
    env->DeleteLocalRef(js);
}

static void send_token(const std::string& access, const std::string& refresh, int expires) {
    JNIEnv* env = env_for_thread();
    if (!env || !g_obj) return;
    jstring a = env->NewStringUTF(access.c_str());
    jstring r = env->NewStringUTF(refresh.c_str());
    env->CallVoidMethod(g_obj, g_onToken, a, r, static_cast<jint>(expires));
    env->DeleteLocalRef(a);
    env->DeleteLocalRef(r);
}

static std::string to_std(JNIEnv* env, jstring s) {
    if (!s) return "";
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// Exchange/refresh result -> hand token to the SDK and connect.
static void use_token(const std::string& access, const std::string& refresh,
                      discordpp::AuthorizationTokenType type, int expires) {
    send_token(access, refresh, expires);
    g_client->UpdateToken(type, access, [](discordpp::ClientResult result) {
        if (result.Successful()) {
            g_client->Connect();
        } else {
            send_status("Error: " + result.Error());
        }
    });
}

extern "C" {

JNIEXPORT void JNICALL
Java_cc_yetracker_mobile_Discord_nativeInit(JNIEnv* env, jobject thiz, jlong appId) {
    env->GetJavaVM(&g_vm);
    g_obj = env->NewGlobalRef(thiz);
    jclass cls = env->GetObjectClass(thiz);
    g_onStatus = env->GetMethodID(cls, "onStatus", "(Ljava/lang/String;)V");
    g_onToken = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;Ljava/lang/String;I)V");
    g_appId = static_cast<uint64_t>(appId);
    g_client = std::make_shared<discordpp::Client>();
    g_client->SetApplicationId(g_appId);
    g_client->SetStatusChangedCallback([](discordpp::Client::Status status,
                                          discordpp::Client::Error error, int32_t detail) {
        send_status(discordpp::Client::StatusToString(status));
    });
}

JNIEXPORT void JNICALL
Java_cc_yetracker_mobile_Discord_nativeAuthorize(JNIEnv*, jobject) {
    if (!g_client) return;
    auto verifier = g_client->CreateAuthorizationCodeVerifier();
    discordpp::AuthorizationArgs args{};
    args.SetClientId(g_appId);
    args.SetScopes(discordpp::Client::GetDefaultPresenceScopes());
    args.SetCodeChallenge(verifier.Challenge());
    g_client->Authorize(args, [verifier](discordpp::ClientResult result, std::string code,
                                         std::string redirectUri) {
        if (!result.Successful()) {
            send_status("Error: " + result.Error());
            return;
        }
        g_client->GetToken(g_appId, code, verifier.Verifier(), redirectUri,
            [](discordpp::ClientResult r, std::string access, std::string refresh,
               discordpp::AuthorizationTokenType type, int32_t expiresIn, std::string scope) {
                if (!r.Successful()) {
                    send_status("Error: " + r.Error());
                    return;
                }
                use_token(access, refresh, type, expiresIn);
            });
    });
}

JNIEXPORT void JNICALL
Java_cc_yetracker_mobile_Discord_nativeResume(JNIEnv* env, jobject, jstring refreshToken) {
    if (!g_client) return;
    std::string rt = to_std(env, refreshToken);
    g_client->RefreshToken(g_appId, rt,
        [](discordpp::ClientResult r, std::string access, std::string refresh,
           discordpp::AuthorizationTokenType type, int32_t expiresIn, std::string scope) {
            if (!r.Successful()) {
                send_status("Error: " + r.Error());
                return;
            }
            use_token(access, refresh, type, expiresIn);
        });
}

JNIEXPORT void JNICALL
Java_cc_yetracker_mobile_Discord_nativeSetPresence(JNIEnv* env, jobject, jstring title,
        jstring state, jstring imageUrl, jlong startMs, jlong endMs) {
    if (!g_client) return;
    discordpp::Activity activity;
    activity.SetType(discordpp::ActivityTypes::Listening);
    activity.SetDetails(to_std(env, title));
    activity.SetState(to_std(env, state));
    std::string img = to_std(env, imageUrl);
    if (!img.empty()) {
        discordpp::ActivityAssets assets;
        assets.SetLargeImage(img);
        activity.SetAssets(assets);
    }
    if (startMs > 0) {
        discordpp::ActivityTimestamps ts;
        ts.SetStart(static_cast<uint64_t>(startMs));
        if (endMs > startMs) ts.SetEnd(static_cast<uint64_t>(endMs));
        activity.SetTimestamps(ts);
    }
    g_client->UpdateRichPresence(activity, [](discordpp::ClientResult result) {
        if (!result.Successful()) send_status("Presence error: " + result.Error());
    });
}

JNIEXPORT void JNICALL
Java_cc_yetracker_mobile_Discord_nativeClear(JNIEnv*, jobject) {
    if (g_client) g_client->ClearRichPresence();
}

JNIEXPORT void JNICALL
Java_cc_yetracker_mobile_Discord_nativeTick(JNIEnv*, jobject) {
    discordpp::RunCallbacks();
}

} // extern "C"
