package cn.xm1221.ygomc.common.duel;

import cn.xm1221.ygomc.common.ocg.OcgDuel;
import cn.xm1221.ygomc.common.ocg.msg.Msg;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 把一局对局的原始消息流录成文件，供离线比对复用。
 *
 * <h2>为什么非要原始字节</h2>
 * 「玩家驱动的应答是否和贪心走同一条路」这个问题，靠读代码已经查不动了
 * （逐条核对过 7 种未验证类型的编码都与贪心一致，差异点仍未定位）。
 * 而离线比对工具 {@code QuestionEquiv} 吃的是原始流。
 * 用解出来的字段重新编码去喂它是不行的：那会引入第二个编码器，
 * 而「两个编码器互相印证」等于自证。
 *
 * <h2>格式</h2>
 * 与 {@code .agent/m1} 下的流一致：每段 {@code (u32 大端长度)(内容)}，无其他头部。
 * 这样同一个离线工具不用改就能读新录的流。
 *
 * <h2>它同时是观察点</h2>
 * 通过委托转发 {@link OcgDuel.Observer#onMessage}，所以录制可以和
 * {@link DuelSnapshotProbe} 之类叠在一起用，而不是二选一。
 */
public final class DuelStreamRecorder implements OcgDuel.Observer, AutoCloseable {

    private final OcgDuel.Observer delegate;
    private final OutputStream out;
    private long messages;
    private long bytes;
    private boolean broken;

    /**
     * @param file     目标文件（已存在则截断）
     * @param delegate 同时要跑的其他观察点；可为 null
     */
    public DuelStreamRecorder(Path file, OcgDuel.Observer delegate) throws IOException {
        this.delegate = delegate;
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.out = new BufferedOutputStream(Files.newOutputStream(file,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE), 1 << 16);
    }

    @Override
    public void onMessage(OcgDuel duel, Msg m, boolean awaitingAnswer) {
        if (delegate != null) {
            delegate.onMessage(duel, m, awaitingAnswer);
        }
    }

    @Override
    public void onRawMessage(byte[] buffer, int offset, int length) {
        if (broken) {
            return;
        }
        try {
            synchronized (this) {
                out.write(length >>> 24 & 0xFF);
                out.write(length >>> 16 & 0xFF);
                out.write(length >>> 8 & 0xFF);
                out.write(length & 0xFF);
                out.write(buffer, offset, length);
                messages++;
                bytes += length;
            }
        } catch (IOException e) {
            // 观察点抛出的异常会终止本局并记为失败。录制坏了就该把这一局判失败，
            // 而不是留一个「看起来跑完了但流是残缺的」文件去误导后面的比对。
            broken = true;
            throw new UncheckedIOException("录制消息流失败", e);
        }
    }

    public long messages() {
        synchronized (this) {
            return messages;
        }
    }

    public long bytes() {
        synchronized (this) {
            return bytes;
        }
    }

    public String report() {
        synchronized (this) {
            return "消息流录制：写入 " + messages + " 条，" + bytes + " 字节"
                    + (broken ? "（写入过程中出错，文件不完整）" : "");
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            try {
                out.flush();
                out.close();
            } catch (IOException e) {
                broken = true;
            }
        }
    }
}
