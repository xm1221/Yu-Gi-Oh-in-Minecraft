package cn.xm1221.ygomc.common.pack;

import cn.xm1221.ygomc.common.card.CardComponents;
import cn.xm1221.ygomc.common.card.CardItem;
import cn.xm1221.ygomc.common.card.CardRarity;
import cn.xm1221.ygomc.common.card.CardRef;
import cn.xm1221.ygomc.common.data.CardDataDb;
import cn.xm1221.ygomc.common.data.DataPacks;
import cn.xm1221.ygomc.common.net.PackNet;
import cn.xm1221.ygomc.common.pack.data.BoosterBoxDef;
import cn.xm1221.ygomc.common.pack.data.BoosterDef;
import cn.xm1221.ygomc.common.pack.data.BoxLot;
import cn.xm1221.ygomc.common.pack.data.PackDisplay;
import cn.xm1221.ygomc.common.pack.data.PackRegistries;
import cn.xm1221.ygomc.common.pack.data.PrintSpec;
import cn.xm1221.ygomc.common.pack.model.Booster;
import cn.xm1221.ygomc.common.pack.model.CardSetData;
import cn.xm1221.ygomc.common.pack.model.Collation;
import cn.xm1221.ygomc.common.pack.model.KnownPrints;
import cn.xm1221.ygomc.common.pack.model.Odds;
import cn.xm1221.ygomc.common.pack.model.PackRollException;
import cn.xm1221.ygomc.common.pack.model.PackRoller;
import cn.xm1221.ygomc.common.pack.model.PrintRef;
import cn.xm1221.ygomc.common.pack.model.Pull;
import cn.xm1221.ygomc.common.pack.model.RollSource;
import cn.xm1221.ygomc.common.registry.YgomcItems;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.function.IntPredicate;

/**
 * 开包和开盒。随机数来自单独的一把锁，不和世界掉落共用。
 * 结果先放进背包，再通知客户端；界面没打开也不影响卡已经到手。
 */
public final class PackOpening {

    private static final Object LOCK = new Object();
    private static final Random RANDOM = new Random(new SecureRandom().nextLong());

    private PackOpening() {
    }

    public static ItemStack packStack(Holder<BoosterDef> booster, int count) {
        ItemStack stack = new ItemStack(YgomcItems.CARD_PACK.get(), count);
        stack.set(CardComponents.BOOSTER.get(), booster);
        applyDisplay(stack, booster.value().display());
        return stack;
    }

    public static ItemStack boxStack(Holder<BoosterBoxDef> box) {
        ItemStack stack = new ItemStack(YgomcItems.BOOSTER_BOX.get());
        stack.set(CardComponents.BOOSTER_BOX.get(), box);
        applyDisplay(stack, box.value().display());
        return stack;
    }

    /** 右键一包。成功时扣掉一包并把卡放进背包。 */
    public static boolean openPack(ServerPlayer player, ItemStack stack) {
        return openPack(player, stack, true);
    }

    /**
     * @param announce 为假时不往动作条写字。开一整盒时由盒子自己汇总
     */
    public static boolean openPack(ServerPlayer player, ItemStack stack, boolean announce) {
        RegistryAccess access = player.registryAccess();
        Holder<BoosterDef> booster = CardPackItem.resolve(stack, access);
        if (booster == null || !booster.isBound()) {
            tell(player, "ygomc.pack.missing");
            return false;
        }
        BoosterDef def = booster.value();
        Booster mechanics = def.mechanics();
        CardSetData set = playable(def.set().value().toModel());
        if (set == null) {
            tell(player, "ygomc.pack.failed");
            return false;
        }
        BoxLot lot = stack.get(CardComponents.BOX_LOT.get());
        Pull fixed = null;
        PackLedger ledger = PackLedger.get(player);
        if (lot != null) {
            BoosterBoxDef box = box(access, lot.box());
            if (box == null) {
                tell(player, "ygomc.pack.missing");
                return false;
            }
            String pullId = Collation.plan(ledger.secret(), lot.lot(), lot.box().toString(), box.spec())[lot.index()];
            if (pullId != null) {
                fixed = mechanics.byId(pullId);
                if (fixed == null) {
                    tell(player, "ygomc.pack.failed");
                    return false;
                }
            }
            if (!ledger.claim(lot.lot(), lot.index(), lot.of())) {
                tell(player, "ygomc.pack.opened_twice");
                return false;
            }
        }
        List<PrintRef> cards;
        try {
            cards = roll(mechanics, set, fixed);
        } catch (PackRollException e) {
            if (lot != null) {
                ledger.release(lot.lot(), lot.index(), lot.of());
            }
            tell(player, "ygomc.pack.failed");
            return false;
        }
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        giveCards(player, cards);
        PackNet.send(player, cards);
        if (announce) {
            tell(player, "ygomc.pack.opened", cards.size());
        }
        return true;
    }

