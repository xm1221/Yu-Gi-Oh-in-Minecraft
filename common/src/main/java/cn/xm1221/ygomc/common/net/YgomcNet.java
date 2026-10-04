package cn.xm1221.ygomc.common.net;

import cn.xm1221.ygomc.common.duel.DuelBoard;
import cn.xm1221.ygomc.common.duel.DuelQuestion;
import cn.xm1221.ygomc.common.duel.DuelWire;
import dev.architectury.networking.NetworkManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Consumer;

/**
 * 服务器与客户端之间的两个包：牌桌+问题（S→C）、应答（C→S）。
 *
 * <p>这一层只做「搬字节」：内容一律交给 {@link DuelWire} 编解码
 * （它已经用 7855 条真实询问验过无损）。这里多写一个字段就会绕过那层验证，
 * 所以本类里不允许出现任何对局语义。
 *
 * <h2>为什么载荷绕了一圈 byte[]</h2>
 * {@code FriendlyByteBuf} 也能直接写字段，但那会把编解码绑死在 Minecraft 上，
 * 变成必须跑起游戏才测得了。这里是「先在纯 JDK 里编好，再整体塞进去」，
 * 代价是一次拷贝，换来的是 {@link DuelWire} 那套离线验证继续有效。
 *
 * <h2>两个容易踩的平台细节</h2>
 * <ol>
 *   <li><b>S2C 的载荷类型必须注册</b>（{@code registerS2CPayloadType}）。
 *       1.20.5 之后自定义载荷要走原版的载荷注册表，漏了这步的典型症状是
 *       <b>客户端一连就断</b>（unknown payload），而服务端日志里什么都看不出来。
 *       签名是从 architectury-13.0.11.jar 里 javap 出来的，不是照记忆写的。</li>
 *   <li><b>收到的包跑在网络线程上</b>，不能直接碰游戏状态，必须
 *       {@code ctx.queue(...)} 回到主线程再动。应答要提交给等待中的对局线程，
 *       更要经过这一步。</li>
 * </ol>
 *
 * <h2>客户端代码的隔离</h2>
 * 这个类在 {@code common} 里，会被专用服务端加载，所以它<b>不能</b>引用
 * {@code Screen}/{@code Minecraft} 等客户端类。因此 S2C 的处理不是写在
 * 这里，而是由客户端把自己的处理函数交进来（{@link #registerClient}）。
 * 类里所有 lambda 只碰 common 类型，服务端加载它不会连带加载客户端类。
 */
/*
 * 【明知而沿用了整套遗留网络接口】
 *
 * 编译时发现 ResourceLocation 那一族方法【全部】被标记为弃用待删除，不只是
 * registerS2CPayloadType：接收器与两个发送方法同样如此。也就是说本类选的不是
 * 「一个过时方法」，而是整套旧接口。13.0.11 的替代品是原版 1.20.5+ 的
 * CustomPacketPayload.Type + StreamCodec。
 *
 * 为什么现在不换：这条链路【一次都没在真实客户端上跑过】。此刻换掉传输层，
 * 等于把「我写的载荷注册对不对」和「我写的界面/房间对不对」两个未验证面
 * 叠在一次运行里；一旦出问题，无从判断是哪一层的错。
 *
 * 迁移方向已定，且不难：自定义两个 CustomPacketPayload 记录
 * （BoardPayload / AnswerPayload），把 DuelWire 的 byte[] 放进它们的
 * StreamCodec，再把注册与发送换成带 Type 的那组重载。DuelWire 不用动——
 * 它本来就是纯 JDK 的，与平台接口无关，这正是当初那样分层的好处。
 *
 * 抑制范围只给本类：这个类的作用就是平台传输，别的类里再出现 removal 警告
 * 仍然会照常报出来。
 */
@SuppressWarnings("removal")
public final class YgomcNet {

    public static final ResourceLocation BOARD =
            ResourceLocation.fromNamespaceAndPath("ygomc", "duel_board");
    public static final ResourceLocation ANSWER =
            ResourceLocation.fromNamespaceAndPath("ygomc", "duel_answer");

    /** 客户端交进来的 S2C 处理函数：参数是已经解码好的牌桌与问题。 */
    private static Consumer<BoardUpdate> boardHandler;

    private YgomcNet() {
    }

    /** 一个 S→C 更新：牌桌 + 当前要问的问题（问题可能为 null，表示只是刷新牌桌）。 */
    public record BoardUpdate(DuelBoard board, DuelQuestion question) {
    }

    // ── 注册 ──────────────────────────────────────────────────────────────

