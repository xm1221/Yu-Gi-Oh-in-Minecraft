package cn.xm1221.ygomc.card;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;

/**
 * 一个稀有度的完整渲染定义 = 若干叠加层。
 *
 * <h2>为什么不把「层」塞进 {@link CardRarity} 枚举</h2>
 * 枚举常量在编译期就固定了，而「红碎到底是全息 + 金边 + 网点，还是全息 + 星光」
 * 属于美术决策，会随资源包变。把层列表做成外部数据（以后放数据包），
 * 同一份代码就能支持不同服务器的不同工艺表；枚举只保留「档位」这一层语义。
 *
 * <p>叠加顺序即列表顺序：索引 0 在最下面，依次往上盖。
 * 返回的是 {@link List#copyOf} 的不可变副本，避免调用方改到共享表。
 *
 * <p>Copyright (C) CAS_ual_TY / YgoDuelingMod —— 本文件沿用其
 * {@code RarityEntry{rarity, List<RarityLayer>}} 设计，未复制其源码；
 * 本项目同样以 GPLv3 分发。
 *
 * @param rarity 档位
 * @param layers 叠加层，从下往上
 */
public record RarityEntry(CardRarity rarity, List<RarityLayer> layers) {

    /** 存档 / 数据包用。 */
    public static final Codec<RarityEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            CardRarity.CODEC.fieldOf("rarity").forGetter(RarityEntry::rarity),
            RarityLayer.CODEC.listOf().fieldOf("layers").forGetter(RarityEntry::layers)
    ).apply(instance, RarityEntry::new));

    public RarityEntry {
        if (rarity == null) {
            throw new IllegalArgumentException("RarityEntry.rarity 不能为 null");
        }
        layers = List.copyOf(layers);
    }

    /** @return 单层稀有度定义的便捷构造。 */
    public static RarityEntry of(CardRarity rarity, RarityLayer... layers) {
        return new RarityEntry(rarity, List.of(layers));
    }

    /** @return 该稀有度是否完全不需要叠加（普通卡）。 */
    public boolean isPlain() {
        return this.layers.isEmpty();
    }
}
