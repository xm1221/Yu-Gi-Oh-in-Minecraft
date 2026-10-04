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
 * 卡名、卡文与效果说明（{@code texts.bin}）的只读索引。
 *
 * <h2>为什么不用 Map&lt;Integer, String&gt;</h2>
 * 15019 张卡如果启动时全部建 String，光卡文就要常驻几十 MB，而实战中<b>绝大多数卡</b>
 * 根本不会在界面上出现。所以这里只把「索引」解析成基本类型数组，
 * 文本留在字节池里，**用到哪张才解哪张**，解出来的卡名再进一个小缓存。
 *
 * <h2>文件格式（v2）</h2>
 * <pre>
 *   0   magic "YGOMCTX2" (8)
 *   8   u32 version = 2
 *   12  u32 count
 *   16  u32 indexOffset    （= 28）
 *   20  u32 poolOffset
 *   24  u32 poolBytes
 *   index: count × 148 字节
 *          { u32 code,
 *            u32 nameOff, u32 nameLen,
 *            u32 descOff, u32 descLen,
 *            16 × (u32 strOff, u32 strLen) }     str1..str16，空的写 len = 0
 *   pool : UTF-8 字节，偏移相对 pool 起点
 * </pre>
 * 卡号按升序排列，所以查表用二分而不是哈希表——省下一整张 HashMap。
 * 生成的文本已把 CRLF 归一化成 {@code \n}（desc 与 str1..str16 一视同仁）。
 *
 * <h2>str1..str16 是干什么的</h2>
 * 引擎给的 {@code description} 是个 u32，解码规则见 {@code DataPacks.desc(int)}：
 * 大于 2047 时高 28 位是卡号、低 4 位是 {@code n - 1}，取的就是这里的 {@code str(n)}。
 * 所以「发动效果显示『（说明 42）』」这个缺口是靠这张表补上的。
 *
 * <p><b>注意</b>：实测有 7174 张卡（48%）的 str1..str16 全是空的，
 * 调用方必须准备好「查到了但内容为空」的退路，否则界面上会是一片空白。
 *
 * <p>数据来源是 KONAMI 的卡牌文本，**不随模组分发**，只从用户本地的数据包读取。
 * 见 {@code THIRD_PARTY_NOTICES.md}。
 */
public final class CardTextDb {

    private static final byte[] MAGIC = "YGOMCTX2".getBytes(StandardCharsets.US_ASCII);
    /** v1 的魔数。读到它要明确报错，不能静默降级。 */
    private static final byte[] OLD_MAGIC = "YGOMCTX1".getBytes(StandardCharsets.US_ASCII);
    private static final int VERSION = 2;
    private static final int HEADER_BYTES = 28;
    /** texts 表的 str1..str16 条数。 */
    public static final int STR_COUNT = 16;
    /** 索引项：u32 卡号 + 4×u32（name/desc 的 off/len）+ 16×(u32 off, u32 len) = 148 */
    private static final int ENTRY_BYTES = 4 + 4 * 4 + STR_COUNT * 8;

    /** 卡名缓存上限。卡名在对局界面里会被反复取，卡文基本只取一次，所以只缓存卡名。 */
    private static final int NAME_CACHE_LIMIT = 4096;

    private final ByteBuffer pool;
    private final int[] codes;
    private final int[] nameOff;
    private final int[] nameLen;
    private final int[] descOff;
    private final int[] descLen;
    /** 每个卡号 16 条 str 的池内偏移与长度，下标 = 卡序 * 16 + (n - 1)。 */
    private final int[] strOff;
    private final int[] strLen;
    private final Map<Integer, String> nameCache = new HashMap<>();

    private CardTextDb(ByteBuffer pool, int[] codes, int[] nameOff, int[] nameLen,
                       int[] descOff, int[] descLen, int[] strOff, int[] strLen) {
        this.pool = pool;
        this.codes = codes;
        this.nameOff = nameOff;
        this.nameLen = nameLen;
        this.descOff = descOff;
        this.descLen = descLen;
        this.strOff = strOff;
        this.strLen = strLen;
    }

    /**
     * 读入并解析索引。文本本身仍是未解码的字节。
     *
     * @throws IOException 文件缺失、魔数不符、是旧版格式、版本不符或长度不自洽
     */
    public static CardTextDb load(Path file) throws IOException {
        byte[] all = Files.readAllBytes(file);
        ByteBuffer bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);

        byte[] magic = new byte[8];
        bb.get(magic);
        if (Arrays.equals(magic, OLD_MAGIC)) {
            // 不静默降级：静默降级的症状是「所有效果名都显示编号」+ 卡文全空，
            // 而那是最难被联想到数据层的一类故障。宁可起不来。
            throw new IOException(file + " 是旧版 texts.bin（魔数 YGOMCTX1，当前需要 YGOMCTX2）。"
                    + "请重跑 tools/mkdatapack.py 重新生成数据包");
        }
        if (!Arrays.equals(magic, MAGIC)) {
            throw new IOException(file + " 不是 texts.bin（魔数不符）");
        }
        int version = bb.getInt();
        int count = bb.getInt();
        int indexOffset = bb.getInt();
        int poolOffset = bb.getInt();
        int poolBytes = bb.getInt();

