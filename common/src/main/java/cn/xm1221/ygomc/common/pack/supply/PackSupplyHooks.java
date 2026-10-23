package cn.xm1221.ygomc.common.pack.supply;

import cn.xm1221.ygomc.common.Ygomc;
import cn.xm1221.ygomc.common.pack.PackOpening;
import cn.xm1221.ygomc.common.pack.data.BoosterBoxDef;
import cn.xm1221.ygomc.common.pack.data.BoosterDef;
import cn.xm1221.ygomc.common.pack.data.PackRegistries;
import cn.xm1221.ygomc.common.registry.YgomcItems;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.architectury.event.events.common.LootEvent;
import dev.architectury.registry.level.entity.trade.TradeRegistry;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 把 {@link PackSupply} 接到原版箱子和村民交易上。
 *
 * <p>包和盒的身份是数据组件，战利品表加载时还不能把引用写死成物品。
 * 掉落或生成交易的时候再按 {@code ygomc:demo} 去注册表里取。没有这个包就什么都不给，
 * 不把一个开不了的空壳放进箱子。
 */
public final class PackSupplyHooks {

    private static final Logger LOGGER = LoggerFactory.getLogger(Ygomc.MOD_ID);

    private static final DeferredRegister<LootItemFunctionType<?>> FUNCTIONS =
            DeferredRegister.create(Ygomc.MOD_ID, Registries.LOOT_FUNCTION_TYPE);

    static final RegistrySupplier<LootItemFunctionType<JarPackFunction>> JAR_PACK =
            FUNCTIONS.register("jar_pack", () -> new LootItemFunctionType<>(JarPackFunction.CODEC));

    private PackSupplyHooks() {
    }

    public static void init() {
        PackSupply.check();
        FUNCTIONS.register();
        LootEvent.MODIFY_LOOT_TABLE.register((key, context, builtin) -> {
            for (PackSupply.Chest chest : PackSupply.chests(key.location().toString(), builtin)) {
                context.addPool(pool(chest));
            }
        });
        for (PackSupply.VillagerDeal deal : PackSupply.VILLAGERS) {
            VillagerProfession profession = profession(deal.profession());
            if (profession == null) {
                LOGGER.warn("不认识的村民职业，跳过卡包交易：{}", deal.profession());
                continue;
            }
            TradeRegistry.registerVillagerTrade(profession, deal.level(), listing(deal.goods(),
                    deal.emeralds(), deal.maxUses(), deal.xp(), deal.priceMultiplier()));
        }
        for (PackSupply.WandererDeal deal : PackSupply.WANDERERS) {
            TradeRegistry.registerTradeForWanderingTrader(deal.rare(), listing(deal.goods(),
                    deal.emeralds(), deal.maxUses(), deal.xp(), deal.priceMultiplier()));
        }
    }

    private static LootPool.Builder pool(PackSupply.Chest chest) {
        Item item = chest.goods() == PackSupply.Goods.BOX
                ? YgomcItems.BOOSTER_BOX.get()
                : YgomcItems.CARD_PACK.get();
        return LootPool.lootPool()
                .setRolls(ConstantValue.exactly(1))
                .when(LootItemRandomChanceCondition.randomChance(chest.chance()))
                .add(LootItem.lootTableItem(item).apply(JarPackFunction.builder(chest.goods())));
    }

    /** 用原版职业实例做键。注册表查询在模组初始化时可能还没填好，静态字段已经在。 */
    private static VillagerProfession profession(String id) {
        if ("minecraft:cartographer".equals(id)) {
            return VillagerProfession.CARTOGRAPHER;
        }
        return null;
    }

    private static VillagerTrades.ItemListing listing(PackSupply.Goods goods, int emeralds, int maxUses,
                                                       int xp, float priceMultiplier) {
        return (Entity trader, net.minecraft.util.RandomSource random) -> {
            ItemStack sale = stack(trader.registryAccess(), goods, 1);
            if (sale.isEmpty()) {
                return null;
            }
            return new MerchantOffer(new ItemCost(Items.EMERALD, emeralds), Optional.empty(), sale,
                    maxUses, xp, priceMultiplier);
        };
    }

    static ItemStack stack(RegistryAccess access, PackSupply.Goods goods, int count) {
        ResourceLocation id = ResourceLocation.parse(PackSupply.DEMO);
        if (goods == PackSupply.Goods.BOX) {
            Holder<BoosterBoxDef> box = holder(access, PackRegistries.BOOSTER_BOX, id);
            if (box == null) {
                return ItemStack.EMPTY;
            }
            ItemStack stack = PackOpening.boxStack(box);
            stack.setCount(count);
            return stack;
        }
        Holder<BoosterDef> booster = holder(access, PackRegistries.BOOSTER, id);
        if (booster == null) {
            return ItemStack.EMPTY;
        }
        return PackOpening.packStack(booster, count);
    }

    private static <T> Holder<T> holder(RegistryAccess access, ResourceKey<Registry<T>> registry, ResourceLocation id) {
        Registry<T> found = access.registry(registry).orElse(null);
        if (found == null) {
            return null;
        }
        return found.getHolder(ResourceKey.create(registry, id)).orElse(null);
    }

    /**
     * 掉落时把物品换成带 {@code ygomc:demo} 组件的包或盒。注册表里没有就掉空。
     */
    record JarPackFunction(PackSupply.Goods goods) implements LootItemFunction {

        static final Codec<PackSupply.Goods> GOODS = Codec.STRING.xmap(
                name -> PackSupply.Goods.valueOf(name.toUpperCase(java.util.Locale.ROOT)),
                goods -> goods.name().toLowerCase(java.util.Locale.ROOT));

        static final MapCodec<JarPackFunction> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                GOODS.fieldOf("goods").forGetter(JarPackFunction::goods)
        ).apply(instance, JarPackFunction::new));

        static LootItemFunction.Builder builder(PackSupply.Goods goods) {
            return () -> new JarPackFunction(goods);
        }

        @Override
        public LootItemFunctionType<JarPackFunction> getType() {
            return JAR_PACK.get();
        }

        @Override
        public ItemStack apply(ItemStack stack, LootContext context) {
            if (stack.isEmpty()) {
                return stack;
            }
            return PackSupplyHooks.stack(context.getLevel().registryAccess(), goods, stack.getCount());
        }
    }
}
