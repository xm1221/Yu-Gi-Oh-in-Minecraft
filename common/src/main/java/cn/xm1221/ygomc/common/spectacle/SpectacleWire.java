package cn.xm1221.ygomc.common.spectacle;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 演出事件列表的线格式（纯 JDK，可离线验）。
 *
 * <p>版本独立于牌桌帧：改字段只动这里。未知 {@code kind} 的单条跳过，
 * 整表版本不对则整表丢弃（返回空列表），避免拖垮牌桌解码。
 */
public final class SpectacleWire {

    /** 列表容器版本。 */
    public static final int VERSION = 1;

    private SpectacleWire() {
    }

    public static byte[] encode(List<SpectacleEvent> events) {
        if (events == null || events.isEmpty()) {
            return new byte[0];
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 + events.size() * 32);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(VERSION);
            out.writeInt(events.size());
            for (SpectacleEvent e : events) {
                out.writeByte(e.kind().wireId());
                out.writeInt(e.code());
                out.writeByte(e.controller());
                out.writeByte(e.location());
                out.writeByte(e.sequence());
                out.writeByte(e.targetController()); // -1..1 fits in signed byte
                out.writeByte(e.targetLocation());
                out.writeByte(e.targetSequence());
                out.writeInt(e.durationMs());
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("编码演出事件失败", ex);
        }
        return bytes.toByteArray();
    }

    public static List<SpectacleEvent> decode(byte[] data) {
        if (data == null || data.length == 0) {
            return List.of();
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int ver = in.readInt();
            if (ver != VERSION) {
                return List.of();
            }
            int n = in.readInt();
            if (n < 0 || n > 512) {
                return List.of();
            }
            List<SpectacleEvent> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int kindId = in.readUnsignedByte();
                SpectacleKind kind = SpectacleKind.fromWireId(kindId);
                int code = in.readInt();
                int controller = in.readUnsignedByte();
                int location = in.readUnsignedByte();
                int sequence = in.readUnsignedByte();
                int targetController = in.readByte(); // signed
                int targetLocation = in.readUnsignedByte();
                int targetSequence = in.readUnsignedByte();
                int durationMs = in.readInt();
                if (kind == null || durationMs < 0) {
                    continue;
                }
                out.add(new SpectacleEvent(kind, code, controller, location, sequence,
                        targetController, targetLocation, targetSequence, durationMs));
            }
            return Collections.unmodifiableList(out);
        } catch (IOException ex) {
            return List.of();
        }
    }
}
