package cn.xm1221.ygomc.common.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * 场地格子材质的加载与绘制。
 *
 * <p>取图路线和 {@code CardTextures} 一致：{@code ResourceLocation.fromNamespaceAndPath}，
 * 交给 {@code GuiGraphics.blit} 去 {@code TextureManager} 里取（第一次用时自动加载，
 * 之后走缓存）。所以这里<b>不做「文件在不在」的检查</b>——不查就不会因为查法写错
 * 而误判；文件缺失时原版会画它自己的紫黑格子，那本身就是「这张图还没画」的信号。
 *
 * <p>缩放走 11 参重载（{@code CardArt} 里记过这个坑：9 参重载把目标尺寸当成采样
 * 尺寸，只会取左上角一小块）。最后两个参数是<b>源贴图尺寸</b>，从
 * {@link FieldTextureSpec} 的清单里取。
 */
public final class FieldTextures {

    private FieldTextures() {
    }

    private static final Map<String, ResourceLocation> CACHE = new HashMap<>();

    /**
     * 把一张材质拉伸到整个矩形。
     *
     * @param id 材质 id，见 {@link FieldTextureSpec}
     * @return 画了返回 true；id 不在清单里返回 false（调用方退回自己画的矩形）
     */
    public static boolean slot(GuiGraphics g, String id, FieldLayout.Rect r) {
        FieldTextureSpec.Entry e = FieldTextureSpec.of(id);
        if (e == null || r.w() <= 0 || r.h() <= 0) {
            return false;
        }
        g.blit(location(e.id()), r.x(), r.y(), r.w(), r.h(), 0.0F, 0.0F,
                e.w(), e.h(), e.w(), e.h());
        return true;
    }

    /**
     * 把一张材质<b>平铺</b>满整个矩形。
     *
     * <p>牌垫不能用拉伸：拉伸会把一张 16×16 的底纹拉成一大片模糊的色斑。
     * 整块的地方整块贴，右边和下边不足一块时按<b>子矩形采样</b>
     * （源尺寸仍传整张，取的宽高传剩余像素）。
     *
     * @return 画了返回 true；id 不在清单里返回 false（调用方退回纯色填充）
     */
    public static boolean tile(GuiGraphics g, String id, FieldLayout.Rect r) {
        FieldTextureSpec.Entry e = FieldTextureSpec.of(id);
        if (e == null || r.w() <= 0 || r.h() <= 0) {
            return false;
        }
        ResourceLocation loc = location(e.id());
        for (int y = r.y(); y < r.bottom(); y += e.h()) {
            int th = Math.min(e.h(), r.bottom() - y);
            for (int x = r.x(); x < r.right(); x += e.w()) {
                int tw = Math.min(e.w(), r.right() - x);
                g.blit(loc, x, y, tw, th, 0.0F, 0.0F, tw, th, e.w(), e.h());
            }
        }
        return true;
    }

    private static ResourceLocation location(String id) {
        return CACHE.computeIfAbsent(id,
                k -> ResourceLocation.fromNamespaceAndPath(FieldTextureSpec.NAMESPACE,
                        FieldTextureSpec.DIR + k + ".png"));
    }
}
