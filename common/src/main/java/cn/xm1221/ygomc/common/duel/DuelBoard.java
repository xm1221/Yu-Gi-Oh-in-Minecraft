package cn.xm1221.ygomc.common.duel;

import cn.xm1221.ygomc.common.ocg.msg.Msg;

import java.util.ArrayList;
import java.util.List;

/**
 * 牌桌状态：把一次内核快照（{@code MSG_RELOAD_FIELD}）整理成界面好用的形状。
 *
 * <h2>为什么以快照而不是以消息流为准</h2>
 * 另一条路是逐条跟踪 {@code MOVE} / {@code DRAW} / {@code POS_CHANGE} 自己算出来。
 * 那条路的问题是<b>错得很安静</b>：漏解一条消息、把 sequence 和 position 弄混，
 * 牌桌就会偏掉，而且偏出来的结果往往还挺像回事，只有真打起来才发现不对。
 * 快照是内核自己的状态，不存在「我算错了」这种失败模式。
 *
 * <p>代价是快照里<b>没有卡号</b>——只有区域占用、表示形式和叠放数。
 * 卡号要按可见性另查，见 {@code Ocg.queryFieldCard}。这是内核刻意为之：
 * 快照里直接带上卡号就等于把对手的盖牌告诉客户端了。
 *
 * <h2>区域数量写死在核心里</h2>
 * 7 个怪兽区、8 个魔陷区（{@code field.cpp:68-69}）。
 * 这里把它们作为<b>不变量断言</b>而不是默默截断——数量对不上就说明
 * 我对快照布局的理解错了，那时宁可炸掉也不要画出一张错的牌桌。
 *
 * <h2>阶段与回合不在快照里</h2>
 * {@code MSG_RELOAD_FIELD} 不带阶段也不带回合数，这两样只能从
 * {@code MSG_NEW_PHASE} / {@code MSG_NEW_TURN} 另记（见 {@code DuelRoom.onMessage}），
 * 取快照时再盖上去（{@link #withPhaseTurn}）。所以 {@code phase} 为
 * {@link #PHASE_UNKNOWN} 是<b>正常状态</b>——对手回合刚开始、
 * 或这一局还没收到过任何阶段消息时就是这样，界面不该拿它当真值用。
 */
