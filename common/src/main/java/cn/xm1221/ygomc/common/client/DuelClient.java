package cn.xm1221.ygomc.common.client;

import cn.xm1221.ygomc.common.net.YgomcNet;
import net.minecraft.client.Minecraft;

/**
 * 客户端胶水：把网络层的 S→C 更新接到界面上。
 *
 * <p>由两个平台的<b>客户端入口</b>各调一次 {@link #init()}。放在 common 的
 * {@code client} 包里，与 {@link CardBrowserScreen}、{@link DuelScreen} 同理：
 * 它引用 {@code Minecraft}，是客户端专属类，专用服务器不能加载到它。
 *
 * <h2>界面生命周期的三种情形</h2>
 * <ul>
 *   <li>服务器推来问题、当前没有对局界面 → 新开一个。</li>
 *   <li>界面已经开着 → 就地更新，不重建。重建会把玩家的勾选和滚动位置抹掉，
 *       在选卡类问题里表现为「勾好的又没了」。</li>
 *   <li>只有牌桌、没有询问 → 只在界面<b>从没露过面</b>时推上去，判据见
 *       {@link DuelScreenFlow}。</li>
 * </ul>
 *
 * <p>注意「玩家主动关掉界面」和「界面还在」是两回事：玩家关掉之后，
 * 下一个问题会重新开界面。这是有意的——关掉不该让对局永久卡住。
 */
public final class DuelClient {

    private static DuelScreen screen;

    /**
     * 当前这个界面推给玩家看过没有。
     *
     * <p>为什么要单记一个标志：对手回合里每一步末尾都会同步一次牌桌，
     * 那些帧不该把玩家从别的界面里拽回来（界面关不关是他的自由）。
     * 但<b>第一帧必须开</b>——2026-10-05 咩咩报「同意后没有弹出 gui」就是这么来的：
     * 双人局里先被问的往往不是自己，而原来的判据是「有询问才开界面」，
     * 于是另一方从开局起一帧界面都看不到。
     */
    private static boolean everShown;

    private DuelClient() {
    }

    public static void init() {
        YgomcNet.registerClient(DuelClient::onUpdate);
    }

    private static void onUpdate(YgomcNet.BoardUpdate update) {
        Minecraft mc = Minecraft.getInstance();
        if (update.board() == null && update.question() == null) {
            // 收尾信号。必须按「是不是当前这个屏」判断再关：
            // 玩家可能已经自己关掉并开了别的界面，这时不该把他从那儿拽出来。
            if (screen != null && mc.screen == screen) {
                mc.setScreen(null);
            }
            screen = null;
            everShown = false;
            return;
        }
        if (screen == null) {
            screen = new DuelScreen(update.board(), update.question());
            everShown = false;
        } else {
            screen.update(update.board(), update.question());
        }
        if (DuelScreenFlow.shouldShow(everShown, update.question() != null, mc.screen == screen)) {
            mc.setScreen(screen);
            everShown = true;
        }
    }
}
