package cn.xm1221.ygomc.neoforge.client;

import cn.xm1221.ygomc.common.Ygomc;
import cn.xm1221.ygomc.common.client.CardBrowserScreen;
import cn.xm1221.ygomc.common.collection.CardBinderItem;
import cn.xm1221.ygomc.common.registry.YgomcItems;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * NeoForge 的客户端专属事件（游戏总线）。
 *
 * <h2>为什么客户端代码要单独一个类，而不是在公共代码里判 isClientSide</h2>
 * 专用服务器也会加载公共代码。{@code CardBrowserScreen} 继承自 {@code Screen}，
 * 属于客户端专属类；只要有一条从公共代码到它的引用进入了服务端的调用链，
 * 服务端就会在类加载阶段抛 {@code NoClassDefFoundError} 而崩服。
 * 用 {@code Dist.CLIENT} 把整类挡在服务端之外，问题从结构上消失，
 * 而不是靠每个调用点都记得加判断。
 *
 * <h2>为什么是 RightClickItem 而不是覆写卡册的 use()</h2>
 * {@code use()} 在两侧都会执行，从那里打开界面就得在公共代码里引用客户端类，
 * 正是上面要避免的事。用客户端事件则完全不碰那个方法，
 * 卡册的 {@code use()} 也就保持原样（它本来就该只做服务端该做的事）。
 */
@EventBusSubscriber(modid = Ygomc.MOD_ID, value = Dist.CLIENT)
public final class YgomcNeoForgeClient {

    private YgomcNeoForgeClient() {
    }

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        // 这个事件两侧都会发。只有客户端这一侧该开界面；服务端那一侧要开的
        // 是「校验玩家确实持有卡册」，等 M4 有了真正的容器界面再说。
        if (!event.getLevel().isClientSide()) {
            return;
        }
        if (event.getItemStack().getItem() instanceof CardBinderItem) {
            CardBrowserScreen.open(0);
        }
    }
}
