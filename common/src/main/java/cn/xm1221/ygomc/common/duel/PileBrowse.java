package cn.xm1221.ygomc.common.duel;

import java.util.ArrayList;
import java.util.List;

/**
 * 「查看牌堆内容」的纯逻辑：哪些堆能点开、标题写什么、每行是已知的卡还是盖着的。
 *
 * <p>抽成纯类是为了能离线断言——这里的规则全是「什么信息该给玩家看」，
 * 写错了不会崩、不会报错，只会把不该给的信息画出来，或者把该给的信息藏起来。
 * 而且它刻意<b>不做过滤</b>：可见性在服务端（{@link FieldCodes#visible}）就已经
 * 定死了，对手的里侧除外和额外卡组到客户端时卡号就是 0。这里只负责把 0
 * 呈现成「盖着的卡」。<b>两边都滤会让人误以为边界在界面这一层。</b>
 */
public final class PileBrowse {

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

    /** 一行：卡号，以及「这张的信息是否已知」。卡号为 0 即未知（里侧、或对手的私有区域）。 */
    public record Row(int code, boolean known) {
    }

    /**
     * 把一张牌桌里的某个区域转成可显示的行。
     *
     * <p>顺序照原样：ocgcore 报墓地/除外是<b>按放入顺序</b>给的，所以末尾是最上面那张。
     * 界面上画堆顶用的也是同一份顺序（{@code DuelScreen.topCard}）。
     */
    public static List<Row> rows(List<DuelBoard.Zone> zones) {
        List<Row> out = new ArrayList<>(zones.size());
        for (DuelBoard.Zone z : zones) {
            int code = z == null ? 0 : (z.code() & 0x7fffffff);
            out.add(new Row(code, code != 0));
        }
        return List.copyOf(out);
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
