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
// Tokens que hay ahora en la memoria del contexto (prompt + respuesta generada), para reutilizar el prefijo
static llama_tokens g_cached_tokens;
// Tokens del prompt que se leyeron de verdad en la última pregunta (sin contar los reutilizados)
static int g_last_decoded_tokens = 0;
static llama_pos system_prompt_position = 0;
static llama_pos current_position = 0;
static llama_pos stop_generation_position = 0;
static std::string cached_token_chars;
static std::ostringstream assistant_ss;

static void reset_long_term_states(const bool clear_kv_cache = true) {
    chat_msgs.clear();
    system_prompt_position = 0;
    current_position = 0;

    if (clear_kv_cache) {
        g_cached_tokens.clear();
        if (g_context) llama_memory_clear(llama_get_memory(g_context), false);
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
Java_org_senda_browser_core_ai_llama_SendaLlamaBridge_nativeLoadModel(JNIEnv *env, jobject /*thiz*/, jstring jmodel_path, jint jload_mode, jint jlazy_mode, jint jgpu_layers, jstring jgpu_device) {
    std::lock_guard<std::mutex> lock(g_llama_mutex);
    if (!jmodel_path) return -1;

    llama_model_params model_params = llama_model_default_params();
    // Modo de carga elegido desde Kotlin (medido en el teléfono): con Q4_0 llama.cpp reordena los pesos para
    // los núcleos dotprod (repack) y con el archivo mapeado entero el teléfono guardaba las dos copias
    // (Gemma 4 E2B: 4,5 GB y Android cerraba otras apps)
    model_params.load_mode = (enum llama_load_mode) jload_mode;
    model_params.lazy_mode = (enum llama_lazy_mode) jlazy_mode;
    // Explícito: si hay GPU, llama.cpp manda por defecto todas las capas a ella. En un Adreno 619 eso escribe
    // 2,3 veces más lento; la calibración decide por teléfono (0 = todo en la CPU)
    model_params.n_gpu_layers = jgpu_layers;
    // Con varias GPU (p. ej. OpenCL y Vulkan sobre el mismo Adreno) se usa solo la elegida por la calibración
    static ggml_backend_dev_t selected_devices[2] = { nullptr, nullptr };
    if (jgpu_layers > 0 && jgpu_device) {
        const char *wanted = env->GetStringUTFChars(jgpu_device, nullptr);
        selected_devices[0] = ggml_backend_dev_by_name(wanted);
        env->ReleaseStringUTFChars(jgpu_device, wanted);
        if (!selected_devices[0]) {
            LOGE("GPU device not found, loading on CPU");
            model_params.n_gpu_layers = 0;
        } else {
            model_params.devices = selected_devices;
        }
    }
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
    // Contexto nuevo: olvidar los tokens del modelo anterior para no reutilizar un prefijo que ya no existe
    reset_long_term_states(true);
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

/**
 * Procesa la conversación completa (sistema + historial + pregunta) con la plantilla Jinja del propio GGUF.
 * La plantilla heredada (sin Jinja) no reconoce Gemma 4 ni desactiva el razonamiento de Qwen 3.5, y antes
 * solo llegaba la última pregunta: el modelo no veía el historial. No se usa shift_context: en modelos
 * híbridos (Qwen 3.5, DeltaNet) no se puede borrar una parte de la memoria, así que Kotlin recorta el
 * historial antes y aquí se rechaza lo que no cabe.
 * Devuelve 0 si está listo para generar, 1 si no cabe en el contexto, 2 si falla la plantilla o el decode.
 */
JNIEXPORT jint JNICALL
Java_org_senda_browser_core_ai_llama_SendaLlamaBridge_nativeProcessConversation(
        JNIEnv *env, jobject /*thiz*/, jobjectArray jroles, jobjectArray jcontents, jint n_predict, jboolean jthinking) {
    std::lock_guard<std::mutex> lock(g_llama_mutex);
    if (!g_context || !g_model || !g_sampler) return -1;

    // Sin borrar la memoria: se reutiliza el prefijo común con la conversación anterior (sistema + historial)
    reset_long_term_states(false);
    reset_short_term_states();
    common_sampler_reset(g_sampler);

    common_chat_templates_inputs inputs;
    const jsize n = env->GetArrayLength(jroles);
    for (jsize i = 0; i < n; i++) {
        auto jrole = (jstring) env->GetObjectArrayElement(jroles, i);
        auto jcontent = (jstring) env->GetObjectArrayElement(jcontents, i);
        const char *role = env->GetStringUTFChars(jrole, nullptr);
        const char *content = env->GetStringUTFChars(jcontent, nullptr);
        common_chat_msg msg;
        msg.role = role;
        msg.content = content;
        inputs.messages.push_back(msg);
        env->ReleaseStringUTFChars(jrole, role);
        env->ReleaseStringUTFChars(jcontent, content);
        env->DeleteLocalRef(jrole);
        env->DeleteLocalRef(jcontent);
    }
    inputs.add_generation_prompt = true;
    inputs.enable_thinking = jthinking;
    inputs.use_jinja = true;

    std::string prompt;
    try {
        prompt = common_chat_templates_apply(g_chat_templates.get(), inputs).prompt;
    } catch (const std::exception &e) {
        LOGW("Jinja template failed (%s), retrying with legacy template", e.what());
        try {
            inputs.use_jinja = false;
            prompt = common_chat_templates_apply(g_chat_templates.get(), inputs).prompt;
        } catch (const std::exception &e2) {
            LOGE("Chat template failed: %s", e2.what());
            return 2;
        }
    }

    const auto tokens = common_tokenize(g_context, prompt, true, true);
    if ((int) tokens.size() + n_predict > DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) {
        LOGE("Conversation too long: %d tokens + %d to predict", (int) tokens.size(), (int) n_predict);
        return 1;
    }
    // Reutilizar lo ya leído: cada pregunta reenvía sistema + historial y, sin esto, el teléfono volvía a leer
    // todo (~15-20 s con Gemma 4 E2B). Si la memoria no admite recortar desde ese punto (modelos híbridos
    // como Qwen 3.5), se borra y se lee entero. Siempre queda al menos un token por leer para tener logits
    size_t n_keep = 0;
    while (n_keep < g_cached_tokens.size() && n_keep + 1 < tokens.size() && g_cached_tokens[n_keep] == tokens[n_keep]) {
        n_keep++;
    }
    auto *mem = llama_get_memory(g_context);
    // Atención de ventana deslizante (Gemma): en conversaciones largas la memoria ya descartó posiciones
    // antiguas y reutilizar desde n_keep daría respuestas erróneas sin error. Misma regla que llama-server
    if (n_keep > 0) {
        const int n_swa = llama_model_n_swa(g_model);
        const llama_pos pos_min = llama_memory_seq_pos_min(mem, 0);
        if (pos_min < 0 || pos_min > std::max(0, (int) n_keep - n_swa)) n_keep = 0;
    }
    if (n_keep == 0 || !llama_memory_seq_rm(mem, 0, (llama_pos) n_keep, -1)) {
        llama_memory_clear(mem, false);
        n_keep = 0;
    }
    const llama_tokens pending(tokens.begin() + (long) n_keep, tokens.end());
    g_last_decoded_tokens = (int) pending.size();
    LOGI("Conversation: %d tokens, %d reused from cache", (int) tokens.size(), (int) n_keep);
    g_cached_tokens.clear();
    if (decode_tokens_in_batches(g_context, g_batch, pending, (llama_pos) n_keep, true)) {
        LOGE("Failed to decode conversation!");
        llama_memory_clear(mem, false);
        return 2;
    }
    g_cached_tokens = tokens;

    system_prompt_position = current_position = (int) tokens.size();
    stop_generation_position = current_position + n_predict;
    return 0;
}

/**
 * Dispositivos que ggml logró cargar en este teléfono, uno por línea: tipo|nombre|descripción|MB libres|MB totales.
 * Cada módulo comprueba sus propios requisitos (variante de CPU, versión de OpenCL, fabricante de la GPU) y no
 * se registra si no los cumple: la lista es lo que de verdad puede ejecutarse aquí.
 */
JNIEXPORT jstring JNICALL
Java_org_senda_browser_core_ai_llama_SendaLlamaBridge_nativeListDevices(JNIEnv *env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_llama_mutex);
    std::ostringstream out;
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        size_t free_mem = 0, total_mem = 0;
        ggml_backend_dev_memory(dev, &free_mem, &total_mem);
        const char *type = "OTHER";
        switch (ggml_backend_dev_type(dev)) {
            case GGML_BACKEND_DEVICE_TYPE_CPU:   type = "CPU"; break;
            case GGML_BACKEND_DEVICE_TYPE_GPU:   type = "GPU"; break;
            case GGML_BACKEND_DEVICE_TYPE_IGPU:  type = "IGPU"; break;
            case GGML_BACKEND_DEVICE_TYPE_ACCEL: type = "ACCEL"; break;
            default: break;
        }
        std::string desc = ggml_backend_dev_description(dev);
        for (auto &c : desc) if (c == '|' || c == '\n') c = ' ';
        out << type << '|' << ggml_backend_dev_name(dev) << '|' << desc << '|'
            << free_mem / (1024 * 1024) << '|' << total_mem / (1024 * 1024) << '\n';
    }
    return env->NewStringUTF(out.str().c_str());
}

/** Temperatura del muestreo; 0 = siempre el token más probable (lo usa la calibración para comparar CPU y GPU). */
JNIEXPORT void JNICALL
Java_org_senda_browser_core_ai_llama_SendaLlamaBridge_nativeSetTemperature(JNIEnv * /*env*/, jobject /*thiz*/, jfloat temp) {
    std::lock_guard<std::mutex> lock(g_llama_mutex);
    if (!g_model) return;
    if (g_sampler) common_sampler_free(g_sampler);
    g_sampler = new_sampler(temp);
}

JNIEXPORT jint JNICALL
Java_org_senda_browser_core_ai_llama_SendaLlamaBridge_nativeLastDecodedTokens(JNIEnv * /*env*/, jobject /*thiz*/) {
    return g_last_decoded_tokens;
}

JNIEXPORT void JNICALL
Java_org_senda_browser_core_ai_llama_SendaLlamaBridge_nativeSetThreads(JNIEnv * /*env*/, jobject /*thiz*/, jint n_gen, jint n_batch) {
    std::lock_guard<std::mutex> lock(g_llama_mutex);
    if (g_context) llama_set_n_threads(g_context, n_gen, n_batch);
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
    g_cached_tokens.push_back(new_token_id);

    if (llama_vocab_is_eog(llama_model_get_vocab(g_model), new_token_id)) {
        // Sin formatear: Kotlin manda la conversación completa en cada pregunta y la plantilla heredada
        // no reconoce todos los modelos (Gemma 4)
        common_chat_msg msg;
        msg.role = ROLE_ASSISTANT;
        msg.content = assistant_ss.str();
        chat_msgs.push_back(msg);
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
