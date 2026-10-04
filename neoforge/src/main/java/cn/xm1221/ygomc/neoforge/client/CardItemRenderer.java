package cn.xm1221.ygomc.neoforge.client;

import cn.xm1221.ygomc.common.card.CardItem;
import cn.xm1221.ygomc.common.card.CardRef;
import cn.xm1221.ygomc.common.client.CardTextures;
import cn.xm1221.ygomc.common.data.CardImageDb;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * 把卡面画在物品本体上（NeoForge）。
 *
 * <h2>为什么这个文件在 neoforge 模块而不是 common</h2>
 * 1.21.1 上没有跨平台的做法，实测依据：
 * <ul>
 *   <li>原版 {@code ItemRenderer} 的构造器接收的是<b>单个</b>
 *       {@link BlockEntityWithoutLevelRenderer} 实例，没有按物品分发的机制。
 *       NeoForge 用 {@code IClientItemExtensions#getCustomRenderer} 补上了这个能力，
 *       所以这边能写；</li>
 *   <li>Fabric 侧原本有 {@code BuiltinItemRendererRegistry}，但在 1.21.1 已被移除
 *       （最后带该类的版本是 {@code fabric-rendering-v1-5.2.1}，
 *       而 1.21.1 用的 {@code 12.5.0} 里没有它）；</li>
 *   <li>剩下的自定义 {@code BakedModel} 路线要手工烘焙 {@code BakedQuad} 顶点，
 *       即要用 {@code FaceBakery}——它的签名在两个平台<b>不同</b>，公共代码用不了。</li>
 * </ul>
 *
 * <h2>几何与坐标还没有在客户端上校准过</h2>
 * 这段代码只做过编译验证。尺寸与朝向按「物品模型空间 0..1、中心在 0.5」
 * 推导，与其它 BEWLR（例如箱子）的写法一致，但<b>显示变换（GUI / 手持 / 掉落）
 * 没有逐档适配</b>：原版普通模型会应用模型 JSON 里的 display 变换，
 * 而自定义渲染器拿不到那个变换，必须自己写。第一版只保证「是一张长宽比正确的卡」，
 * 具体每一档的朝向与缩放留待看到实际画面后再调。
 */
public final class CardItemRenderer extends BlockEntityWithoutLevelRenderer {

    /** 卡面高度占多少个方块。宽度按卡图长宽比推出来，免得拉扁。 */
    private static final float CARD_HEIGHT = 0.9F;

    /**
     * 标准卡面长宽（200:290，与 {@code pics.bin} 的 FULL 档一致）。
     *
     * <p>卡背按这个比例铺，而不是按 {@code cover.jpg} 自己的像素尺寸——
     * 理由见 {@link #renderByItem}。
     */
    private static final float CARD_ASPECT_W = 200.0F;
    private static final float CARD_ASPECT_H = 290.0F;

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
     * 这个方法是渲染时调用的，那时实例一定已经就绪。
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
        ResourceLocation texture = null;
        // 画卡面还是画卡背，只影响「用哪张贴图」和「按什么长宽比铺」，
        // 不影响几何——所以先把这个决定做完，后面只有一条绘制路径。
        float aspectW = 0.0F;
        float aspectH = 0.0F;

        CardRef ref = CardItem.ref(stack);
        if (ref != null) {
            texture = CardTextures.get(ref.cardCode(), CardImageDb.TIER_FULL);
            aspectW = CardTextures.width(ref.cardCode(), CardImageDb.TIER_FULL);
            aspectH = CardTextures.height(ref.cardCode(), CardImageDb.TIER_FULL);
        }
        if (texture == null || aspectW <= 0 || aspectH <= 0) {
            // 三种情况走这里：没有 card_ref 组件、卡号在数据包里查不到、
            // 这张卡本身没图。对玩家来说它们是同一件事——「这不是一张能认出来的卡」——
            // 所以都显示卡背，而不是留白。留白会让「缺数据」和「渲染坏了」看起来一样。
            texture = CardTextures.back();
            // 卡背按【标准卡面】的比例铺，不按它自己的像素尺寸。
            // 否则同一张卡「有图」和「没图」时会呈现两种轮廓宽度，
            // 看起来像是物品本身变了，而不是缺了张图。
            aspectW = CARD_ASPECT_W;
            aspectH = CARD_ASPECT_H;
        }
        if (texture == null) {
            // 连卡背都没有（数据包没放）。这时只能留白：
            // 画什么都只会让人以为渲染出了问题。
            return;
        }

        float h = CARD_HEIGHT;
        float w = h * aspectW / aspectH;
        float x0 = 0.5F - w / 2.0F;
        float x1 = 0.5F + w / 2.0F;
        float y0 = 0.5F - h / 2.0F;
        float y1 = 0.5F + h / 2.0F;
        // z 放在 0.5：物品模型空间里 0.5 就是「方块中心」那一层，
        // 与其它 BEWLR 的惯例一致。
        float z = 0.5F;

        // entityCutoutNoCull 关掉了背面剔除，所以一个面从两侧都可见；
        // 但仍然画两个朝向相反的面，否则背面的光照会按正面的法线算，看起来是黑的。
        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(texture));
        PoseStack.Pose p = pose.last();

        vertex(vc, p, x0, y1, z, 0.0F, 0.0F, light, overlay, 0.0F, 0.0F, 1.0F);
        vertex(vc, p, x1, y1, z, 1.0F, 0.0F, light, overlay, 0.0F, 0.0F, 1.0F);
        vertex(vc, p, x1, y0, z, 1.0F, 1.0F, light, overlay, 0.0F, 0.0F, 1.0F);
        vertex(vc, p, x0, y0, z, 0.0F, 1.0F, light, overlay, 0.0F, 0.0F, 1.0F);

        // 背面：绕序与 UV 都翻转，法线朝 -Z
        vertex(vc, p, x1, y1, z, 0.0F, 0.0F, light, overlay, 0.0F, 0.0F, -1.0F);
        vertex(vc, p, x0, y1, z, 1.0F, 0.0F, light, overlay, 0.0F, 0.0F, -1.0F);
        vertex(vc, p, x0, y0, z, 1.0F, 1.0F, light, overlay, 0.0F, 0.0F, -1.0F);
        vertex(vc, p, x1, y0, z, 0.0F, 1.0F, light, overlay, 0.0F, 0.0F, -1.0F);
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose pose,
                               float x, float y, float z, float u, float v,
                               int light, int overlay, float nx, float ny, float nz) {
        vc.addVertex(pose, x, y, z)
                .setColor(255, 255, 255, 255)
                .setUv(u, v)
                .setOverlay(overlay)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
    }
}
