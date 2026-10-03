/*
 * ygomc_ocg.cpp —— ygomc 的 ocgcore JNI 桥
 *
 * 职责边界（刻意做得很薄）：
 *   - 把 Java 调用转成 ocgcore 的 20 个 extern "C" 函数；
 *   - 提供两个 C 侧回调（脚本读取、卡数据读取），用进程内缓存避免每次过 JNI；
 *   - 把原生崩溃面收敛掉：脚本指针终身有效、缓冲区尺寸固定、异常不外泄。
 *
 * 刻意不做的事：
 *   - 不解析任何消息（那是 Java 侧 message 层的事）；
 *   - 不实现任何界面逻辑；
 *   - 不改动 ocgcore 语义。
 *
 * 线程模型（见 .agent/reference/05-protocol-verified.md 5.7）：
 *   - 一局一专用线程；process / set_response / query_* 全程不加锁；
 *   - create_duel / end_duel 触碰内核全局 duel_set，用 g_duel_mtx 串行化；
 *   - 卡表在开局前一次性灌入，读取用共享锁；脚本缓存用互斥锁 + 负缓存。
 *
 * 许可证：本项目 GPLv3。ocgcore 为 MIT（见 THIRD_PARTY_NOTICES.md）。
 */

#include <jni.h>

#include <atomic>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <mutex>
#include <shared_mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "ocgapi.h"
#include "card_data.h"

using duel_ptr_t = intptr_t;

