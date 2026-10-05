package cn.xm1221.ygomc.common.duel;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * 决斗盘的客户端行为（「把对局界面拉回来」）的挂载点。
 *
 * <h2>为什么要绕一个钩子，而不是在 {@link DuelDiskItem} 里直接调客户端类</h2>
 * {@code DuelDiskItem} 是<b>两端都会加载</b>的类；而「拉回界面」的实现活在
 * {@code common/client/DuelClient}，那个类引用 {@code Minecraft}。
 * 直接在物品里引用它，专用服务器上会就地
 * {@code NoClassDefFoundError: net/minecraft/client/Minecraft}——
 * 这种错只有在<b>专用服务器</b>上才露头，开发时（集成服务器）永远看不见。
 *
 * <p>所以物品只认这一个钩子，真正的实现由客户端入口在 {@code DuelClient.init()}
 * 里装进来；服务端保持默认的「没有界面可拉」。
 */
public final class DuelDiskHooks {

    /** 返回值 = 有没有一局正开着（true 表示界面已经在玩家眼前）。 */
    private static final AtomicReference<BooleanSupplier> REOPEN =
            new AtomicReference<>(() -> false);

    private DuelDiskHooks() {
    }

    /** 由客户端入口（{@code DuelClient.init()}）调用一次。 */
    public static void installReopen(BooleanSupplier impl) {
        REOPEN.set(impl == null ? () -> false : impl);
    }

    /** 试图把当前对局界面拉回来；没有进行中的对局返回 false。 */
    public static boolean reopenScreen() {
        return REOPEN.get().getAsBoolean();
    }
}
