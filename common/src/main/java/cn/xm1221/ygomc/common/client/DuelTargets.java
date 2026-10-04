package cn.xm1221.ygomc.common.client;

import cn.xm1221.ygomc.common.duel.DuelQuestion;

import java.util.ArrayList;
import java.util.List;

/**
 * 点击层：把询问里的选项挂到牌桌上的具体矩形，玩家<b>点那张卡/那个格子</b>就是选它。
 *
 * <h2>为什么不是按钮网格</h2>
 * 原先把选项拍成一片按钮，等于把内核已经给出的空间信息丢掉：
 * {@code MSG_SELECT_CARD} 之类的询问本来就带着 {@code controller/location/sequence}，
 * 而 ygo 客户端与 YDM 都是直接点牌桌——点手牌选中它、点格子放下它，
 * 第二次点击本身就是确认（YDM 的对局界面除了聊天框几乎没有按钮）。
 *
 * <h2>为什么可以脱离游戏断言</h2>
 * 这个类不依赖任何 Minecraft 类，只吃 {@link FieldLayout}（同样纯 JDK）。
 * 于是「每个带位置的选项都点得到」「点中的一定是我以为的那个选项」
 * 可以变成可执行断言，而不用靠盯着画面看。
 */
public final class DuelTargets {

    /** 一个可点目标。{@code optionIndex} 是它在 {@link DuelQuestion#options()} 里的下标。 */
    public record Target(FieldLayout.Rect rect, int optionIndex, DuelQuestion.Option option) {
    }

    // 区域位，取自内核 common.h:55-65。不引用 Msg.Location 的名字是为了
    // 不依赖那一层是否导出了 FZONE/PZONE。
    private static final int LOC_DECK = 0x01;
    private static final int LOC_HAND = 0x02;
    private static final int LOC_MZONE = 0x04;
    private static final int LOC_SZONE = 0x08;
    private static final int LOC_GRAVE = 0x10;
    private static final int LOC_REMOVED = 0x20;
    private static final int LOC_EXTRA = 0x40;
    private static final int LOC_FZONE = 0x100;
    private static final int LOC_PZONE = 0x200;

    /** 手牌行左边留给「我方手牌 N」那行字的宽度，与 {@code DuelScreen} 画手牌时一致。 */
    private static final int HAND_LABEL_W = 78;

    private DuelTargets() {
    }

    /**
     * 算出这个询问在牌桌上的所有可点目标。
     *
     * @param mySeat 本地玩家的座位号（{@code controller} 与它相等就是自己这边）
     * @return 按选项顺序排列的目标；没有位置信息的选项不会出现在里面
     */
    public static List<Target> of(DuelQuestion q, FieldLayout L, int mySeat) {
        return of(q, L, mySeat, null);
    }

    /**
     * 同上，但手牌张数由调用方给出。
     *
     * <p>手牌的横向排布依赖<b>一共有几张</b>（要摊开、重叠），而选项表里未必列出全部手牌——
     * 只有 3 张能选时，若按「表里有 3 张」去排，这 3 张就会被摊到整行宽度上，
     * 画出来的位置和点得到的位置对不上。有真实牌桌时一律由调用方传张数。
     *
     * @param handCounts 索引即座位号；{@code null} 或某项为负时退回按选项表推断
     */
    public static List<Target> of(DuelQuestion q, FieldLayout L, int mySeat, int[] handCounts) {
        List<Target> out = new ArrayList<>();
        if (q == null) {
            return out;
        }
        int[] counts = handCounts != null ? handCounts : inferHandCounts(q);
        List<DuelQuestion.Option> options = q.options();
        for (int i = 0; i < options.size(); i++) {
            DuelQuestion.Option o = options.get(i);
            if (o.isCancel() || !o.hasPlace()) {
                continue;
            }
            FieldLayout.Rect r = rectOf(L, o.controller(), o.location(), o.sequence(), mySeat, counts);
            if (r != null) {
                out.add(new Target(r, i, o));
            }
        }
        return out;
    }

