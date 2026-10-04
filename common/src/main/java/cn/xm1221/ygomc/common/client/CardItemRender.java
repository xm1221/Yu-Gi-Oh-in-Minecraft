package cn.xm1221.ygomc.common.client;

import cn.xm1221.ygomc.common.card.CardItem;
import cn.xm1221.ygomc.common.card.CardRef;
import cn.xm1221.ygomc.common.data.CardImageDb;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * 把一个卡牌物品画成一张卡。平台无关，两个平台共用同一份。
 *
 * <h2>为什么这个类在 common 却用客户端类</h2>
 * 它确实只在客户端跑，但内容全是原版类（{@code PoseStack} / {@code MultiBufferSource} /
 * {@code RenderType}），两个平台完全一样，没有一处平台 API。放进 common 是为了让
 * NeoForge 的 {@code IClientItemExtensions} 路径和 Fabric 的 mixin 路径调用
 * <b>同一份几何</b>：两边各抄一份的话，改了一边忘了另一边只是时间问题
 * （下面这个镜像 bug 就正是「同一件事被写了两遍」的那类问题）。
 *
 * <p>专用服务端不会加载这个类——只有平台侧的客户端适配器引用它。
 *
 * <h2>为什么只画一个面</h2>
 * 这里只画一个面。早先的版本画了<b>两个 z 完全相同</b>的面（正面 + 背面 UV 反向），
 * 结果是 z-fighting，而背面是后画的、通常赢；可背面那套 UV 是给「从背后看」准备的，
 * 从正面看就成了<b>左右镜像</b>——表现就是卡面整个反了。
 *
 * <p>当初画两个面是想解决「背面光照按正面法线算，看起来是黑的」。这个担心买不到东西：
 * {@code entityCutoutNoCull} 走的是 entity 系着色器，只吃 lightmap，
 * 不做方向性漫反射，法线根本不参与明暗计算。所以第二个面纯属有害无益。
 *
 * <p>单面也正是原版平面物品（地图、纸、画）的做法。它们从背后看同样是镜像的——
 * 这是原版平面物品本来就有的性质，不是缺陷。
 */
public final class CardItemRender {

    private CardItemRender() {}

    /** 卡面高度占多少个方块。宽度按卡图长宽比推出来，免得拉扁。 */
    private static final float CARD_HEIGHT = 0.9F;

    /** 标准卡面长宽（200:290，与 {@code pics.bin} 的 FULL 档一致）。 */
    private static final float CARD_ASPECT_W = 200.0F;
    private static final float CARD_ASPECT_H = 290.0F;

    /**
     * 在当前 pose 下画一个卡牌物品。
     *
     * @param context 显示档位（GUI / 手持 / 掉落…）。
     *                <b>当前没有拿它做任何事</b>——显示变换还没有逐档适配。
     *                留着这个参数是因为那件事迟早要做，而改签名会同时牵动两个平台的调用点。
     */
    public static void render(ItemStack stack, ItemDisplayContext context, PoseStack pose,
                              MultiBufferSource buffers, int light, int overlay) {
        // 画卡面还是画卡背，只影响「用哪张贴图」「按什么长宽比铺」，不影响几何——
        // 所以先把决定做完，后面只剩一条绘制路径。
        ResourceLocation texture = null;
        float aspectW = 0.0F;
        float aspectH = 0.0F;

        CardRef ref = CardItem.ref(stack);
        if (ref != null) {
            texture = CardTextures.get(ref.cardCode(), CardImageDb.TIER_FULL);
            aspectW = CardTextures.width(ref.cardCode(), CardImageDb.TIER_FULL);
            aspectH = CardTextures.height(ref.cardCode(), CardImageDb.TIER_FULL);
        }
        if (texture == null || aspectW <= 0 || aspectH <= 0) {
            // 三种情况走这里：没有 card_ref 组件、卡号在数据包里查不到、这张卡本身没图。
            // 对玩家来说它们是同一件事——「这不是一张能认出来的卡」——所以都显示卡背，
            // 而不是留白。留白会让「缺数据」和「渲染坏了」看起来一模一样。
            texture = CardTextures.back();
            // 卡背按【标准卡面】的比例铺，不按它自己的像素尺寸。
            // 否则同一张卡「有图」和「没图」会呈现两种轮廓宽度，
            // 看起来像是物品本身变了，而不是缺了张图。
            aspectW = CARD_ASPECT_W;
            aspectH = CARD_ASPECT_H;
        }
        if (texture == null) {
            // 连卡背都没有（数据包没放）。这时只能留白：画什么都只会让人以为渲染坏了。
            return;
        }

        float h = CARD_HEIGHT;
        float w = h * aspectW / aspectH;
        float x0 = 0.5F - w / 2.0F;
        float x1 = 0.5F + w / 2.0F;
        float y0 = 0.5F - h / 2.0F;
        float y1 = 0.5F + h / 2.0F;
        // z 取 0.5：物品模型空间里 0.5 就是「方块中心」那一层，与其它 BEWLR 的惯例一致。
        float z = 0.5F;

        // 恒定满亮度，<b>不用</b>传入的 light。
        //
        // 卡面是要「读」的东西——卡名、数值、卡文都在上面——而掉落物通常落在地上，
        // 采集的是方块光照，暗处就变成一块看不清的黑卡。玩家要的是认出这是哪张卡，
        // 不是「这张卡所在位置的亮度」。地图、告示牌这类需要阅读的平面物品
        // 走的是同一条思路（可见性优先于光照真实感）。
        //
        // 代价是卡片在暗处也会亮着。对一张要读的卡来说这是对的取舍：
        // 亮度信息在这里没有传达任何玩家关心的东西。
        int readLight = LightTexture.FULL_BRIGHT;

        // 绕序按「从 +Z 看是逆时针」排，UV 左上角贴 (x0, y1)——
        // 即 u 沿 +X 增、v 沿 -Y 增。正面朝 +Z，也就是 GUI / 手持时朝向玩家。
        VertexConsumer vc = buffers.getBuffer(RenderType.entityCutoutNoCull(texture));
        PoseStack.Pose p = pose.last();
        vertex(vc, p, x0, y1, z, 0.0F, 0.0F, readLight, overlay, 0.0F, 0.0F, 1.0F);
        vertex(vc, p, x1, y1, z, 1.0F, 0.0F, readLight, overlay, 0.0F, 0.0F, 1.0F);
        vertex(vc, p, x1, y0, z, 1.0F, 1.0F, readLight, overlay, 0.0F, 0.0F, 1.0F);
        vertex(vc, p, x0, y0, z, 0.0F, 1.0F, readLight, overlay, 0.0F, 0.0F, 1.0F);
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
