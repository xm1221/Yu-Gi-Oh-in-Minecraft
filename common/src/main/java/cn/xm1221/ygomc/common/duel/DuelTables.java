package cn.xm1221.ygomc.common.duel;

import cn.xm1221.ygomc.common.collection.PlayerCollections;
import cn.xm1221.ygomc.common.command.YgomcCommand;
import cn.xm1221.ygomc.common.deck.DeckBoxItem;

import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 决斗台的候场与开局。房间仍是 {@link DuelRoom}，卡组仍是 {@link DuelLobby}，
 * 这里只回答「这一格上谁在等、谁正在打」。
 *
 * <p>键是维度加方块坐标，所以两台可以各打各的。玩家同时只能有一局
 * （{@link DuelRoom} 按人索引），候场也只占一台。台子被拆掉只丢掉这一格的绑定，
 * 不中止已经跑起来的 {@link DuelRoom}——台子本来就不是房间的存档。
 *
 * <p>只在服务器主线程上改这些表。交互、拆方块、玩家下线、停服都在那条线上。
 */
public final class DuelTables {

    /** 维度 id + {@link BlockPos#asLong()}。 */
    public record Key(String dimension, long pos) {
        public static Key of(Level level, BlockPos pos) {
            return new Key(level.dimension().location().toString(), pos.asLong());
        }
    }

    /** 一格上的绑定。{@code players == null} 表示还在候场，只有 {@link #host}。 */
    private static final class Binding {
        private UUID host;
        private UUID[] players;
        private String[] names;
        private boolean solo;
    }

    private static final Map<Key, Binding> TABLES = new ConcurrentHashMap<>();

    /** 正在候场的人 → 他占的那一格。开了局就从这里拿掉。 */
    private static final Map<UUID, Key> WAITING = new ConcurrentHashMap<>();

    private DuelTables() {
    }

    public static void init() {
        PlayerEvent.PLAYER_QUIT.register(DuelTables::forget);
        LifecycleEvent.SERVER_STOPPING.register(server -> clear());
    }

    /**
     * 右键决斗台。调用方已经确认这是服务端。
     *
     * <p>手持卡组盒（含末影卡盒）时，先把盒子里的卡组写成对战卡组，再按
     * {@link TableRules} 决定坐下、加入、对人机或离开。潜行是「现在就对人机」，
     * 普通右键是「坐下等另一个人」。
     */
    public static void interact(ServerPlayer player, Level level, BlockPos pos, InteractionHand hand) {
        if (!DuelRoom.isDueling(player)) {
            Component rejected = takeHeldDeck(player, hand);
            if (rejected != null) {
                say(player, rejected);
                return;
            }
        }
        Key key = Key.of(level, pos);
        dropIfDuelOver(player, key);
        Binding binding = TABLES.get(key);
        UUID id = player.getUUID();
        boolean live = binding != null && binding.players != null;
        boolean host = binding != null && binding.players == null && id.equals(binding.host);
        ServerPlayer waiting = hostPlayer(player, binding);
        DuelLobby.Seat mine = DuelLobby.selected(id);
        DuelLobby.Seat theirs = waiting == null ? null : DuelLobby.selected(waiting.getUUID());
        boolean hostDueling = waiting != null && DuelRoom.isDueling(waiting);

        TableRules.Action action = TableRules.decide(new TableRules.View(
                live ? TableRules.Phase.LIVE : binding == null ? TableRules.Phase.EMPTY : TableRules.Phase.WAITING,
                host,
                live && isParticipant(binding, id),
                DuelRoom.isDueling(player),
                waiting != null,
                mine != null,
                theirs != null,
                player.isShiftKeyDown(),
                hostDueling));

        switch (action) {
            case SIT -> sit(player, key, mine);
            case LEAVE -> {
                releaseWaiting(key, id);
                say(player, Component.translatable("ygomc.table.left"));
            }
            case START_SOLO -> startSolo(player, key, mine);
            case JOIN -> join(player, key, waiting, mine, theirs);
            case REOPEN -> {
                // 界面在客户端。方块交互两端都会跑，客户端那一侧已经调了 DuelDiskHooks。
            }
            case SPECTATE -> say(player, spectate(binding));
            case BUSY -> {
                if (host) {
                    // 人已经用别的入口开了局，这一格不该继续把他算成在等。
                    releaseWaiting(key, id);
                }
                say(player, Component.translatable("ygomc.table.busy"));
            }
            case HOST_OFFLINE -> {
                if (binding != null && binding.host != null) {
                    releaseWaiting(key, binding.host);
                }
                say(player, Component.translatable("ygomc.table.host_gone"));
            }
            case HOST_STARTED -> {
                if (binding != null && binding.host != null) {
                    releaseWaiting(key, binding.host);
                }
                say(player, Component.translatable("ygomc.table.host_dueling"));
            }
            case NEED_MY_DECK -> say(player, Component.translatable("ygomc.table.need_deck"));
            case NEED_THEIR_DECK -> say(player, Component.translatable("ygomc.table.need_their_deck",
                    waiting == null ? "" : waiting.getName().getString()));
        }
    }

    /** 方块被换掉或拆掉。只解这一格的绑定。 */
    public static void release(Level level, BlockPos pos) {
        if (level.isClientSide()) {
            return;
        }
        Binding binding = TABLES.remove(Key.of(level, pos));
        if (binding != null && binding.players == null && binding.host != null) {
            WAITING.remove(binding.host, Key.of(level, pos));
        }
    }

    /** 玩家下线。他若正在某台候场，把那一格腾出来。已经开的局不动。 */
    public static void forget(ServerPlayer player) {
        Key key = WAITING.remove(player.getUUID());
        if (key == null) {
            return;
        }
        Binding binding = TABLES.get(key);
        if (binding != null && binding.players == null && player.getUUID().equals(binding.host)) {
            TABLES.remove(key);
        }
    }

