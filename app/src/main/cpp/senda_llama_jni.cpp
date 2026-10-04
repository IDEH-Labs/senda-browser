#include <android/log.h>
#include <jni.h>
#include <iomanip>
#include <cmath>
#include <string>
#include <sstream>
#include <vector>
#include <mutex>
#include <unistd.h>
#include <sampling.h>

#include "chat.h"
#include "common.h"
#include "llama.h"

#define TAG "SendaLlamaJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static std::mutex g_llama_mutex;

constexpr int   N_THREADS_MIN           = 2;
constexpr int   N_THREADS_MAX           = 4;
constexpr int   N_THREADS_HEADROOM      = 2;

constexpr int   DEFAULT_CONTEXT_SIZE    = 4096;
constexpr int   OVERFLOW_HEADROOM       = 4;
constexpr int   BATCH_SIZE              = 512;
constexpr float DEFAULT_SAMPLER_TEMP    = 0.3f;

static llama_model             * g_model = nullptr;
static llama_context           * g_context = nullptr;
static common_batch              g_batch;
static common_chat_templates_ptr g_chat_templates;
static common_sampler          * g_sampler = nullptr;

static void senda_llama_log_callback(enum ggml_log_level level, const char* text, void* /*user*/) {
    int prio = ANDROID_LOG_DEFAULT;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN; break;
        case GGML_LOG_LEVEL_INFO:  prio = ANDROID_LOG_INFO; break;
        case GGML_LOG_LEVEL_DEBUG: prio = ANDROID_LOG_DEBUG; break;
        default: break;
    }
    __android_log_write(prio, TAG, text);
}

static llama_context *init_context(llama_model *model, const int n_ctx = DEFAULT_CONTEXT_SIZE) {
    if (!model) {
        LOGE("%s: model cannot be null", __func__);
        return nullptr;
    }

    const int n_threads = std::max(N_THREADS_MIN, std::min(N_THREADS_MAX,
                                                     (int) sysconf(_SC_NPROCESSORS_ONLN) -
                                                     N_THREADS_HEADROOM));
    LOGI("%s: Using %d threads", __func__, n_threads);

    llama_context_params ctx_params = llama_context_default_params();
    const int trained_context_size = llama_model_n_ctx_train(model);
    if (n_ctx > trained_context_size && trained_context_size > 0) {
        LOGW("%s: Model was trained with %d context size! Enforcing %d...",
             __func__, trained_context_size, n_ctx);
    }
    ctx_params.n_ctx = n_ctx;
    ctx_params.n_batch = BATCH_SIZE;
    ctx_params.n_ubatch = BATCH_SIZE;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;
    auto *context = llama_init_from_model(g_model, ctx_params);
    if (context == nullptr) {
        LOGE("%s: llama_init_from_model() returned null", __func__);
    }
    return context;
}

static common_sampler *new_sampler(float temp) {
    common_params_sampling sparams;
    sparams.temp = temp;
    return common_sampler_init(g_model, sparams);
}

constexpr const char *ROLE_SYSTEM    = "system";
constexpr const char *ROLE_USER      = "user";
constexpr const char *ROLE_ASSISTANT = "assistant";

static std::vector<common_chat_msg> chat_msgs;
static llama_pos system_prompt_position = 0;
static llama_pos current_position = 0;
static llama_pos stop_generation_position = 0;
static std::string cached_token_chars;
static std::ostringstream assistant_ss;

static void reset_long_term_states(const bool clear_kv_cache = true) {
    chat_msgs.clear();
    system_prompt_position = 0;
    current_position = 0;

    if (clear_kv_cache && g_context) {
        llama_memory_clear(llama_get_memory(g_context), false);
    }
}

static void reset_short_term_states() {
    stop_generation_position = 0;
    cached_token_chars.clear();
    assistant_ss.str("");
}

