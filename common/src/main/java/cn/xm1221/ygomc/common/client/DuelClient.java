package cn.xm1221.ygomc.common.client;

import cn.xm1221.ygomc.common.duel.DuelDiskHooks;
import cn.xm1221.ygomc.common.net.YgomcNet;
import dev.architectury.event.events.client.ClientPlayerEvent;
import dev.architectury.event.events.client.ClientTickEvent;
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
 * 此外还有一个明确的入口：手持决斗盘右键空气，见 {@link #reopenScreen()}。
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
        // 演出总线在 Screen 外：关 GUI 也要继续 tick，世界层以后共用。
        ClientTickEvent.CLIENT_POST.register(mc -> DuelSpectacle.tickNow());
        DuelDiskHooks.installReopen(DuelClient::reopenScreen);
        ClientPlayerEvent.CLIENT_PLAYER_QUIT.register(player -> leaveWorld());
    }

    /** 断开连接 / 回到标题画面。只清客户端自己的东西，不动服务端那一局。 */
    static void leaveWorld() {
        Minecraft mc = Minecraft.getInstance();
        if (screen != null && mc.screen == screen) {
            mc.setScreen(null);
        }
        screen = null;
        everShown = false;
        CardTextures.clear();
        DuelSpectacle.clear();
    }

    /**
     * 把这一局的界面重新推到玩家眼前。
     *
     * <p>玩家自己关掉界面之后，原来唯一的「回来」方式是等下一个询问；
     * 而轮到对手操作时，可能很久都不会有自己的询问。决斗盘给了个明确入口：
     * 手持决斗盘对着空气右键。
     *
     * @return 有没有一局正开着（没有的话调用方不用响应，交给服务器走原来的开局逻辑）
     */
    static boolean reopenScreen() {
        if (screen == null) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != screen) {
            mc.setScreen(screen);
        }
        everShown = true;
        return true;
    }

    /**
     * 收局画面关掉之后，把这个屏忘掉。
     *
     * <p>不摘的话，决斗盘右键会把它再拽回来（见 {@link #reopenScreen}）——
     * 而那一局已经结束了，回来只会看到一块再也点不动的板。
     */
    static void forgetScreen(DuelScreen s) {
        if (screen == s) {
            screen = null;
            everShown = false;
        }
    }

    private static void onUpdate(YgomcNet.BoardUpdate update) {
        if (update.spectacles() != null && !update.spectacles().isEmpty()) {
            DuelSpectacle.accept(update.spectacles());
        }

        Minecraft mc = Minecraft.getInstance();
        if (update.result() != null) {
            // 收局帧。牌桌一般是 null（服务端只带结果），界面会自己留着上一帧当背景
            // （见 DuelScreen.update 的收局分支），所以这里不能走下面那条「关界面」的路。
            if (screen == null) {
                screen = new DuelScreen(update.board(), null, update.viewerSeat(), update.notice());
                everShown = false;
            }
            screen.update(update.board(), null, update.viewerSeat(), update.notice(),
                    update.result());
            // 结果必须让玩家看见：他可能早就把界面关掉了（等对手操作时很常见）。
            if (mc.screen != screen) {
                mc.setScreen(screen);
            }
            everShown = true;
            return;
        }
        if (update.board() == null && update.question() == null) {
            // 收尾信号。必须按「是不是当前这个屏」判断再关：
            // 玩家可能已经自己关掉并开了别的界面，这时不该把他从那儿拽出来。
            if (screen != null && mc.screen == screen) {
                mc.setScreen(null);
            }
            screen = null;
            everShown = false;
            DuelSpectacle.clear();
            return;
        }
        if (screen == null) {
            // 先建空屏，再走 update。被自动回掉的空时点如果在构造器里就塞进询问，
            // 会在 update 决定「这一问不摆」之前先画出来。
            screen = new DuelScreen(update.board(), null, update.viewerSeat(), null);
            everShown = false;
        }
        // update 的返回值已经把「自动回掉的空时点」和「不画的必发提示」排除掉了。
        // 以前这里用 question != null || notice != null，那两样都会把关掉界面的玩家拽回来。
        boolean attention = screen.update(update.board(), update.question(), update.viewerSeat(),
                update.notice(), null, update.questionId(), update.secondsLeft());
        if (DuelScreenFlow.shouldShow(everShown, attention, mc.screen == screen)) {
            mc.setScreen(screen);
            everShown = true;
        }
    }
}
