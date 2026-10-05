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
