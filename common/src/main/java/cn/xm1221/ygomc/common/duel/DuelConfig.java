package cn.xm1221.ygomc.common.duel;

import cn.xm1221.ygomc.common.ocg.DuelOptions;
import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.ConfigHolder;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;

/**
 * 服务端配置（<b>Cloth Config / AutoConfig</b>）。
 *
 * <p>为什么不自己写：第一版我手搓了一个 {@code config/ygomc.json} 的读写
 * （Gson 解析 + mtime 判断重读 + 自己写默认文件），咩咩一句话点掉——
 * 「配置用 cloth config，乱写」。配置这件事有现成的、整个模组圈通用的实现，
 * 自己造一份只会多出一套要维护、要单测、语义还跟别人不一样的读写。
 *
 * <p>文件位置就是 AutoConfig 的约定：服务器目录下的 {@code config/ygomc.json}。
 * 这里的每一项都是<b>规则</b>（超时判负、什么时候问效果）而不是客户端外观，
 * 所以以服务端的值为准。加字段时旧配置文件照样能读——缺的字段拿默认值。
 */
@Config(name = "ygomc")
public class DuelConfig implements ConfigData {

    /** 超时判负的默认秒数。咩咩定的：默认 100 秒。 */
    public static final int DEFAULT_TIMEOUT_SECONDS = 100;

    /**
     * 有人轮到该他操作却一直不动，多少秒后判他负。
     *
     * <p>超时只<b>判负</b>，绝不替他作答——不许替玩家做决定。
     * 0 或负数会被 {@link #saneSeconds} 当作「没配」，退回默认值。
     */
    public int duelTimeoutSeconds = DEFAULT_TIMEOUT_SECONDS;

    /**
     * 「效果询问」这一组：什么时候问、问到哪一步。
     *
     * <p>对应官方客户端右上角那三个互斥按钮与一个复选框
     * （{@code strings.conf:346-348} 的 1292 忽略时点 / 1293 显示时点 / 1294 可用时点，
     * 判据在 {@code duelclient.cpp:1836}；复选框是 {@code :1845} 的 {@code chkAutoChain}）。
     */
    @ConfigEntry.Gui.CollapsibleObject
    public EffectPrompt effectPrompt = new EffectPrompt();

    /** 见 {@link #effectPrompt}。 */
    public static class EffectPrompt {

        /**
         * 唯一合法答案（没有候选项的时点）自动答掉「不发动」，不摆给玩家。
         *
         * <p><b>默认 false ＝ 都问</b>（咩咩 2026-10-05）。开着它等于官方的
         * 「可用时点」（{@code chain_when_avail}，1294）：只在真有可发动的效果时才问。
         * 关着的时候，那些「只有不发动一个答案」的时点也会摆出来让玩家自己按一下——
         * 一个询问哪怕答案唯一，也是玩家的回合在走。
         */
        public boolean autoAnswerSoleChain = false;

        /**
         * 忽略时点：连锁/发动询问一律不问，直接放弃。
         *
         * <p>默认 false。对应官方 {@code ignore_chain}（1292「忽略时点」）。
         * 开了之后玩家连「要不要发动」都不会被问，等于替玩家做了决定，所以默认关。
         */
        public boolean ignoreChainTiming = false;

        /**
         * 必发效果自动发动，不再询问。
         *
         * <p>默认 false。对应官方 {@code chkAutoChain}（{@code duelclient.cpp:1845}）。
         * 强制连锁虽然没有别的选择，但「看着它发动」本身就是信息（谁先谁后、连锁几层），
         * 所以默认仍然问；咩咩 2026-10-05：必发也要问，只是界面上只有「确认」。
         */
        public boolean autoForcedChain = false;

        /**
         * 内核<b>自己发动</b>的必发效果，要不要在界面上告知玩家。
         *
         * <p>默认 true＝提示（咩咩 2026-10-05）。判据在 {@code MandatoryEffect}：连锁的
         * 发动者是我方、而且这条连锁不是玩家刚回答某个询问（「要不要发动」/
         * 「发动哪个效果」）造成的——也就是内核没问过就直接发动的那一种。
         * 玩家自己点了发动的不提示（他知道自己按了什么），对手那边的也不提示。
         *
         * <p>提示<b>只告知，不回任何答案</b>：界面上只有一颗「确认」，按下去只把提示
         * 收起来。ygo 官方客户端在这件事上是什么都不显示的（{@code duelclient.cpp:3010}
         * 只放音效和连锁动画），所以关掉这个开关只是变回官方表现，不影响对局。
         */
        public boolean notifyMandatoryEffects = true;

