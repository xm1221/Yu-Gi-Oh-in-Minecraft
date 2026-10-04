package cn.xm1221.ygomc.common.duel;

import cn.xm1221.ygomc.common.net.YgomcNet;
import cn.xm1221.ygomc.common.ocg.DeclareCardName;
import cn.xm1221.ygomc.common.ocg.DuelSession;
import cn.xm1221.ygomc.common.ocg.DuelSessions;
import cn.xm1221.ygomc.common.ocg.FirstChoiceResponder;
import cn.xm1221.ygomc.common.ocg.OcgDuel;
import cn.xm1221.ygomc.common.ocg.PlayerResponder;
import cn.xm1221.ygomc.common.ocg.Responder;
import cn.xm1221.ygomc.common.ocg.msg.Msg;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一个玩家对本地贪心对手的对局房间：把已经分别验证过的几件东西串起来。
 *
 * <pre>
 *   对局线程 ── PlayerResponder.answer ── listener ──┐
 *                                                    ├─ snapshot() 取牌桌
 *                                                    └─ YgomcNet.sendBoard ── 客户端 DuelScreen
 *   服务器主线程 ── YgomcNet 收到应答 ── responder.submit ── 唤醒对局线程
 * </pre>
 *
 * <h2>它自己不解析任何东西</h2>
 * 牌桌来自内核快照（不靠逐条重建 MOVE/DRAW——重建错得安静，快照不会），
 * 问题来自 {@link DuelQuestion}，编码来自 {@link DuelWire}。
 * 这里只负责「什么时候取、发给谁、把回信交给谁」。
 *
 * <h2>席位</h2>
 * 真人坐 0 号位，1 号位交给 {@link FirstChoiceResponder}。
 * 这个划分必须显式告诉 {@link PlayerResponder}——否则它会为<b>双方</b>的询问
 * 阻塞等待，真人被问到对手该答的问题，界面上还会把对手的选项摆给他。
 * 这是 M1 自检遗留的缺口：那时两种应答都是自动的，所以看不出来。
 */
public final class DuelRoom implements OcgDuel.Observer {

    private static final Logger LOGGER = LoggerFactory.getLogger("ygomc/duel-room");

    /** 进行中的房间，按玩家 UUID 索引。用来把收到的应答交回正确的房间。 */
    private static final Map<UUID, DuelRoom> ACTIVE = new ConcurrentHashMap<>();

    /** 真人席位。对手在另一席，由 fallback 应答。 */
    private static final int HUMAN_SEAT = 0;

    private final ServerPlayer player;
    private final PlayerResponder responder;
    private final OcgDuel.DeckLoadout loadout;
    /**
     * 最近一次回调里的对局句柄。
     *
     * <p>只在<b>对局线程</b>上写、也只在对局线程上读（listener 是从
     * {@code PlayerResponder.answer} 里同步调出来的，同一条线程），
     * 所以不需要同步，更不能跨线程用——内核不能在对局线程之外碰。
     */
    private OcgDuel currentDuel;

    private DuelRoom(ServerPlayer player, PlayerResponder responder, OcgDuel.DeckLoadout loadout) {
        this.player = player;
        this.responder = responder;
        this.loadout = loadout;
    }

    // ── 开局 ──────────────────────────────────────────────────────────────

    /** 把应答的去处接上。两端都要能收到，所以由公共初始化调一次。 */
    public static void registerAnswerSink() {
        YgomcNet.setAnswerSink(DuelRoom::acceptAnswer);
    }

    /**
     * 为玩家开一局，对手是本地贪心。
     *
     * @param loadout 双方都用这一副（单人测试的简化；换成选项卡组是下一步的事）
     * @return 开局失败的原因；成功返回 null
     */
    public static String startFor(ServerPlayer player, OcgDuel.DeckLoadout loadout) {
        UUID id = player.getUUID();
        if (ACTIVE.containsKey(id)) {
            return "你已有一局在进行中";
        }
        PlayerResponder responder =
                new PlayerResponder(new FirstChoiceResponder(DeclareCardName.packagedTable()));
        responder.setSeat(HUMAN_SEAT);
        DuelRoom room = new DuelRoom(player, responder, loadout);
        responder.setListener(room::onQuestion);
        ACTIVE.put(id, room);

        try {
            DuelSessions.start("room-" + player.getName().getString(),
                    new OcgDuel.DeckLoadout[]{loadout, loadout},
                    responder, room, s -> room.finish(s));
        } catch (IllegalStateException e) {
            // 开局失败必须把房间撤掉，否则这个玩家会被永久记成「正在对局中」，
            // 之后再想开局只会得到「你已有一局在进行中」——一个自己造的锁死。
            ACTIVE.remove(id);
            return "开局失败：" + e.getMessage();
        }
        return null;
    }

    // ── 对局线程 ──────────────────────────────────────────────────────────

    @Override
    public void onMessage(OcgDuel duel, Msg m, boolean awaitingAnswer) {
        // 记下句柄，供随后同步触发的 listener 取快照用。
        currentDuel = duel;
    }

    /** 由 {@link PlayerResponder} 在问题诞生时同步调出，跑在对局线程上。 */
    private void onQuestion(DuelQuestion question) {
        OcgDuel duel = currentDuel;
        DuelBoard board = null;
        if (duel != null) {
            try {
                board = DuelBoard.of(duel.snapshot());
            } catch (RuntimeException e) {
                // 取快照失败不该把对局打死：牌桌这一帧画不出来，
                // 但问题本身是好的，玩家仍然能作答。
                LOGGER.warn("取牌桌快照失败，这一帧只发问题：{}", e.toString());
            }
            if (board != null) {
                try {
                    // 快照只有形状没有卡号，卡号必须在这里、在对局线程上另查。
                    // 界面能画出一张具体的卡，全靠这一步。
                    board = FieldCodes.attach(duel, board, HUMAN_SEAT);
                } catch (RuntimeException e) {
                    // 卡号填不上就退回「只有形状的牌桌」：界面画卡背，
                    // 总好过画一张错位的卡（两条路径对不上时 FieldCodes 会抛）。
                    LOGGER.warn("查卡号失败，这一帧只发牌桌形状：{}", e.toString());
                }
            }
        }
        YgomcNet.sendBoard(player, board, question);
    }

    private void finish(DuelSession session) {
        ACTIVE.remove(player.getUUID());
        // 用「null 牌桌 + null 问题」收尾：客户端据此关掉界面，
        // 而不是把最后一帧的按钮留在屏幕上让玩家空点。
        YgomcNet.sendBoard(player, null, null);
        player.displayClientMessage(Component.literal("对局结束"), false);
    }

    // ── 服务器主线程 ──────────────────────────────────────────────────────

    /** 收到某个玩家的应答。返回 false 表示没有房间在等他。 */
    private static boolean acceptAnswer(UUID who, DuelWire.Responder2 answer) {
        DuelRoom room = ACTIVE.get(who);
        if (room == null) {
            return false;
        }
        Responder.Response r = answer.bytes() != null
                ? Responder.Response.of(answer.bytes())
                : Responder.Response.of(answer.value());
        return room.responder.submit(r);
    }
}
