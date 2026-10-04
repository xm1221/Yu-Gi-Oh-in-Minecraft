package cn.xm1221.ygomc.common.data;

import dev.architectury.platform.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 数据包的运行期持有者。
 *
 * <h2>为什么要单独一层，而不是直接调 {@link DataPack#openDefault}</h2>
 * {@link DataPack} 本身刻意<b>不依赖任何平台 API</b>——它的定位逻辑
 * （{@link DataPack#candidates}）只做路径拼接，可以脱离 Minecraft 单独编译和测试
 * （{@code .agent/m1} 下的检查程序就是这么用的）。真正需要平台 API 的只有一件事：
 * 「游戏目录在哪」。把那一句隔离在这里，{@code DataPack} 就能保持纯粹。
 *
 * <h2>为什么懒加载，而不是在模组构造阶段就打开</h2>
 * 构造阶段调用平台 API 有踩空的风险，而数据包<b>不是</b>模组能不能加载的前提
 * （缺文件是正常情况，见 {@link DataPack} 的类注释）。
 * 所以打开时机定在「第一次真的要用」——但 {@link #init()} 会在启动时主动触发一次，
 * 这样用户在日志里立刻能看到数据包是否就绪，而不必等到某次交互失败才发现。
 *
 * <h2>线程安全</h2>
 * 卡牌查询会被客户端渲染线程和服务端逻辑线程同时调用（例如工具提示在渲染线程上
 * 拼卡名），所以这里用双重检查加锁。{@link DataPack} 内部只读、且卡图走
 * {@code FileChannel} 位置式读取，因此拿到实例之后不需要再加锁。
 */
public final class DataPacks {

    private static final Logger LOGGER = LoggerFactory.getLogger("ygomc/datapack");

    private static volatile DataPack instance;

    private DataPacks() {
    }

    /**
     * 取数据包，必要时打开。
     *
     * <p>永远返回非 null：数据包缺失时返回的是一个三份文件都为 null 的实例，
     * 调用方用 {@link DataPack#problems()} 或各 getter 的 null 判断即可，
     * 不必到处写空值检查。
     */
    public static DataPack get() {
        DataPack pack = instance;
        if (pack == null) {
            synchronized (DataPacks.class) {
                pack = instance;
                if (pack == null) {
                    // 只在没有显式指定时才去问 Architectury 要游戏目录：
                    // `Platform.getGameFolder()` 脱离 Minecraft 会直接抛断言
                    // （Architectury 的 Platform.java:86），而它是**在实参位置被求值**的，
                    // 所以哪怕用户写了 -Dygomc.datapack 也照样炸。
                    // 修好这一处，`OcgDuel.playOut` 这条生产路径才能离线跑。
                    String override = System.getProperty(DataPack.PROPERTY);
                    boolean explicit = override != null && !override.isBlank();
                    pack = DataPack.openDefault(explicit ? null : Platform.getGameFolder());
                    instance = pack;
                }
            }
        }
        return pack;
    }

    /**
     * 启动时主动打开一次并打日志。
     *
     * <p>由 {@link cn.xm1221.ygomc.common.Ygomc#init()} 调用。这里的意义只有「让状态可见」——
     * 数据包缺失是预期情况，所以只记日志，不抛异常、不阻断加载。
     */
    public static void init() {
        DataPack pack = get();
        if (pack.problems().isEmpty()) {
            LOGGER.info("数据包就绪: {}（{} 张卡，卡图 {}）",
                    pack.dir().toAbsolutePath(),
                    pack.cardData() == null ? "?" : pack.cardData().size(),
                    pack.cardImages() == null ? "不可用" : "已索引");
        } else {
            // 逐条打印：problems() 里第一条在「一个候选都没找到」时是多行文本，
            // 里面本来就带了每个查找过的位置，直接原样输出最有用。
            for (String problem : pack.problems()) {
                LOGGER.warn("数据包不完整: {}", problem);
            }
        }
    }

    /**
     * 关掉当前实例，下次 {@link #get()} 重新打开。
     *
     * <p>用户重新生成数据包后不必重启游戏。注意：已渲染并缓存的卡图纹理不会因此失效，
     * 那是渲染层要自己处理的事。
     */
    public static synchronized void reload() {
        DataPack old = instance;
        instance = null;
        if (old != null) {
            try {
                old.close();
            } catch (Exception e) {
                LOGGER.warn("关闭旧数据包时出错（忽略）: {}", e.toString());
            }
        }
    }

    // ── description → 文本（照官方 data_manager.cpp:267-278，阈值与拆法已用本地数据验证）──

    /** 官方 {@code MAX_STRING_ID = 0x7ff}。小于等于它的 description 是系统文本编号。 */
    public static final int MAX_STRING_ID = 0x7ff;

    /**
     * 把引擎给的 {@code description} / {@code edesc} / {@code strCode} 解码成人能看懂的文本。
     *
     * <pre>
     *   description &lt;= 2047  → strings.conf 的 !system &lt;description&gt;
     *   否则                 → 卡号 = (description &gt;&gt;&gt; 4) &amp; 0x0fffffff
     *                          取该卡 texts 表的 str((description &amp; 0xf) + 1)
     * </pre>
     *
     * <p>阈值与拆分不是照抄文档，是用本地数据独立验证过的，推导见
     * {@code tools/mkdatapack.py} 里 {@code MAX_STRING_ID} 上方那段注释。
     *
     * <p><b>本方法不抛异常</b>，兜底顺序是：
     * <pre>
     *   &lt;= 2047 查到系统文本 → 用它
     *   &lt;= 2047 查不到       → "系统文本 " + description
     *   拆分后说明非空       → 用它
     *   拆分后说明为空/无此卡 → "说明 " + description
     * </pre>
     * 留着「说明 N」这层是为了让<b>「数据没装」和「代码写错」在界面上长得不一样</b>：
     * 数据没装时稳定显示「系统文本/说明 N」；代码写错（偏移或位运算搞反）则会算出个荒唐的
     * 卡号，显示成某张无关的卡名甚至空白——一眼能分辨。
     *
     * <p>注意实测有 7174 张卡（48%）的 str1..str16 全是空的，所以「拆分后有说明」这条
     * 必须真的检查内容非空，不能只看卡号存在。
     *
     * @return 人看的文本；{@code description <= 0} 时返回 null（调用方应显示成「无说明」）
     */
    public static String desc(int description) {
        if (description <= 0) {
            return null;
        }
        DataPack pack = get();
        if (description <= MAX_STRING_ID) {
            StringsDb strings = pack.strings();
            String sys = strings == null ? null : strings.sys(description);
            return (sys == null || sys.isEmpty()) ? "系统文本 " + description : sys;
        }
        int code = (description >>> 4) & 0x0FFFFFFF;
        int n = (description & 0xF) + 1;
        CardTextDb texts = pack.cardText();
        String s = texts == null ? null : texts.str(code, n);
        return (s == null || s.isEmpty()) ? "说明 " + description : s;
    }

    /**
     * 属性名。
     *
     * @param bit 内核的属性位掩码（EARTH = 1、WATER = 2、…、DEVINE = 0x40）
     * @return 查不到时退成 {@code "属性 0x40"}——界面上的「位 0x40」正是缺了这张表
     */
    public static String attributeName(int bit) {
        StringsDb s = get().strings();
        String v = s == null ? null : s.attributeName(bit);
        return (v == null || v.isEmpty()) ? "属性 0x" + Integer.toHexString(bit) : v;
    }

    /** @param bit 种族位掩码（内核顺序） @return 种族名；查不到时退成 {@code "种族 0x…"} */
    public static String raceName(int bit) {
        StringsDb s = get().strings();
        String v = s == null ? null : s.raceName(bit);
        return (v == null || v.isEmpty()) ? "种族 0x" + Integer.toHexString(bit) : v;
    }

    /** @param bit 类型位掩码（内核顺序） @return 类型名；查不到时退成 {@code "类型 0x…"} */
    public static String typeName(int bit) {
        StringsDb s = get().strings();
        String v = s == null ? null : s.typeName(bit);
        return (v == null || v.isEmpty()) ? "类型 0x" + Integer.toHexString(bit) : v;
    }

    /**
     * 指示物名。
     *
     * @param type {@code SELECT_COUNTER} 里的指示物类型
     * @return 查不到时退成 {@code "指示物 3"}——原先界面上只有「可放 N 个」而没有名字
     */
    public static String counterName(int type) {
        StringsDb s = get().strings();
        String v = s == null ? null : s.counter(type);
        return (v == null || v.isEmpty()) ? "指示物 " + type : v;
    }

    /**
     * 胜负原因。
     *
     * @param reason 内核给的胜负原因编号（{@code !victory 0x…}）
     * @return 查不到时退成 {@code "胜负原因 3"}
     */
    public static String victoryName(int reason) {
        StringsDb s = get().strings();
        String v = s == null ? null : s.victory(reason);
        return (v == null || v.isEmpty()) ? "胜负原因 " + reason : v;
    }
}
