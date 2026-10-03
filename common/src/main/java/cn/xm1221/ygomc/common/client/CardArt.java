package cn.xm1221.ygomc.common.client;

import cn.xm1221.ygomc.common.card.RarityEntry;
import cn.xm1221.ygomc.common.card.RarityLayer;
import cn.xm1221.ygomc.common.data.CardImageDb;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * 卡面绘制：卡图 + 稀有度分层。
 *
 * <p>这一层刻意<b>只</b>依赖 {@link GuiGraphics} 与原版 API，不碰任何平台专属的东西，
 * 因此 NeoForge 与 Fabric 共用同一份代码。物品栏图标、卡组界面、对局界面
 * 都是调这里的同一套方法，视觉表现自然一致。
 *
 * <h2>缺图与缺层的处理</h2>
 * 卡图缺失（数据包里这张卡没图）时画一个深色占位框，而不是留白：
 * 留白会让「这张卡没图」和「界面画错了」看起来一样。
 * 稀有度层的贴图来自用户自己的资源包，<b>本模组不提供任何闪面贴图</b>
 * （那属于第三方美术资产），所以层贴图缺失是<b>正常状态</b>；
 * 这时 Minecraft 会画它的 missingno 贴图，而不会崩。
 */
public final class CardArt {

    /** 占位框的填充色与边框色。 */
    private static final int PLACEHOLDER_FILL = 0xFF2B2B33;
    private static final int PLACEHOLDER_BORDER = 0xFF6A6A78;

    /** 全息层的亮度上限，避免叠到纯白刺眼。 */
    private static final float FOIL_INTENSITY = 0.45F;

    private CardArt() {
    }

    /**
     * 画一张卡的卡面（FULL 档，200×290）。
     *
     * @param rarity 稀有度分层；{@code null} 或 {@link RarityEntry#isPlain()} 时只画卡图
     * @return 是否画出了真实卡图（false 表示画的是占位框）
     */
    public static boolean draw(GuiGraphics g, int code, int x, int y, int w, int h,
                               @Nullable RarityEntry rarity) {
        boolean hasArt = blitTier(g, code, CardImageDb.TIER_FULL, x, y, w, h);
        if (rarity == null || rarity.isPlain() || rarity.layers().isEmpty()) {
            return hasArt;
        }
        for (RarityLayer layer : rarity.layers()) {
            switch (layer.layerType()) {
                case OVERLAY -> blitLayer(g, layer.texture(), x, y, w, h);
                case FOIL -> foilLayer(g, layer.texture(), x, y, w, h);
            }
        }
        return hasArt;
    }

    /**
     * 画物品栏图标（ICON 档，64×93）。用 ICON 而不是 FULL 是有意的：
     * 一格物品栏里 FULL 的 200×290 会被缩到看不清，却照样付 232 KB 显存与解码时间。
     */
    public static boolean drawIcon(GuiGraphics g, int code, int x, int y, int size) {
        // 图标是 64×93，按比例算高度，免得拉扁
        int h = Math.max(1, size * 93 / 64);
        return blitTier(g, code, CardImageDb.TIER_ICON, x, y, size, h);
    }

    /** 画某一档卡图；缺图时画占位框。 */
    private static boolean blitTier(GuiGraphics g, int code, int tier, int x, int y, int w, int h) {
        ResourceLocation texture = CardTextures.get(code, tier);
        if (texture == null) {
            placeholder(g, x, y, w, h);
            return false;
        }
        // 必须用带真实贴图尺寸的那个重载：7 参数版假定贴图是 256×256，
        // 而卡图是 200×290，用错会让整张图轻微错位并采样到相邻像素。
        int texW = CardTextures.width(code, tier);
        int texH = CardTextures.height(code, tier);
        g.blit(texture, x, y, 0.0F, 0.0F, w, h,
                texW > 0 ? texW : w, texH > 0 ? texH : h);
        return true;
    }

    /** 普通 alpha 混合的覆盖层。 */
    private static void blitLayer(GuiGraphics g, ResourceLocation texture, int x, int y, int w, int h) {
        // 层贴图与卡图同尺寸（由资源包作者保证），所以直接用 1:1 的贴图尺寸
        g.blit(texture, x, y, 0.0F, 0.0F, w, h, w, h);
    }

    /**
     * 全息层：叠加混合 + 随时间流动的色相。
     *
     * <p>为什么用<b>加法</b>混合而不是普通 alpha：实卡闪面的物理效果是「反射叠加」，
     * 亮的地方只会更亮，不会把底下的图案盖掉。普通 alpha 混出来的是一张贴纸，
     * 加法混出来才像箔膜。这也是 {@code RarityLayerType} 要把混合方式放进数据的原因——
     * 换成别的闪法时不该改这里。
     *
     * <p>色相随时间循环，是为了让静态截图里看起来像「会反光」。
     * 真正的视角相关反射需要自定义 shader，那是后续的事。
     */
    private static void foilLayer(GuiGraphics g, ResourceLocation texture, int x, int y, int w, int h) {
        float hue = (Util.getMillis() % 4000L) / 4000.0F;
        int rgb = java.awt.Color.HSBtoRGB(hue, 0.65F, 1.0F);
        float r = ((rgb >> 16) & 0xFF) / 255.0F * FOIL_INTENSITY;
        float gr = ((rgb >> 8) & 0xFF) / 255.0F * FOIL_INTENSITY;
        float b = (rgb & 0xFF) / 255.0F * FOIL_INTENSITY;

        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        g.setColor(r, gr, b, 1.0F);
        g.blit(texture, x, y, 0.0F, 0.0F, w, h, w, h);
        // 必须还原：这两个都是全局 GL 状态，不改回去会让后面所有界面都变成加法混合。
        g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    /** 缺图占位：深色底 + 一圈边框。 */
    private static void placeholder(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PLACEHOLDER_FILL);
        g.fill(x, y, x + w, y + 1, PLACEHOLDER_BORDER);
        g.fill(x, y + h - 1, x + w, y + h, PLACEHOLDER_BORDER);
        g.fill(x, y, x + 1, y + h, PLACEHOLDER_BORDER);
        g.fill(x + w - 1, y, x + w, y + h, PLACEHOLDER_BORDER);
    }
}
