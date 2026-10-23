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
 * <h2>正面、牌背、厚度是三张不同的面</h2>
 * 几何在 {@link CardPlate}：正面贴卡图、背面贴牌背，两面隔开一个厚度，四条纸边把缝补上。
 * 早先把正反两面画在<b>同一个 z</b> 上，结果是 z-fighting，从正面看牌背还是镜像的。
 * 侧面用 {@code entitySolid}，它会剔除背面，所以绕序必须从外侧看是逆时针，否则厚度整片消失。
 */
public final class CardItemRender {

    private CardItemRender() {}

    /**
     * 进显示变换之前的卡面高度，单位是方块。宽度仍按卡图长宽比推，不在这里改比例。
     *
     * <p>GUI 把 1 个方块画成 16 像素，所以 0.9 大约是 14 像素高，物品栏和快捷栏还能看清字样。
     * 手持、掉落、头上、物品展示框的缩小不写在这里：那些档位的旋转、平移和缩放在
     * {@code assets/ygomc/models/item/card.json} 的 {@code display} 里，原版
     * {@code ItemRenderer} 会在调用本方法<b>之前</b>乘到 pose 上。这里再缩一次就会缩两遍。
     * 按 0.9 的底尺寸，第一人称约 0.34 格高，第三人称约 0.29 格高，掉落约 0.32 格高。
     */
    private static final float CARD_HEIGHT = 0.9F;

    /** 标准卡面长宽（200:290，与 {@code pics.bin} 的 FULL 档一致）。 */
    private static final float CARD_ASPECT_W = 200.0F;
    private static final float CARD_ASPECT_H = 290.0F;

    /** 侧面没有自己的贴图，铺原版纯白再乘纸边色。 */
    private static final ResourceLocation WHITE =
            ResourceLocation.withDefaultNamespace("textures/misc/white.png");

    /**
     * 在当前 pose 下画一个卡牌物品。
     *
     * @param context 显示档位（GUI / 手持 / 掉落…）。各档的缩放和朝向已经由物品模型的
     *                {@code display} 写进这个 pose，本方法不再改它，避免和模型叠两次。
     *                参数留在签名里是因为两个平台的调用点都要按原版 {@code renderByItem} 传进来。
     */
    public static void render(ItemStack stack, ItemDisplayContext context, PoseStack pose,
                              MultiBufferSource buffers, int light, int overlay) {
        // 正面用卡图；没有 card_ref、卡号查不到、或这张卡没图时，正面也铺牌背。
        // 留白会让「缺数据」和「渲染坏了」看起来一模一样。
        ResourceLocation frontTex = null;
        float aspectW = 0.0F;
        float aspectH = 0.0F;
        CardRef ref = CardItem.ref(stack);
        if (ref != null) {
            frontTex = CardTextures.get(ref.cardCode(), CardImageDb.TIER_FULL);
            aspectW = CardTextures.width(ref.cardCode(), CardImageDb.TIER_FULL);
            aspectH = CardTextures.height(ref.cardCode(), CardImageDb.TIER_FULL);
        }
        ResourceLocation backTex = CardTextures.back();
        if (frontTex == null || aspectW <= 0 || aspectH <= 0) {
            // 牌背按标准卡面比例铺，不按它自己的像素尺寸。
            // 否则同一张卡「有图」和「没图」会变成两种轮廓宽度。
            frontTex = backTex;
            aspectW = CARD_ASPECT_W;
            aspectH = CARD_ASPECT_H;
        }
        if (frontTex == null) {
            return;
        }
        // 正面有图时，缺牌背也不许改贴正面。两面都是卡图，玩家会以为牌背没做。
        // 数据包没有 back.jpg 时 CardTextures.back() 会落到模组自带牌背；这里只是守住那条线。

        CardPlate plate = CardPlate.of(CARD_HEIGHT, CardPlate.THICKNESS, aspectW, aspectH);

        // 恒定满亮度，不用传入的 light。卡面是要读的，暗处掉在地上不该变成一块黑卡。
        int readLight = LightTexture.FULL_BRIGHT;
        PoseStack.Pose p = pose.last();
        for (CardPlate.Quad quad : plate.quads()) {
            ResourceLocation texture = switch (quad.material()) {
                case FRONT -> frontTex;
                case BACK -> backTex;
                case EDGE -> WHITE;
            };
            if (texture == null) {
                continue;
            }
            RenderType type = quad.material() == CardPlate.Material.EDGE
                    ? RenderType.entitySolid(texture)
                    : RenderType.entityCutout(texture);
            VertexConsumer vc = buffers.getBuffer(type);
            int argb = quad.colorArgb();
            int a = (argb >>> 24) & 0xFF;
            int r = (argb >>> 16) & 0xFF;
            int g = (argb >>> 8) & 0xFF;
            int b = argb & 0xFF;
            for (CardPlate.Corner c : quad.corners()) {
                vc.addVertex(p, c.x(), c.y(), c.z())
                        .setColor(r, g, b, a)
                        .setUv(c.u(), c.v())
                        .setOverlay(overlay)
                        .setLight(readLight)
                        .setNormal(p, c.nx(), c.ny(), c.nz());
            }
        }
    }
}
