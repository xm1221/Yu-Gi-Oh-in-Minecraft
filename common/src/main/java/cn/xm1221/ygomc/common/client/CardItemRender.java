package cn.xm1221.ygomc.common.client;

import cn.xm1221.ygomc.common.card.CardItem;
import cn.xm1221.ygomc.common.card.CardRef;
import cn.xm1221.ygomc.common.client.CardPlate.Material;
import cn.xm1221.ygomc.common.client.CardPlate.Quad;
import cn.xm1221.ygomc.common.data.CardImageDb;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * 把一个卡牌物品画成一张<b>有厚度的卡</b>：一面卡面、一面牌背、四个纸边侧面。
 * 平台无关，两个平台共用同一份。
 *
 * <h2>为什么这个类在 common 却用客户端类</h2>
 * 它确实只在客户端跑，但内容全是原版类（{@code PoseStack} / {@code MultiBufferSource} /
 * {@code RenderType}），两个平台完全一样，没有一处平台 API。放进 common 是为了让
 * NeoForge 的 {@code IClientItemExtensions} 路径和 Fabric 的 mixin 路径调用
 * <b>同一份几何</b>：两边各抄一份的话，改了一边忘了另一边只是时间问题。
 *
 * <p>专用服务端不会加载这个类——只有平台侧的客户端适配器引用它。
 *
 * <h2>几何不在这里</h2>
 * 顶点、UV、法线、绕序全在 {@link CardPlate}——那个类不依赖 Minecraft，所以
 * 离线断言（{@code .agent/m5/java/ygomc/m5/CardPlateCheck.java}）能直接测
 * <b>真正被画的那一份</b>。这里只负责三件事：决定用哪张贴图、把面转成
 * {@link VertexConsumer} 调用、以及配色。几何若要改，改 {@link CardPlate}。
 *
 * <h2>展示框里的朝向不在这里，在模型的 display 块</h2>
 * 单面四边形从背后看必然是左右镜像。展示框（{@code FIXED}）拿到的正是那个面的背面，
 * 所以卡面看起来是反的。加上牌背后，展示框会直接显示<b>牌背</b>——因为展示框看到的
 * 是 -Z 那一侧。修法照原版 {@code builtin/entity} 物品（如 {@code minecraft:item/shield}）：
 * 朝向写在 {@code assets/ygomc/models/item/card.json} 的 {@code display} 块里，
 * 由原版 {@code ItemRenderer} 应用（它在调 {@code renderByItem} <b>之前</b>就
 * {@code getTransforms().getTransform(context).apply(...)}，对
 * {@code isCustomRenderer()} 的模型同样如此——这一条是从 1.21.1 的字节码上读出来的）。
 * {@code fixed} 那条 {@code rotation [0, 180, 0]} 就是用来把 +Z 面翻向观察者的。
 *
 * <h2>逐档朝向总表（唯一一处；改就改这里，值在 card.json）</h2>
 * <pre>
 * 档位                      rotation        依据
 * gui                       [0,0,0]         照原版 item/generated（生成的平面物品默认值）
 * fixed                     [0,180,0]       照原版 item/generated ← 修展示框翻转的那一条
 * ground                    [0,0,0]         照原版 item/generated
 * head                      [0,180,0]       照原版 item/generated
 * thirdperson_righthand     [0,0,0]         照原版 item/generated
 * thirdperson_lefthand      [0,0,0]         照原版 item/generated
 * firstperson_righthand     [0,-90,25]      照原版 item/generated
 * firstperson_lefthand      [0,-90,25]      照原版 item/generated
 * </pre>
 * 上面每个「照原版」都是 {@code minecraft:item/generated}（平面物品的父模型）里写着的值；
 * 旋转全部可溯源，<b>没有自创角度</b>。translation / scale 只有 ground 与 fixed 沿用原版，
 * 其余取自 {@code minecraft:item/shield}（原版里唯一一个自带位移的 {@code builtin/entity}
 * 手持物品）并按卡牌尺寸改过——<b>这些数值属「未核实、待游戏内确认」</b>：
 * 它们要靠手感调，离线断言测不到。三个「未核实」的点：
 * <ol>
 *   <li>gui 的 scale 0.9（原版 1.0）：卡高 0.9 方块，按 1.0 放会在格子里顶到边，但没人实测过；</li>
 *   <li>两个第三人称与两个第一人称的 translation（取自盾牌，而盾牌模型中心不在 0.5）；</li>
 *   <li>fixed 的 translation z = -0.02（半个厚度）：只是为了和展示框背板拉开一点，防 z-fighting。</li>
 * </ol>
 * 展示框那一条（{@code fixed} 的 180°）不是「未核实」：原版平面物品就靠它让图形朝向观察者，
 * 卡牌走的正是同一条路。
 *
 * <h2>恒定满亮度</h2>
 * 卡面是要「读」的东西——卡名、数值、卡文都在上面——而掉落物通常落在地上，
 * 采集的是方块光照，暗处就变成一块看不清的黑卡。玩家要的是认出这是哪张卡，
 * 不是「这张卡所在位置的亮度」。地图、告示牌这类需要阅读的平面物品走的是同一条思路
 * （可见性优先于光照真实感）。代价是卡片在暗处也会亮着，对一张要读的卡来说这是对的取舍。
 * 所以这里用 {@link LightTexture#FULL_BRIGHT}，<b>不用</b>传入的 {@code light}。
 */
public final class CardItemRender {

    private CardItemRender() {}

    /** 卡面高度占多少个方块。宽度按卡图长宽比推出来，免得拉扁。 */
    private static final float CARD_HEIGHT = 0.9F;

    /** 卡图长宽（200:290，与 {@code pics.bin} 的 FULL 档一致）。回退到卡背时按它铺。 */
    private static final float CARD_ASPECT_W = CardPlate.STANDARD_ASPECT_W;
    private static final float CARD_ASPECT_H = CardPlate.STANDARD_ASPECT_H;

    /**
     * 在当前 pose 下画一个卡牌物品。
     *
     * @param context 显示档位（GUI / 手持 / 掉落…）。<b>这里不读它</b>：逐档朝向已经
     *                写在模型 JSON 的 {@code display} 块里，由原版 {@code ItemRenderer}
     *                在调用本方法之前应用。留着这个参数是因为两条平台路径都得原样
     *                转发它，而改签名会同时牵动两个平台的调用点。
     */
    public static void render(ItemStack stack, ItemDisplayContext context, PoseStack pose,
                              MultiBufferSource buffers, int light, int overlay) {
        // 画卡面还是画卡背，只影响「用哪张贴图」「按什么长宽比铺」，不影响几何——
        // 所以先把决定做完，后面只剩一条绘制路径。
        ResourceLocation front = null;
        float aspectW = 0.0F;
        float aspectH = 0.0F;

        CardRef ref = CardItem.ref(stack);
        if (ref != null) {
            front = CardTextures.get(ref.cardCode(), CardImageDb.TIER_FULL);
            aspectW = CardTextures.width(ref.cardCode(), CardImageDb.TIER_FULL);
            aspectH = CardTextures.height(ref.cardCode(), CardImageDb.TIER_FULL);
        }
        if (front == null || aspectW <= 0 || aspectH <= 0) {
            // 三种情况走这里：没有 card_ref 组件、卡号在数据包里查不到、这张卡本身没图。
            // 对玩家来说它们是同一件事——「这不是一张能认出来的卡」——所以都显示卡背，
            // 而不是留白。留白会让「缺数据」和「渲染坏了」看起来一模一样。
            front = CardTextures.back();
            // 卡背按【标准卡面】的比例铺，不按它自己的像素尺寸。
            // 否则同一张卡「有图」和「没图」会呈现两种轮廓宽度，
            // 看起来像是物品本身变了，而不是缺了张图。
            aspectW = CARD_ASPECT_W;
            aspectH = CARD_ASPECT_H;
        }
        if (front == null) {
            // 连卡背都没有（数据包没放）。这时只能留白：画什么都只会让人以为渲染坏了。
            return;
        }

        // 牌背单独取一次：它不属于任何卡号，也不进 LRU。取不到就只跳过背面那一个四边形
        // ——正面和四个侧面照旧。整块卡都不画是不对的：那会连「这里有张牌」都看不出来。
        ResourceLocation back = CardTextures.back();

        CardPlate plate = CardPlate.of(CARD_HEIGHT, CardPlate.THICKNESS, aspectW, aspectH);
        PoseStack.Pose p = pose.last();

        for (Quad quad : plate.quads()) {
            ResourceLocation texture = switch (quad.material()) {
                case FRONT -> front;
                case BACK -> back;
                case EDGE -> EDGE_TEXTURE;
            };
            if (texture == null) {
                continue;
            }
            // 侧面走 entitySolid：它开启背面剔除，而 CardPlate 里每个面的绕序都是
            // 「从外侧看逆时针」，所以四个侧面朝外时不会被剔掉。正面/背面沿用
            // 原先的 entityCutoutNoCull——卡图有透明边（异画、灵摆的边角），
            // 剔除或 alpha 测试会把它们切掉。两张贴图各自绑定，不需要图集。
            RenderType type = quad.material() == Material.EDGE
                    ? RenderType.entitySolid(EDGE_TEXTURE)
                    : RenderType.entityCutoutNoCull(texture);
            VertexConsumer vc = buffers.getBuffer(type);
            for (CardPlate.Corner corner : quad.corners()) {
                vc.addVertex(p, corner.x(), corner.y(), corner.z())
                        .setColor(quad.colorArgb())
                        .setUv(corner.u(), corner.v())
                        .setOverlay(overlay)
                        .setLight(LightTexture.FULL_BRIGHT)
                        .setNormal(p, corner.nx(), corner.ny(), corner.nz());
            }
        }
    }

    /** 侧面的纸边贴图：原版纯白图，靠 {@link CardPlate#EDGE_ARGB} 调成纸边色。 */
    private static final ResourceLocation EDGE_TEXTURE =
            ResourceLocation.parse(CardPlate.EDGE_TEXTURE);
}