    /** 右键一盒：变成若干包。潜行则把盒里的包直接开成卡。 */
    public static boolean openBox(ServerPlayer player, ItemStack stack, boolean intoCards) {
        Holder<BoosterBoxDef> holder = stack.get(CardComponents.BOOSTER_BOX.get());
        if (holder == null || !holder.isBound()) {
            tell(player, "ygomc.pack.missing");
            return false;
        }
        BoosterBoxDef box = holder.value();
        ResourceLocation boxId = holder.unwrapKey().map(key -> key.location()).orElse(null);
        if (box.mode() == BoosterBoxDef.Mode.COLLATED && boxId == null) {
            tell(player, "ygomc.pack.failed");
            return false;
        }
        try {
            box.spec().check(box.booster().value().mechanics());
        } catch (RuntimeException e) {
            tell(player, "ygomc.pack.failed");
            return false;
        }
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        UUID lot = UUID.randomUUID();
        List<PrintRef> opened = new ArrayList<>();
        for (int index = 0; index < box.packs(); index++) {
            ItemStack pack = packStack(box.booster(), 1);
            if (box.mode() == BoosterBoxDef.Mode.COLLATED) {
                pack.set(CardComponents.BOX_LOT.get(), new BoxLot(lot, index, box.packs(), boxId));
            }
            if (intoCards) {
                if (!openPack(player, pack, false)) {
                    give(player, pack);
                }
            } else {
                give(player, pack);
            }
        }
        CardDataDb db = DataPacks.get().cardData();
        IntPredicate known = db == null ? null : db::contains;
        for (PrintSpec extra : box.extras()) {
            PrintRef ref = extra.toRef();
            if (!KnownPrints.keep(ref.code(), known)) {
                continue;
            }
            give(player, card(ref));
            opened.add(ref);
        }
        if (!opened.isEmpty()) {
            PackNet.send(player, opened);
        }
        tell(player, intoCards ? "ygomc.box.opened_cards" : "ygomc.box.opened", box.packs());
        return true;
    }

    public static List<Component> oddsLines(Booster booster) {
        List<Component> lines = new ArrayList<>();
        for (CardRarity rarity : CardRarity.values()) {
            double chance = Odds.atLeastOne(booster, rarity);
            if (chance <= 0) {
                continue;
            }
            lines.add(Component.translatable("ygomc.pack.odds",
                    Component.translatable("ygomc.rarity." + rarity.getSerializedName()),
                    String.format(Locale.ROOT, "%.2f", chance * 100)));
        }
        return lines;
    }

    /**
     * 按卡表丢掉未知卡密。滤完为空返回 {@code null}，调用方不要开包。
     * 没有卡表时只丢掉非正卡号，和 {@link cn.xm1221.ygomc.common.pack.data.PackCatalog} 同一套。
     */
    public static CardSetData playable(CardSetData expanded) {
        CardDataDb db = DataPacks.get().cardData();
        return KnownPrints.filter(expanded, db == null ? null : db::contains);
    }

    /** 模拟 {@code packs} 包，返回每种稀有度的张数。权限命令用，不进背包。池必须已经滤过未知卡密。 */
    public static long[] simulate(Booster booster, CardSetData set, int packs) {
        long[] counts = new long[CardRarity.values().length];
        synchronized (LOCK) {
            RollSource random = RANDOM::nextInt;
            for (int i = 0; i < packs; i++) {
                for (PrintRef card : PackRoller.open(booster, set, random)) {
                    counts[card.rarity().ordinal()]++;
                }
            }
        }
        return counts;
    }

    private static List<PrintRef> roll(Booster booster, CardSetData set, Pull fixed) {
        synchronized (LOCK) {
            RollSource random = RANDOM::nextInt;
            if (fixed == null) {
                return PackRoller.open(booster, set, random);
            }
            return PackRoller.open(booster, set, fixed, random);
        }
    }

    private static BoosterBoxDef box(RegistryAccess access, ResourceLocation id) {
        Registry<BoosterBoxDef> registry = access.registry(PackRegistries.BOOSTER_BOX).orElse(null);
        if (registry == null) {
            return null;
        }
        return registry.get(id);
    }

    private static void giveCards(ServerPlayer player, List<PrintRef> cards) {
        for (PrintRef card : cards) {
            give(player, card(card));
        }
    }

    private static ItemStack card(PrintRef print) {
        ItemStack stack = CardItem.stack(print.code(), print.rarity());
        if (print.variant() != 0) {
            stack.set(CardComponents.CARD_REF.get(), new CardRef(print.code(), print.rarity(), print.variant()));
        }
        return stack;
    }

    public static void give(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    private static void applyDisplay(ItemStack stack, PackDisplay display) {
        stack.set(DataComponents.ITEM_NAME, display.name());
        stack.set(DataComponents.RARITY, display.rarity());
    }

    private static void tell(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable(key, args), true);
    }
}
