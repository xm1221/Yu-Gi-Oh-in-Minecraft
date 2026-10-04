package cn.xm1221.ygomc.common.data;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 系统文本的只读索引（{@code strings.bin}），来源是 ygopro 的 {@code strings.conf}。
 *
 * <h2>为什么需要它</h2>
 * 界面上一大半「看不懂的显示」都是缺了这张表：
 * <ul>
 *   <li>「位 0x40」——属性的系统文本（{@link #attributeName(int)}）；</li>
 *   <li>「可放 N 个」却没有指示物名——{@link #counterName(int)}；</li>
 *   <li>胜负原因说不清——{@link #victoryName(int)}。</li>
 * </ul>
 *
 * <h2>文件格式</h2>
 * <pre>
 *   0   magic "YGOMCST1" (8)
 *   8   u32 version = 1
 *   12  u32 systemCount
 *   16  u32 counterCount
 *   20  u32 victoryCount
 *   24  u32 setnameCount
 *   28  u32 indexOffset    （= 40）
 *   32  u32 poolOffset
 *   36  u32 poolBytes
 *   index: 四节依次排列，每节 count × 12 字节 { u32 id, u32 off, u32 len }，id 升序
 *   pool : UTF-8 字节，偏移相对 pool 起点
 * </pre>
 *
 * <h2>id 的口径（照官方，这里踩过坑）</h2>
 * <ul>
 *   <li>{@code !system} 的 id 是<b>十进制</b>；其余三节是<b>十六进制</b>，
 *       数据里写作 {@code 0x} 前缀（{@code !counter 0x1 魔力指示物}）。
 *       生成脚本已经统一成整数写进文件，所以这里不需要再关心原始写法。</li>
 *   <li>{@code !setname} 的文本在 {@code strings.conf} 里遇 tab 截断（官方拿 tab 做行内注释），
 *       这个截断在<b>生成时</b>做掉了：{@code !setname 0x1 正义盟军<TAB>A・O・J} 存进来的是
 *       「正义盟军」。这里读到的已经是截断后的结果。</li>
 * </ul>
 *
 * <p>数据来源是 ygopro 客户端（GPLv2）的 {@code strings.conf}，**不随模组分发**，
 * 只从用户本地的数据包读取。见 {@code THIRD_PARTY_NOTICES.md}。
 */
public final class StringsDb {

    private static final byte[] MAGIC = "YGOMCST1".getBytes(StandardCharsets.US_ASCII);
    private static final int VERSION = 1;
    private static final int HEADER_BYTES = 40;
    private static final int ENTRY_BYTES = 12;
    private static final int CACHE_LIMIT = 4096;

    /** 节的序号。与文件里的排列顺序一致，不要随意调换。 */
    public static final int SYSTEM = 0;
    public static final int COUNTER = 1;
    public static final int VICTORY = 2;
    public static final int SETNAME = 3;

    /** 属性名的编号起点：{@code !system 1010 + i}，i = 0..6。 */
    public static final int SYS_ATTRIBUTE = 1010;
    /** 种族名的编号起点：{@code !system 1020 + i}，i = 0..25。 */
    public static final int SYS_RACE = 1020;
    /** 类型名的编号起点：{@code !system 1050 + i}，i = 0..25。 */
    public static final int SYS_TYPE = 1050;

    private final ByteBuffer pool;
    private final int[][] ids = new int[4][];
    private final int[][] off = new int[4][];
    private final int[][] len = new int[4][];
    private final Map<Integer, String> cache = new HashMap<>();

    private StringsDb(ByteBuffer pool) {
        this.pool = pool;
    }

    /**
     * 读入并解析索引。文本本身仍是未解码的字节。
     *
     * @throws IOException 文件缺失、魔数不符、版本不符、长度不自洽或某节 id 未升序
     */
    public static StringsDb load(Path file) throws IOException {
        byte[] all = Files.readAllBytes(file);
        ByteBuffer bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);

        byte[] magic = new byte[8];
        bb.get(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new IOException(file + " 不是 strings.bin（魔数不符）。"
                    + "请重跑 tools/mkdatapack.py 重新生成数据包");
        }
        int version = bb.getInt();
        int nSystem = bb.getInt();
        int nCounter = bb.getInt();
        int nVictory = bb.getInt();
        int nSetname = bb.getInt();
        int indexOffset = bb.getInt();
        int poolOffset = bb.getInt();
        int poolBytes = bb.getInt();

        if (version != VERSION) {
            throw new IOException("strings.bin 版本不支持: " + version
                    + "（需要 " + VERSION + "，请重跑 tools/mkdatapack.py）");
        }
        int[] counts = {nSystem, nCounter, nVictory, nSetname};
        long total = 0;
        for (int c : counts) {
            if (c < 0) {
                throw new IOException("strings.bin 某节条数为负: " + c);
            }
            total += c;
        }
        if (indexOffset != HEADER_BYTES
                || poolOffset != indexOffset + total * ENTRY_BYTES
                || poolOffset + (long) poolBytes != all.length) {
            throw new IOException("strings.bin 头部与文件长度不自洽"
                    + "（四节 " + nSystem + "/" + nCounter + "/" + nVictory + "/" + nSetname
                    + " index=" + indexOffset + " pool=" + poolOffset + "+" + poolBytes
                    + " 实际=" + all.length + "）");
        }

        // 先把字节池切出来，再建实例 —— 顺序很重要：
        // 曾经写成「先建实例填索引、最后 return new StringsDb(pool)」，
        // 那个新实例的四节索引全是 null，任何一次查询都 NPE。
        // 编译期看不出来，只有真跑一次才会露。
        byte[] pool = new byte[poolBytes];
        bb.position(poolOffset);
        bb.get(pool);
        StringsDb db = new StringsDb(ByteBuffer.wrap(pool));

        bb.position(indexOffset);
        for (int sec = 0; sec < 4; sec++) {
            int n = counts[sec];
            int[] id = new int[n];
            int[] o = new int[n];
            int[] l = new int[n];
            int prev = Integer.MIN_VALUE;
            for (int i = 0; i < n; i++) {
                id[i] = bb.getInt();
                o[i] = bb.getInt();
                l[i] = bb.getInt();
                if (id[i] <= prev) {
                    throw new IOException("strings.bin 第 " + sec + " 节的 id 未严格升序，第 " + i
                            + " 项为 " + id[i] + "（前一项 " + prev + "）；二分查表会失效");
                }
                prev = id[i];
                if (o[i] < 0 || l[i] < 0 || o[i] + (long) l[i] > poolBytes) {
                    throw new IOException("strings.bin 第 " + sec + " 节第 " + i + " 项越出字节池");
                }
            }
            db.ids[sec] = id;
            db.off[sec] = o;
            db.len[sec] = l;
        }
        return db;
    }

    /** 四节的条数，顺序为 system / counter / victory / setname。 */
    public int count(int section) {
        return ids[section].length;
    }

    /** @return {@code !system <id>} 的文本；没有则 null */
    public String sys(int id) {
        return get(SYSTEM, id);
    }

    /** @return {@code !counter <id>} 的指示物名；没有则 null */
    public String counter(int id) {
        return get(COUNTER, id);
    }

    /** @return {@code !victory <id>} 的胜负原因；没有则 null */
    public String victory(int id) {
        return get(VICTORY, id);
    }

    /** @return {@code !setname <id>} 的系列名；没有则 null */
    public String setname(int id) {
        return get(SETNAME, id);
    }

    /**
     * 属性名。
     *
     * @param bit 内核给的属性位掩码（EARTH = 1、WATER = 2、…、DEVINE = 0x40）
     * @return 属性名；位不合法或查不到时 null
     */
    public String attributeName(int bit) {
        if (bit <= 0 || Integer.bitCount(bit) != 1) {
            return null;
        }
        return sys(SYS_ATTRIBUTE + Integer.numberOfTrailingZeros(bit));
    }

    /** @param bit 种族位掩码（内核顺序，1 起） @return 种族名；位不合法或查不到时 null */
    public String raceName(int bit) {
        if (bit <= 0 || Integer.bitCount(bit) != 1) {
            return null;
        }
        return sys(SYS_RACE + Integer.numberOfTrailingZeros(bit));
    }

    /** @param bit 类型位掩码（怪物类型 / 魔法陷阱类型，内核顺序，1 起） @return 类型名；查不到时 null */
    public String typeName(int bit) {
        if (bit <= 0 || Integer.bitCount(bit) != 1) {
            return null;
        }
        return sys(SYS_TYPE + Integer.numberOfTrailingZeros(bit));
    }

    private String get(int section, int id) {
        int key = (section << 24) | (id & 0xFFFFFF);
        String hit = cache.get(key);
        if (hit != null) {
            return hit;
        }
        int i = indexOf(ids[section], id);
        if (i < 0) {
            return null;
        }
        String s = decode(off[section][i], len[section][i]);
        if (cache.size() >= CACHE_LIMIT) {
            cache.clear();
        }
        cache.put(key, s);
        return s;
    }

    private static int indexOf(int[] sorted, int id) {
        int lo = 0, hi = sorted.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (sorted[mid] == id) {
                return mid;
            }
            if (sorted[mid] < id) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return -1;
    }

    private String decode(int off, int len) {
        if (len == 0) {
            return "";
        }
        ByteBuffer dup = pool.duplicate();
        dup.position(off);
        dup.limit(off + len);
        return StandardCharsets.UTF_8.decode(dup).toString();
    }

    @Override
    public String toString() {
        return "StringsDb[system=" + ids[SYSTEM].length + " counter=" + ids[COUNTER].length
                + " victory=" + ids[VICTORY].length + " setname=" + ids[SETNAME].length
                + ", 字节池 " + pool.capacity() + " B]";
    }
}
