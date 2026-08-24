#include <jni.h>
#include <string>
#include <vector>
#include <mutex>
#include <rime_api.h>

namespace {
std::mutex g_mutex;
RimeApi* g_api = nullptr;
RimeSessionId g_session = 0;
bool g_started = false;

std::string j2s(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return out;
}

jstring s2j(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

void reset_locked() {
    if (!g_api) return;
    if (g_session) {
        g_api->destroy_session(g_session);
        g_session = 0;
    }
    if (g_started) {
        g_api->finalize();
        g_started = false;
    }
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jingwei_aikeyboard_RimeBridge_nativeStart(
        JNIEnv* env, jclass, jstring sharedDir, jstring userDir, jstring versionName) {
    std::lock_guard<std::mutex> lock(g_mutex);
    reset_locked();

    g_api = rime_get_api();
    if (!g_api) return JNI_FALSE;

    std::string shared = j2s(env, sharedDir);
    std::string user = j2s(env, userDir);
    std::string version = j2s(env, versionName);

    RIME_STRUCT(RimeTraits, traits);
    traits.shared_data_dir = shared.c_str();
    traits.user_data_dir = user.c_str();
    traits.distribution_name = "Jingwei AI Keyboard";
    traits.distribution_code_name = "jingwei_ai_keyboard";
    traits.distribution_version = version.c_str();
    traits.app_name = "com.jingwei.aikeyboard";

    g_api->setup(&traits);
    g_api->initialize(&traits);
    g_started = true;

    // Rebuild schema/table files on first start or when config changes.
    if (g_api->start_maintenance) {
        g_api->start_maintenance(true);
        if (g_api->join_maintenance_thread) g_api->join_maintenance_thread();
    }

    g_session = g_api->create_session();
    return g_session ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_jingwei_aikeyboard_RimeBridge_nativeStop(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    reset_locked();
}

extern "C" JNIEXPORT void JNICALL
Java_com_jingwei_aikeyboard_RimeBridge_nativeClearComposition(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_api && g_session) g_api->clear_composition(g_session);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jingwei_aikeyboard_RimeBridge_nativeProcessAscii(JNIEnv* env, jclass, jstring text) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_api || !g_session) return JNI_FALSE;
    std::string value = j2s(env, text);
    bool handled = false;
    for (unsigned char c : value) {
        handled = g_api->process_key(g_session, static_cast<int>(c), 0) || handled;
    }
    return handled ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jingwei_aikeyboard_RimeBridge_nativeProcessBackspace(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_api || !g_session) return JNI_FALSE;
    return g_api->process_key(g_session, 0xff08, 0) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_jingwei_aikeyboard_RimeBridge_nativeGetInput(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_api || !g_session) return s2j(env, "");
    RIME_STRUCT(RimeContext, ctx);
    std::string out;
    if (g_api->get_context(g_session, &ctx)) {
        if (ctx.input) out = ctx.input;
        g_api->free_context(&ctx);
    }
    return s2j(env, out);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_jingwei_aikeyboard_RimeBridge_nativeGetComposition(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_api || !g_session) return s2j(env, "");
    RIME_STRUCT(RimeContext, ctx);
    std::string out;
    if (g_api->get_context(g_session, &ctx)) {
        if (ctx.composition.preedit) out = ctx.composition.preedit;
        g_api->free_context(&ctx);
    }
    return s2j(env, out);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_jingwei_aikeyboard_RimeBridge_nativeGetCandidates(JNIEnv* env, jclass, jint limit) {
    std::lock_guard<std::mutex> lock(g_mutex);
    jclass stringClass = env->FindClass("java/lang/String");
    if (!g_api || !g_session) return env->NewObjectArray(0, stringClass, nullptr);

    RIME_STRUCT(RimeContext, ctx);
    std::vector<std::string> values;
    if (g_api->get_context(g_session, &ctx)) {
        int count = static_cast<int>(ctx.menu.num_candidates);
        int cap = limit <= 0 ? count : std::min(count, static_cast<int>(limit));
        for (int i = 0; i < cap; ++i) {
            const char* text = ctx.menu.candidates[i].text;
            if (text && *text) values.emplace_back(text);
        }
        g_api->free_context(&ctx);
    }

    jobjectArray array = env->NewObjectArray(static_cast<jsize>(values.size()), stringClass, nullptr);
    for (jsize i = 0; i < static_cast<jsize>(values.size()); ++i) {
        env->SetObjectArrayElement(array, i, s2j(env, values[i]));
    }
    return array;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_jingwei_aikeyboard_RimeBridge_nativeSelectCandidate(JNIEnv* env, jclass, jint index) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_api || !g_session) return s2j(env, "");
    if (!g_api->select_candidate_on_current_page(g_session, index)) return s2j(env, "");

    RIME_STRUCT(RimeCommit, commit);
    std::string text;
    if (g_api->get_commit(g_session, &commit)) {
        if (commit.text) text = commit.text;
        g_api->free_commit(&commit);
    }
    return s2j(env, text);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jingwei_aikeyboard_RimeBridge_nativeSetSchema(JNIEnv* env, jclass, jstring schemaId) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_api || !g_session) return JNI_FALSE;
    std::string id = j2s(env, schemaId);
    return g_api->select_schema(g_session, id.c_str()) ? JNI_TRUE : JNI_FALSE;
}
