package cn.xm1221.ygomc.common.card;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.StringRepresentable;

import java.util.List;

/**
 * 稀有度的一层叠加贴图。
 *
 * <p>{@code texture} 是资源包里的贴图路径（例如 {@code ygomc:textures/card/foil/holo.png}），
 * {@code layerType} 决定这一层怎么混上去。一个稀有度就是若干个这样的层，
 * 按列表顺序从下往上叠。
 *
 * <h2>为什么层是「数据」而不是「代码」</h2>
 * 实卡闪面工艺的组合会一直增加（新的限定罕、新活动卡）。把「什么图、怎么混」做成
 * 可序列化的数据，加新工艺就只是往数据里加一条 {@link RarityEntry}，不必改渲染器、
 * 也不必发新版本模组。
 *
 * <p>Copyright (C) CAS_ual_TY / YgoDuelingMod —— 本文件沿用其
 * {@code RarityLayer{texture, layerType}} 的分层建模设计，未复制其源码；
 * 本项目同样以 GPLv3 分发。
 *
 * @param texture   叠加层贴图
 * @param layerType 混合方式
 */
public record RarityLayer(ResourceLocation texture, RarityLayerType layerType) {

    /** 存档 / 数据包用：{@code {"texture": "...", "layer_type": "foil"}}。 */
    public static final Codec<RarityLayer> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("texture").forGetter(RarityLayer::texture),
            StringRepresentable.fromEnum(RarityLayerType::values)
                    .fieldOf("layer_type").forGetter(RarityLayer::layerType)
    ).apply(instance, RarityLayer::new));

    public RarityLayer {
        if (texture == null) {
            throw new IllegalArgumentException("RarityLayer.texture 不能为 null");
        }
        if (layerType == null) {
            throw new IllegalArgumentException("RarityLayer.layerType 不能为 null");
        }
    }

    /** @return 只含这一层的不可变列表，方便拼装常见工艺。 */
    public List<RarityLayer> singleton() {
        return List.of(this);
    }
}