static void shift_context() {
    if (!g_context) return;
    const int n_discard = (current_position - system_prompt_position) / 2;
    LOGI("%s: Discarding %d tokens", __func__, n_discard);
    llama_memory_seq_rm(llama_get_memory(g_context), 0, system_prompt_position, system_prompt_position + n_discard);
    llama_memory_seq_add(llama_get_memory(g_context), 0, system_prompt_position + n_discard, current_position, -n_discard);
    current_position -= n_discard;
    LOGI("%s: Context shifted. Current position: %d", __func__, current_position);
}

static std::string chat_add_and_format(const std::string &role, const std::string &content) {
    common_chat_msg new_msg;
    new_msg.role = role;
    new_msg.content = content;
    auto formatted = common_chat_format_single(
            g_chat_templates.get(), chat_msgs, new_msg, role == ROLE_USER, /* use_jinja */ false);
    chat_msgs.push_back(new_msg);
    return formatted;
}

static int decode_tokens_in_batches(
        llama_context *context,
        common_batch &batch,
        const llama_tokens &tokens,
        const llama_pos start_pos,
        const bool compute_last_logit = false) {
    for (int i = 0; i < (int) tokens.size(); i += BATCH_SIZE) {
        const int cur_batch_size = std::min((int) tokens.size() - i, BATCH_SIZE);
        batch.clear();

        if (start_pos + i + cur_batch_size >= DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) {
            LOGW("%s: Context full, shifting...", __func__);
            shift_context();
        }

        for (int j = 0; j < cur_batch_size; j++) {
            const llama_token token_id = tokens[i + j];
            const llama_pos position = start_pos + i + j;
            const bool want_logit = compute_last_logit && (i + j == (int)tokens.size() - 1);
            batch.add(token_id, position, 0, want_logit);
        }

        const int decode_result = llama_process(context, LLAMA_PROCESS_TYPE_DECODE, batch.get());
        if (decode_result) {
            LOGE("%s: llama_process failed w/ %d", __func__, decode_result);
            return 1;
        }
    }
    return 0;
}

static bool is_valid_utf8(const char *string) {
    if (!string) return true;
    const auto *bytes = (const unsigned char *) string;
    while (*bytes != 0x00) {
        int num = 0;
        if ((*bytes & 0x80) == 0x00) {
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            num = 4;
        } else {
            return false;
        }
        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) return false;
            bytes += 1;
        }
    }
    return true;
}

extern "C" {

JNIEXPORT void JNICALL
Java_org_senda_browser_core_ai_llama_SendaLlamaBridge_nativeInit(JNIEnv *env, jobject /*thiz*/, jstring nativeLibDir) {
    std::lock_guard<std::mutex> lock(g_llama_mutex);
    llama_log_set(senda_llama_log_callback, nullptr);

    if (nativeLibDir) {
        const auto *path_to_backend = env->GetStringUTFChars(nativeLibDir, 0);
        LOGI("Loading backends from %s", path_to_backend);
        ggml_backend_load_all_from_path(path_to_backend);
        env->ReleaseStringUTFChars(nativeLibDir, path_to_backend);
    }

    llama_backend_init();
    LOGI("Senda llama native backend initialized successfully.");
}

JNIEXPORT jint JNICALL
Java_org_senda_browser_core_ai_llama_SendaLlamaBridge_nativeLoadModel(JNIEnv *env, jobject /*thiz*/, jstring jmodel_path) {
    std::lock_guard<std::mutex> lock(g_llama_mutex);
    if (!jmodel_path) return -1;

    llama_model_params model_params = llama_model_default_params();
    const auto *model_path = env->GetStringUTFChars(jmodel_path, 0);
    LOGI("Loading GGUF model: %s", model_path);

    auto *model = llama_model_load_from_file(model_path, model_params);
    env->ReleaseStringUTFChars(jmodel_path, model_path);

    if (!model) {
        LOGE("Failed to load GGUF model from file");
        return 1;
    }

    g_model = model;
    return 0;
}

JNIEXPORT jint JNICALL
Java_org_senda_browser_core_ai_llama_SendaLlamaBridge_nativePrepare(JNIEnv * /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_llama_mutex);
    if (!g_model) {
        LOGE("Cannot prepare: model is null");
        return 1;
    }

    auto *context = init_context(g_model);
    if (!context) return 2;

    g_context = context;
    g_batch = common_batch(context);
    g_chat_templates = common_chat_templates_init(g_model, "");
    g_sampler = new_sampler(DEFAULT_SAMPLER_TEMP);
    reset_long_term_states(false);
    reset_short_term_states();
    LOGI("Senda llama prepared: context and sampler ready");
    return 0;
}

