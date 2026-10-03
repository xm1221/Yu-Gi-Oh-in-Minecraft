package cn.xm1221.ygomc.card;

import net.minecraft.util.StringRepresentable;

import java.util.Locale;

/**
 * 一个稀有度叠加层的「合成方式」。
 *
 * <h2>为什么层要分类型，而不是一张贴图叠上去就完事</h2>
 * 复刻实卡闪面至少有两种物理上不同的做法：
 * <ul>
 *   <li>{@link #FOIL}：把一张半透明的全息/箔膜贴图用叠加（screen / add）方式混上去，
 *       颜色由贴图本身与视角决定——这是「彩虹反光」的来源。</li>
 *   <li>{@link #OVERLAY}：一张带 alpha 的纹理层直接普通混合（normal blend），
 *       用来画凸版凹槽、点阵网点、金边这类不随角度变化的固定纹理。</li>
 * </ul>
 * 如果把两者都当成「叠一张图」，就必须把混合模式硬编码进渲染器，
 * 于是每加一种新闪法都要改渲染代码。把混合方式提到数据里，渲染器就只剩一个
 * 「按顺序、按类型 blit」的循环。
 *
 * <p>实现 {@link StringRepresentable} 是为了让 {@link RarityLayer} 的 Codec 用
 * 稳定的小写名读写，理由与 {@link CardRarity} 相同（枚举 ordinal 会漂移）。
 *
 * <p>设计参考 {@code YgoDuelingMod}（GPLv3）的 {@code RarityLayer}/{@code RarityLayerType}
 * 分层思路。Copyright (C) CAS_ual_TY / YgoDuelingMod —— 本项目同样以 GPLv3 分发。
 */
public enum RarityLayerType implements StringRepresentable {
    /** 全息层：颜色叠加，随视角/光照变化。 */
    FOIL("foil"),
    /** 覆盖层：固定纹理，只做 alpha 混合。 */
    OVERLAY("overlay");

    private final String name;

    RarityLayerType(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return this.name;
    }

    /** @return 未知名字回落到 {@link #FOIL}，避免一个手滑的数据包条目让整个卡包加载失败。 */
    public static RarityLayerType byName(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        for (RarityLayerType t : values()) {
            if (t.name.equals(key)) {
                return t;
            }
        }
        return FOIL;
    }
}
