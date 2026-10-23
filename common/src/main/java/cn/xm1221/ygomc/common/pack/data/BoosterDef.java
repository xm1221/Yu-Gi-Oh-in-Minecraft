package cn.xm1221.ygomc.common.pack.data;

import cn.xm1221.ygomc.common.card.CardRarity;
import cn.xm1221.ygomc.common.pack.model.Booster;
import cn.xm1221.ygomc.common.pack.model.Pull;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.resources.RegistryFileCodec;
import net.minecraft.util.StringRepresentable;

import java.util.List;

/**
 * 一种卡包商品。{@code set} 可以是注册表 id，也可以内联一份系列定义。
 */
public record BoosterDef(
        Holder<CardSetDef> set,
        PackDisplay display,
        List<Pull> pulls,
        Booster.Duplicates duplicates,
        List<CardRarity> fallback,
        OddsOrigin oddsOrigin
) {

    /**
     * 抽法从哪来。缺省 {@link OddsOrigin#UNSPECIFIED}，旧的 {@code demo.json} 不用改。
     * 计划第 7.1 节。
     */
    public enum OddsOrigin implements StringRepresentable {
        SHEET("sheet"),
        SHEET_ADAPTED("sheet_adapted"),
        PRECONSTRUCTED("preconstructed"),
        PRINT_WEIGHT("print_weight"),
        UNSPECIFIED("unspecified");

        public static final Codec<OddsOrigin> CODEC = StringRepresentable.fromEnum(OddsOrigin::values);

        private final String name;

        OddsOrigin(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public static final Codec<BoosterDef> DIRECT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            RegistryFileCodec.create(PackRegistries.CARD_SET, CardSetDef.CODEC).fieldOf("set").forGetter(BoosterDef::set),
            PackDisplay.CODEC.fieldOf("display").forGetter(BoosterDef::display),
            Pull.CODEC.listOf().fieldOf("pulls").forGetter(BoosterDef::pulls),
            Booster.Duplicates.CODEC.optionalFieldOf("duplicates", Booster.Duplicates.AVOID_IN_PACK)
                    .forGetter(BoosterDef::duplicates),
            CardRarity.CODEC.listOf().fieldOf("fallback").forGetter(BoosterDef::fallback),
            OddsOrigin.CODEC.optionalFieldOf("odds_origin", OddsOrigin.UNSPECIFIED)
                    .forGetter(BoosterDef::oddsOrigin)
    ).apply(instance, BoosterDef::new));

    /** 引用或内联，挂在物品组件上。 */
    public static final Codec<Holder<BoosterDef>> HOLDER_CODEC =
            RegistryFileCodec.create(PackRegistries.BOOSTER, DIRECT_CODEC);

    public BoosterDef {
        pulls = List.copyOf(pulls);
        fallback = List.copyOf(fallback);
        if (oddsOrigin == null) {
            oddsOrigin = OddsOrigin.UNSPECIFIED;
        }
        // 构造时就把抽法校验掉，坏的数据包在加载期失败，而不是等玩家开包。
        new Booster(pulls, duplicates, fallback);
    }

    public Booster mechanics() {
        return new Booster(pulls, duplicates, fallback);
    }
}