    /** 两端都要调（放在 common 的初始化里）。 */
    public static void registerCommon() {
        // 这里【只】注册 C2S。S2C 的载荷类型由 registerClient 里那句
        // registerReceiver(Side.S2C, ...) 一并完成——Architectury 的 S2C 接收器
        // 注册路径会自己去注册载荷类型。
        //
        // 曾经在这里多写了一句 registerS2CPayloadType(BOARD)，于是同一个 ID 被注册两遍，
        // 客户端在模组加载阶段直接崩：
        //   UnsupportedOperationException: Cannot register payload ygomc:duel_board
        //   as it is already registered.
        // 只在客户端崩，因为 registerCommon 两端都跑、registerClient 只有客户端跑，
        // 只有客户端会同时走到这两条——服务端自检因此一直是绿的。
        NetworkManager.registerReceiver(NetworkManager.Side.C2S, ANSWER, YgomcNet::onAnswer);
    }

    /**
     * 由<b>客户端</b>代码调用，把自己的处理函数交进来。
     *
     * <p>参数里的回调会在主线程上被调用（已经过 {@code ctx.queue}），
     * 所以它可以安全地开界面。
     */
    public static void registerClient(Consumer<BoardUpdate> handler) {
        boardHandler = handler;
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, BOARD, (buf, ctx) -> {
            byte[] board = buf.readByteArray();
            boolean hasQuestion = buf.readBoolean();
            byte[] question = hasQuestion ? buf.readByteArray() : null;
            // 先解码再排队：解码是纯计算，放在网络线程上没问题，
            // 这样主线程拿到的已经是可用对象，也就把「解析失败」和「界面出错」
            // 这两类故障分到了不同的线程，排查时不会混在一起。
            BoardUpdate update = new BoardUpdate(DuelWire.decodeBoard(board),
                    hasQuestion ? DuelWire.decodeQuestion(question) : null);
            Consumer<BoardUpdate> h = boardHandler;
            if (h != null) {
                ctx.queue(() -> h.accept(update));
            }
        });
    }

    // ── 发送 ──────────────────────────────────────────────────────────────

    public static void sendBoard(ServerPlayer player, DuelBoard board, DuelQuestion question) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                io.netty.buffer.Unpooled.buffer(), player.registryAccess());
        buf.writeByteArray(DuelWire.encodeBoard(board));
        buf.writeBoolean(question != null);
        if (question != null) {
            buf.writeByteArray(DuelWire.encodeQuestion(question));
        }
        NetworkManager.sendToPlayer(player, BOARD, buf);
    }

    /**
     * 客户端提交一个应答。{@code answer} 由 {@link DuelWire#encodeAnswer} 产生。
     *
     * <p>要求调用方传 {@code RegistryAccess}。不传（也就是给 null）在本载荷下
     * 其实也能跑，但那只是「这个载荷恰好不读注册表」的巧合，换个载荷就变成 NPE——
     * 所以这里不给它留一个会静默生效的错误默认值。客户端手上就有
     * （{@code level.registryAccess()}）。
     */
    public static void sendAnswer(byte[] answer, net.minecraft.core.RegistryAccess registries) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                io.netty.buffer.Unpooled.buffer(), registries);
        buf.writeByteArray(answer);
        NetworkManager.sendToServer(ANSWER, buf);
    }

    // ── 服务端处理 ────────────────────────────────────────────────────────

    /** 由对局房间装入：收到应答后交给它。 */
    private static AnswerSink answerSink;

    /** 应答的去处。返回 true 表示被接收；false 表示没有在等这个玩家的应答。 */
    @FunctionalInterface
    public interface AnswerSink {
        boolean accept(java.util.UUID player, DuelWire.Responder2 answer);
    }

    public static void setAnswerSink(AnswerSink sink) {
        answerSink = sink;
    }

    private static void onAnswer(RegistryFriendlyByteBuf buf, NetworkManager.PacketContext ctx) {
        DuelWire.Responder2 answer = DuelWire.decodeAnswer(buf.readByteArray());
        java.util.UUID who = ctx.getPlayer().getUUID();
        AnswerSink sink = answerSink;
        // 回到主线程再提交：应答要唤醒等待中的对局线程，而那边会碰对局状态。
        ctx.queue(() -> {
            if (sink == null || !sink.accept(who, answer)) {
                // 说清楚是「没人等」而不是静默丢弃：这类问题最常见的成因是
                // 客户端发早了（问题还没到）或者界面没跟着状态清掉按钮。
                org.slf4j.LoggerFactory.getLogger("ygomc/net")
                        .warn("收到 {} 的应答但没有对局在等它，已丢弃", who);
            }
        });
    }
}
