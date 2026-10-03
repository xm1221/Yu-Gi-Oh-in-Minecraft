package cn.xm1221.ygomc.common.ocg;

/**
 * ocgcore 的 JNI 绑定。
 *
 * <p>这是「引擎层」的最底一层：只做 Java ↔ 原生函数的一对一转发，不含任何
 * 消息解析、界面或业务逻辑。上层 {@code OcgDuel} 负责把一局封装成对象。
 *
 * <h2>线程模型</h2>
 * 内核的进程级可变全局只有四处（{@code ocgapi.cpp:25-29}）：
 * {@code sreader / creader / mhandler / duel_set}。前三个是只读回调指针，
 * {@code duel_set} 只在 {@link #create} 和 {@link #destroy} 里改动。
 * 因此：
 * <ul>
 *   <li>除 {@link #create} / {@link #destroy} 外，本类所有方法都<b>不加锁</b>，
 *       可以安全地每局一个专用线程并发调用；</li>
 *   <li>{@link #init} 与 {@link #putCards} 必须在任何 {@link #create} 之前完成。</li>
 * </ul>
 * 实测：16 局并发在同一进程内各自跑完一整局，无异常（见 .agent/m0/ocg_stress.py）。
 *
 * <h2>应答编码（最容易搞错的地方）</h2>
 * 单选类命令的应答是 <b>{@code (子序号 << 16) | 类型}</b> —— 类型在<b>低</b> 16 位；
 * 多选类命令的应答是 <b>{@code [u8 数量, u8 下标...]}</b> —— 下标是 1 字节。
 * 详见 .agent/reference/05-protocol-verified.md 5.2。
 *
 * <h2>原生库</h2>
 * 本类由 {@code ygomc_ocg.dll} 提供实现，后者动态依赖 {@code ocgcore.dll}。
 * 加载顺序必须是先 {@code ocgcore.dll} 再 {@code ygomc_ocg.dll}
 * （Windows 加载器按模块名复用已加载模块）。见 {@link #load}。
 */
public final class Ocg {

    private Ocg() {}

    /** {@link #getMessage} 需要的缓冲下限（内核 {@code SIZE_MESSAGE_BUFFER}）。 */
    public static final int SIZE_MESSAGE_BUFFER = 0x2000;
    /** {@link #queryFieldInfo} / {@link #queryFieldCard} 需要的缓冲下限。 */
    public static final int SIZE_QUERY_BUFFER = 0x4000;
    /** 一个 {@code card_data} 的字节数（内核结构体恰好 80 字节，无填充）。 */
    public static final int CARD_DATA_SIZE = 80;

    // ── 区域（ocgcore/common.h）────────────────────────────────────────────
    public static final int LOCATION_DECK = 0x01;
    public static final int LOCATION_HAND = 0x02;
    public static final int LOCATION_MZONE = 0x04;
    public static final int LOCATION_SZONE = 0x08;
    public static final int LOCATION_GRAVE = 0x10;
    public static final int LOCATION_REMOVED = 0x20;
    public static final int LOCATION_EXTRA = 0x40;
    public static final int LOCATION_OVERLAY = 0x80;
    public static final int LOCATION_PZONE = 0x200;

    // ── 表示形式 ──────────────────────────────────────────────────────────
    public static final int POS_FACEUP_ATTACK = 0x1;
    public static final int POS_FACEDOWN_ATTACK = 0x2;
    public static final int POS_FACEUP_DEFENSE = 0x4;
    public static final int POS_FACEDOWN_DEFENSE = 0x8;

    // ── process() 返回值 ──────────────────────────────────────────────────
    /** 低 28 位：本次可取走的消息字节数。 */
    public static final int PROCESSOR_BUFFER_LEN = 0x0FFFFFFF;
    /** 引擎正在等待玩家应答。 */
    public static final int PROCESSOR_WAITING = 0x10000000;
    /**
     * 引擎内部流程结束。注意：<b>对局分出胜负时并不会返回这个标志</b>，
     * 收局信号是消息 {@code MSG_WIN}。见 .agent/reference/05-protocol-verified.md 5.4。
     */
    public static final int PROCESSOR_END = 0x20000000;

    /** 大师规则 2020，写进 {@code options} 的高 16 位。 */
    public static final int CURRENT_RULE = 5;

    // ── 生命周期 ──────────────────────────────────────────────────────────

    /**
     * 设置脚本根目录并注册内核回调。幂等，但脚本根变化时会清空脚本缓存。
     *
     * @param scriptRoot 数据包内的 {@code script} 目录绝对路径
     */
    public static native boolean init(String scriptRoot);