namespace {

// ── 内核常量（ocgcore/common.h 里这几个是宏，所以本地改名避免冲突）──────────
constexpr int kMsgBuf      = 0x2000;   // common.h:30 SIZE_MESSAGE_BUFFER
constexpr int kRespBuf     = 256;      // common.h:31 SIZE_RETURN_VALUE
constexpr int kQueryBuf    = 0x4000;   // ygopro game.h:706 SIZE_QUERY_BUFFER
constexpr int kCardDataLen = 80;       // card_data 恰好 80 字节，无填充

static_assert(sizeof(card_data) == kCardDataLen,
              "card_data 必须恰好 80 字节，否则内核 memcpy 会越界");

// ── 进程级状态 ────────────────────────────────────────────────────────────
std::mutex        g_duel_mtx;      // create_duel / end_duel 串行化
std::mutex        g_script_mtx;    // 脚本缓存
std::shared_mutex g_card_mtx;      // 卡表（多读少写）

std::string g_script_root;         // 数据包里的 script 目录

// 节点式容器 → 元素地址在任何插入/再散列后仍然稳定。
// 内核会长期持有脚本指针（interpreter.cpp:135），所以这一点是硬要求。
std::unordered_map<std::string, std::vector<byte>> g_scripts;
std::unordered_map<uint32_t, card_data>            g_cards;

std::atomic<uint32_t> g_last_handler_type{0};
std::atomic<uint64_t> g_script_hit{0};
std::atomic<uint64_t> g_script_miss{0};
std::atomic<uint64_t> g_script_denied{0};
std::atomic<uint64_t> g_card_hit{0};
std::atomic<uint64_t> g_card_miss{0};

// Lua 错误文本。必须在回调内部当场取走：内核把错误写进 duel::strbuffer，
// 而那个缓冲会被下一条日志覆盖。
std::mutex  g_err_mtx;
std::string g_last_error;
std::atomic<uint64_t> g_err_count{0};

// ── 工具 ──────────────────────────────────────────────────────────────────
std::string to_utf8(JNIEnv* env, jstring s) {
    if (!s) return std::string();
    const char* p = env->GetStringUTFChars(s, nullptr);
    std::string out = p ? p : "";
    if (p) env->ReleaseStringUTFChars(s, p);
    return out;
}

void throw_state(JNIEnv* env, const std::string& what) {
    if (jclass c = env->FindClass("java/lang/IllegalStateException")) {
        env->ThrowNew(c, what.c_str());
    }
}

void throw_arg(JNIEnv* env, const std::string& what) {
    if (jclass c = env->FindClass("java/lang/IllegalArgumentException")) {
        env->ThrowNew(c, what.c_str());
    }
}

// 剥掉内核加的前缀：./script/x.lua、./expansions/x.lua、./x.lua
std::string strip_prefix(const char* raw) {
    std::string s = raw ? raw : "";
    for (char& c : s) {
        if (c == '\\') c = '/';
    }
    static const char* kPrefixes[] = {"./script/", "./expansions/", "./"};
    for (const char* p : kPrefixes) {
        size_t n = std::strlen(p);
        if (s.size() >= n && s.compare(0, n, p) == 0) {
            s.erase(0, n);
            break;
        }
    }
    return s;
}

// 只允许数据包目录内的相对路径，挡住 ../ 与绝对路径
bool is_safe_rel(const std::string& s) {
    if (s.empty() || s.size() > 512) return false;
    if (s.front() == '/') return false;
    if (s.size() >= 2 && s[1] == ':') return false;
    if (s.find("..") != std::string::npos) return false;
    return true;
}

bool read_file(const std::string& path, std::vector<byte>& out) {
    std::ifstream f(path, std::ios::binary | std::ios::ate);
    if (!f) return false;
    std::streamoff n = f.tellg();
    if (n < 0) return false;
    f.seekg(0, std::ios::beg);
    out.resize(static_cast<size_t>(n));
    if (n > 0 && !f.read(reinterpret_cast<char*>(out.data()), n)) {
        out.clear();
        return false;
    }
    return true;
}

// ── 回调 1：脚本读取 ──────────────────────────────────────────────────────
// 内核调用形如 ./script/constant.lua；返回的指针会被长期持有，所以必须指向缓存内部。
byte* cb_read_script(const char* name, int* out_len) {
    if (out_len) *out_len = 0;
    if (!name) return nullptr;

    const std::string rel = strip_prefix(name);
    if (!is_safe_rel(rel)) {
        g_script_denied.fetch_add(1, std::memory_order_relaxed);
        return nullptr;
    }

    std::lock_guard<std::mutex> lk(g_script_mtx);
    auto it = g_scripts.find(rel);
    if (it != g_scripts.end()) {
        if (it->second.empty()) {                  // 负缓存：确认过不存在
            g_script_miss.fetch_add(1, std::memory_order_relaxed);
            return nullptr;
        }
        g_script_hit.fetch_add(1, std::memory_order_relaxed);
        if (out_len) *out_len = static_cast<int>(it->second.size());
        return it->second.data();
    }

    std::vector<byte> data;
    const std::string path = g_script_root.empty() ? rel : (g_script_root + "/" + rel);
    if (read_file(path, data) && !data.empty()) {
        auto res = g_scripts.emplace(rel, std::move(data));
        it = res.first;
        g_script_hit.fetch_add(1, std::memory_order_relaxed);
        if (out_len) *out_len = static_cast<int>(it->second.size());
        return it->second.data();
    }

    g_script_miss.fetch_add(1, std::memory_order_relaxed);
    g_scripts.emplace(rel, std::vector<byte>{});    // 记住不存在，避免反复打盘
    return nullptr;
}

// ── 回调 2：卡数据读取 ────────────────────────────────────────────────────
// 开局前一次性灌入，之后只读；未收录的卡返回全零（内核会当白板卡处理）。
uint32_t cb_read_card(uint32_t code, card_data* out) {
    if (!out) return 0;
    {
        std::shared_lock<std::shared_mutex> lk(g_card_mtx);
        auto it = g_cards.find(code);
        if (it != g_cards.end()) {
            std::memcpy(out, &it->second, kCardDataLen);
            g_card_hit.fetch_add(1, std::memory_order_relaxed);
            return 1;
        }
    }
    std::memset(out, 0, kCardDataLen);
    g_card_miss.fetch_add(1, std::memory_order_relaxed);
    return 0;
}

// ── 回调 3：内核消息回调 ──────────────────────────────────────────────────
// 当前内核只发一个消息类型：
//   type == 1 → Lua/解释器错误（interpreter.cpp 里全部 8 个 handle_message 调用点）
//               触发前内核刚把错误文本 sprintf 进 duel::strbuffer，
//               也就是 get_log_message() 读的那个缓冲。
// 因此在回调内部立刻把它抄出来——晚一步就会被后续日志覆盖。
uint32_t cb_message(intptr_t pduel, uint32_t type) {
    g_last_handler_type.store(type, std::memory_order_relaxed);
    if (type == 1) {
        char buf[256] = {0};              // duel::strbuffer 就是 256 字节，会被截断
        get_log_message(pduel, buf);
        {
            std::lock_guard<std::mutex> lk(g_err_mtx);
            g_last_error = buf;
        }
        g_err_count.fetch_add(1, std::memory_order_relaxed);
    }
    return 0;
}

// 回调是进程级的，注册一次即可
void ensure_callbacks_registered() {
    static std::once_flag once;
    std::call_once(once, [] {
        set_script_reader(&cb_read_script);
        set_card_reader(&cb_read_card);
        set_message_handler(&cb_message);
    });
}

inline duel_ptr_t as_duel(jlong h) { return static_cast<duel_ptr_t>(h); }

}  // namespace

