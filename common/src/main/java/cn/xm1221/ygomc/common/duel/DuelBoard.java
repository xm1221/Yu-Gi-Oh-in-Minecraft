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
 */
public record DuelBoard(int duelRule, int chainCount, PlayerBoard player0, PlayerBoard player1) {

    /** 怪兽区数量，内核 {@code field.cpp:68} 写死。 */
    public static final int MONSTER_ZONES = 7;
    /** 魔陷区数量，内核 {@code field.cpp:69} 写死。 */
    public static final int SPELL_ZONES = 8;

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

    /** 一格。{@code occupied} 为假时其余字段无意义。 */
    public record Zone(boolean occupied, int position, int overlayCount) {

        public static final Zone EMPTY = new Zone(false, 0, 0);

        /** 表侧（攻击表示或守备表示的正面）。 */
        public boolean faceUp() {
            return (position & (POS_FACEUP_ATTACK | POS_FACEUP_DEFENSE)) != 0;
        }

        /** 攻击表示。 */
        public boolean attack() {
            return (position & (POS_FACEUP_ATTACK | POS_FACEDOWN_ATTACK)) != 0;
        }
    }

    /** 一方玩家。 */
    public record PlayerBoard(int lp, List<Zone> monsterZones, List<Zone> spellZones,
                              int deckCount, int handCount, int graveCount,
                              int removedCount, int extraCount, int extraPCount) {
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