JNIEXPORT jstring JNICALL
Java_org_senda_browser_core_ai_llama_SendaLlamaBridge_nativeSystemInfo(JNIEnv *env, jobject /*thiz*/) {
    return env->NewStringUTF(llama_print_system_info());
}

JNIEXPORT jstring JNICALL
Java_org_senda_browser_core_ai_llama_SendaLlamaBridge_nativeBenchModel(JNIEnv *env, jobject /*thiz*/, jint pp, jint tg, jint pl, jint nr) {
    std::lock_guard<std::mutex> lock(g_llama_mutex);
    if (!g_model) return env->NewStringUTF("Model not loaded");

    auto *context = init_context(g_model, pp);
    if (!context) return env->NewStringUTF("Failed to create context for benchmark");

    double pp_avg = 0.0, tg_avg = 0.0;
    for (int nri = 0; nri < nr; nri++) {
        common_batch batch(context);
        for (int i = 0; i < pp; i++) batch.add(0, i, 0, false);
        batch.set_output(batch.size() - 1, true);
        llama_memory_clear(llama_get_memory(context), false);

        auto t_pp_start = ggml_time_us();
        llama_process(context, LLAMA_PROCESS_TYPE_DECODE, batch.get());
        auto t_pp_end = ggml_time_us();

        llama_memory_clear(llama_get_memory(context), false);
        auto t_tg_start = ggml_time_us();
        for (int i = 0; i < tg; i++) {
            batch.clear();
            for (int j = 0; j < pl; j++) batch.add(0, i, j, true);
            llama_process(context, LLAMA_PROCESS_TYPE_DECODE, batch.get());
        }
        auto t_tg_end = ggml_time_us();

        double t_pp = double(t_pp_end - t_pp_start) / 1000000.0;
        double t_tg = double(t_tg_end - t_tg_start) / 1000000.0;
        pp_avg += double(pp) / t_pp;
        tg_avg += double(pl * tg) / t_tg;
    }
    llama_free(context);

    pp_avg /= double(nr);
    tg_avg /= double(nr);

    char model_desc[128];
    llama_model_desc(g_model, model_desc, sizeof(model_desc));

    std::ostringstream ss;
    ss << "Model: " << model_desc << " | Prompt Processing: " << std::fixed << std::setprecision(2)
       << pp_avg << " t/s | Text Generation: " << tg_avg << " t/s";
    return env->NewStringUTF(ss.str().c_str());
}

JNIEXPORT jint JNICALL
Java_org_senda_browser_core_ai_llama_SendaLlamaBridge_nativeProcessSystemPrompt(JNIEnv *env, jobject /*thiz*/, jstring jsystem_prompt) {
    std::lock_guard<std::mutex> lock(g_llama_mutex);
    if (!g_context || !g_model) return -1;

    reset_long_term_states();
    reset_short_term_states();

    const auto *system_prompt = env->GetStringUTFChars(jsystem_prompt, nullptr);
    std::string formatted_system_prompt(system_prompt);

    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_system_prompt = chat_add_and_format(ROLE_SYSTEM, system_prompt);
    }
    env->ReleaseStringUTFChars(jsystem_prompt, system_prompt);

    const auto system_tokens = common_tokenize(g_context, formatted_system_prompt, has_chat_template, has_chat_template);
    const int max_batch_size = DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM;
    if ((int) system_tokens.size() > max_batch_size) {
        LOGE("System prompt too long for context!");
        return 1;
    }

    if (decode_tokens_in_batches(g_context, g_batch, system_tokens, current_position)) {
        LOGE("Failed to decode system tokens!");
        return 2;
    }

    system_prompt_position = current_position = (int) system_tokens.size();
    return 0;
}