// ─────────────────────────────────────────────────────────────────────────
// JNI 入口
// ─────────────────────────────────────────────────────────────────────────

// boolean init(String scriptRoot)
static jboolean j_init(JNIEnv* env, jclass, jstring script_root) {
    try {
        std::string root = to_utf8(env, script_root);
        for (char& c : root) {
            if (c == '\\') c = '/';
        }
        while (!root.empty() && root.back() == '/') root.pop_back();

        {
            std::lock_guard<std::mutex> lk(g_script_mtx);
            if (root != g_script_root) {
                g_scripts.clear();
                g_script_hit = g_script_miss = g_script_denied = 0;
            }
            g_script_root = root;
        }
        ensure_callbacks_registered();
        return JNI_TRUE;
    } catch (const std::exception& e) {
        throw_state(env, std::string("Ocg.init 失败: ") + e.what());
        return JNI_FALSE;
    }
}

// int putCards(int[] codes, byte[] blob80)
// 批量灌入卡表；blob 必须是 codes.length * 80 字节，每 80 字节一个 card_data。
static jint j_putCards(JNIEnv* env, jclass, jintArray codes, jbyteArray blob) {
    if (!codes || !blob) {
        throw_arg(env, "putCards: 参数不能为 null");
        return 0;
    }
    const jsize n = env->GetArrayLength(codes);
    const jsize bytes = env->GetArrayLength(blob);
    if (n <= 0) return 0;
    if (bytes != n * kCardDataLen) {
        throw_arg(env, "putCards: blob 长度必须等于 codes.length * 80");
        return 0;
    }

    std::vector<jint> code_buf(static_cast<size_t>(n));
    std::vector<jbyte> data_buf(static_cast<size_t>(bytes));
    env->GetIntArrayRegion(codes, 0, n, code_buf.data());
    env->GetByteArrayRegion(blob, 0, bytes, data_buf.data());
    if (env->ExceptionCheck()) return 0;

    std::unique_lock<std::shared_mutex> lk(g_card_mtx);
    for (jsize i = 0; i < n; ++i) {
        card_data cd;
        std::memcpy(&cd, data_buf.data() + static_cast<size_t>(i) * kCardDataLen,
                    kCardDataLen);
        g_cards[static_cast<uint32_t>(code_buf[i])] = cd;
    }
    return n;
}

// void putCard(int code, byte[] one80)
static void j_putCard(JNIEnv* env, jclass, jint code, jbyteArray one) {
    if (!one || env->GetArrayLength(one) != kCardDataLen) {
        throw_arg(env, "putCard: 需要恰好 80 字节的 card_data");
        return;
    }
    card_data cd;
    env->GetByteArrayRegion(one, 0, kCardDataLen, reinterpret_cast<jbyte*>(&cd));
    if (env->ExceptionCheck()) return;
    std::unique_lock<std::shared_mutex> lk(g_card_mtx);
    g_cards[static_cast<uint32_t>(code)] = cd;
}

// void clearCards()
static void j_clearCards(JNIEnv*, jclass) {
    std::unique_lock<std::shared_mutex> lk(g_card_mtx);
    g_cards.clear();
    g_card_hit = g_card_miss = 0;
}

// int cardCount()
static jint j_cardCount(JNIEnv*, jclass) {
    std::shared_lock<std::shared_mutex> lk(g_card_mtx);
    return static_cast<jint>(g_cards.size());
}

// long create(int[] seeds)   —— 长度必须是 8
static jlong j_create(JNIEnv* env, jclass, jintArray seeds) {
    if (!seeds || env->GetArrayLength(seeds) != 8) {
        throw_arg(env, "create: seeds 必须是长度 8 的 int[]");
        return 0;
    }
    jint buf[8];
    env->GetIntArrayRegion(seeds, 0, 8, buf);
    if (env->ExceptionCheck()) return 0;

    uint32_t seq[8];
    for (int i = 0; i < 8; ++i) seq[i] = static_cast<uint32_t>(buf[i]);

    ensure_callbacks_registered();
    std::lock_guard<std::mutex> lk(g_duel_mtx);
    intptr_t h = create_duel_v2(seq);
    return static_cast<jlong>(h);
}