        /** 一行摘要，供命令回执与日志使用。 */
        public String describe() {
            return "唯一合法答案自动应答=" + autoAnswerSoleChain
                    + "　忽略时点=" + ignoreChainTiming
                    + "　必发自动发动=" + autoForcedChain
                    + "　必发提示=" + notifyMandatoryEffects;
        }
    }

    private static ConfigHolder<DuelConfig> holder;

    /** 注册配置。由公共入口 {@code Ygomc.init()} 在启动时调一次（两个平台都走那里）。 */
    public static synchronized void init() {
        if (holder == null) {
            holder = AutoConfig.register(DuelConfig.class, GsonConfigSerializer::new);
        }
        apply(holder.getConfig());
    }

    /**
     * 当前配置。
     *
     * <p>每次都把「效果询问」那一组推给运行期策略（{@link DuelOptions}）：
     * Cloth 的配置界面存盘后不会通知我们，而 {@link #timeoutSeconds()} 每道询问都会走到这里，
     * 于是改完配置最迟下一道询问就生效。推的是同一批值，重复推没有副作用。
     */
    public static DuelConfig get() {
        init();
        DuelConfig c = holder.getConfig();
        // 手改 JSON 可能把这一组写成 null（Gson 照单全收）：补上再推，别在别处到处判空。
        if (c.effectPrompt == null) {
            c.effectPrompt = new EffectPrompt();
        }
        apply(c);
        return c;
    }

    /** 生效的超时判负秒数。 */
    public static int timeoutSeconds() {
        return saneSeconds(get().duelTimeoutSeconds);
    }

    /**
     * 0 或负数当作没配，退回默认。
     *
     * <p>抽成纯函数是为了能离线钉住它：配置文件是手写的，
     * 一个 0 秒判负的值只会让人莫名其妙地输掉，不能照单全收。
     */
    public static int saneSeconds(int raw) {
        return raw > 0 ? raw : DEFAULT_TIMEOUT_SECONDS;
    }

    // ── 命令侧：改一项、存盘、并立刻推给运行期策略 ──────────────────────────

    /** 设置「唯一合法答案自动应答」。 */
    public static void setAutoAnswerSoleChain(boolean v) {
        get().effectPrompt.autoAnswerSoleChain = v;
        DuelOptions.setAutoAnswerSoleChain(v);
        saveQuietly();
    }

    /** 设置「忽略时点」。 */
    public static void setIgnoreChainTiming(boolean v) {
        get().effectPrompt.ignoreChainTiming = v;
        DuelOptions.setIgnoreChainTiming(v);
        saveQuietly();
    }

    /** 设置「必发自动发动」。 */
    /** 设置「必发自动发动」。 */
    public static void setAutoForcedChain(boolean v) {
        get().effectPrompt.autoForcedChain = v;
        DuelOptions.setAutoForcedChain(v);
        saveQuietly();
    }

    /** 设置「必发提示」：内核自己发动的必发效果要不要在界面上告知。 */
    public static void setNotifyMandatoryEffects(boolean v) {
        get().effectPrompt.notifyMandatoryEffects = v;
        DuelOptions.setNotifyMandatoryEffects(v);
        saveQuietly();
    }

    /** 当前「效果询问」策略的一行摘要。 */
    public static String describeEffectPrompt() {
        DuelConfig c = get();
        return c.effectPrompt == null ? new EffectPrompt().describe() : c.effectPrompt.describe();
    }

    /**
     * 把这组开关推给运行期策略。
     *
     * <p>策略（{@link DuelOptions}）刻意不依赖 Cloth：离线自检要能直接验
     * 「默认都问」「打开开关就自动答」这些规则，注册 AutoConfig 需要平台环境。
     */
    public static void apply(DuelConfig c) {
        EffectPrompt p = c.effectPrompt == null ? new EffectPrompt() : c.effectPrompt;
        DuelOptions.setAutoAnswerSoleChain(p.autoAnswerSoleChain);
        DuelOptions.setIgnoreChainTiming(p.ignoreChainTiming);
        DuelOptions.setAutoForcedChain(p.autoForcedChain);
        DuelOptions.setNotifyMandatoryEffects(p.notifyMandatoryEffects);
    }

    private static void saveQuietly() {
        try {
            holder.save();
        } catch (RuntimeException e) {
            // 写盘失败不该让命令报错：设置本身已经生效，重启后会回到文件里的值。
            org.slf4j.LoggerFactory.getLogger("ygomc/config")
                    .warn("配置写盘失败（本次运行内仍然生效）：{}", e.toString());
        }
    }
}