    /**
     * 没有真实牌桌时，按选项表里的手牌序号推断张数。
     *
     * <p>至少要取到 {@code max(序号)+1}，否则最高的那几张会被排到行外。
     * 这是「离线也能自洽」的下限，不是真实张数；真实路径见
     * {@link #of(DuelQuestion, FieldLayout, int, int[])}。
     */
    private static int[] inferHandCounts(DuelQuestion q) {
        int[] counts = new int[2];
        for (DuelQuestion.Option o : q.options()) {
            if (o.location() == LOC_HAND && o.controller() >= 0 && o.controller() < 2) {
                counts[o.controller()] = Math.max(counts[o.controller()], o.sequence() + 1);
            }
        }
        return counts;
    }

    /**
     * 一个位置对应的矩形；无法映射时返回 {@code null}。
     *
     * <p>映射规则按【现行大师规则】：
     * <ul>
     *   <li>怪兽区 {@code mzone 0..4} 是 5 个主怪兽区；{@code mzone 5,6} 是
     *       <b>双方共用</b>的额外怪兽区，画在中线中间列，所以不区分归属方；</li>
     *   <li>魔陷区 {@code szone 0..4} 是 5 个主魔陷区（灵摆已并入 0/4）；
     *       {@code szone 5} 是场地区，画在怪兽行的一端；</li>
     *   <li>灵摆位 {@code 0x200} 在现行规则下就是魔陷区的第 1、5 格，
     *       所以映射到 {@code szone 1} 与 {@code szone 5}；</li>
     *   <li>墓地/卡组/额外卡组分别是魔陷行的左右两端，除外区放在中线行的两端。</li>
     * </ul>
     */
    public static FieldLayout.Rect rectOf(FieldLayout L, int controller, int location,
                                          int sequence, int mySeat) {
        return rectOf(L, controller, location, sequence, mySeat, null);
    }

    /**
     * 同上，另接受各座位的手牌张数（只影响 {@code LOC_HAND}）。
     *
     * @param handCounts 索引即座位号；{@code null} 时该项按 {@code sequence + 1} 兜底
     */
    public static FieldLayout.Rect rectOf(FieldLayout L, int controller, int location,
                                          int sequence, int mySeat, int[] handCounts) {
        boolean mine = controller == mySeat;
        FieldLayout.Rect monsterRow = mine ? L.myMonsterRow() : L.oppMonsterRow();
        FieldLayout.Rect spellRow = mine ? L.mySpellRow() : L.oppSpellRow();
        switch (location) {
            case LOC_MZONE:
                if (sequence >= FieldLayout.MAIN_ZONES
                        && sequence < FieldLayout.MAIN_ZONES + FieldLayout.EXTRA_MONSTER_ZONES) {
                    return L.extraMonster(mine ? sequence - FieldLayout.MAIN_ZONES
                            : FieldLayout.MAIN_ZONES + 1 - sequence);
                }
                if (sequence >= 0 && sequence < FieldLayout.MAIN_ZONES) {
                    return L.col(monsterRow, mine ? 1 + sequence : 5 - sequence);
                }
                return null;
            case LOC_SZONE:
                if (sequence >= 0 && sequence < FieldLayout.MAIN_ZONES) {
                    return L.col(spellRow, mine ? 1 + sequence : 5 - sequence);
                }
                if (sequence == 5) {
                    return L.col(monsterRow, mine ? 0 : 6);
                }
                return null;
            case LOC_FZONE:
                return L.col(monsterRow, mine ? 0 : 6);
            case LOC_PZONE:
                // 内核 field.cpp:499-512 的 get_pzone_sequence：LOCATION_PZONE 传的是
                // 灵摆位序号 0/1，现行大师规则下分别【映射】到 szone 0 与 szone 4。
                // 所以这里收的是 0/1，不是 0/4——写成 0/4 会让右灵摆位永远点不到。
                if (sequence == 0) {
                    return L.col(spellRow, mine ? 1 : 5);
                }
                if (sequence == 1) {
                    return L.col(spellRow, mine ? 5 : 1);
                }
                return null;
            case LOC_GRAVE:
                return L.col(monsterRow, mine ? 6 : 0);
            case LOC_DECK:
                return L.col(spellRow, mine ? 6 : 0);
            case LOC_EXTRA:
                return L.col(spellRow, mine ? 0 : 6);
            case LOC_REMOVED:
                // 除外区画在中线行的两端（中线行只有中间两格有内容，两端是空的）
                return L.col(L.extraMonsterRow(), mine ? 6 : 0);
            case LOC_HAND: {
                int count = handCounts != null && controller >= 0 && controller < handCounts.length
                        ? handCounts[controller] : sequence + 1;
                return handHitRect(L, mine ? L.myHand() : L.oppHand(), sequence, count);
            }
            default:
                return null;
        }
    }

