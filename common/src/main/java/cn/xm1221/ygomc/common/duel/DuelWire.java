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

    /** 格式版本。字段有任何增删都要 +1，让新旧两端明确不兼容而不是错位解读。 */
    public static final int VERSION = 1;

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
            out.writeInt(q.min());
            out.writeInt(q.max());
            out.writeBoolean(q.cancelable());
            out.writeInt(q.options().size());
            for (DuelQuestion.Option o : q.options()) {
                out.writeUTF(o.label());
                out.writeInt(o.cardCode());
                out.writeInt(o.index());
                out.writeInt(o.value());
                out.writeInt(o.controller());
                out.writeInt(o.location());
                out.writeInt(o.sequence());
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
            int min = in.readInt();
            int max = in.readInt();
            boolean cancelable = in.readBoolean();
            int n = in.readInt();
            List<DuelQuestion.Option> options = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                options.add(new DuelQuestion.Option(in.readUTF(), in.readInt(), in.readInt(),
                        in.readInt(), in.readInt(), in.readInt(), in.readInt()));
            }
            return new DuelQuestion(type, player, mode, title, options, min, max, cancelable);
        } catch (IOException e) {
            throw new UncheckedIOException("解码问题失败", e);
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

    public static byte[] encodeBoard(DuelBoard b) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(128);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(VERSION);
            out.writeInt(b.duelRule());
            out.writeInt(b.chainCount());
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
    }

    private static void encodeZones(DataOutputStream out, List<DuelBoard.Zone> zones)
            throws IOException {
        out.writeInt(zones.size());
        for (DuelBoard.Zone z : zones) {
            out.writeBoolean(z.occupied());
            out.writeByte(z.position());
            // 叠放数用 short：它可能超过 255（超量素材堆得很高时）。
            out.writeShort(z.overlayCount());
        }
    }

    public static DuelBoard decodeBoard(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            requireVersion(in.readInt());
            int rule = in.readInt();
            int chain = in.readInt();
            DuelBoard.PlayerBoard p0 = decodePlayer(in);
            DuelBoard.PlayerBoard p1 = decodePlayer(in);
            return new DuelBoard(rule, chain, p0, p1);
        } catch (IOException e) {
            throw new UncheckedIOException("解码牌桌失败", e);
        }
    }

    private static DuelBoard.PlayerBoard decodePlayer(DataInputStream in) throws IOException {
        int lp = in.readInt();
        int deck = in.readInt();
        int hand = in.readInt();
        int grave = in.readInt();
        int removed = in.readInt();
        int extra = in.readInt();
        int extraP = in.readInt();
        List<DuelBoard.Zone> monsters = decodeZones(in);
        List<DuelBoard.Zone> spells = decodeZones(in);
        return new DuelBoard.PlayerBoard(lp, monsters, spells, deck, hand, grave, removed,
                extra, extraP);
    }

    private static List<DuelBoard.Zone> decodeZones(DataInputStream in) throws IOException {
        int n = in.readInt();
        List<DuelBoard.Zone> zones = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            zones.add(new DuelBoard.Zone(in.readBoolean(), in.readByte(), in.readShort()));
        }
        return zones;
    }

    private static void requireVersion(int got) {
        if (got != VERSION) {
            throw new IllegalStateException("线格式版本不符：收到 " + got + "，本端是 " + VERSION);
        }
    }
}
