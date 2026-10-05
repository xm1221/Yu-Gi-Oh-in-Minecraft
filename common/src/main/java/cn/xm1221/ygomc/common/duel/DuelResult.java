package cn.xm1221.ygomc.common.duel;

/**
 * 一局的收局结果：谁赢了、为什么、收局时双方的 LP、打到第几回合。
 *
 * <h2>为什么要有这个类型</h2>
 * 收局画面要报的东西都是<b>观测值</b>（赢家、原因码、两个 LP、回合数），
 * 不是句子。服务端只把这些数字发过来，界面按当前语言去资源里取句子——
 * 这是硬约束「界面文本必须来自模组语言资源，服务端只送 key + 参数」在收局这一处的落地。
 *
 * <h2>原因码照搬 ygopro</h2>
 * {@code reason} <b>就是</b> ygopro 的 {@code !victory 0x…} 表的键
 * （{@code strings.conf}：{@code 0x0 投降}、{@code 0x1 基本分变成0}、
 * {@code 0x2 没有卡可抽}、{@code 0x3 超时}、{@code 0x4 失去连接}、
 * {@code 0x10+} 各种特殊胜利）。内核 {@code MSG_WIN} 的 reason 字段直接来自
 * 卡脚本的 {@code Duel.Win(player, reason)}（{@code libduel.cpp:1264-1276} 的
 * {@code duel_win}），所以特殊胜利的编号天然就在这张表里，我们照抄即可，
 * <b>不要另立一套</b>：自立的编号一旦和内核脚本对不上，收局画面就会报错原因。
 *
 * <p>界面的做法是：先拿 {@code reason} 去内核的 victory 表里查原文
 * （{@code DataPacks.victoryString}），查不到才退到我们自己的语言资源
 * （{@link #REASON_LP}、{@link #REASON_DECKOUT} 这类兜底）。
 *
 * @param winner 赢家座位（0/1）；{@link #PLAYER_NONE}（2）= 平局，与内核同值
 * @param reason 胜负原因码，见上（{@code !victory 0x…} 的键）
 * @param lp0    收局时 0 号席的 LP；{@link #LP_UNKNOWN} 表示这一份没有
 * @param lp1    收局时 1 号席的 LP；{@link #LP_UNKNOWN} 表示这一份没有
 * @param turns  打到第几个回合（内核 {@code MSG_NEW_TURN} 的条数）
 */
public record DuelResult(int winner, int reason, int lp0, int lp1, int turns) {

    /** 平局（内核 {@code PLAYER_NONE} 同值 2）。 */
    public static final int PLAYER_NONE = 2;

    // ── 原因码：与 ygopro 的 !victory 表逐条对应 ────────────────────────────
    /** {@code !victory 0x0} 投降。 */
    public static final int REASON_SURRENDER = 0x0;
    /** {@code !victory 0x1} 基本分变成 0。内核 win check 会自己发这个码。 */
    public static final int REASON_LP = 0x1;
    /** {@code !victory 0x2} 没有卡可抽（内核的 {@code overdraw}）。 */
    public static final int REASON_DECKOUT = 0x2;
    /**
     * {@code !victory 0x3} 超时。
     *
     * <p>我们自己判超时时用这个码——不是 0：0 在那张表里是「投降」，
     * 用它报超时会告诉玩家一个错的原因。
     */
    public static final int REASON_TIMEOUT = 0x3;

    /** LP 未知（这一帧取不到快照）。界面据此<b>不画</b> LP 那一行，而不是画 0:0。 */
    public static final int LP_UNKNOWN = -1;

    /** 这一局是平局。 */
    public boolean isDraw() {
        return winner == PLAYER_NONE;
    }

    /**
     * 这一局是不是 {@code seat} 赢的。
     *
     * <p>平局两边都是 false——界面要用「平局」而不是「你赢了」。
     * 视角的换算是纯判据，所以能离线断言。
     */
    public boolean wonBy(int seat) {
        return !isDraw() && winner == seat;
    }

    /** 取某一席收局时的 LP；不知道时返回 {@link #LP_UNKNOWN}。 */
    public int lpAt(int seat) {
        return seat == 0 ? lp0 : lp1;
    }

    /** 两个 LP 都知道，界面才画那一行。 */
    public boolean lpKnown() {
        return lp0 >= 0 && lp1 >= 0;
    }

    @Override
    public String toString() {
        return "DuelResult[winner=" + (isDraw() ? "平局" : winner)
                + ", reason=0x" + Integer.toHexString(reason)
                + ", lp=" + lp0 + ":" + lp1
                + ", turns=" + turns + "]";
    }
}