        if (version != VERSION) {
            throw new IOException("texts.bin 版本不支持: " + version
                    + "（需要 " + VERSION + "，请重跑 tools/mkdatapack.py）");
        }
        if (count < 0 || indexOffset != HEADER_BYTES
                || poolOffset != indexOffset + (long) count * ENTRY_BYTES
                || poolOffset + (long) poolBytes != all.length) {
            throw new IOException("texts.bin 头部与文件长度不自洽"
                    + "（count=" + count + " index=" + indexOffset
                    + " pool=" + poolOffset + "+" + poolBytes
                    + " 实际=" + all.length + "）");
        }

        int[] codes = new int[count];
        int[] nOff = new int[count];
        int[] nLen = new int[count];
        int[] dOff = new int[count];
        int[] dLen = new int[count];
        int[] sOff = new int[count * STR_COUNT];
        int[] sLen = new int[count * STR_COUNT];

        bb.position(indexOffset);
        int prev = Integer.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            codes[i] = bb.getInt();
            nOff[i] = bb.getInt();
            nLen[i] = bb.getInt();
            dOff[i] = bb.getInt();
            dLen[i] = bb.getInt();
            if (codes[i] <= prev) {
                throw new IOException("texts.bin 卡号未严格升序，第 " + i
                        + " 项为 " + codes[i] + "（前一项 " + prev + "）；二分查表会失效");
            }
            prev = codes[i];
            if (nOff[i] < 0 || nLen[i] < 0 || dOff[i] < 0 || dLen[i] < 0
                    || nOff[i] + (long) nLen[i] > poolBytes
                    || dOff[i] + (long) dLen[i] > poolBytes) {
                throw new IOException("texts.bin 第 " + i + " 项字符串越出字节池");
            }
            for (int k = 0; k < STR_COUNT; k++) {
                int o = bb.getInt();
                int l = bb.getInt();
                sOff[i * STR_COUNT + k] = o;
                sLen[i * STR_COUNT + k] = l;
                if (o < 0 || l < 0 || o + (long) l > poolBytes) {
                    throw new IOException("texts.bin 第 " + i + " 项的 str" + (k + 1)
                            + " 越出字节池");
                }
            }
        }

        // 只把字节池切出来，不解码
        byte[] pool = new byte[poolBytes];
        bb.position(poolOffset);
        bb.get(pool);
        return new CardTextDb(ByteBuffer.wrap(pool), codes, nOff, nLen, dOff, dLen, sOff, sLen);
    }

    public int size() {
        return codes.length;
    }

    /** @return 卡名；卡号不存在时返回 null */
    public String name(int code) {
        String hit = nameCache.get(code);
        if (hit != null) {
            return hit;
        }
        int i = indexOf(code);
        if (i < 0) {
            return null;
        }
        String s = decode(nameOff[i], nameLen[i]);
        if (nameCache.size() >= NAME_CACHE_LIMIT) {
            nameCache.clear();                     // 简单粗暴，但省掉了 LRU 的簿记开销
        }
        nameCache.put(code, s);
        return s;
    }

    /** @return 卡文（已把 CRLF 归一化为 {@code \n}）；卡号不存在时返回 null */
    public String desc(int code) {
        int i = indexOf(code);
        return i < 0 ? null : decode(descOff[i], descLen[i]);
    }

    /** @return 卡文是否为空（本数据集里有 2 张） */
    public boolean hasDesc(int code) {
        int i = indexOf(code);
        return i >= 0 && descLen[i] > 0;
    }

    /**
     * 取 texts 表里的 str1..str16。
     *
     * @param n 1..16
     * @return 说明文本；卡号不存在或 n 越界返回 null；
     *         <b>该条存在但内容为空时返回 ""</b>——
     *         本数据集里 48% 的卡 16 条全空，调用方要区别对待「没有这张卡」与「这条是空的」
     */
    public String str(int code, int n) {
        if (n < 1 || n > STR_COUNT) {
            return null;
        }
        int i = indexOf(code);
        if (i < 0) {
            return null;
        }
        int k = i * STR_COUNT + (n - 1);
        return decode(strOff[k], strLen[k]);
    }

    /** @return 第 n 条说明是否非空 */
    public boolean hasStr(int code, int n) {
        if (n < 1 || n > STR_COUNT) {
            return false;
        }
        int i = indexOf(code);
        return i >= 0 && strLen[i * STR_COUNT + (n - 1)] > 0;
    }

    /** 卡号是否存在于文本表。 */
    public boolean contains(int code) {
        return indexOf(code) >= 0;
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
        return "CardTextDb[" + codes.length + " 条, 字节池 "
                + pool.capacity() + " B]";
    }
}
