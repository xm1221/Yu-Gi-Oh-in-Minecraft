package cn.xm1221.ygomc.common.duel;

/**
 * 「某某的效果发动（必发）」这条一次性通知（服务端 → 客户端）。
 *
 * <h2>它不是一个询问</h2>
 * 这条通知<b>没有答案</b>：内核自己发动的必发效果该发生还是会发生，
 * 提示只是告知玩家「刚才那一下是你的哪张卡自己动的手」。
 * 玩家按「确认」只把提示收起来，客户端<b>不回任何应答</b>——
 * 回一个答案会真的去影响对局，那就成了替玩家做决定。
 *
 * <h2>只送一次</h2>
 * 服务端把它放进 {@link Slot}，下一次给那一席推帧时捎出去并<b>清掉</b>
 * （见 {@code DuelRoom}）；客户端按「确认」只清本地那一份。
 * 两边的「只一次」都由 {@link Slot} 这一份取走语义保证。
 *
 * <h2>为什么不在这边把卡名拼好</h2>
 * 卡名与效果文案在客户端那套里（{@code CardTips} / {@code DescText}），
 * 服务端只送<b>编号与位置</b>，正文由 {@link #text} 在客户端合成——
 * 这样卡名跟着玩家自己的语言与数据包走，也不必在服务端再维护一张名字表。
 *
 * @param code        发动的那张卡的卡号；0 表示不知道（那就退回效果描述）
 * @param description 内核对这条连锁给的效果描述号（{@code MSG_CHAINING} 的 desc）
 * @param controller  发动者（0/1）
 * @param location    {@code FieldCodes.LOCATION_*}（这张卡当时在哪）
 * @param sequence    区域内的序号
 * @param chainCount  这条连锁是第几层（1 起）
 */
public record ChainNotice(int code, int description, int controller, int location,
                          int sequence, int chainCount) {

    /**
     * 提示上那颗唯一按键的<b>语言资源 key</b>。整条提示只有这一颗键。
     *
     * <p>这里放 key 而不是中文：字体由玩家的语言资源定，写死「确认」在英文环境下
     * 就是一颗中文字。用 key 的另一个好处是这个类在服务端也会被加载
     * （{@code DuelRoom} 持有 {@link Slot}），而语言资源只有客户端有——
     * 所以这里连 {@code DuelText} 都不引用，只跟它共用同一个字符串常量值。
     */
    public static final String CONFIRM_LABEL = "ygomc.duel.button.confirm";

    /**
     * 提示正文（纯逻辑，可离线钉）：
     * <ul>
     *   <li>知道卡名 → {@code 『青眼白龙』的效果发动（必发）}</li>
     *   <li>不知道卡名但有效果描述 → {@code 「从手卡特殊召唤」发动（必发）}</li>
     *   <li>两样都没有 → {@code 某张卡的效果发动（必发）}（宁可含糊，也不能空白）</li>
     * </ul>
     *
     * @param cardName   客户端查出来的卡名；查不到给 null
     * @param effectText 客户端查出来的效果描述；查不到给 null
     */
    public static String text(String cardName, String effectText) {
        if (cardName != null && !cardName.isBlank()) {
            return "『" + cardName + "』的效果发动（必发）";
        }
        if (effectText != null && !effectText.isBlank()) {
            return "「" + effectText + "」发动（必发）";
        }
        return "某张卡的效果发动（必发）";
    }

    /**
     * 待发通知的槽：<b>取走即清空</b>，所以同一条通知只会被送出去一次。
     *
     * <p>服务端在推帧时取一次（第一次拿到、之后拿到 null），
     * 离线断言钉的就是这个语义。
     */
    public static final class Slot {

        private ChainNotice value;

        public void put(ChainNotice notice) {
            value = notice;
        }

        /** 取走待发的那一条；没有就是 null。取走之后槽是空的。 */
        public ChainNotice take() {
            ChainNotice n = value;
            value = null;
            return n;
        }

        public boolean pending() {
            return value != null;
        }
    }
}
