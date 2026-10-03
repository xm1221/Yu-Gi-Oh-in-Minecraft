package cn.xm1221.ygomc.neoforge.client;

import cn.xm1221.ygomc.common.Ygomc;
import cn.xm1221.ygomc.common.registry.YgomcItems;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

/**
 * NeoForge 的客户端注册（模组总线）。
 *
 * <p>与 {@link YgomcNeoForgeClient} 分成两个类，是因为它们挂在不同总线上：
 * {@link RegisterClientExtensionsEvent} 实现的是 {@code IModBusEvent}，
 * 所以它在<b>模组总线</b>上；而 {@code PlayerInteractEvent} 在<b>游戏总线</b>上。
 * {@code @EventBusSubscriber} 的 bus 属性是类级别的，一个类只能选一条。
 *
 * <p>注意 bus 属性在 21.1.252 里已被标记为「弃用并待删除」。
 * 这里仍然显式写出来，是因为它在当前版本<b>仍然生效</b>，而写明总线的语义
 * 比依赖「按事件类型自动推断」更不容易出错；等该版本线真的移除它时再改。
 */
@EventBusSubscriber(modid = Ygomc.MOD_ID, value = Dist.CLIENT,
        bus = EventBusSubscriber.Bus.MOD)
@SuppressWarnings("removal")
public final class YgomcNeoForgeClientSetup {

    /**
     * 卡牌物品的客户端扩展。
     *
     * <p>{@code getCustomRenderer} 返回的实例由 NeoForge 在渲染卡牌物品时调用，
     * 借此把默认的平面图标换成真正的卡面。这是 NeoForge 独有的能力——
     * 原版没有按物品分发自定义渲染器的机制（{@code ItemRenderer} 的构造器
     * 只接收一个全局实例）。
     */
    private static final IClientItemExtensions CARD_EXTENSIONS = new IClientItemExtensions() {
        @Override
        public BlockEntityWithoutLevelRenderer getCustomRenderer() {
            return CardItemRenderer.get();
        }
    };

    private YgomcNeoForgeClientSetup() {
    }

    @SubscribeEvent
    public static void onRegisterClientExtensions(RegisterClientExtensionsEvent event) {
        // 只对卡牌这一个物品注册。卡册、决斗盘这些继续用普通的平面模型，
        // 它们的卡面由各自界面负责。
        event.registerItem(CARD_EXTENSIONS, YgomcItems.CARD.get());
    }
}
