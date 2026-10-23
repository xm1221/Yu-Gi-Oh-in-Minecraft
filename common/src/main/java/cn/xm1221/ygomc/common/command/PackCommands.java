package cn.xm1221.ygomc.common.command;

import cn.xm1221.ygomc.common.Ygomc;
import cn.xm1221.ygomc.common.card.CardRarity;
import cn.xm1221.ygomc.common.pack.PackOpening;
import cn.xm1221.ygomc.common.pack.data.BoosterBoxDef;
import cn.xm1221.ygomc.common.pack.data.BoosterDef;
import cn.xm1221.ygomc.common.pack.data.BoosterDef.OddsOrigin;
import cn.xm1221.ygomc.common.pack.data.PackRegistries;
import cn.xm1221.ygomc.common.pack.model.Booster;
import cn.xm1221.ygomc.common.pack.model.CardSetData;
import cn.xm1221.ygomc.common.pack.model.Odds;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** {@code /ygomc pack}：发放、概率和模拟。发放与模拟需要权限 2。 */
public final class PackCommands {

    private PackCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("ygomc").then(Commands.literal("pack")
                .then(Commands.literal("give")
                        .requires(PackCommands::op)
                        .then(Commands.argument("id", ResourceLocationArgument.id())
                                .suggests((ctx, builder) -> suggest(ctx, builder, PackRegistries.BOOSTER))
                                .executes(ctx -> give(ctx.getSource(), id(ctx), 1))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                        .executes(ctx -> give(ctx.getSource(), id(ctx),
                                                IntegerArgumentType.getInteger(ctx, "count"))))))
                .then(Commands.literal("box")
                        .requires(PackCommands::op)
                        .then(Commands.argument("id", ResourceLocationArgument.id())
                                .suggests((ctx, builder) -> suggest(ctx, builder, PackRegistries.BOOSTER_BOX))
                                .executes(ctx -> box(ctx.getSource(), id(ctx)))))
                .then(Commands.literal("odds")
                        .then(Commands.argument("id", ResourceLocationArgument.id())
                                .suggests((ctx, builder) -> suggest(ctx, builder, PackRegistries.BOOSTER))
                                .executes(ctx -> odds(ctx.getSource(), id(ctx)))))
                .then(Commands.literal("simulate")
                        .requires(PackCommands::op)
                        .then(Commands.argument("id", ResourceLocationArgument.id())
                                .suggests((ctx, builder) -> suggest(ctx, builder, PackRegistries.BOOSTER))
                                .then(Commands.argument("packs", IntegerArgumentType.integer(1, 1_000_000))
                                        .executes(ctx -> simulate(ctx.getSource(), id(ctx),
                                                IntegerArgumentType.getInteger(ctx, "packs"))))))));
    }

    private static boolean op(CommandSourceStack source) {
        return source.hasPermission(2);
    }

    /**
     * 原版的 id 参数在没写命名空间时收成 {@code minecraft:}。
     * 不能换一种自定义参数类型：没在 {@code ArgumentTypeInfos} 登记的参数
     * 会让指令树发不出去，玩家进世界时被踢，提示是「无效的玩家数据」。
     * 所以这里只把那个默认命名空间改成 {@code ygomc}。写了别的命名空间则照写。
     */
    private static ResourceLocation id(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ResourceLocation raw = ResourceLocationArgument.getId(ctx, "id");
        if (ResourceLocation.DEFAULT_NAMESPACE.equals(raw.getNamespace())) {
            return ResourceLocation.fromNamespaceAndPath(Ygomc.MOD_ID, raw.getPath());
        }
        return raw;
    }

    private static <T> CompletableFuture<Suggestions> suggest(CommandContext<CommandSourceStack> ctx,
                                                              SuggestionsBuilder builder,
                                                              ResourceKey<Registry<T>> key) {
        Registry<T> registry = registries(ctx.getSource()).registry(key).orElse(null);
        if (registry == null) {
            return builder.buildFuture();
        }
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        boolean typedNamespace = remaining.indexOf(':') >= 0;
        for (ResourceLocation location : registry.keySet()) {
            String shown = typedNamespace ? location.toString() : location.getPath();
            if (shown.startsWith(remaining)) {
                builder.suggest(shown);
            }
        }
        return builder.buildFuture();
    }

    private static int give(CommandSourceStack source, ResourceLocation id, int count) throws CommandSyntaxException {
        Holder<BoosterDef> booster = holder(source, PackRegistries.BOOSTER, id);
        if (booster == null) {
            return missing(source, id);
        }
        ServerPlayer player = player(source);
        if (player == null) {
            return 0;
        }
        int left = count;
        while (left > 0) {
            int stack = Math.min(16, left);
            PackOpening.give(player, PackOpening.packStack(booster, stack));
            left -= stack;
        }
        source.sendSuccess(() -> Component.translatable("ygomc.pack.given", count, id.toString()), true);
        return count;
    }

    private static int box(CommandSourceStack source, ResourceLocation id) {
        Holder<BoosterBoxDef> box = holder(source, PackRegistries.BOOSTER_BOX, id);
        if (box == null) {
            return missing(source, id);
        }
        ServerPlayer player = player(source);
        if (player == null) {
            return 0;
        }
        PackOpening.give(player, PackOpening.boxStack(box));
        source.sendSuccess(() -> Component.translatable("ygomc.box.given", id.toString()), true);
        return 1;
    }

    private static int odds(CommandSourceStack source, ResourceLocation id) {
        Holder<BoosterDef> booster = holder(source, PackRegistries.BOOSTER, id);
        if (booster == null) {
            return missing(source, id);
        }
        Booster mechanics = booster.value().mechanics();
        source.sendSuccess(() -> Component.translatable("ygomc.pack.odds_head", id.toString()), false);
        OddsOrigin origin = booster.value().oddsOrigin();
        if (origin == OddsOrigin.PRINT_WEIGHT || origin == OddsOrigin.SHEET_ADAPTED) {
            source.sendSuccess(() -> Component.translatable(
                    "ygomc.pack.odds_not_real",
                    Component.translatable("ygomc.pack.odds_origin." + origin.getSerializedName())), false);
        }
        for (Component line : PackOpening.oddsLines(mechanics)) {
            source.sendSuccess(() -> line, false);
        }
        return 1;
    }

    private static int simulate(CommandSourceStack source, ResourceLocation id, int packs) {
        Holder<BoosterDef> booster = holder(source, PackRegistries.BOOSTER, id);
        if (booster == null) {
            return missing(source, id);
        }
        Booster mechanics = booster.value().mechanics();
        CardSetData set = PackOpening.playable(booster.value().set().value().toModel());
        if (set == null) {
            source.sendFailure(Component.translatable("ygomc.pack.failed"));
            return 0;
        }
        long[] counts = PackOpening.simulate(mechanics, set, packs);
        source.sendSuccess(() -> Component.translatable("ygomc.pack.simulate_head", packs, id.toString()), false);
        for (CardRarity rarity : CardRarity.values()) {
            double expected = packs * Odds.expectedCount(mechanics, rarity);
            long got = counts[rarity.ordinal()];
            source.sendSuccess(() -> Component.translatable("ygomc.pack.simulate_line",
                    Component.translatable("ygomc.rarity." + rarity.getSerializedName()),
                    got,
                    String.format(Locale.ROOT, "%.1f", expected)), false);
        }
        return packs;
    }

    private static <T> Holder<T> holder(CommandSourceStack source,
                                        net.minecraft.resources.ResourceKey<Registry<T>> key,
                                        ResourceLocation id) {
        Registry<T> registry = registries(source).registry(key).orElse(null);
        if (registry == null) {
            return null;
        }
        Holder<T> found = registry.getHolder(id).orElse(null);
        if (found != null) {
            return found;
        }
        // 文件在 data/<命名空间>/ygomc/booster/ 下，id 有时和命令里写的命名空间不一致。按路径再对一次。
        for (ResourceLocation location : registry.keySet()) {
            if (location.getPath().equals(id.getPath())) {
                return registry.getHolder(location).orElse(null);
            }
        }
        return null;
    }

    /** 数据包注册表在重载后的这份里，不在启动时那份静态注册表里。 */
    private static net.minecraft.core.RegistryAccess registries(CommandSourceStack source) {
        ReloadableServerRegistries.Holder reloadable = source.getServer().reloadableRegistries();
        return reloadable != null ? reloadable.get() : source.getServer().registryAccess();
    }

    private static int missing(CommandSourceStack source, ResourceLocation id) {
        source.sendFailure(Component.translatable("ygomc.pack.unknown", id.toString()));
        return 0;
    }

    private static ServerPlayer player(CommandSourceStack source) {
        try {
            return source.getPlayerOrException();
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            source.sendFailure(Component.translatable("ygomc.pack.need_player"));
            return null;
        }
    }
}