    // ── 卡表 ──────────────────────────────────────────────────────────────

    /**
     * 批量灌入卡表。必须在 {@link #create} 之前调用。
     *
     * @param codes 卡号数组
     * @param blob80 与 {@code codes} 等长的 80 字节结构体拼接，长度必须是 {@code codes.length * 80}
     * @return 实际写入张数
     */
    public static native int putCards(int[] codes, byte[] blob80);

    /** 单张写入（数据包热更新用）。 */
    public static native void putCard(int code, byte[] cardData80);

    public static native void clearCards();

    public static native int cardCount();

    // ── 单局 ──────────────────────────────────────────────────────────────

    /** @param seeds 长度必须是 8；同一副种子产生同一局 */
    public static native long create(int[] seeds);

    public static native void destroy(long handle);

    public static native void setPlayerInfo(long handle, int player, int lp, int startHand, int drawCount);

    public static native void newCard(long handle, int code, int owner, int player,
                                      int location, int sequence, int position);

    /** @param options {@code (rule << 16) | 标志位}，例如 {@code (5 << 16)} */
    public static native void startDuel(long handle, int options);

    /** @return {@code (标志位) | (消息长度)}；见 {@link #PROCESSOR_WAITING} 等 */
    public static native int process(long handle);

    /**
     * 取走并清空引擎的输出缓冲。缓冲里是<b>多条消息首尾拼接</b>，需要自行按消息长度走纸带。
     *
     * @param out 至少 {@link #SIZE_MESSAGE_BUFFER} 字节
     * @return 实际写入的字节数
     */
    public static native int getMessage(long handle, byte[] out);

    public static native void setResponseI(long handle, int value);

    public static native void setResponseB(long handle, byte[] resp);

    // ── 查询 ──────────────────────────────────────────────────────────────

    public static native int queryFieldCount(long handle, int player, int location);

    /** @param out 至少 {@link #SIZE_QUERY_BUFFER} 字节 */
    public static native int queryFieldCard(long handle, int player, int location,
                                            int queryFlag, boolean useCache, byte[] out);

    /** 取整场快照（一个完整 {@code MSG_RELOAD_FIELD}）。@param out 至少 {@link #SIZE_QUERY_BUFFER} 字节 */
    public static native int queryFieldInfo(long handle, byte[] out);

    public static native byte[] queryCard(long handle, int player, int location, int sequence,
                                          int queryFlag, boolean useCache);

    public static native String getLogMessage(long handle);

    public static native int preloadScript(long handle, String name);

    /** 诊断用：脚本/卡表缓存命中与内核回调计数。 */
    public static native String stats();

    /**
     * 截至目前内核上报的脚本错误次数。
     *
     * <p>内核唯一的错误上报通道是 {@code message_handler(type=1)}，它只在
     * {@code interpreter.cpp} 的 Lua 执行路径上触发（脚本语法错、运行时错、
     * 调用不存在的函数、参数个数不符等）。<b>12000+ 张卡脚本里出问题很正常</b>，
     * 所以这个通道必须接住，否则出错是完全静默的。
     */
    public static native long errorCount();

    /**
     * 取出并清空最近一条脚本错误文本；没有则返回 null。
     *
     * <p>错误文本来自内核的 {@code duel::strbuffer}，容量只有 256 字节，会被截断。
     * 这条文本在回调内部就被抄走了——因为那个缓冲会被后续日志覆盖。
     */
    public static native String takeLastError();

    public static native void clearErrors();

    public static native String buildInfo();

    // ── 加载 ──────────────────────────────────────────────────────────────

    private static volatile boolean loaded;

    /**
     * 按正确顺序加载两个原生库。
     *
     * <p>顺序是有意的：{@code ygomc_ocg.dll} 动态依赖 {@code ocgcore.dll}，
     * 而 Windows 的依赖解析只看「模块名是否已在本进程内」，不看「是否同目录」。
     * 所以必须先显式加载 {@code ocgcore.dll}。
     *
     * @param ocgCoreDll {@code ocgcore.dll} 绝对路径
     * @param bridgeDll  {@code ygomc_ocg.dll} 绝对路径
     */
    public static synchronized void load(String ocgCoreDll, String bridgeDll) {
        if (loaded) {
            return;
        }
        System.load(ocgCoreDll);
        System.load(bridgeDll);
        loaded = true;
    }

    public static boolean isLoaded() {
        return loaded;
    }
}
