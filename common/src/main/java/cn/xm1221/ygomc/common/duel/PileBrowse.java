package cn.xm1221.ygomc.common.duel;

import java.util.ArrayList;
import java.util.List;

/**
 * 「查看牌堆内容」的纯逻辑：哪些堆能点开、标题写什么、每行是已知的卡还是盖着的、
 * 堆顶上那张画正面还是画牌背。
 *
 * <p>抽成纯类是为了能离线断言——这里的规则全是「什么信息该给玩家看」，
 * 写错了不会崩、不会报错，只会把不该给的信息画出来，或者把该给的信息藏起来。
 * 而且它刻意<b>不做过滤</b>：可见性在服务端（{@link FieldCodes#visible}）就已经
 * 定死了，对手的里侧除外和额外卡组到客户端时卡号就是 0。这里只负责把 0
 * 呈现成「盖着的卡」。<b>两边都滤会让人误以为边界在界面这一层。</b>
 *
 * <h2>为什么「已知」还要再分「里侧」</h2>
 * 我方里侧的除外卡，服务端按 {@code visible(mine=true)} 把<b>真卡号</b>给了我们
 * （自己盖的卡，名字当然知道），但它在场上是盖着的。所以：
 * <ul>
 *   <li>场上画牌堆顶：<b>照物理状态</b>——里侧一律牌背（{@link #topArt}）；</li>
 *   <li>查看列表：我方那几张写卡名、但要画斜体（{@link #italic}），
 *       对手那几张连卡号都没有，写「盖着的卡」。</li>
 * </ul>
 */
public final class PileBrowse {

    /** {@link #topArt} 的返回值：牌堆是空的。 */
    public static final int NO_ART = -1;

    /** {@link #topArt} 的返回值：画牌背（看不见正面）。 */
    public static final int BACK_ART = 0;

    private PileBrowse() {
    }

    /**
     * 这个区域能不能点开查看。
     *
     * <p>墓地、除外、额外卡组可以；<b>卡组不行</b>——卡组顺序对双方都是隐藏信息，
     * 能翻就不是「卡组」了。手牌也不在这条路上（手牌本来就画在桌上）。
     */
    public static boolean browsable(int location) {
        return location == FieldCodes.LOCATION_GRAVE
                || location == FieldCodes.LOCATION_REMOVED
                || location == FieldCodes.LOCATION_EXTRA;
    }

    /**
     * 一行：卡号、「这张的信息是否已知」、是不是<b>我方</b>的、在场上是不是表侧。
     *
     * <p>{@code mine} 与 {@code faceUp} 只用来决定卡名写不写斜体（见 {@link #italic}），
     * 不影响「已知/未知」——可见性的边界在服务端。
     *
     * @param mine   {@code true} = 这是我方的堆（我是这一堆的主人）
     * @param faceUp 在场上是不是表侧（里侧的除外卡按物理状态是盖着的）
     */
    public record Row(int code, boolean known, boolean mine, boolean faceUp) {
    }

    /**
     * 把一张牌桌里的某个区域转成可显示的行。
     *
     * <p>顺序照原样：ocgcore 报墓地/除外是<b>按放入顺序</b>给的，所以末尾是最上面那张。
     * 界面上画堆顶用的也是同一份顺序（{@link #topArt}）。
     *
     * @param mine 这一堆是不是我方的
     */
    public static List<Row> rows(List<DuelBoard.Zone> zones, boolean mine) {
        List<Row> out = new ArrayList<>(zones.size());
        for (DuelBoard.Zone z : zones) {
            int code = z == null ? 0 : (z.code() & 0x7fffffff);
            boolean faceUp = z != null && z.faceUp();
            out.add(new Row(code, code != 0, mine, faceUp));
        }
        return List.copyOf(out);
    }

    /**
     * 这一行的卡名要不要写斜体：<b>我方 + 已知 + 里侧</b>。
     *
     * <p>「我方里侧除外，卡名用斜体」是咩咩 2026-10-05 定的。含义是「我知道它是哪张，
     * 但它现在是盖着的」——只判 {@code known} 会让表侧的卡也斜体，只判 {@code mine}
     * 会把对手的也斜体（而对手的连卡号都没有）。
     */
    public static boolean italic(Row row) {
        return row != null && row.known() && row.mine() && !row.faceUp();
    }

    /**
     * 牌堆顶上那张该画什么。
     *
     * <p>ocgcore 报墓地/除外是<b>按放入顺序</b>给的，最后一张就是最上面那张
     * （ygo 客户端也是拿最后一张摊在堆上）。
     *
     * <p>里侧一律返回 {@link #BACK_ART}，<b>哪怕卡号对我们已知</b>：场上照物理状态画。
     * 这条以前漏了——只看 {@code code != 0} 就画正面，而我方里侧的除外卡号对我们
     * 是已知的（{@code visible(mine=true)} 恒真），于是牌堆顶上画出了卡面。
     *
     * @return 卡号（{@code > 0}）、{@link #BACK_ART}（看不见正面）或 {@link #NO_ART}（空堆）
     */
    public static int topArt(List<DuelBoard.Zone> pile) {
        for (int i = pile.size() - 1; i >= 0; i--) {
            DuelBoard.Zone z = pile.get(i);
            if (z != null && z.occupied()) {
                if (!z.faceUp()) {
                    return BACK_ART;
                }
                int code = z.code() & 0x7fffffff;
                return code == 0 ? BACK_ART : code;
            }
        }
        return NO_ART;
    }

    /**
     * 窗口标题：「自己墓地 3」。
     *
     * @param seat       这是谁的堆
     * @param viewerSeat 谁在看
     */
    public static String title(int seat, int location, int count, int viewerSeat) {
        return (seat == viewerSeat ? "自己" : "对手") + zone(location) + " " + count;
    }

    /** 区域名，与状态行/堆标签用的是同一套说法。 */
    public static String zone(int location) {
        return switch (location) {
            case FieldCodes.LOCATION_GRAVE -> "墓地";
            case FieldCodes.LOCATION_REMOVED -> "除外";
            case FieldCodes.LOCATION_EXTRA -> "额外卡组";
            case FieldCodes.LOCATION_DECK -> "卡组";
            case FieldCodes.LOCATION_HAND -> "手牌";
            case FieldCodes.LOCATION_MZONE -> "怪兽区";
            case FieldCodes.LOCATION_SZONE -> "魔法陷阱区";
            default -> "区域";
        };
    }

    /** 未知的那些行显示什么。别写「里侧」——额外卡组也是未知，但它不是里侧。 */
    public static String unknownLabel() {
        return "（盖着的卡）";
    }
}
