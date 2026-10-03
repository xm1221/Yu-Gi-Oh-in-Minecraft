package cn.xm1221.ygomc.data;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * 卡图数据包的随机访问读取器（{@code pics.bin}）。
 *
 * <h2>为什么不整个读进内存</h2>
 * 卡图包有 334 MB。{@code Files.readAllBytes} 会立刻吃掉这么多堆内存，
 * 而一局对局里真正需要显示的卡最多几十张。所以这里<b>只把索引读进内存</b>
 * （15017 张 × 20 字节 ≈ 300 KB），图片字节留在文件里，
 * 用到哪张才 {@link FileChannel#read(ByteBuffer, long) 定位读}那一段。
 *
 * <p>用位置式读取（带 {@code position} 参数的那个重载）还有个好处：
 * 它**不改变 channel 的当前位置**，因此可以被多个线程同时调用，
 * 不需要额外加锁。贴图解码本来就要放到工作线程，这一点很关键。
 *
 * <h2>两档尺寸</h2>
 * 同一张卡存了两份，用途不同：
 * <ul>
 *   <li>{@link #TIER_FULL} —— 200×290，对局界面里 1:1 显示、卡牌详情大图；</li>
 *   <li>{@link #TIER_ICON} —— 64×93，物品栏图标、世界里的小尺寸绘制。</li>
 * </ul>
 * 物品栏里一张卡最多占一两格，用 200×290 纯属浪费显存和解码时间。
 *
 * <h2>文件格式</h2>
 * <pre>
 *   0   magic "YGOMCPIC" (8)
 *   8   u32 version = 1
 *   12  u32 count
 *   16  u32 tierCount
 *   20  u32 entriesOffset （= 32）
 *   24  u32 dataOffset     （= 32 + count * (4 + tierCount * 8)）
 *   28  u32 dataBytes
 *   entries: count 项，每项 (4 + tierCount*8) 字节，按卡号<b>严格升序</b>
 *       u32 code
 *       每档 { u32 offset, u32 length }   offset 相对 dataOffset
 *   data:    各档 JPEG 字节首尾拼接
 * </pre>
 * 卡号升序是为了这里能二分查表。某张卡缺图时该档 {@code length = 0}，
 * {@link #image} 会返回 {@code null}，渲染端据此回退到通用卡背。
 *
 * <p>图片字节是 <b>JPEG</b>（不是 PNG）。解码要用 {@code ImageIO}，
 * 不能用 {@code NativeImage.read} —— 后者按 PNG 解析。实测同尺寸 PNG 要大 5 倍
 * （200×290 时 107 KB vs 20.3 KB），15017 张会从 297 MB 涨到 1.5 GB。
 *
 * <p>数据来源是 KONAMI 的卡图，<b>不随模组分发</b>，只从用户本地数据包读取。
 * 见 {@code THIRD_PARTY_NOTICES.md}。
 */
public final class CardImageDb implements Closeable {

    private static final byte[] MAGIC = "YGOMCPIC".getBytes(StandardCharsets.US_ASCII);
    private static final int HEADER_BYTES = 32;

    /** 200×290，给界面 1:1 显示用。 */
    public static final int TIER_FULL = 0;
    /** 64×93，给物品栏图标与小尺寸绘制用。 */
    public static final int TIER_ICON = 1;

    private final Path file;
    private final FileChannel channel;
    private final int[] codes;
    private final int[] offsets;          // 长度 count * tierCount，按 [卡号下标 * tierCount + 档位] 索引
    private final int[] lengths;
    private final int tierCount;
    private final long dataOffset;

    private CardImageDb(Path file, FileChannel channel, int[] codes, int[] offsets,
                        int[] lengths, int tierCount, long dataOffset) {
        this.file = file;
        this.channel = channel;
        this.codes = codes;
        this.offsets = offsets;
        this.lengths = lengths;
        this.tierCount = tierCount;
        this.dataOffset = dataOffset;
    }

    /**
     * 打开并解析索引。图片字节不读。
     *
     * @throws IOException 文件缺失、魔数/版本不符、索引越界或卡号非升序
     */
    public static CardImageDb open(Path file) throws IOException {
        long fileSize = Files.size(file);
        FileChannel ch = FileChannel.open(file, StandardOpenOption.READ);

        try {
            ByteBuffer head = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            readFully(ch, head, 0);
            head.flip();

            byte[] magic = new byte[8];
            head.get(magic);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new IOException(file + " 不是 pics.bin（魔数不符）");
            }
            int version = head.getInt();
            int count = head.getInt();
            int tierCount = head.getInt();
            int entriesOffset = head.getInt();
            int dataOffset = head.getInt();
            int dataBytes = head.getInt();

            if (version != 1) {
                throw new IOException("pics.bin 版本不支持: " + version);
            }
            if (tierCount <= 0 || tierCount > 8) {
                throw new IOException("pics.bin 档位数不合理: " + tierCount);
            }
            int entryBytes = 4 + tierCount * 8;
            long expectEntries = HEADER_BYTES;
            long expectData = expectEntries + (long) count * entryBytes;
            if (entriesOffset != expectEntries || dataOffset != expectData
                    || dataOffset + (long) dataBytes != fileSize) {
                throw new IOException("pics.bin 头部与文件长度不自洽"
                        + "（count=" + count + " tier=" + tierCount
                        + " entries=" + entriesOffset + " data=" + dataOffset
                        + "+" + dataBytes + " 实际=" + fileSize + "）");
            }

            // 索引只有 300 KB 左右，一次读完
            ByteBuffer idx = ByteBuffer.allocate((int) ((long) count * entryBytes))
                    .order(ByteOrder.LITTLE_ENDIAN);
            readFully(ch, idx, entriesOffset);
            idx.flip();

            int[] codes = new int[count];
            int[] offsets = new int[count * tierCount];
            int[] lengths = new int[count * tierCount];
            int prev = Integer.MIN_VALUE;
            for (int i = 0; i < count; i++) {
                codes[i] = idx.getInt();
                if (codes[i] <= prev) {
                    throw new IOException("pics.bin 卡号未严格升序，第 " + i + " 项为 "
                            + codes[i] + "（前一项 " + prev + "）；二分查表会失效");
                }
                prev = codes[i];
                for (int t = 0; t < tierCount; t++) {
                    int off = idx.getInt();
                    int len = idx.getInt();
                    if (off < 0 || len < 0 || (long) off + len > dataBytes) {
                        throw new IOException("pics.bin 第 " + i + " 项档 " + t
                                + " 越出数据区（off=" + off + " len=" + len
                                + " dataBytes=" + dataBytes + "）");
                    }
                    offsets[i * tierCount + t] = off;
                    lengths[i * tierCount + t] = len;
                }
            }
            return new CardImageDb(file, ch, codes, offsets, lengths, tierCount, dataOffset);
        } catch (IOException | RuntimeException e) {
            ch.close();
            throw e;
        }
    }

    /** 卡图张数。 */
    public int size() {
        return codes.length;
    }

    /** 档位数量（当前为 2）。 */
    public int tierCount() {
        return tierCount;
    }

    /** 这张卡有没有图。 */
    public boolean has(int code) {
        int i = indexOf(code);
        if (i < 0) {
            return false;
        }
        for (int t = 0; t < tierCount; t++) {
            if (lengths[i * tierCount + t] > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 读出某张卡某一档的 JPEG 字节。
     *
     * <p>这是本类唯一的 I/O 操作，每次只读这一张图（FULL 约 20 KB）。
     * 调用方应把它放在工作线程里，解码后再上传成 GPU 纹理。
     *
     * @param tier {@link #TIER_FULL} 或 {@link #TIER_ICON}
     * @return JPEG 字节；卡号不存在或该档缺图时返回 {@code null}
     */
    public byte[] image(int code, int tier) {
        if (tier < 0 || tier >= tierCount) {
            throw new IllegalArgumentException("档位越界: " + tier);
        }
        int i = indexOf(code);
        if (i < 0) {
            return null;
        }
        int len = lengths[i * tierCount + tier];
        if (len <= 0) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.allocate(len);
        try {
            readFully(channel, buf, dataOffset + offsets[i * tierCount + tier]);
        } catch (IOException e) {
            throw new RuntimeException("读卡图失败: " + file + " code=" + code
                    + " tier=" + tier, e);
        }
        return buf.array();
    }

    /** 某档的字节总长度，用于估算解码与显存开销。 */
    public long tierBytes(int tier) {
        long sum = 0;
        for (int i = 0; i < codes.length; i++) {
            sum += lengths[i * tierCount + tier];
        }
        return sum;
    }

    private int indexOf(int code) {
        int lo = 0, hi = codes.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int c = codes[mid];
            if (c == code) {
                return mid;
            }
            if (c < code) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return -1;
    }

    private static void readFully(FileChannel ch, ByteBuffer buf, long position)
            throws IOException {
        long p = position;
        while (buf.hasRemaining()) {
            int n = ch.read(buf, p);
            if (n < 0) {
                throw new IOException("文件提前结束（位置 " + p + "，还需 "
                        + buf.remaining() + " 字节）");
            }
            p += n;
        }
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    @Override
    public String toString() {
        return "CardImageDb[" + file.getFileName() + ", " + codes.length + " 张, "
                + tierCount + " 档, FULL " + (tierBytes(TIER_FULL) / 1048576) + " MB, ICON "
                + (tierBytes(TIER_ICON) / 1048576) + " MB]";
    }
}
