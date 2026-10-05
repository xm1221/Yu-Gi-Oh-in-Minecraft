package cn.xm1221.ygomc.common.duel;

import cn.xm1221.ygomc.common.data.DescText;
import cn.xm1221.ygomc.common.net.YgomcNet;

import cn.xm1221.ygomc.common.ocg.DeclareCardName;
import cn.xm1221.ygomc.common.ocg.DuelSession;
import cn.xm1221.ygomc.common.ocg.DuelSessions;
import cn.xm1221.ygomc.common.ocg.FirstChoiceResponder;
import cn.xm1221.ygomc.common.ocg.OcgDuel;
import cn.xm1221.ygomc.common.ocg.PlayerResponder;
import cn.xm1221.ygomc.common.ocg.Responder;
import cn.xm1221.ygomc.common.ocg.msg.Msg;
import cn.xm1221.ygomc.common.ocg.msg.MsgType;

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
     * 这一局的会话句柄，用来中止它。
     *
     * <p>没有它的话，「玩家走开导致对局卡住」就只能靠重启服务器解决——
     * 而内核不响应中断，等它自己结束是等不到的。
     */
    private volatile DuelSession session;
    /**
     * 最近一次回调里的对局句柄。
     *
     * <p>只在<b>对局线程</b>上写、也只在对局线程上读（listener 是从
     * {@code PlayerResponder.answer} 里同步调出来的，同一条线程），
     * 所以不需要同步，更不能跨线程用——内核不能在对局线程之外碰。
     */
    private OcgDuel currentDuel;

    /** 本「步」里是否已经发过牌桌（{@link #onQuestion} 发的是带问题的那一份）。 */
    private boolean boardSentThisStep;

    /** {@code MSG_HINT} 的 hintType：1 = 时点事件、3 = 选择提示（同 ygo 的 HINT_EVENT/HINT_SELECTMSG）。 */
    private static final int HINT_EVENT = 1;
    private static final int HINT_SELECTMSG = 3;

    /** 最近一条选择提示的文本，用作随后那个选择框的标题；用完即清。 */
    private String selectHint;

    /** 最近一条时点事件的文本（「伤害步骤开始时」这类）；只贴在发动询问上，同 ygo。 */
    private String eventText;

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
     * @param loadout 玩家自己的卡组
     * @param opponentLoadout 对手（AI）的卡组。刻意与玩家那副分开：
     *        两边同一副时对手的牌路完全由玩家的卡组决定，
     *        测试时「换了玩家卡组」和「引擎行为变了」两件事就分不清了。
     * @return 开局失败的原因；成功返回 null
     */
    public static String startFor(ServerPlayer player, OcgDuel.DeckLoadout loadout,
                                  OcgDuel.DeckLoadout opponentLoadout) {
        UUID id = player.getUUID();
        if (ACTIVE.containsKey(id)) {
            return "你已有一局在进行中";
        }
        PlayerResponder responder =
                new PlayerResponder(new FirstChoiceResponder(DeclareCardName.packagedTable()));
        responder.setSeat(HUMAN_SEAT);
        DuelRoom room = new DuelRoom(player, responder, loadout);
        responder.setListener(room::onQuestion);
        // 超时兜底要在聊天栏说出来。这条通知来自守护调度线程，
        // 而给玩家发消息不是线程安全的，所以必须排回服务器主线程。
        responder.setNotice(room::notice);
        ACTIVE.put(id, room);

        try {
            room.session = DuelSessions.start("room-" + player.getName().getString(),
                    new OcgDuel.DeckLoadout[]{loadout, opponentLoadout},
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
        if (m instanceof Msg.Hint h) {
            // 这两种提示以前整个丢掉了：选择提示是「请选择要解放的卡」这类问句，
            // 时点是「伤害步骤开始时」这类「现在是什么时候」。
            // ygo 前者当选择框标题（client_field.cpp:1056），后者贴在
            // 「是否发动效果」上面（duelclient.cpp:1585）。
            if (h.hintType() == HINT_EVENT) {
                eventText = DescText.getDesc(h.description());
            } else if (h.hintType() == HINT_SELECTMSG) {
                selectHint = DescText.selectMessage(h.description());
            }
        }
    }

    /**
     * 一步走完就同步一片牌桌——<b>不论是谁的回合、不论有没有问到真人</b>。
     *
     * <p>以前只有「真人席位诞生新问题」时才发牌桌（{@link #onQuestion} 那一处），
     * 于是对手回合里整段时间一个包都不发：{@link PlayerResponder} 把对手席位的询问
     * 直接交给兜底应答并 return，根本不通知 listener。玩家的观感就是
     * 「对手干了什么完全看不到，轮到自己时画面一下子跳过去」。
     *
     * <p>同步只挂在这一处，不逐条消息发：一次 {@code advance()} 可能带回十几条消息，
     * 而客户端只画最后一帧。一步一片，正好。
     */
    @Override
    public void onStepEnd(OcgDuel duel) {
        if (boardSentThisStep) {
            // 这一步末尾问到了真人，那一份已经连牌桌一起发过了，不重复发。
            boardSentThisStep = false;
            return;
        }
        YgomcNet.sendBoard(player, snapshot(duel), null);
    }

    /** 由 {@link PlayerResponder} 在问题诞生时同步调出，跑在对局线程上。 */
    private void onQuestion(DuelQuestion question) {
        boardSentThisStep = true;
        YgomcNet.sendBoard(player, snapshot(currentDuel), hintAware(question));
    }

    /**
     * 把内核的提示并进询问标题。
     *
     * <p>照抄 ygo：{@code HINT_SELECTMSG} 的文本<b>就是</b>随后那个选择框的标题
     * （{@code client_field.cpp:1056} 的 {@code display_hint}，不是补充说明）；
     * {@code HINT_EVENT} 是贴在 {@code SELECT_EFFECTYN} 上面的一行
     * （{@code duelclient.cpp:1585} 的 {@code L"%ls\n%ls"}）——「什么时候」
     * 配上「要不要发动」才读得懂。
     *
     * <p>时点也贴给「选择行动」，这一条是我们自己加的：ygo 靠常驻阶段条显示现在是哪个阶段，
     * 我们的阶段条只说「能按哪个」、说不出「现在是抽卡阶段」。而抽卡阶段同样要问行动
     * （可以发动效果），不给阶段名，玩家看到的永远是「选择行动」，那就等于没告诉他在哪个阶段。
     */
    private DuelQuestion hintAware(DuelQuestion q) {
        if (eventText != null && (q.type() == MsgType.SELECT_EFFECTYN
                || q.type() == MsgType.SELECT_IDLECMD || q.type() == MsgType.SELECT_BATTLECMD)) {
            // 用完就清：陈旧的时点配一个新问句，比不显示更糟。
            String e = eventText;
            eventText = null;
            return q.withTitle(e + "　" + q.title());
        }
        if (selectHint != null) {
            // 同上，用完就清。
            String h = selectHint;
            selectHint = null;
            return q.withTitle(h);
        }
        return q;
    }

    /**
     * 取一份「本地玩家视角」的牌桌快照；取不到就返回 null（只发问题，不发牌桌）。
     *
     * <p>必须跑在对局线程上：卡号是另外查出来的，内核句柄不能跨线程用。
     */
    private DuelBoard snapshot(OcgDuel duel) {
        if (duel == null) {
            return null;
        }
        DuelBoard board;
        try {
            board = DuelBoard.of(duel.snapshot());
        } catch (RuntimeException e) {
            // 取快照失败不该把对局打死：牌桌这一帧画不出来，
            // 但问题本身是好的，玩家仍然能作答。
            LOGGER.warn("取牌桌快照失败，这一帧只发问题：{}", e.toString());
            return null;
        }
        try {
            // 快照只有形状没有卡号，卡号必须在这里、在对局线程上另查。
            // 界面能画出一张具体的卡，全靠这一步。
            return FieldCodes.attach(duel, board, HUMAN_SEAT);
        } catch (RuntimeException e) {
            // 卡号填不上就退回「只有形状的牌桌」：界面画卡背，
            // 总好过画一张错位的卡（两条路径对不上时 FieldCodes 会抛）。
            LOGGER.warn("查卡号失败，这一帧只发牌桌形状：{}", e.toString());
            return board;
        }
    }

    /**
     * 中止某个玩家正在进行的对局。返回是否真的中止了。
     *
     * <p>中止的方式是让阻塞中的应答器带着异常解开，而不是去杀线程——
     * 内核不响应中断，杀线程只会把它留在半途中；让它从 {@code answer} 里抛出去，
     * 对局线程才有机会走完整的收尾路径。
     */
    public static boolean abortFor(ServerPlayer player) {
        DuelRoom room = ACTIVE.get(player.getUUID());
        if (room == null) {
            return false;
        }
        DuelSession s = room.session;
        if (s == null) {
            return false;
        }
        // 先解开可能正挂着的等待，再让会话停下；两步都做是因为
        // 「正卡在等玩家」与「正在跑」这两种状态都要能收场。
        room.responder.cancel();
        s.abort();
        return true;
    }

    /** 是否有玩家正在对局中。 */
    public static boolean isDueling(ServerPlayer player) {
        return ACTIVE.containsKey(player.getUUID());
    }

    /**
     * 对玩家说一句话（超时提醒、超时代答）。
     *
     * <p>调用方可能在守护调度线程上，所以只是把动作排回服务器主线程；
     * 服务器已经停了就什么都不做——给一个已经断开的人发消息没有意义。
     */
    private void notice(String message) {
        var server = player.getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            if (!player.hasDisconnected()) {
                player.displayClientMessage(Component.literal(message), false);
            }
        });
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
