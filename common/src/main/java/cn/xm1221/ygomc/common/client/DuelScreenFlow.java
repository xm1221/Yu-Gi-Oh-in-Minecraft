package cn.xm1221.ygomc.common.client;

/**
 * 对局界面「该不该推到玩家眼前」的判据。<b>纯逻辑</b>：只吃布尔、返回布尔，
 * 不碰 Minecraft，所以能离线穷举。
 *
 * <p>抽出来的理由和 {@code LobbyRules} 一样：这条判据错了<b>不会报任何错</b>，
 * 只会「什么都没发生」——界面不弹出来。2026-10-05 咩咩报的正是这个：
 * 双人局同意后没有弹出界面。原来的判据是「有询问才开」，而开局那几帧只有牌桌
 * （对手回合里每一步末尾也都只有牌桌），先被问的往往不是自己，
 * 于是另一方从开局起一帧界面都看不到，只能对着游戏画面干等。
 */
public final class DuelScreenFlow {

    private DuelScreenFlow() {
    }

    /**
     * @param everShown   当前这个界面推给玩家看过没有
     * @param hasQuestion 这一帧带没带需要玩家作答的询问
     * @param isCurrent   这个界面是不是已经是当前屏了
     */
    /**
     * 「确认（完成）」这颗键该不该出现。
     *
     * <p>ygo 全场只有一颗 {@code btnCancelOrFinish}（event_handler.cpp:2348-2367），
     * 文字在「取消」（sys 1295）/「完成」（sys 1296）之间变。规则：
     * <ul>
     *   <li>连锁的第一段（先问「XX时，是否发动效果？」）→ 要给「确认」；</li>
     *   <li>是/否类 → 要给（确认＝是）；</li>
     *   <li>选卡类 → <b>够条件才出现</b>（{@code select_ready}，duelclient.cpp:1677-1678）。
     *       这一点很要紧：ygo 是「<b>不摆</b>」，不是「摆着但是灰的」——
     *       一直摆一颗点不动的键，看起来就是咩咩说的「时灵时不灵」。</li>
     * </ul>
     */
    /**
     * 必发提示条该留还是该收——这是本条提示的<b>生命周期</b>规则。
     *
     * <p>咩咩 2026-10-05 报的「最底下那圈黄框是错误的残留」就是这里：
     * 原来的写法是 `if (notice != null) this.notice = notice;`，只有<b>来新提示</b>才覆盖，
     * 于是那条金黄边会一直挂在状态条右端，直到下一次必发为止。
     *
     * <p>规则：来了新的就覆盖旧的；没来的话，只要还停在同一问上就留着
     * （对手回合每一步末尾都会推不带提示的牌桌帧，那些帧不能把它清掉——
     * 否则玩家还没看清就没了）；一旦<b>换了询问</b>，上一条就已经过期，收掉。
     *
     * <p>另外玩家一旦作答（{@code DuelScreen.dispatch}）也立刻收掉：他动了手，
     * 就是看过了。提示不是询问，收掉它不产生任何应答。
     *
     * @param current      现在挂着的那条（可为 null）
     * @param incoming     这一帧带来的（可为 null）
     * @param sameQuestion 这一帧是不是还停在同一个询问上
     */
    public static <T> T nextNotice(T current, T incoming, boolean sameQuestion) {
        if (incoming != null) {
            return incoming;
        }
        return sameQuestion ? current : null;
    }

    public static boolean showFinish(boolean askStage, boolean yesNo, boolean ready) {
        return askStage || yesNo || ready;
    }

    /**
     * 「取消」这颗键该不该出现。
     *
     * <p>规则来自 ygo 每次点选之后的那段状态机（event_handler.cpp:1320-1326、:711-717）：
     * <b>可取消 且 一个都还没选</b> 才显示——一旦选了东西，「取消」就消失
     * （要改主意就把已选的那张再点一次）。是/否类的「取消」就是「否」，与选没选无关。
     *
     * @param cancelable   询问自己带的可取消标志（{@code DuelQuestion.cancelable()}）
     * @param nothingChosen 当前一个都没选
     */
    public static boolean showCancel(boolean askStage, boolean yesNo, boolean cancelable,
                                     boolean nothingChosen) {
        if (askStage) {
            // 连锁第一段的「取消」＝不发动（ygo 在窗里点否 → SetResponseI(-1)，:245-249）。
            return true;
        }
        if (yesNo) {
            return true;
        }
        return cancelable && nothingChosen;
    }

    public static boolean shouldShow(boolean everShown, boolean hasQuestion, boolean isCurrent) {
        if (isCurrent) {
            // 已经在他眼前了。重复 setScreen 会把界面重建一次，
            // 选卡类问题里表现为「勾好的又没了」。
            return false;
        }
        if (hasQuestion) {
            // 有询问就必须显示：玩家不答，对局就停在那儿。
            // 这也是「玩家自己关掉界面」之后唯一会把他拽回来的情形——
            // 关掉是他的自由，但不该让对局永久卡住。
            return true;
        }
        // 只有牌桌：只在从没露过面时显示。
        // 「露过面」之后就不再自动弹了，否则对手回合里每一步末尾的同步
        // 都会把刚关掉界面的玩家从别的界面里拽回来。
        return !everShown;
    }
}