// void destroy(long handle)
static void j_destroy(JNIEnv* env, jclass, jlong handle) {
    if (handle == 0) {
        throw_arg(env, "destroy: handle 为 0");
        return;
    }
    std::lock_guard<std::mutex> lk(g_duel_mtx);
    end_duel(as_duel(handle));
}

// void setPlayerInfo(long h, int player, int lp, int hand, int draw)
static void j_setPlayerInfo(JNIEnv*, jclass, jlong h, jint player, jint lp, jint hand, jint draw) {
    set_player_info(as_duel(h), player, lp, hand, draw);
}

// void newCard(long h, int code, int owner, int player, int loc, int seq, int pos)
static void j_newCard(JNIEnv*, jclass, jlong h, jint code, jint owner, jint player,
                      jint loc, jint seq, jint pos) {
    new_card(as_duel(h), static_cast<uint32_t>(code),
             static_cast<uint8_t>(owner), static_cast<uint8_t>(player),
             static_cast<uint8_t>(loc), static_cast<uint8_t>(seq),
             static_cast<uint8_t>(pos));
}

// void startDuel(long h, int options)
static void j_startDuel(JNIEnv*, jclass, jlong h, jint options) {
    start_duel(as_duel(h), static_cast<uint32_t>(options));
}

// int process(long h)
static jint j_process(JNIEnv*, jclass, jlong h) {
    return static_cast<jint>(process(as_duel(h)));
}

// int getMessage(long h, byte[] out) —— out 至少 0x2000 字节
static jint j_getMessage(JNIEnv* env, jclass, jlong h, jbyteArray out) {
    if (!out || env->GetArrayLength(out) < kMsgBuf) {
        throw_arg(env, "getMessage: 缓冲至少需要 8192 字节");
        return 0;
    }
    static thread_local byte buf[kMsgBuf];
    const int32_t n = get_message(as_duel(h), buf);
    if (n > 0) env->SetByteArrayRegion(out, 0, n, reinterpret_cast<jbyte*>(buf));
    return n;
}

// void setResponseI(long h, int value)
static void j_setResponseI(JNIEnv*, jclass, jlong h, jint value) {
    set_responsei(as_duel(h), value);
}

// void setResponseB(long h, byte[] resp) —— 内核固定 memcpy 256 字节
static void j_setResponseB(JNIEnv* env, jclass, jlong h, jbyteArray resp) {
    byte buf[kRespBuf];
    std::memset(buf, 0, sizeof buf);
    if (resp) {
        const jsize n = env->GetArrayLength(resp);
        if (n > 0) {
            env->GetByteArrayRegion(resp, 0, n < kRespBuf ? n : kRespBuf,
                                    reinterpret_cast<jbyte*>(buf));
            if (env->ExceptionCheck()) return;
        }
    }
    set_responseb(as_duel(h), buf);
}

// int queryFieldCount(long h, int player, int location)
static jint j_queryFieldCount(JNIEnv*, jclass, jlong h, jint player, jint loc) {
    return query_field_count(as_duel(h), static_cast<uint8_t>(player), static_cast<uint8_t>(loc));
}

// int queryFieldCard(long h, int player, int loc, int flag, boolean useCache, byte[] out)
static jint j_queryFieldCard(JNIEnv* env, jclass, jlong h, jint player, jint loc,
                             jint flag, jboolean use_cache, jbyteArray out) {
    if (!out || env->GetArrayLength(out) < kQueryBuf) {
        throw_arg(env, "queryFieldCard: 缓冲至少需要 16384 字节");
        return 0;
    }
    static thread_local byte buf[kQueryBuf];
    const int32_t n = query_field_card(as_duel(h), static_cast<uint8_t>(player),
                                       static_cast<uint8_t>(loc), static_cast<uint32_t>(flag),
                                       buf, use_cache ? 1 : 0);
    if (n > 0) {
        env->SetByteArrayRegion(out, 0, n < kQueryBuf ? n : kQueryBuf,
                                reinterpret_cast<jbyte*>(buf));
    }
    return n;
}

