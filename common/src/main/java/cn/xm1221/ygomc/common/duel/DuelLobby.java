package cn.xm1221.ygomc.common.duel;

import cn.xm1221.ygomc.common.ocg.OcgDuel;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 双人自选卡组的候场室。
 *
 * <p>流程（先以指令形式，咩咩要的是「能测」）：
 *
 * <pre>
 *   /ygomc duel deck 「卡组」   两位各自选一副（随时可改）
 *   /ygomc duel invite &lt;玩家&gt;   甲邀请乙
 *   /ygomc duel accept         乙接受 → 两副都在就开局
 * </pre>
 *
 * <h2>为什么要有这一层</h2>
 * {@link DuelRoom} 只认「两副卡组 + 两个玩家」，不管这两副是怎么来的。
 * 「谁选了哪副、谁邀请了谁、还差什么才能开局」是另一件事，混进房间类里
 * 会让那个类同时管对局与排队。所以这里只管候场，开局那一脚踢给
 * {@link DuelRoom#startVersus}。
 *
 * <p>「能不能开局」的判据在 {@link LobbyRules}（纯逻辑、能离线穷举），
 * 这里只负责查状态、说话、开局。这样分是因为本类引用了 {@code Component}，
 * 一旦加载就离不开 Minecraft 运行时——判据留在这里就跟着一块儿没法离线测了。
 *
 * <h2>刻意不做的事</h2>
 * 不做超时、不做队列：这是给测试用的入口，把「谁在等谁」摆到明面上比自动撮合
 * 更好查。要正式的对战大厅再写一个，别把测试入口改造成大厅。
 */
public final class DuelLobby {

    /** 某位玩家选的卡组。 */
    public record Seat(OcgDuel.DeckLoadout loadout, String label) {
    }

    /** 玩家 UUID → 他选的卡组。 */
    private static final Map<UUID, Seat> DECKS = new ConcurrentHashMap<>();

    /** 被邀请者 UUID → 邀请者 UUID。 */
    private static final Map<UUID, UUID> INVITES = new ConcurrentHashMap<>();

    private DuelLobby() {
    }

    /** 选一副卡组（改主意就再选一次）。对局中途不许改。 */
    public static void chooseDeck(ServerPlayer player, OcgDuel.DeckLoadout loadout, String label,
                                  int warnings) {
        if (DuelRoom.isDueling(player)) {
            say(player, "对局进行中，卡组不能改");
            return;
        }
        DECKS.put(player.getUUID(), new Seat(loadout, label));
        say(player, "双人局卡组已选：「" + label + "」"
                + (warnings > 0 ? "（有 " + warnings + " 项提示）" : ""));
    }

    /**
     * 甲邀请乙。
     *
     * @return 给甲看的一句话
     */
    public static String invite(ServerPlayer from, ServerPlayer to) {
        LobbyRules.Blocker blocker = LobbyRules.inviteBlocker(from.getUUID().equals(to.getUUID()),
                DuelRoom.isDueling(from), DuelRoom.isDueling(to));
        if (blocker != null) {
            return inviteMessage(blocker, to);
        }
        INVITES.put(to.getUUID(), from.getUUID());
        Seat mine = DECKS.get(from.getUUID());
        say(to, from.getName().getString() + " 邀请你对战。"
                + (mine == null ? "（他还没选卡组）" : "（他选了「" + mine.label() + "」）")
                + " 用 /ygomc duel deck <卡组> 选卡组、/ygomc duel accept 接受。");
        return "已邀请 " + to.getName().getString() + "；双方都选好卡组后由他 /ygomc duel accept。";
    }

    /**
     * 接受邀请并开局。
     *
     * @return 给接受者看的一句话
     */
    public static String accept(ServerPlayer me) {
        UUID inviterId = INVITES.get(me.getUUID());
        ServerPlayer inviter = inviterId == null || me.getServer() == null
                ? null
                : me.getServer().getPlayerList().getPlayer(inviterId);
        Seat mine = DECKS.get(me.getUUID());
        Seat his = inviter == null ? null : DECKS.get(inviter.getUUID());

        LobbyRules.Blocker blocker = LobbyRules.acceptBlocker(inviterId != null, inviter != null,
                DuelRoom.isDueling(me), inviter != null && DuelRoom.isDueling(inviter),
                mine != null, his != null);
        if (blocker != null) {
            if (blocker == LobbyRules.Blocker.INVITER_GONE) {
                // 人都不在了，这条邀请留着只会让他下次点接受时再看到一次坏消息。
                INVITES.remove(me.getUUID());
            }
            return acceptMessage(blocker, inviter);
        }

        String problem = DuelRoom.startVersus(inviter, his.loadout(), me, mine.loadout());
        if (problem != null) {
            return problem;
        }
        INVITES.remove(me.getUUID());
        // 两边都清掉卡组选择：这是「这一局用哪副」，不是「我以后一直用哪副」。
        // 留着会让下一局悄悄沿用上一局的卡组，看起来像「换的卡组没生效」。
        DECKS.remove(inviter.getUUID());
        DECKS.remove(me.getUUID());
        say(inviter, "对局开始：你「" + his.label() + "」对 " + me.getName().getString()
                + "「" + mine.label() + "」");
        return "对局开始：你「" + mine.label() + "」对 " + inviter.getName().getString()
                + "「" + his.label() + "」";
    }

    private static String inviteMessage(LobbyRules.Blocker blocker, ServerPlayer to) {
        return switch (blocker) {
            case SELF -> "不能邀请自己";
            case ME_DUELING -> "你已有一局在进行中";
            default -> to.getName().getString() + " 已有一局在进行中";
        };
    }

    private static String acceptMessage(LobbyRules.Blocker blocker, ServerPlayer inviter) {
        String name = inviter == null ? "对方" : inviter.getName().getString();
        return switch (blocker) {
            case NO_INVITE -> "现在没有人邀请你";
            case INVITER_GONE -> "邀请你的人已经不在线了";
            case ME_DUELING -> "你已有一局在进行中";
            case THEM_DUELING -> name + " 已经开局了";
            case MY_DECK -> "你还没选卡组，先用 /ygomc duel deck <卡组>";
            default -> name + " 还没选卡组，等他选好再 /ygomc duel accept";
        };
    }

    /** 玩家下线时清掉他的候场状态。 */
    public static void forget(ServerPlayer player) {
        DECKS.remove(player.getUUID());
        INVITES.remove(player.getUUID());
        INVITES.values().removeIf(id -> id.equals(player.getUUID()));
    }

    /** 候场状态（给状态命令/排查用）。 */
    public static String describe(ServerPlayer player) {
        Seat seat = DECKS.get(player.getUUID());
        UUID inviter = INVITES.get(player.getUUID());
        return "候场：卡组 " + (seat == null ? "未选" : "「" + seat.label() + "」")
                + "，邀请 " + (inviter == null ? "无" : inviter.toString());
    }

    private static void say(ServerPlayer player, String message) {
        var server = player.getServer();
        if (server == null) {
            return;
        }
        // 命令是从主线程来的，但别的入口将来也可能调，统一排回主线程更稳。
        server.execute(() -> {
            if (!player.hasDisconnected()) {
                player.displayClientMessage(Component.literal(message), false);
            }
        });
    }
}