JNIEXPORT jint JNICALL
Java_org_senda_browser_core_ai_llama_SendaLlamaBridge_nativeProcessUserPrompt(JNIEnv *env, jobject /*thiz*/, jstring juser_prompt, jint n_predict) {
    std::lock_guard<std::mutex> lock(g_llama_mutex);
    if (!g_context || !g_model) return -1;

    reset_short_term_states();

    const auto *user_prompt = env->GetStringUTFChars(juser_prompt, nullptr);
    std::string formatted_user_prompt(user_prompt);

    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_user_prompt = chat_add_and_format(ROLE_USER, user_prompt);
    }
    env->ReleaseStringUTFChars(juser_prompt, user_prompt);

    auto user_tokens = common_tokenize(g_context, formatted_user_prompt, has_chat_template, has_chat_template);
    const int user_prompt_size = (int) user_tokens.size();
    const int max_batch_size = DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM;
    if (user_prompt_size > max_batch_size) {
        user_tokens.resize(max_batch_size);
    }

    if (decode_tokens_in_batches(g_context, g_batch, user_tokens, current_position, true)) {
        LOGE("Failed to decode user prompt!");
        return 2;
    }

    current_position += user_prompt_size;
    stop_generation_position = current_position + user_prompt_size + n_predict;
    return 0;
}

JNIEXPORT jstring JNICALL
Java_org_senda_browser_core_ai_llama_SendaLlamaBridge_nativeGenerateNextToken(JNIEnv *env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_llama_mutex);
    if (!g_context || !g_model || !g_sampler) return nullptr;

    if (current_position >= DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) {
        shift_context();
    }

    if (current_position >= stop_generation_position) {
        return nullptr;
    }

    const auto new_token_id = common_sampler_sample(g_sampler, g_context, -1);
    common_sampler_accept(g_sampler, new_token_id, true);

    g_batch.clear();
    g_batch.add(new_token_id, current_position, 0, true);
    if (llama_process(g_context, LLAMA_PROCESS_TYPE_DECODE, g_batch.get()) != 0) {
        LOGE("llama_process failed during token generation");
        return nullptr;
    }

    current_position++;

    if (llama_vocab_is_eog(llama_model_get_vocab(g_model), new_token_id)) {
        chat_add_and_format(ROLE_ASSISTANT, assistant_ss.str());
        return nullptr;
    }

    auto new_token_chars = common_token_to_piece(g_context, new_token_id);
    cached_token_chars += new_token_chars;

    if (is_valid_utf8(cached_token_chars.c_str())) {
        jstring result = env->NewStringUTF(cached_token_chars.c_str());
        assistant_ss << cached_token_chars;
        cached_token_chars.clear();
        return result;
    } else {
        return env->NewStringUTF("");
    }
}

JNIEXPORT void JNICALL
Java_org_senda_browser_core_ai_llama_SendaLlamaBridge_nativeUnload(JNIEnv * /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_llama_mutex);
    reset_long_term_states();
    reset_short_term_states();

    if (g_sampler) {
        common_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
    g_chat_templates.reset();
    g_batch = common_batch();
    if (g_context) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    LOGI("Senda llama model and context unloaded.");
}

JNIEXPORT void JNICALL
Java_org_senda_browser_core_ai_llama_SendaLlamaBridge_nativeShutdown(JNIEnv * /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_llama_mutex);
    llama_backend_free();
    LOGI("Senda llama native backend shutdown.");
}

} // extern "C"
