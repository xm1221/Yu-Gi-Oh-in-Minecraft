package cn.xm1221.ygomc.common.ocg;

/**
 * 询问策略：那些「效果询问」什么时候摆给玩家。
 *
 * <h2>为什么需要它</h2>
 * 「是否发动效果」这类询问是按<b>时点</b>来的：每到一个时点内核就问一次。
 * 于是玩家会遇到「明明没有可以发动的效果，还是被问了好几遍」——
 * 那些时点的合法答案只有一个（不发动）。
 *
 * <p>官方客户端给的是<b>三个互斥按钮</b>加一个复选框，见
 * {@code duelclient.cpp:1836}（静默放弃的判据）与 {@code :1845}（必发自动发动）：
 * <pre>
 * if(!select_trigger &amp;&amp; !chain_forced
 *    &amp;&amp; (ignore_chain || ((count == 0 || specount == 0) &amp;&amp; !always_chain))
 *    &amp;&amp; (count == 0 || !chain_when_avail)) { SetResponseI(-1); ... }
 * if(chkAutoChain-&gt;isChecked() &amp;&amp; chain_forced &amp;&amp; !(always_chain || chain_when_avail)) { 选第一个必发 }
 * </pre>
 * 三个按钮的文案在 {@code strings.conf:346-348}：1292 忽略时点 / 1293 显示时点 / 1294 可用时点。
 *
 * <h2>默认值：都问（咩咩 2026-10-05 定）</h2>
 * 「唯一合法答案就自动答掉、不问真人」那条路<b>默认关闭</b>：
 * 一个询问哪怕答案唯一，也是玩家的回合在走，要由玩家自己按下去。
 * 想回到官方的省事行为，把 {@link #setAutoAnswerSoleChain}（配置里的
 * 「唯一合法答案自动应答」）打开即可——这正是官方的「可用时点」。
 *
 * <h2>线程</h2>
 * 这些字段会被对局线程读、被命令/配置线程写，取值都是单个 boolean，
 * 用 {@code volatile} 保证可见性即可，不需要锁——读到旧值最多是「这一条询问
 * 还按老规矩问」，下一刻就生效，不会算错应答。
 */
public final class DuelOptions {

    /** 对一条连锁询问要做的事。 */
    public enum ChainAction {
        /** 摆给玩家点。 */
        ASK,
        /** 不问，直接回「不发动」。 */
        DECLINE,
        /** 不问，直接选第一个必发效果。 */
        PICK_FIRST
    }

    /**
     * 唯一合法答案（没有候选项的时点）自动答掉「不发动」，不摆给玩家。
     *
     * <p>默认 <b>false</b>＝都问（咩咩 2026-10-05）。打开它等于官方的「可用时点」
     * （{@code chain_when_avail}，1294）：只在真有候选项时才问。
     */
    private static volatile boolean autoAnswerSoleChain = false;

    /**
     * 忽略时点：连锁/发动询问一律不问，直接放弃。
     *
     * <p>对应官方 {@code ignore_chain}（1292「忽略时点」）。默认关——
     * 开了之后玩家连「我要不要发动」都不会被问，等于替玩家做了决定。
     */
    private static volatile boolean ignoreChainTiming = false;

    /**
     * 必发效果自动发动（对应官方 {@code chkAutoChain}，{@code duelclient.cpp:1845}）。
     *
     * <p>默认关：强制连锁虽然没有别的选择，但「看着它发动」本身就是信息
     * （谁先谁后、连锁几层）。打开后由 {@code PlayerResponder} 选第一个必发项。
     */
    private static volatile boolean autoForcedChain = false;

    /**
     * 内核自己发动的必发效果要不要在界面上告知玩家（默认开）。
     *
     * <p>与上面三个开关不同，它<b>不改变任何作答</b>：提示没有答案，
     * 玩家按「确认」只把提示收起来。判定见 {@code MandatoryEffect.shouldNotify}。
     */
    private static volatile boolean notifyMandatoryEffects = true;

    private DuelOptions() {
    }

    /**
     * 这条连锁询问要不要真的摆给玩家。
     *
     * @param forced     是否含必发效果（{@code Msg.SelectChain.hasForced()}）
     * @param candidates 内核给出的可选连锁项数量（不含「不发动」）
     */
    public static ChainAction chainAction(boolean forced, int candidates) {
        if (forced) {
            // 必发：没有「不发动」这个合法答案，所以只可能是「问」或「替他选第一个」。
            // 默认问——「必发」不等于「玩家想看它发动」这件事可以省略（咩咩 2026-10-05）。
            return autoForcedChain ? ChainAction.PICK_FIRST : ChainAction.ASK;
        }
        if (ignoreChainTiming) {
            return ChainAction.DECLINE;
        }
        if (candidates > 0) {
            return ChainAction.ASK;
        }
        // 没有候选项：唯一合法答案就是「不发动」。默认仍然问（把这一条摆出去）。
        return autoAnswerSoleChain ? ChainAction.DECLINE : ChainAction.ASK;
    }

    public static boolean autoAnswerSoleChain() {
        return autoAnswerSoleChain;
    }

    public static void setAutoAnswerSoleChain(boolean v) {
        autoAnswerSoleChain = v;
    }

    public static boolean ignoreChainTiming() {
        return ignoreChainTiming;
    }

    public static void setIgnoreChainTiming(boolean v) {
        ignoreChainTiming = v;
    }

    public static boolean autoForcedChain() {
        return autoForcedChain;
    }

    public static void setAutoForcedChain(boolean v) {
        autoForcedChain = v;
    }

    public static boolean notifyMandatoryEffects() {
        return notifyMandatoryEffects;
    }

    public static void setNotifyMandatoryEffects(boolean v) {
        notifyMandatoryEffects = v;
    }

    /** 当前策略的一行摘要，供命令回执与日志使用。 */
    public static String describe() {
        return "唯一合法答案自动应答=" + autoAnswerSoleChain
                + "　忽略时点=" + ignoreChainTiming
                + "　必发自动发动=" + autoForcedChain
                + "　必发提示=" + notifyMandatoryEffects;
    }
}
