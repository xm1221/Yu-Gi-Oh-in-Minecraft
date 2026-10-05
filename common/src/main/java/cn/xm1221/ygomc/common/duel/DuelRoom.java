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
import cn.xm1221.ygomc.common.ocg.SeatResponders;
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
 * 一局对战房间：把已经分别验证过的几件东西串起来。
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
 * 一个座位的真人由 {@link #humans} 记着，{@code null} 表示那一席是 AI
 * （兜底走 {@link FirstChoiceResponder}）。两种摆法：
 * <ul>
 *   <li>{@link #startFor}：人机。真人坐 {@value #HUMAN_SEAT} 号席，
 *       另一席是贪心 AI，<b>不等人</b>；</li>
 *   <li>{@link #startVersus}：双人。两席各一个真人，<b>两席都等人</b>，
 *       各自收自己那份牌桌（视角不同，卡号可见性也不同）。</li>
 * </ul>
 * 席位怎么摆、兜底链怎么接，全在 {@link SeatResponders} 里，那里写了为什么。
 *
 * <h2>为什么每席各存一份「提示」与「本步是否已发牌桌」</h2>
 * {@code MSG_HINT} 自带 {@code player} 字段，选择提示与时点都是<b>针对某一位玩家</b>
 * 说的话（「请选择要解放的卡」是问他的）。两人局里共用一份缓冲就会把
 * 甲的提示贴到乙的询问标题上。牌桌同理：一步里可能两位都诞生了询问，
 * 各发一次、各带各的问题，不能靠一个布尔值记。
 */
public final class DuelRoom implements OcgDuel.Observer {

    private static final Logger LOGGER = LoggerFactory.getLogger("ygomc/duel-room");

    /** 进行中的房间，按玩家 UUID 索引（双人局两个人的 UUID 都指向同一个房间）。 */
    private static final Map<UUID, DuelRoom> ACTIVE = new ConcurrentHashMap<>();

    /** 人机局里真人的席位；双人局里 0 号席也用它。 */
    public static final int HUMAN_SEAT = 0;

    /** 每个座位一个应答器。 */
    private final PlayerResponder[] responders = new PlayerResponder[2];

    /** 每个座位的真人；AI 座位是 {@code null}。 */
    private final ServerPlayer[] humans = new ServerPlayer[2];

    /** 每个座位最近一条选择提示（{@code HINT_SELECTMSG}）；用完即清。 */
    private final String[] selectHints = new String[2];

    /** 每个座位最近一条时点事件（{@code HINT_EVENT}）。 */
    private final String[] eventTexts = new String[2];

    /** 每个座位本「步」是否已经连牌桌一起发过。 */
    private final boolean[] boardSent = new boolean[2];

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

    /** {@code MSG_HINT} 的 hintType：1 = 时点事件、3 = 选择提示（同 ygo 的 HINT_EVENT/HINT_SELECTMSG）。 */
    private static final int HINT_EVENT = 1;
    private static final int HINT_SELECTMSG = 3;

    private DuelRoom() {
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
        if (ACTIVE.containsKey(player.getUUID())) {
            return "你已有一局在进行中";
        }
        DuelRoom room = new DuelRoom();
        room.humans[HUMAN_SEAT] = player;
        room.responders[HUMAN_SEAT] = SeatResponders.humanVsBot(HUMAN_SEAT, greedy(),
                q -> room.onQuestionOf(HUMAN_SEAT, q), msg -> room.notice(HUMAN_SEAT, msg));
        ACTIVE.put(player.getUUID(), room);
        room.armTimeout(HUMAN_SEAT);

        try {
            room.session = DuelSessions.start("room-" + player.getName().getString(),
                    new OcgDuel.DeckLoadout[]{loadout, opponentLoadout},
                    room.responders[HUMAN_SEAT], room, s -> room.finish(s));
        } catch (IllegalStateException e) {
            // 开局失败必须把房间撤掉，否则这个玩家会被永久记成「正在对局中」，
            // 之后再想开局只会得到「你已有一局在进行中」——一个自己造的锁死。
            ACTIVE.remove(player.getUUID());
            return "开局失败：" + e.getMessage();
        }
        return null;
    }

    /**
     * 两个真人各坐一席开一局。
     *
     * <p>与 {@link #startFor} 的唯一区别是<b>两席都等人</b>，而且两个人各有各的牌桌
     * （{@link FieldCodes#attach} 的视角座位不同，对手的里侧除外/额外卡组
     * 到各自客户端时卡号就是 0）。
     *
     * @param firstLoadout  0 号席的卡组
     * @param secondLoadout 1 号席的卡组
     * @return 开局失败的原因；成功返回 null
     */
    public static String startVersus(ServerPlayer first, OcgDuel.DeckLoadout firstLoadout,
                                     ServerPlayer second, OcgDuel.DeckLoadout secondLoadout) {
        if (first.getUUID().equals(second.getUUID())) {
            return "不能和自己对战";
        }
        if (ACTIVE.containsKey(first.getUUID())) {
            return first.getName().getString() + " 已有一局在进行中";
        }
        if (ACTIVE.containsKey(second.getUUID())) {
            return second.getName().getString() + " 已有一局在进行中";
        }
        DuelRoom room = new DuelRoom();
        room.humans[0] = first;
        room.humans[1] = second;
        PlayerResponder[] pair = SeatResponders.versus(greedy(),
                q -> room.onQuestionOf(0, q), msg -> room.notice(0, msg),
                q -> room.onQuestionOf(1, q), msg -> room.notice(1, msg));
        room.responders[0] = pair[0];
        room.responders[1] = pair[1];
        ACTIVE.put(first.getUUID(), room);
        ACTIVE.put(second.getUUID(), room);
        room.armTimeout(0);
        room.armTimeout(1);

        try {
            room.session = DuelSessions.start(
                    "room-" + first.getName().getString() + "-vs-" + second.getName().getString(),
                    new OcgDuel.DeckLoadout[]{firstLoadout, secondLoadout},
                    pair[0], room, s -> room.finish(s));
        } catch (IllegalStateException e) {
            ACTIVE.remove(first.getUUID());
            ACTIVE.remove(second.getUUID());
            return "开局失败：" + e.getMessage();
        }
        return null;
    }

    /** 两席都不认识时最后的兜底（正常不会用到，见 {@link SeatResponders}）。 */
    private static Responder greedy() {
        return new FirstChoiceResponder(DeclareCardName.packagedTable());
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
            //
            // 提示自带 player：它是【对某一位玩家】说的话，所以按座位分开存，
            // 免得把甲的提示贴到乙的询问标题上。
            int seat = h.player() == 1 ? 1 : 0;
            if (h.hintType() == HINT_EVENT) {
                eventTexts[seat] = DescText.getDesc(h.description());
            } else if (h.hintType() == HINT_SELECTMSG) {
                selectHints[seat] = DescText.selectMessage(h.description());
            }
        }
    }

    /**
     * 一步走完就同步一片牌桌——<b>不论是谁的回合、不论有没有问过人</b>。
     *
     * <p>以前只有「真人席位诞生新问题」时才发牌桌（{@link #onQuestionOf} 那一处），
     * 于是对手回合里整段时间一个包都不发：{@link PlayerResponder} 把对手席位的询问
     * 直接交给兜底应答并 return，根本不通知 listener。玩家的观感就是
     * 「对手干了什么完全看不到，轮到自己时画面一下子跳过去」。
     *
     * <p>同步只挂在这一处，不逐条消息发：一次 {@code advance()} 可能带回十几条消息，
     * 而客户端只画最后一帧。一步一片，正好。
     */
    @Override
    public void onStepEnd(OcgDuel duel) {
        for (int seat = 0; seat < 2; seat++) {
            boolean sent = boardSent[seat];
            boardSent[seat] = false;
            if (sent || humans[seat] == null) {
                // 这一席这一步末尾已经连问题一起发过了；AI 席位没有人收。
                continue;
            }
            YgomcNet.sendBoard(humans[seat], snapshot(duel, seat), null);
        }
    }

    /** 由 {@link PlayerResponder} 在问题诞生时同步调出，跑在对局线程上。 */
    private void onQuestionOf(int seat, DuelQuestion question) {
        ServerPlayer who = humans[seat];
        if (who == null) {
            // AI 席位的询问不发给任何人，也不记「已发过」——
            // 记了会让这一步的牌桌同步对真人那席也哑掉。
            return;
        }
        boardSent[seat] = true;
        YgomcNet.sendBoard(who, snapshot(currentDuel, seat), hintAware(seat, question));
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
    private DuelQuestion hintAware(int seat, DuelQuestion q) {
        if (eventTexts[seat] != null && (q.type() == MsgType.SELECT_EFFECTYN
                || q.type() == MsgType.SELECT_IDLECMD || q.type() == MsgType.SELECT_BATTLECMD)) {
            // 用完就清：陈旧的时点配一个新问句，比不显示更糟。
            String e = eventTexts[seat];
            eventTexts[seat] = null;
            return q.withTitle(e + "　" + q.title());
        }
        if (selectHints[seat] != null) {
            // 同上，用完就清。
            String h = selectHints[seat];
            selectHints[seat] = null;
            return q.withTitle(h);
        }
        return q;
    }

    /**
     * 取一份「某个座位视角」的牌桌快照；取不到就返回 null（只发问题，不发牌桌）。
     *
     * <p>必须跑在对局线程上：卡号是另外查出来的，内核句柄不能跨线程用。
     *
     * @param viewerSeat 以谁的视角取——对手的私有信息在这一步就被滤成卡号 0
     */
    private DuelBoard snapshot(OcgDuel duel, int viewerSeat) {
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
            return FieldCodes.attach(duel, board, viewerSeat);
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
        // 双人局要把两席都解开：只解开一席，另一席还会挂在那里。
        for (PlayerResponder r : room.responders) {
            if (r != null) {
                r.cancel();
            }
        }
        s.abort();
        return true;
    }

    /** 这一局是否已经有结果。超时判负只能判一次（两席都不动时先到点的那个判负）。 */
    private volatile boolean ended;

    /** 生效的超时判负秒数，只为报出准确数字，值来自服务端配置。 */
    private volatile int timeoutSeconds = DuelConfig.DEFAULT_TIMEOUT_SECONDS;

    /**
     * 给某一席的真人挂上超时判负。
     *
     * <p>只给真人席挂：对手那边是 {@code Responder}（AI 或托管），不会挂住等人。
     * 秒数来自 {@link DuelConfig}（Cloth Config，默认 100 秒）。
     */
    private void armTimeout(int seat) {
        PlayerResponder r = responders[seat];
        if (r == null) {
            return;
        }
        int seconds = DuelConfig.timeoutSeconds();
        timeoutSeconds = seconds;
        r.setTimeouts(seconds, 0);
        r.setOnTimeout(() -> timeoutLoss(seat));
    }

    /**
     * 某一席超时未操作，判他负。
     *
     * <p>照搬 ygopro {@code SingleDuel::Surrender}（single_duel.cpp:553-574）：
     * 它<b>不碰内核</b>——服务端自己造一条 3 字节
     * {@code MSG_WIN(winner = 1 - 该席, reason = 0)} 发给两边，然后 {@code EndDuel()}。
     * 内核里也没有「判负」这个 API（Lua 的 {@code Duel.Win} 是给卡片效果用的），
     * 所以判负本来就该由服务端说出口。
     *
     * <p>收摊手段与 {@link #abortFor} 相同：先让阻塞中的应答器解开——
     * 内核不响应线程中断，只能让它从 {@code answer} 里抛出来，对局线程才有机会
     * 走完整的收尾路径——再让会话停下。区别只在于「谁赢」由我们自己宣布。
     */
    private void timeoutLoss(int seat) {
        if (ended) {
            return;
        }
        ended = true;
        int winner = 1 - seat;
        notice(seat, "你超过 " + timeoutSeconds + " 秒没有操作，本局判负");
        notice(winner, "对方超时未操作，本局你获胜");
        for (PlayerResponder r : responders) {
            if (r != null) {
                r.cancel();
            }
        }
        DuelSession s = session;
        if (s != null) {
            s.abort();
        }
    }

    /** 是否有玩家正在对局中。 */
    public static boolean isDueling(ServerPlayer player) {
        return ACTIVE.containsKey(player.getUUID());
    }

    /** 某个玩家坐的是哪一席；不在这个房间返回 -1。 */
    private int seatOf(UUID id) {
        for (int seat = 0; seat < 2; seat++) {
            if (humans[seat] != null && humans[seat].getUUID().equals(id)) {
                return seat;
            }
        }
        return -1;
    }

    /**
     * 对某一席的玩家说一句话（超时提醒、超时代答）。
     *
     * <p>调用方可能在守护调度线程上，所以只是把动作排回服务器主线程；
     * 服务器已经停了就什么都不做——给一个已经断开的人发消息没有意义。
     */
    private void notice(int seat, String message) {
        ServerPlayer who = humans[seat];
        if (who == null) {
            return;
        }
        var server = who.getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            if (!who.hasDisconnected()) {
                who.displayClientMessage(Component.literal(message), false);
            }
        });
    }

    private void finish(DuelSession session) {
        ended = true;
        // 两个人的 UUID 都指向这个房间，撤的时候也要都撤掉，
        // 否则另一个人会被永久记成「正在对局中」。
        for (int seat = 0; seat < 2; seat++) {
            ServerPlayer who = humans[seat];
            if (who == null) {
                continue;
            }
            ACTIVE.remove(who.getUUID());
            // 用「null 牌桌 + null 问题」收尾：客户端据此关掉界面，
            // 而不是把最后一帧的按钮留在屏幕上让玩家空点。
            YgomcNet.sendBoard(who, null, null);
            who.displayClientMessage(Component.literal("对局结束"), false);
        }
    }

    // ── 服务器主线程 ──────────────────────────────────────────────────────

    /** 收到某个玩家的应答。返回 false 表示没有房间在等他。 */
    private static boolean acceptAnswer(UUID who, DuelWire.Responder2 answer) {
        DuelRoom room = ACTIVE.get(who);
        if (room == null) {
            return false;
        }
        int seat = room.seatOf(who);
        if (seat < 0 || room.responders[seat] == null) {
            return false;
        }
        Responder.Response r = answer.bytes() != null
                ? Responder.Response.of(answer.bytes())
                : Responder.Response.of(answer.value());
        // 交给【他自己那一席】的应答器：双人局里两个人的回信都走这里，
        // 交错了就会把甲的答案填到乙的询问上。
        return room.responders[seat].submit(r);
    }
}
