package cn.xm1221.ygomc.fabric.client;

import cn.xm1221.ygomc.common.client.CardBrowserScreen;
import cn.xm1221.ygomc.common.collection.CardBinderItem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.world.InteractionResultHolder;

/**
 * Fabric 客户端入口。
 *
 * <h2>为什么客户端要单独一个入口，而不是在公共入口里判 {@code Env}</h2>
 * 模组的公共代码会同时被<b>专用服务器</b>加载。任何直接引用渲染、
 * 界面、按键这类客户端专属类的代码，只要出现在公共入口的调用链上，
 * 专用服务器就会在类加载阶段抛 {@code NoClassDefFoundError} 而崩服。
 * 把客户端专属初始化放进独立的入口类，服务端根本不会加载到它，问题从结构上消失。
 *
 * <h2>与 NeoForge 侧的能力差异（有意为之，不是遗漏）</h2>
 * NeoForge 侧额外注册了卡牌的自定义物品渲染，让<b>物品栏里的图标</b>就显示卡面；
 * 这边没有做，因为 1.21.1 上没有可用的注册点：
 * {@code BuiltinItemRendererRegistry} 已被移除（实测最后带它的版本是
 * {@code fabric-rendering-v1-5.2.1}，而 1.21.1 用的 {@code 12.5.0} 里没有），
 * 剩下的路要么写 mixin，要么自定义 {@code BakedModel} 并手工烘焙顶点
 * （{@code FaceBakery} 的签名两平台不同，公共代码用不了）。
 * 界面里的卡图是共享代码，两个平台都能看到。
 */
public final class YgomcFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // 右键卡册 -> 打开卡图浏览界面。
        //
        // 这里用 Fabric 的事件而不是覆写卡册的 use()：use() 两侧都会执行，
        // 从那里开界面就得在公共代码里引用 Screen 这类客户端专属类，
        // 正是上面要避免的事。
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide() && player.getItemInHand(hand).getItem() instanceof CardBinderItem) {
                CardBrowserScreen.open(0);
            }
            // 必须返回 PASS 而不是 SUCCESS：卡册本身在服务端没有行为，
            // 在这里「消费」掉这次交互会让服务端看不到它，以后加服务端逻辑时会莫名其妙失效。
            return InteractionResultHolder.pass(player.getItemInHand(hand));
        });
        // TODO(M2): 注册决斗相关的客户端界面与网络接收端。
    }
}