// int queryFieldInfo(long h, byte[] out) —— 内核不接收长度参数，缓冲区必须给足
static jint j_queryFieldInfo(JNIEnv* env, jclass, jlong h, jbyteArray out) {
    if (!out || env->GetArrayLength(out) < kQueryBuf) {
        throw_arg(env, "queryFieldInfo: 缓冲至少需要 16384 字节");
        return 0;
    }
    static thread_local byte buf[kQueryBuf];
    std::memset(buf, 0, sizeof buf);
    const int32_t n = query_field_info(as_duel(h), buf);
    if (n > 0) {
        env->SetByteArrayRegion(out, 0, n < kQueryBuf ? n : kQueryBuf,
                                reinterpret_cast<jbyte*>(buf));
    }
    return n;
}

// byte[] queryCard(long h, int player, int loc, int seq, int flag, boolean useCache)
static jbyteArray j_queryCard(JNIEnv* env, jclass, jlong h, jint player, jint loc,
                              jint seq, jint flag, jboolean use_cache) {
    static thread_local byte buf[kQueryBuf];
    const int32_t n = query_card(as_duel(h), static_cast<uint8_t>(player),
                                 static_cast<uint8_t>(loc), static_cast<uint8_t>(seq),
                                 static_cast<uint32_t>(flag), buf, use_cache ? 1 : 0);
    if (n <= 0) return nullptr;
    const int32_t len = n < kQueryBuf ? n : kQueryBuf;
    jbyteArray out = env->NewByteArray(len);
    if (out) env->SetByteArrayRegion(out, 0, len, reinterpret_cast<jbyte*>(buf));
    return out;
}

// String getLogMessage(long h)
static jstring j_getLogMessage(JNIEnv* env, jclass, jlong h) {
    char buf[1024];
    std::memset(buf, 0, sizeof buf);
    get_log_message(as_duel(h), buf);
    return env->NewStringUTF(buf);   // 内核写的是 UTF-8
}

// int preloadScript(long h, String name)
static jint j_preloadScript(JNIEnv* env, jclass, jlong h, jstring name) {
    const std::string n = to_utf8(env, name);
    if (n.empty()) return 0;
    // 内核要求 ./script/ 前缀形式的路径
    std::string full = n;
    if (full.compare(0, 2, "./") != 0) full = "./script/" + full;
    return preload_script(as_duel(h), full.c_str());
}

// String stats() —— 给验证用，产品里可忽略
static jstring j_stats(JNIEnv* env, jclass) {
    char buf[640];
    std::snprintf(buf, sizeof buf,
                  "scripts: cache=%zu hit=%llu miss=%llu denied=%llu | "
                  "cards: cached=%zu hit=%llu miss=%llu | "
                  "lua_errors=%llu last_handler_msg=%u",
                  g_scripts.size(),
                  static_cast<unsigned long long>(g_script_hit.load()),
                  static_cast<unsigned long long>(g_script_miss.load()),
                  static_cast<unsigned long long>(g_script_denied.load()),
                  g_cards.size(),
                  static_cast<unsigned long long>(g_card_hit.load()),
                  static_cast<unsigned long long>(g_card_miss.load()),
                  static_cast<unsigned long long>(g_err_count.load()),
                  g_last_handler_type.load());
    return env->NewStringUTF(buf);
}

// long errorCount()
static jlong j_errorCount(JNIEnv*, jclass) {
    return static_cast<jlong>(g_err_count.load());
}

// String takeLastError() —— 取出并清空最近一条 Lua 错误文本
static jstring j_takeLastError(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lk(g_err_mtx);
    if (g_last_error.empty()) return nullptr;
    jstring s = env->NewStringUTF(g_last_error.c_str());
    g_last_error.clear();
    return s;
}

// void clearErrors()
static void j_clearErrors(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lk(g_err_mtx);
    g_last_error.clear();
    g_err_count.store(0);
}

// String buildInfo()
static jstring j_buildInfo(JNIEnv* env, jclass) {
    char buf[160];
    std::snprintf(buf, sizeof buf, "ygomc_ocg | built %s %s | %s | card_data=%d B",
                  __DATE__, __TIME__,
#ifdef _MSC_VER
                  "MSVC",
#else
                  "non-MSVC",
#endif
                  kCardDataLen);
    return env->NewStringUTF(buf);
}

