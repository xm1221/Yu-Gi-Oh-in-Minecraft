package cn.xm1221.ygomc.fabric.client.mixin;

import cn.xm1221.ygomc.common.client.CardItemRender;
import cn.xm1221.ygomc.common.registry.YgomcItems;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Fabric 侧把卡牌物品接到自定义渲染器上。
 *
 * <h2>为什么非要用 mixin</h2>
 * 原版 {@code ItemRenderer} 的构造器只接<b>一个</b> {@code BlockEntityWithoutLevelRenderer}
 * 实例，没有「按物品分发」的机制。NeoForge 用 {@code IClientItemExtensions}
 * 补上了这个能力，所以那边不用 mixin；Fabric 侧没有对应补丁
 * （1.21.1 上 {@code BuiltinItemRendererRegistry} 已被移除），
 * 只能把 render 里那次 {@code renderByItem} 调用换掉。
 *
 * <h2>为什么是 @Redirect 而不是 @Inject</h2>
 * 要替换的正是「原版调 BEWLR」这一次调用本身。{@code @Inject} 只能在调用前后插桩，
 * 拦住原调用还得再配 {@code cancellable} 和返回值，反而更绕。
 *
 * <h2>目标签名是查出来的，不是猜的</h2>
 * 本类上的 {@code required: true} + {@code defaultRequire: 1} 意味着目标找不到就
 * <b>直接崩启动</b>，所以描述符必须准。这里的描述符是用 {@code javap} 从
 * 1.21.1 的映射 jar 上读出来的，不是照抄别的版本：
 *
 * <pre>
 *   net.minecraft.client.renderer.entity.ItemRenderer        ← 注意在 .entity 子包下
 *     public void render(ItemStack, ItemDisplayContext, boolean, PoseStack,
 *                        MultiBufferSource, int, int, BakedModel)
 *   net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer
 *     public void renderByItem(ItemStack, ItemDisplayContext, PoseStack,
 *                              MultiBufferSource, int, int)
 * </pre>
 */
@Mixin(ItemRenderer.class)
public abstract class ItemRendererMixin {

    @Redirect(
            method = "render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;"
                    + "ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;"
                    + "IILnet/minecraft/client/resources/model/BakedModel;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/BlockEntityWithoutLevelRenderer;"
                            + "renderByItem(Lnet/minecraft/world/item/ItemStack;"
                            + "Lnet/minecraft/world/item/ItemDisplayContext;"
                            + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                            + "Lnet/minecraft/client/renderer/MultiBufferSource;II)V"))
    private void ygomc$renderCardByItem(BlockEntityWithoutLevelRenderer original, ItemStack stack,
                                        ItemDisplayContext context, PoseStack pose,
                                        MultiBufferSource buffers, int light, int overlay) {
        if (stack.getItem() == YgomcItems.CARD.get()) {
            CardItemRender.render(stack, context, pose, buffers, light, overlay);
        } else {
            // 别的 builtin/entity 物品（箱子、盾牌…）照旧走原版渲染器，
            // 不能因为拦了这一次调用就把它们一起弄丢。
            original.renderByItem(stack, context, pose, buffers, light, overlay);
        }
    }
}