public record DuelBoard(int duelRule, int chainCount, PlayerBoard player0, PlayerBoard player1,
                        int phase, int turn) {

    /** 怪兽区数量，内核 {@code field.cpp:68} 写死。 */
    public static final int MONSTER_ZONES = 7;
    /** 魔陷区数量，内核 {@code field.cpp:69} 写死。 */
    public static final int SPELL_ZONES = 8;

    /** 阶段未知。{@code 0} 不是任何 {@code PHASE_*} 取值，用来表示「还没收到过 NEW_PHASE」。 */
    public static final int PHASE_UNKNOWN = 0;

    /**
     * 阶段位，逐条照抄内核 {@code common.h:407-416}。
     *
     * <p>性质和上面的 {@code POS_*} 一样：写在这里，让本类不依赖 JNI 层。
     *
     * <p>注意 {@code 0x08} 是<b>战斗阶段开始</b>、{@code 0x80} 才是战斗阶段本身，
     * 中间还夹着战斗步骤（{@code 0x10}）、伤害步骤（{@code 0x20}）与伤害计算（{@code 0x40}）——
     * 战斗阶段里内核会依次经过这一串值，它们在本类看来都算「战斗」。
     */
    public static final int PHASE_DRAW = 0x01;
    public static final int PHASE_STANDBY = 0x02;
    public static final int PHASE_MAIN1 = 0x04;
    public static final int PHASE_BATTLE_START = 0x08;
    public static final int PHASE_BATTLE_STEP = 0x10;
    public static final int PHASE_DAMAGE = 0x20;
    public static final int PHASE_DAMAGE_CAL = 0x40;
    public static final int PHASE_BATTLE = 0x80;
    public static final int PHASE_MAIN2 = 0x100;
    public static final int PHASE_END = 0x200;

    /**
     * 不带阶段/回合的五参构造。
     *
     * <p>线格式 v2 没有这两个字段，{@link #of} 也只有形状；
     * 两处都退化成「阶段未知、回合 0」，与旧行为完全一致。
     */
    public DuelBoard(int duelRule, int chainCount, PlayerBoard player0, PlayerBoard player1) {
        this(duelRule, chainCount, player0, player1, PHASE_UNKNOWN, 0);
    }

    /**
     * 六个阶段格的下标：抽卡 / 准备 / 主要1 / 战斗 / 主要2 / 结束。
     *
     * <p>战斗阶段内部的五个值全部映到同一格——玩家要看的是「现在在战斗阶段」，
     * 不是「现在在伤害计算的哪一个子步」（子步由内核的时点提示单独说，见
     * {@code DuelRoom} 的 {@code HINT_EVENT} 处理）。
     *
     * @return 下标；{@code phase} 不是阶段值（含 {@link #PHASE_UNKNOWN}）时返回 {@code -1}
     */
    public static int phaseBarIndex(int phase) {
        return switch (phase) {
            case PHASE_DRAW -> 0;
            case PHASE_STANDBY -> 1;
            case PHASE_MAIN1 -> 2;
            case PHASE_BATTLE_START, PHASE_BATTLE_STEP, PHASE_DAMAGE, PHASE_DAMAGE_CAL,
                 PHASE_BATTLE -> 3;
            case PHASE_MAIN2 -> 4;
            case PHASE_END -> 5;
            default -> -1;
        };
    }

    /** 盖上阶段与回合数（{@link #of} 造出来的板子这两项是空的）。 */
    public DuelBoard withPhaseTurn(int phase, int turn) {
        return new DuelBoard(duelRule, chainCount, player0, player1, phase, turn);
    }

    /**
     * 表示形式位，取自内核 {@code common.h} 的 {@code POS_*}。
     *
     * <p>不直接引用 {@code Ocg} 里的常量是为了让这个类不依赖 JNI 层——
     * 它应该能在没有原生库的情况下被单元测试。
     */
    public static final int POS_FACEUP_ATTACK = 0x1;
    public static final int POS_FACEDOWN_ATTACK = 0x2;
    public static final int POS_FACEUP_DEFENSE = 0x4;
    public static final int POS_FACEDOWN_DEFENSE = 0x8;

    /**
     * 一张卡当前的「会变的数值」。
     *
     * <p>只有<b>当前值</b>：原本值由客户端拿卡库比对（{@code DataPacks.statsOf}），
     * 内核的 {@code BASE_ATTACK/BASE_DEFENSE} 就不必再过一遍线。
     * 里侧与隐藏的卡不带它——那种查询（{@link FieldCodes#FLAG_HIDDEN}）根本没要这些字段。
     *
     * @param level 等级；超量怪兽这里是 0（阶级在 {@code rank}）
     * @param rank  阶级；不是超量怪兽时为 0
     * @param link  连接值；不是连接怪兽时为 0
     */
    public record Stats(int attack, int defense, int attribute, int race,
                        int level, int rank, int link) {

        /**
         * 显示用的「等级类」数值与它到底是哪一种。
         *
         * <p>内核把等级/阶级/连接分成三个字段：超量写 {@code rank}、连接写 {@code link}、
         * 其余写 {@code level}。界面上它们是同一处显示，所以先归一。
         */
        public int levelClass() {
            if (link > 0) {
                return link;
            }
            return rank > 0 ? rank : level;
        }
    }

    /**
     * 一格（或一张手牌 / 一张墓地卡）。
     *
     * <p>{@code occupied} 为假时其余字段无意义。
     *
     * @param code  卡号。{@code 0} 表示<b>未知或不可见</b>——对手的手牌、里侧盖牌、
     *              对手的卡组与额外卡组都会是 0。界面据此画卡背；
     *              取值来源与可见性判据见 {@link FieldCodes}。
     * @param stats 当前攻守等数值；不可见时为 {@code null}（见 {@link Stats}）
     */
    public record Zone(boolean occupied, int position, int overlayCount, int code, Stats stats) {

        public static final Zone EMPTY = new Zone(false, 0, 0, 0, null);

        /**
         * 不带数值的四参构造（线格式 v1-v3、以及不关心数值的调用点）。
         */
        public Zone(boolean occupied, int position, int overlayCount, int code) {
            this(occupied, position, overlayCount, code, null);
        }

        /**
         * 不带卡号的三参构造。
         *
         * <p>保留它有两个用处：线格式 v1 没有卡号字段，解码时用得上；
         * 只想描述「牌桌形状」的调用点（{@link #of}）也不必硬塞一个 0 进去。
         */
        public Zone(boolean occupied, int position, int overlayCount) {
            this(occupied, position, overlayCount, 0);
        }

        /** 表侧（攻击表示或守备表示的正面）。 */
        public boolean faceUp() {
            return (position & (POS_FACEUP_ATTACK | POS_FACEUP_DEFENSE)) != 0;
        }

        /** 攻击表示。 */
        public boolean attack() {
            return (position & (POS_FACEUP_ATTACK | POS_FACEDOWN_ATTACK)) != 0;
        }

        /** 卡号是否已知。false 时界面只能画卡背。 */
        public boolean known() {
            return code != 0;
        }
    }

    /**
     * 一方玩家。
     *
     * <p>前九个分量是「数量 + 场上两排」的紧凑形状；后四个是逐张列表，
     * 元素与对应区域的每一张卡一一对应、按 sequence 升序：
     * <ul>
     *   <li>{@link #hand()}：本地座位填真实卡号；对手座位条数等于手牌数，
     *       但每张 {@code code()} 为 0（只够画卡背）。</li>
     *   <li>{@link #grave()}、{@link #removed()}、{@link #extra()}：同理，
     *       对手的额外卡组一律 0。</li>
     *   <li><b>卡组没有列表</b>：卡组顺序对双方都是隐藏信息，
     *       造一串全 0 的列表不比 {@link #deckCount()} 多任何信息。</li>
     * </ul>
     */
    public record PlayerBoard(int lp, List<Zone> monsterZones, List<Zone> spellZones,
                              int deckCount, int handCount, int graveCount,
                              int removedCount, int extraCount, int extraPCount,
                              List<Zone> hand, List<Zone> grave, List<Zone> removed,
                              List<Zone> extra) {

        public PlayerBoard {
            monsterZones = List.copyOf(monsterZones);
            spellZones = List.copyOf(spellZones);
            hand = List.copyOf(hand);
            grave = List.copyOf(grave);
            removed = List.copyOf(removed);
            extra = List.copyOf(extra);
        }

        /**
         * 不带逐张列表的九参构造。
         *
         * <p>{@link DuelBoard#of} 只从快照里拿到形状（快照没有卡号），
         * 卡号要由 {@link FieldCodes#attach} 另查后补上；线格式 v1 亦然。
         */
        public PlayerBoard(int lp, List<Zone> monsterZones, List<Zone> spellZones,
                           int deckCount, int handCount, int graveCount, int removedCount,
                           int extraCount, int extraPCount) {
            this(lp, monsterZones, spellZones, deckCount, handCount, graveCount, removedCount,
                    extraCount, extraPCount, List.of(), List.of(), List.of(), List.of());
        }
    }

    public PlayerBoard playerAt(int i) {
        return i == 0 ? player0 : player1;
    }

    /**
     * 由内核快照构造。
     *
     * @throws IllegalStateException 快照结构与预期不符（区域数量不对）。
     *         宁可在这里明确失败，也不要画出一张错的牌桌——
     *         界面上「少了一格」和「我解析错了」看起来是一样的。
     */
    public static DuelBoard of(Msg.ReloadField f) {
        return new DuelBoard(f.duelRule(), f.chainCount(),
                player(f.player0(), "player0"), player(f.player1(), "player1"));
    }

    private static PlayerBoard player(Msg.ReloadPlayer p, String which) {
        if (p.monsterZones().length != MONSTER_ZONES) {
            throw new IllegalStateException(which + " 的怪兽区数量是 "
                    + p.monsterZones().length + "，预期 " + MONSTER_ZONES
                    + "：快照布局与预期不符");
        }
        if (p.spellZones().length != SPELL_ZONES) {
            throw new IllegalStateException(which + " 的魔陷区数量是 "
                    + p.spellZones().length + "，预期 " + SPELL_ZONES
                    + "：快照布局与预期不符");
        }
        return new PlayerBoard(p.lp(), zones(p.monsterZones()), zones(p.spellZones()),
                p.deckCount(), p.handCount(), p.graveCount(), p.removedCount(),
                p.extraCount(), p.extraPCount());
    }

    private static List<Zone> zones(Msg.ReloadZone[] raw) {
        List<Zone> out = new ArrayList<>(raw.length);
        for (Msg.ReloadZone z : raw) {
            out.add(z.occupied()
                    ? new Zone(true, z.position(), z.overlayCount())
                    : Zone.EMPTY);
        }
        return List.copyOf(out);
    }

    /** 一行紧凑描述，给日志和自检用。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("规则 ").append(duelRule);
        if (turn > 0) {
            sb.append("，第 ").append(turn).append(" 回合");
        }
        if (phase != PHASE_UNKNOWN) {
            sb.append("，阶段 0x").append(Integer.toHexString(phase));
        }
        if (chainCount > 0) {
            sb.append("，连锁 ").append(chainCount);
        }
        for (int i = 0; i < 2; i++) {
            PlayerBoard p = playerAt(i);
            sb.append("\n  P").append(i).append(" LP=").append(p.lp())
                    .append(" 怪兽=").append(occupied(p.monsterZones()))
                    .append(" 魔陷=").append(occupied(p.spellZones()))
                    .append(" 卡组=").append(p.deckCount())
                    .append(" 手牌=").append(p.handCount())
                    .append(" 墓地=").append(p.graveCount())
                    .append(" 除外=").append(p.removedCount())
                    .append(" 额外=").append(p.extraCount())
                    .append(" 灵摆=").append(p.extraPCount());
        }
        return sb.toString();
    }

    private static String occupied(List<Zone> zones) {
        StringBuilder sb = new StringBuilder();
        for (Zone z : zones) {
            if (!z.occupied()) {
                sb.append('·');
            } else if (z.faceUp()) {
                sb.append(z.attack() ? 'A' : 'D');
            } else {
                sb.append('?');
            }
        }
        return sb.toString();
    }
}