    private static void clear() {
        TABLES.clear();
        WAITING.clear();
    }

    private static void sit(ServerPlayer player, Key key, DuelLobby.Seat mine) {
        vacateOther(player, key);
        Binding binding = new Binding();
        binding.host = player.getUUID();
        TABLES.put(key, binding);
        WAITING.put(player.getUUID(), key);
        if (mine == null) {
            say(player, Component.translatable("ygomc.table.waiting_need_deck"));
        } else {
            say(player, Component.translatable("ygomc.table.waiting", mine.labelText()));
        }
    }

    private static void startSolo(ServerPlayer player, Key key, DuelLobby.Seat mine) {
        vacateOther(player, key);
        String problem = DuelRoom.startFor(player, mine.loadout(), YgomcCommand.opponentLoadoutForDuel());
        if (problem != null) {
            say(player, Component.literal(problem));
            return;
        }
        DuelLobby.clearDeck(player.getUUID());
        Binding binding = new Binding();
        binding.players = new UUID[]{player.getUUID()};
        binding.names = new String[]{player.getName().getString()};
        binding.solo = true;
        TABLES.put(key, binding);
        WAITING.remove(player.getUUID());
        say(player, Component.translatable("ygomc.table.started_solo",
                mine.labelText(), mine.loadout().main().length, mine.loadout().extra().length));
    }

    private static void join(ServerPlayer player, Key key, ServerPlayer host,
                             DuelLobby.Seat mine, DuelLobby.Seat theirs) {
        vacateOther(player, key);
        String problem = DuelRoom.startVersus(host, theirs.loadout(), player, mine.loadout());
        if (problem != null) {
            say(player, Component.literal(problem));
            return;
        }
        DuelLobby.clearDeck(host.getUUID());
        DuelLobby.clearDeck(player.getUUID());
        Binding binding = new Binding();
        binding.players = new UUID[]{host.getUUID(), player.getUUID()};
        binding.names = new String[]{host.getName().getString(), player.getName().getString()};
        TABLES.put(key, binding);
        WAITING.remove(host.getUUID());
        WAITING.remove(player.getUUID());
        say(host, Component.translatable("ygomc.table.started_you",
                theirs.labelText(), player.getName().getString(), mine.labelText()));
        say(player, Component.translatable("ygomc.table.started_you",
                mine.labelText(), host.getName().getString(), theirs.labelText()));
    }

    /** 这个人若在别的台子上候场，先让出来。正在打的那一格不动。 */
    private static void vacateOther(ServerPlayer player, Key here) {
        Key occupied = WAITING.get(player.getUUID());
        if (occupied != null && !occupied.equals(here)) {
            releaseWaiting(occupied, player.getUUID());
        }
    }

    /**
     * 这个人若还在某台候场，把那一席腾出来。
     * 指令或决斗盘开局成功时调用：席位不该继续显示「在等」，否则下一个人会去加入一场已经开始的对局。
     */
    public static void releaseWaiting(UUID player) {
        if (player == null) {
            return;
        }
        Key key = WAITING.get(player);
        if (key == null) {
            return;
        }
        releaseWaiting(key, player);
    }

    private static void releaseWaiting(Key key, UUID host) {
        Binding binding = TABLES.get(key);
        if (binding != null && binding.players == null && host.equals(binding.host)) {
            TABLES.remove(key);
        }
        WAITING.remove(host, key);
    }

    /**
     * 这一格记着一局，但两边都已经不在打了（收局不会回调到这里）。
     * 下一击就把它当成空台，而不是继续挡着。
     */
    private static void dropIfDuelOver(ServerPlayer viewer, Key key) {
        Binding binding = TABLES.get(key);
        if (binding == null || binding.players == null) {
            return;
        }
        var server = viewer.getServer();
        if (server == null) {
            return;
        }
        boolean any = false;
        for (UUID id : binding.players) {
            ServerPlayer who = server.getPlayerList().getPlayer(id);
            if (who != null && DuelRoom.isDueling(who)) {
                any = true;
                break;
            }
        }
        if (!any) {
            TABLES.remove(key);
        }
    }

    private static ServerPlayer hostPlayer(ServerPlayer viewer, Binding binding) {
        if (binding == null || binding.players != null || binding.host == null) {
            return null;
        }
        var server = viewer.getServer();
        if (server == null) {
            return null;
        }
        return server.getPlayerList().getPlayer(binding.host);
    }

    private static boolean isParticipant(Binding binding, UUID id) {
        return binding.players != null && Arrays.asList(binding.players).contains(id);
    }

    private static Component spectate(Binding binding) {
        String a = binding.names != null && binding.names.length > 0 ? binding.names[0] : "";
        if (binding.solo || binding.names == null || binding.names.length < 2) {
            return Component.translatable("ygomc.table.spectate_solo", a);
        }
        return Component.translatable("ygomc.table.spectate_pvp", a, binding.names[1]);
    }

    /**
     * 手里的卡组盒写成对战卡组。
     *
     * @return 校验失败的原因；手里不是卡组盒，或写成功，都是 {@code null}
     */
    private static Component takeHeldDeck(ServerPlayer player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof DeckBoxItem box)) {
            return null;
        }
        var deck = box.ender()
                ? PlayerCollections.get(player).deck(player.getUUID()).toDeckData()
                : DeckBoxItem.cards(stack).toDeckData();
        String label = box.ender() ? "item.ygomc.ender_deck_box" : "item.ygomc.deck_box";
        return DuelLobby.storeDeck(player, deck, label, true);
    }

    private static void say(Player player, Component message) {
        player.displayClientMessage(message, false);
    }
}