    /**
     * 手牌行里第 {@code index} 张卡的<b>完整</b>矩形——绘制用这一份。
     *
     * <p>手牌张数多时彼此压住一部分，所以「画在哪」和「点哪里」不是同一个矩形：
     * 画的是整张卡，点的是没被后一张盖住的那一条。两者分开不是因为要求不同，
     * 而是因为点击落在一张被盖住的卡上时，玩家指的明明是压在它上面的那张。
     *
     * <p>整行横向居中摊开，步长不超过一个卡宽——超了就成了「散开的图标」，
     * 而不是一把扇形的牌。
     */
    public static FieldLayout.Rect handCard(FieldLayout L, FieldLayout.Rect band,
                                           int index, int count) {
        return new FieldLayout.Rect(handX(L, band, index, count), band.y(),
                L.cellW(), band.h());
    }

    /**
     * 手牌行里第 {@code index} 张卡的<b>可点</b>矩形：完整矩形减去被后一张盖住的部分。
     *
     * <p>最后一张不会被盖，整张都可点；其余只露出左侧一条，宽度正好是一个步长。
     * 倒序命中（见 {@link #hit}）加上这个裁剪，才能做到「点哪张就是哪张」。
     */
    public static FieldLayout.Rect handHitRect(FieldLayout L, FieldLayout.Rect band,
                                               int index, int count) {
        int w = L.cellW();
        int x = handX(L, band, index, count);
        if (index + 1 < count) {
            w = Math.max(1, Math.min(w, handX(L, band, index + 1, count) - x));
        }
        return new FieldLayout.Rect(x, band.y(), w, band.h());
    }

    /** 手牌行里第 {@code index} 张卡左边缘的 x。绘制与命中必须共用这一个。 */
    private static int handX(FieldLayout L, FieldLayout.Rect band, int index, int count) {
        int w = L.cellW();
        int n = Math.max(1, count);
        int step = n <= 1 ? 0 : Math.min(w + 2, Math.max(1, (band.w() - w) / (n - 1)));
        int total = w + (n - 1) * step;
        return band.x() + (band.w() - total) / 2 + index * step;
    }

    /**
     * 命中测试：返回被点中的目标下标，没点中返回 -1。
     *
     * <p>倒序扫描：后画的目标压在先画的上面，点击也该落在最后画的那个上。
     * 手牌之间会互相压住一点，顺序错了就会「点右边那张选中左边那张」。
     */
    public static int hit(List<Target> targets, double mx, double my) {
        for (int i = targets.size() - 1; i >= 0; i--) {
            FieldLayout.Rect r = targets.get(i).rect();
            if (mx >= r.x() && mx < r.right() && my >= r.y() && my < r.bottom()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 落在这一点上的<b>所有</b>选项下标（按画出顺序）。
     *
     * <p>一张卡可能有多个可做的行动——手牌既能通常召唤又能盖放，
     * 场上的怪既能攻击又能发动效果。这时要在光标处弹出菜单让玩家挑，
     * 所以这里要拿到全部而不是第一个。
     */
    public static List<Integer> optionIndicesAt(List<Target> targets, double mx, double my) {
        List<Integer> out = new ArrayList<>();
        for (Target t : targets) {
            FieldLayout.Rect r = t.rect();
            if (mx >= r.x() && mx < r.right() && my >= r.y() && my < r.bottom()) {
                out.add(t.optionIndex());
            }
        }
        return out;
    }

    /** 行动菜单一行的高度与行间距。渲染与命中测试共用这一份，免得两边错位。 */
    public static final int MENU_ROW_H = 14;
    public static final int MENU_ROW_GAP = 2;

    /** 行动菜单第 {@code i} 行的矩形。 */
    public static FieldLayout.Rect menuRow(int x, int y, int w, int i) {
        return new FieldLayout.Rect(x, y + i * (MENU_ROW_H + MENU_ROW_GAP), w, MENU_ROW_H);
    }
}
