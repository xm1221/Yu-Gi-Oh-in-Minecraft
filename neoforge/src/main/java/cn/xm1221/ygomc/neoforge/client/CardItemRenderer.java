package cn.xm1221.ygomc.neoforge.client;

import cn.xm1221.ygomc.common.client.CardItemRender;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * NeoForge 侧的物品卡面适配器。
 *
 * <p>几何不在这里，在 {@link CardItemRender}——Fabric 侧走 mixin 调的是同一份。
 * 这个类只负责 NeoForge 特有的那一段：把渲染器接到
 * {@code IClientItemExtensions#getCustomRenderer} 上。
 *
 * <h2>能被调用到的完整链条（缺一环就退化成紫黑格，而且编译期看不出来）</h2>
 * <pre>
 *   models/item/card.json 的父模型是 builtin/entity
 *     → 烘出来的模型 isCustomRenderer() 为真
 *     → 原版 ItemRenderer 走 else 分支，而不是画那个（不存在的）模型
 *     → （NeoForge 补丁）IClientItemExtensions.of(stack).getCustomRenderer()
 *     → 就是下面这个 renderByItem
 * </pre>
 * Fabric 侧前两步一样，第三步由 {@code ItemRendererMixin} 换成 {@link CardItemRender}。
 *
 * <p>这条链条只靠编译验证是不成立的：模型文件不在时，代码写得再对也永远不会被调用，
 * 而结果只是画面上一个紫黑棋盘格，没有任何报错。
 */
public final class CardItemRenderer extends BlockEntityWithoutLevelRenderer {

    private static CardItemRenderer instance;

    private CardItemRenderer() {
        super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),
                Minecraft.getInstance().getEntityModels());
    }

    /**
     * 单例。
     *
     * <p>延迟创建是必须的：父类构造器要用 {@code Minecraft.getInstance()}，
     * 而在类加载阶段（例如事件注册时）Minecraft 实例还不存在。
     * 这个方法是渲染时被调用的，那时实例一定已经就绪。
     */
    public static CardItemRenderer get() {
        if (instance == null) {
            instance = new CardItemRenderer();
        }
        return instance;
    }

    @Override
    public void renderByItem(ItemStack stack, ItemDisplayContext context, PoseStack pose,
                             MultiBufferSource buffers, int light, int overlay) {
        CardItemRender.render(stack, context, pose, buffers, light, overlay);
    }
}