// ─────────────────────────────────────────────────────────────────────────
// 注册
// ─────────────────────────────────────────────────────────────────────────
namespace {
const JNINativeMethod kMethods[] = {
    {const_cast<char*>("init"),           const_cast<char*>("(Ljava/lang/String;)Z"),  reinterpret_cast<void*>(j_init)},
    {const_cast<char*>("putCards"),       const_cast<char*>("([I[B)I"),                reinterpret_cast<void*>(j_putCards)},
    {const_cast<char*>("putCard"),        const_cast<char*>("(I[B)V"),                 reinterpret_cast<void*>(j_putCard)},
    {const_cast<char*>("clearCards"),     const_cast<char*>("()V"),                    reinterpret_cast<void*>(j_clearCards)},
    {const_cast<char*>("cardCount"),      const_cast<char*>("()I"),                    reinterpret_cast<void*>(j_cardCount)},
    {const_cast<char*>("create"),         const_cast<char*>("([I)J"),                  reinterpret_cast<void*>(j_create)},
    {const_cast<char*>("destroy"),        const_cast<char*>("(J)V"),                   reinterpret_cast<void*>(j_destroy)},
    {const_cast<char*>("setPlayerInfo"),  const_cast<char*>("(JIIII)V"),               reinterpret_cast<void*>(j_setPlayerInfo)},
    {const_cast<char*>("newCard"),        const_cast<char*>("(JIIIIII)V"),             reinterpret_cast<void*>(j_newCard)},
    {const_cast<char*>("startDuel"),      const_cast<char*>("(JI)V"),                  reinterpret_cast<void*>(j_startDuel)},
    {const_cast<char*>("process"),        const_cast<char*>("(J)I"),                   reinterpret_cast<void*>(j_process)},
    {const_cast<char*>("getMessage"),     const_cast<char*>("(J[B)I"),                 reinterpret_cast<void*>(j_getMessage)},
    {const_cast<char*>("setResponseI"),   const_cast<char*>("(JI)V"),                  reinterpret_cast<void*>(j_setResponseI)},
    {const_cast<char*>("setResponseB"),   const_cast<char*>("(J[B)V"),                 reinterpret_cast<void*>(j_setResponseB)},
    {const_cast<char*>("queryFieldCount"),const_cast<char*>("(JII)I"),                 reinterpret_cast<void*>(j_queryFieldCount)},
    {const_cast<char*>("queryFieldCard"), const_cast<char*>("(JIIIZ[B)I"),             reinterpret_cast<void*>(j_queryFieldCard)},
    {const_cast<char*>("queryFieldInfo"), const_cast<char*>("(J[B)I"),                 reinterpret_cast<void*>(j_queryFieldInfo)},
    {const_cast<char*>("queryCard"),      const_cast<char*>("(JIIIIZ)[B"),             reinterpret_cast<void*>(j_queryCard)},
    {const_cast<char*>("getLogMessage"),  const_cast<char*>("(J)Ljava/lang/String;"),  reinterpret_cast<void*>(j_getLogMessage)},
    {const_cast<char*>("preloadScript"),  const_cast<char*>("(JLjava/lang/String;)I"), reinterpret_cast<void*>(j_preloadScript)},
    {const_cast<char*>("stats"),          const_cast<char*>("()Ljava/lang/String;"),   reinterpret_cast<void*>(j_stats)},
    {const_cast<char*>("errorCount"),     const_cast<char*>("()J"),                    reinterpret_cast<void*>(j_errorCount)},
    {const_cast<char*>("takeLastError"),  const_cast<char*>("()Ljava/lang/String;"),   reinterpret_cast<void*>(j_takeLastError)},
    {const_cast<char*>("clearErrors"),    const_cast<char*>("()V"),                    reinterpret_cast<void*>(j_clearErrors)},
    {const_cast<char*>("buildInfo"),      const_cast<char*>("()Ljava/lang/String;"),   reinterpret_cast<void*>(j_buildInfo)},
};
constexpr char kClassName[] = "cn/xm1221/ygomc/ocg/Ocg";
}  // namespace

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) != JNI_OK || !env) {
        return JNI_ERR;
    }
    jclass cls = env->FindClass(kClassName);
    if (!cls) return JNI_ERR;
    const jint rc = env->RegisterNatives(
        cls, kMethods, static_cast<jint>(sizeof(kMethods) / sizeof(kMethods[0])));
    if (rc != JNI_OK) return JNI_ERR;
    return JNI_VERSION_1_8;
}
