package cn.xm1221.ygomc.common.duel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 对局界面与服务器之间的线格式：问题、牌桌、应答的编解码。
 *
 * <h2>为什么用 {@code DataOutput} 而不是 {@code FriendlyByteBuf}</h2>
 * 这个类要能被<b>离线验证</b>——把录制流里每一条真实询问编码再解码，
 * 检查解码后的问题是否仍然给出同一个应答。绑到 {@code FriendlyByteBuf} 上就
 * 必须跑起 Minecraft 才能测，而这一层恰恰是最容易「看起来对」的：
 * 字段少写一个、顺序写反，编译一点事都没有，只有在真实对局里才表现为
 * 「选项点不动」或者「答错卡」。
 *
 * <p>所以这里是纯 JDK 的编解码，平台侧只负责把 {@code byte[]} 塞进包。
 * 多一层拷贝，换掉一整类只能靠运气发现的错误。
 *
 * <h2>编码的原则是「解码后行为必须完全相同」</h2>
 * 保留的不是「界面画得出来」所需的字段，而是 {@link DuelQuestion#response}
 * 与 {@link DuelQuestion#defaultChoice} 会用到的<b>全部</b>字段。
 * 漏掉 {@code value} 会让单选类答错值，漏掉 {@code index} 会让多选类答错卡，
 * 漏掉 {@code controller/location/sequence} 会让选址类放错格。
 */
public final class DuelWire {

    /**
     * 问题 / 应答的格式版本。字段有任何增删都要 +1，让新旧两端明确不兼容而不是错位解读。
     *
     * <p>v2 起问题多了两个「求和选择」专用字段（目标合计值与强制卡参数表）。
     * 它们只在 {@code Mode.SUM} 下非零，但必须一起编进来：客户端要拿它们
     * 校验玩家的选择并拼出应答，缺了就只能自己再猜一遍。
     *
     * <p>v3 起多了一样东西：必发效果通知（{@link ChainNotice}）。它是牌桌帧尾巴上
     * 捎带的一份独立小载荷，但统一用一个版本号——装完新 jar 必须重启游戏，
     * 两端才在同一个版本上（otherwise 老客户端读到新帧会当场解码失败，这是有意的：
     * 错位解读比直接报错难查得多）。
     *
     * <p>v4 起界面的文案来源与语义跟着问题一起过线：{@link DuelQuestion.Title}
     * （标题该怎么取）与 {@link DuelQuestion.Option#labelKind()} /
     * {@link DuelQuestion.Option#action()}。这三样都是<b>语义标记</b>而不是译文——
     * 真正的中文/英文在客户端的语言资源里，服务端只告诉它「这一项是行动类的第 2 种」。
     * 不带它们过线的话，客户端只能拿中文 {@code label} 去 {@code startsWith}，
     * 那正是玩家报的「换语言就坏」。
     */
    public static final int VERSION = 4;

    /**
     * 牌桌快照的格式版本，<b>独立于 {@link #VERSION}</b>。
     *
     * <p>v2 起 {@code Zone} 多了一个卡号字段、{@code PlayerBoard} 多了四个逐张列表。
     * 版本号单独走是因为牌桌只在「出问题」时才发，改动节奏和问题/应答不一样；
     * 解码端<b>同时接受 1 与 2</b>，见 {@link #decodeBoard}。
     *
     * <p>v3 起牌桌顶上多了「阶段」与「回合数」两个字段（{@code chainCount} 之后）。
     * 它们不在内核快照里，是服务端从 {@code MSG_NEW_PHASE} / {@code MSG_NEW_TURN}
     * 另记后盖上去的（见 {@link DuelBoard#withPhaseTurn}）。解码端同时接受 2 与 3：
     * 收到 v2 帧时这两项就是「阶段未知、回合 0」。
     */
    public static final int BOARD_VERSION = 3;

    private DuelWire() {
    }

    // ── 问题 ──────────────────────────────────────────────────────────────

    public static byte[] encodeQuestion(DuelQuestion q) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(VERSION);
            out.writeInt(q.type());
            out.writeInt(q.player());
            out.writeByte(q.mode().ordinal());
            out.writeUTF(q.title());
            // v4：标题的取法。RAW 时客户端照 title 原文显示；否则去查语言资源。
            out.writeByte(q.titleText().kind().ordinal());
            out.writeUTF(q.titleText().key());
            out.writeUTF(q.titleText().rawHint());
            out.writeInt(q.titleText().args().length);
            for (String a : q.titleText().args()) {
                out.writeUTF(a == null ? "" : a);
            }
            out.writeInt(q.min());
            out.writeInt(q.max());
            out.writeBoolean(q.cancelable());
            out.writeInt(q.sumTarget());
            out.writeInt(q.forcedParams().length);
            for (int v : q.forcedParams()) {
                out.writeInt(v);
            }
            out.writeInt(q.options().size());
            for (DuelQuestion.Option o : q.options()) {
                out.writeUTF(o.label());
                out.writeInt(o.cardCode());
                out.writeInt(o.index());
                out.writeInt(o.value());
                out.writeInt(o.controller());
                out.writeInt(o.location());
                out.writeInt(o.sequence());
                // v4：这一项是哪一类名字，以及行动项的语义编号。
                out.writeByte(o.labelKind().ordinal());
                out.writeInt(o.action());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("编码问题失败", e);
        }
        return bytes.toByteArray();
    }

    public static DuelQuestion decodeQuestion(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            requireVersion(in.readInt());
            int type = in.readInt();
            int player = in.readInt();
            DuelQuestion.Mode mode = DuelQuestion.Mode.values()[in.readByte()];
            String title = in.readUTF();
            int titleKind = in.readByte();
            String titleKey = in.readUTF();
            String titleHint = in.readUTF();
            int titleArgCount = in.readInt();
            String[] titleArgs = new String[titleArgCount];
            for (int i = 0; i < titleArgCount; i++) {
                titleArgs[i] = in.readUTF();
            }
            int min = in.readInt();
            int max = in.readInt();
            boolean cancelable = in.readBoolean();
            int sumTarget = in.readInt();
            int forcedCount = in.readInt();
            int[] forcedParams = new int[forcedCount];
            for (int i = 0; i < forcedCount; i++) {
                forcedParams[i] = in.readInt();
            }
            int n = in.readInt();
            List<DuelQuestion.Option> options = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                options.add(new DuelQuestion.Option(in.readUTF(), in.readInt(), in.readInt(),
                        in.readInt(), in.readInt(), in.readInt(), in.readInt(),
                        DuelQuestion.Option.LabelKind.values()[in.readByte()], in.readInt()));
            }
            DuelQuestion.Title titleText = new DuelQuestion.Title(
                    DuelQuestion.Title.Kind.values()[titleKind], titleKey, titleHint, titleArgs);
            return new DuelQuestion(type, player, mode, title, options, min, max, cancelable,
                    sumTarget, forcedParams, titleText);
        } catch (IOException e) {
            throw new UncheckedIOException("解码问题失败", e);
        }
    }

    // ── 必发通知 ──────────────────────────────────────────────────────────

    /**
     * 编码「某某的效果发动（必发）」这条一次性通知。
     *
     * <p>与问题/应答共用 {@link #VERSION}：它是同一条协议里的东西，两端必须一起换。
     */
    public static byte[] encodeNotice(ChainNotice n) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(32);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(VERSION);
            out.writeInt(n.code());
            out.writeInt(n.description());
            out.writeInt(n.controller());
            out.writeInt(n.location());
            out.writeInt(n.sequence());
            out.writeInt(n.chainCount());
        } catch (IOException e) {
            throw new UncheckedIOException("编码必发通知失败", e);
        }
        return bytes.toByteArray();
    }

    public static ChainNotice decodeNotice(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            requireVersion(in.readInt());
            return new ChainNotice(in.readInt(), in.readInt(), in.readInt(), in.readInt(),
                    in.readInt(), in.readInt());
        } catch (IOException e) {
            throw new UncheckedIOException("解码必发通知失败", e);
        }
    }

    // ── 应答 ──────────────────────────────────────────────────────────────

    /**
     * 应答只有两种形状：一个整数，或者一串字节。
     *
     * <p>把「是哪一种」也编进去，而不是靠询问类型去推断——推断错了会在
     * 引擎那边表现为「答了个别的」而不是「格式不对」，比编一个字节贵得多。
     */
    public static byte[] encodeAnswer(Responder2 a) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(16);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(VERSION);
            out.writeBoolean(a.bytes() != null);
            if (a.bytes() != null) {
                out.writeInt(a.bytes().length);
                out.write(a.bytes());
            } else {
                out.writeInt(a.value());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("编码应答失败", e);
        }
        return bytes.toByteArray();
    }

    /** 只有两种形状，所以用一个小记录而不是复用 ocg 的 Response（免得又拉一条依赖）。 */
    public record Responder2(int value, byte[] bytes) {
        public static Responder2 of(int value) {
            return new Responder2(value, null);
        }

        public static Responder2 of(byte[] bytes) {
            return new Responder2(0, bytes);
        }
    }

    public static Responder2 decodeAnswer(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            requireVersion(in.readInt());
            if (in.readBoolean()) {
                byte[] b = new byte[in.readInt()];
                in.readFully(b);
                return Responder2.of(b);
            }
            return Responder2.of(in.readInt());
        } catch (IOException e) {
            throw new UncheckedIOException("解码应答失败", e);
        }
    }

    // ── 牌桌 ──────────────────────────────────────────────────────────────

    /**
     * 编码牌桌。
     *
     * <p>{@code null} 编成<b>空载荷</b>——这是收尾帧的约定（「这一局散了，把界面关掉」），
     * 与 {@link #decodeBoard} 的空载荷分支对称。
     *
     * <p>不这么写就会在收局那一刻炸 NPE：{@code DuelRoom.finish} 正是传 null 让它发收尾帧的，
     * 而 {@code b.duelRule()} 会当场抛。所以这个约定必须写在这里而不是靠调用方自觉——
     * 那条 NPE 以前被会话层的 {@code Throwable} 兜住，症状是「收局后界面不自己关、
     * 聊天里也没有『对局结束』」，看起来像网络问题，其实是这里。
     */
    public static byte[] encodeBoard(DuelBoard b) {
        if (b == null) {
            // 空数组与 null 在编/解码两侧含义相同；统一给空数组，因为
            // 有的载荷实现写 byte[] 时会先读长度，不接受 null。
            return new byte[0];
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(128);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(BOARD_VERSION);
            out.writeInt(b.duelRule());
            out.writeInt(b.chainCount());
            // v3 新增：阶段与回合。顺序固定为 阶段、回合，解码端按同一顺序读。
            out.writeInt(b.phase());
            out.writeInt(b.turn());
            encodePlayer(out, b.player0());
            encodePlayer(out, b.player1());
        } catch (IOException e) {
            throw new UncheckedIOException("编码牌桌失败", e);
        }
        return bytes.toByteArray();
    }

    private static void encodePlayer(DataOutputStream out, DuelBoard.PlayerBoard p)
            throws IOException {
        out.writeInt(p.lp());
        out.writeInt(p.deckCount());
        out.writeInt(p.handCount());
        out.writeInt(p.graveCount());
        out.writeInt(p.removedCount());
        out.writeInt(p.extraCount());
        out.writeInt(p.extraPCount());
        encodeZones(out, p.monsterZones());
        encodeZones(out, p.spellZones());
        // v2 新增：四个逐张列表。顺序固定为 手牌/墓地/除外/额外，
        // 解码端按同一顺序读；改顺序等于改版本号。
        encodeZones(out, p.hand());
        encodeZones(out, p.grave());
        encodeZones(out, p.removed());
        encodeZones(out, p.extra());
    }

    private static void encodeZones(DataOutputStream out, List<DuelBoard.Zone> zones)
            throws IOException {
        out.writeInt(zones.size());
        for (DuelBoard.Zone z : zones) {
            out.writeBoolean(z.occupied());
            out.writeByte(z.position());
            // 叠放数用 short：它可能超过 255（超量素材堆得很高时）。
            out.writeShort(z.overlayCount());
            // 卡号：0 = 未知/不可见。用 int 而不是 varint——
            // 卡号最大 8 位十进制，int 是唯一不用想边界的宽度。
            out.writeInt(z.code());
        }
    }

    /**
     * 解码牌桌。<b>同时接受版本 1、2 与 3</b>。
     *
     * <p>版本 1 是加卡号之前的线格式：没有 {@code code} 字段、也没有逐张列表。
     * 老服务端发来的 v1 帧因此仍然能解出来，只是所有 {@code Zone.code()} 都是 0、
     * 四个列表都是空的（手牌只剩 {@code handCount()}）。反过来，老客户端收到 v2 帧
     * 会在它自己的版本检查上拒绝——那一侧我们改不了，实际部署时两端是一起发的。
     *
     * <p>版本 3 多的是阶段与回合数（{@code chainCount} 之后）；v2 帧解码后这两项是
     * {@link DuelBoard#PHASE_UNKNOWN} 与 0，界面表现为「阶段条没有当前格」，
     * 与加这两个字段之前的行为一致。
     */
    public static DuelBoard decodeBoard(byte[] data) {
        // 收尾帧：服务端用【空载荷】表示「这一局散了，把界面关掉」。
        // 约定写在这里而不是让调用方判空，是因为这条路径以前会把 null 一路传到
        // encodeBoard 里炸掉（见 DuelRoom.finish 的注释）。
        if (data == null || data.length == 0) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int version = in.readInt();
            if (version != 1 && version != 2 && version != BOARD_VERSION) {
                throw new IllegalStateException("牌桌线格式版本不支持：收到 " + version
                        + "，本端支持 1（旧，无卡号）、2（有卡号）与 " + BOARD_VERSION + "（当前）");
            }
            int rule = in.readInt();
            int chain = in.readInt();
            int phase = version >= 3 ? in.readInt() : DuelBoard.PHASE_UNKNOWN;
            int turn = version >= 3 ? in.readInt() : 0;
            DuelBoard.PlayerBoard p0 = decodePlayer(in, version);
            DuelBoard.PlayerBoard p1 = decodePlayer(in, version);
            return new DuelBoard(rule, chain, p0, p1, phase, turn);
        } catch (IOException e) {
            throw new UncheckedIOException("解码牌桌失败", e);
        }
    }

    private static DuelBoard.PlayerBoard decodePlayer(DataInputStream in, int version)
            throws IOException {
        int lp = in.readInt();
        int deck = in.readInt();
        int hand = in.readInt();
        int grave = in.readInt();
        int removed = in.readInt();
        int extra = in.readInt();
        int extraP = in.readInt();
        // v1 与 v2 的场上两排布局相同，连字段宽度都没变——只有 code 是后加的。
        List<DuelBoard.Zone> monsters = decodeZones(in, version);
        List<DuelBoard.Zone> spells = decodeZones(in, version);
        if (version == 1) {
            return new DuelBoard.PlayerBoard(lp, monsters, spells, deck, hand, grave, removed,
                    extra, extraP);
        }
        List<DuelBoard.Zone> handList = decodeZones(in, version);
        List<DuelBoard.Zone> graveList = decodeZones(in, version);
        List<DuelBoard.Zone> removedList = decodeZones(in, version);
        List<DuelBoard.Zone> extraList = decodeZones(in, version);
        return new DuelBoard.PlayerBoard(lp, monsters, spells, deck, hand, grave, removed,
                extra, extraP, handList, graveList, removedList, extraList);
    }

    private static List<DuelBoard.Zone> decodeZones(DataInputStream in, int version)
            throws IOException {
        int n = in.readInt();
        List<DuelBoard.Zone> zones = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            boolean occupied = in.readBoolean();
            int position = in.readByte();
            int overlay = in.readShort();
            int code = version >= 2 ? in.readInt() : 0;
            zones.add(new DuelBoard.Zone(occupied, position, overlay, code));
        }
        return zones;
    }

    private static void requireVersion(int got) {
        if (got != VERSION) {
            throw new IllegalStateException("线格式版本不符：收到 " + got + "，本端是 " + VERSION);
        }
    }

    // ── 收局结果 ──────────────────────────────────────────────────────────

    /**
     * 结果尾巴的版本。
     *
     * <p>它挂在牌桌帧的<b>最末尾</b>（通知之后），与 {@link #BOARD_VERSION} 各自演进：
     * 牌桌内容改版时动 {@code BOARD_VERSION}，只动结果字段时动这个号。
     * 分成两个号是因为老客户端读到牌桌版本就停手了——尾巴多一段它看不见也不报错，
     * 而牌桌版本号一改它就会明确拒绝。
     */
    public static final int RESULT_VERSION = 1;

    /**
     * 编码收局结果。
     *
     * <p>版式：{@code i32 版本, u8 赢家, i16 原因, i32 P0 的 LP, i32 P1 的 LP, i32 回合数}。
     * 原因用 16 位：内核 {@code MSG_WIN} 里它是 u8，但值域来自 {@code !victory 0x…}
     * 那张表，留出 16 位就不必在想加码时改版式。LP 允许 {@link DuelResult#LP_UNKNOWN}
     * （负数），所以用 int 而不是无符号。
     */
    public static byte[] encodeResult(DuelResult r) {
        if (r == null) {
            return new byte[0];
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(32);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(RESULT_VERSION);
            out.writeByte(r.winner());
            out.writeShort(r.reason());
            out.writeInt(r.lp0());
            out.writeInt(r.lp1());
            out.writeInt(r.turns());
        } catch (IOException e) {
            throw new UncheckedIOException("编码收局结果失败", e);
        }
        return bytes.toByteArray();
    }

    /**
     * 解码收局结果；空载荷（没有这一段）返回 {@code null}。
     *
     * <p>空载荷是常态而不是异常：大多数帧根本没有结果，收尾帧（对局中断）也没有。
     */
    public static DuelResult decodeResult(byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int version = in.readInt();
            if (version != RESULT_VERSION) {
                throw new IllegalStateException("收局结果线格式版本不支持：收到 " + version
                        + "，本端是 " + RESULT_VERSION);
            }
            int winner = in.readUnsignedByte();
            int reason = in.readShort();
            int lp0 = in.readInt();
            int lp1 = in.readInt();
            int turns = in.readInt();
            return new DuelResult(winner, reason, lp0, lp1, turns);
        } catch (IOException e) {
            throw new UncheckedIOException("解码收局结果失败", e);
        }
    }
}
