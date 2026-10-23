package cn.xm1221.ygomc.common.pack.data;

import cn.xm1221.ygomc.common.data.CardDataDb;
import cn.xm1221.ygomc.common.data.DataPacks;
import cn.xm1221.ygomc.common.pack.model.Booster;
import cn.xm1221.ygomc.common.pack.model.CardSetData;
import cn.xm1221.ygomc.common.pack.model.KnownPrints;
import cn.xm1221.ygomc.common.pack.model.PrintRef;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 数据包重载之后核对卡包：卡号在不在卡表里、抽法能不能开出来、盒装配比有没有引用错抽法。
 * 由两个平台在重载完成时调用。
 */
public final class PackCatalog {

    private static final Logger LOGGER = LoggerFactory.getLogger("ygomc/pack");

    private PackCatalog() {
    }

    public static void validate(RegistryAccess access) {
        Registry<CardSetDef> sets = access.registry(PackRegistries.CARD_SET).orElse(null);
        Registry<BoosterDef> boosters = access.registry(PackRegistries.BOOSTER).orElse(null);
        Registry<BoosterBoxDef> boxes = access.registry(PackRegistries.BOOSTER_BOX).orElse(null);
        if (sets == null || boosters == null || boxes == null) {
            LOGGER.error("卡包注册表没有登记（card_set={} booster={} booster_box={}）",
                    sets != null, boosters != null, boxes != null);
            return;
        }
        CardDataDb db = DataPacks.get().cardData();
        int warnings = 0;
        int errors = 0;
        for (var entry : boosters.entrySet()) {
            ResourceLocation id = entry.getKey().location();
            BoosterDef def = entry.getValue();
            CardSetData pool;
            int[] dropped = new int[1];
            try {
                pool = knownCards(def.set().value(), db, id, dropped);
            } catch (RuntimeException e) {
                errors++;
                LOGGER.error("卡包 {} 的系列展不开：{}", id, e.getMessage());
                continue;
            }
            warnings += dropped[0];
            if (pool == null) {
                warnings++;
                continue;
            }
            for (String issue : def.mechanics().validate(pool)) {
                if (issue.startsWith("ERROR")) {
                    errors++;
                    LOGGER.error("卡包 {}：{}", id, issue.substring("ERROR".length()).strip());
                } else {
                    warnings++;
                    LOGGER.warn("卡包 {}：{}", id, issue.substring("WARN".length()).strip());
                }
            }
        }
        for (var entry : boxes.entrySet()) {
            ResourceLocation id = entry.getKey().location();
            BoosterBoxDef box = entry.getValue();
            try {
                Booster mechanics = box.booster().value().mechanics();
                box.spec().check(mechanics);
            } catch (RuntimeException e) {
                errors++;
                LOGGER.error("盒装卡包 {}：{}", id, e.getMessage());
            }
        }
        String cards = db == null ? "无卡片数据库，跳过卡号核对" : "已核对卡号";
        LOGGER.info("套包：系列 {}，卡包 {}，盒装 {}，警告 {}，错误 {}（{}）",
                sets.size(), boosters.size(), boxes.size(), warnings, errors, cards);
    }

    /**
     * 去掉卡表里没有的卡号。全部被去掉时返回 {@code null}，调用方记一条警告。
     * 没有卡表时只丢掉非正卡号。规则与 {@link KnownPrints} 相同，开包走同一套。
     */
    private static CardSetData knownCards(CardSetDef set, CardDataDb db, ResourceLocation boosterId, int[] dropped) {
        CardSetData expanded = set.toModel();
        java.util.function.IntPredicate known = db == null ? null : db::contains;
        for (var rarity : cn.xm1221.ygomc.common.card.CardRarity.values()) {
            for (PrintRef print : expanded.pool(rarity)) {
                if (!KnownPrints.keep(print.code(), known)) {
                    LOGGER.warn("卡包 {} 的卡号 {} 不在卡片数据库里，已从池中剔除", boosterId, print.code());
                    dropped[0]++;
                }
            }
        }
        CardSetData filtered = KnownPrints.filter(expanded, known);
        if (filtered == null) {
            LOGGER.warn("卡包 {} 剔除未知卡号后池是空的", boosterId);
        }
        return filtered;
    }
}
