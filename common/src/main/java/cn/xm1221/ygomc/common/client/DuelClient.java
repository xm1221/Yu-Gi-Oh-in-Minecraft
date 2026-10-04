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
 * <h2>界面生命周期的两种情形</h2>
 * <ul>
 *   <li>服务器推来问题、当前没有对局界面 → 新开一个。</li>
 *   <li>界面已经开着 → 就地更新，不重建。重建会把玩家的勾选和滚动位置抹掉，
 *       在选卡类问题里表现为「勾好的又没了」。</li>
 * </ul>
 *
 * <p>注意「玩家主动关掉界面」和「界面还在」是两回事：玩家关掉之后，
 * 下一个问题会重新开界面。这是有意的——关掉不该让对局永久卡住。
 */
public final class DuelClient {

    private static DuelScreen screen;

    private DuelClient() {
    }

    public static void init() {
        YgomcNet.registerClient(DuelClient::onUpdate);
    }

    private static void onUpdate(YgomcNet.BoardUpdate update) {
        Minecraft mc = Minecraft.getInstance();
        if (screen == null || mc.screen != screen) {
            screen = new DuelScreen(update.board(), update.question());
            mc.setScreen(screen);
            return;
        }
        screen.update(update.board(), update.question());
    }
}
