package cn.xm1221.ygomc.data;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * 卡牌结构化数据（{@code cards.bin}）：既喂给引擎，也给界面读攻守/属性。
 *
 * <h2>两个用途，同一份数据</h2>
 * <ul>
 *   <li><b>喂引擎</b>：{@link #codes()} + {@link #blob()} 原样交给
 *       {@code Ocg.putCards}，一次性把 15019 张推进 native 内存缓存。
 *       引擎要的是内核的 {@code card_data} 结构体布局，所以这里必须原样保留字节。</li>
 *   <li><b>给界面</b>：卡牌 tooltip 要显示星级/属性/种族/攻守。这些字段就在同一个
 *       80 字节结构体里，所以不必再单独存一份——直接按偏移解出来即可。</li>
 * </ul>
 *
 * <h2>字段偏移（与内核 card_data.h 严格对应）</h2>
 * <pre>
 *   0  u32 code         印刷卡号
 *   4  u32 alias        异画/异判指向的卡号，0 表示没有
 *   8  u16 setcode[16]  64 字节信息拆成的 16 位槽
 *   40 u32 type         卡种位掩码
 *   44 u32 level        星级
 *   48 u32 attribute    属性位掩码
 *   52 u32 race         种族位掩码
 *   56 i32 attack
 *   60 i32 defense      （连接怪兽此处为连接标记，见下）
 *   64 u32 lscale       左灵摆刻度
 *   68 u32 rscale       右灵摆刻度
 *   72 u32 link_marker
 *   76 u32 rule_code    决斗中实际使用的卡号，0 表示用 alias/code
 * </pre>
 * <b>注意 cdb 与 card_data 的形态不同</b>：{@code cards.cdb} 把星级与左右灵摆刻度
 * 打包在同一个 {@code level} 列里（{@code 0xLLRR00LV}），而 {@code card_data}
 * 把它们拆成三个独立字段。{@code tools/mkdatapack.py} 已经完成了这个拆分，
 * 所以本类读到的 {@code level} 就是纯星级。
 * 内核有 {@code static_assert(sizeof(card_data) == 80)}（{@code card_data.h:86}），
 * 本类在读取时也校验 80，任何一侧改动都会立刻暴露。
 *
 * <p><b>连接怪兽的坑</b>：内核在写入时把 {@code link_marker = defense; defense = 0;}
 * （{@code data_manager.cpp}），所以连接怪兽的 {@code defense} 字段恒为 0，
 * 连接标记在 {@link Stats#linkMarker()} 里。
 *
 * <p><b>alias 的坑</b>：内核 {@code card_data.h:76} 把 {@code alias} 注释成
 * 「卡面上印刷的卡号」（{@code get_original_code() = alias ? alias : code}），
 * 但<b>照它去查卡名会显示成另一张卡</b>。实测本数据集里
 * {@code 295517 传说之都 亚特兰蒂斯} 的 alias 是 {@code 22702055 海}，
 * 而 {@code datas} 与 {@code texts} 的 id 集合完全相同（每张卡都有自己的卡文行）。
 * 所以 alias 是<b>规则别名</b>（卡名视为某某），不是「异画指向原型」。
 * 详情与实测数据见 {@link DataPack} 的类注释。
 * <b>显示卡名/卡文/卡图一律用卡片自身卡号，不要用 {@link Stats#originalCode()}。</b>
 */
public final class CardDataDb {

    private static final byte[] MAGIC = "YGOMCCD1".getBytes(StandardCharsets.US_ASCII);
    private static final int HEADER_BYTES = 20;
    private static final int RECORD_BYTES = 84;      // u32 code + 80 字节 card_data
    private static final int STRUCT_BYTES = 80;

    private final Path file;
    private final int[] codes;
    private final byte[] blob;

    private CardDataDb(Path file, int[] codes, byte[] blob) {
        this.file = file;
        this.codes = codes;
        this.blob = blob;
    }

    /**
     * 读入。这个文件只有 1.2 MB，整个读进来是对的——引擎本来就要一次性拿走全部字节。
     *
     * @throws IOException 文件缺失、魔数/版本/记录长度不符、卡号非升序
     */
    public static CardDataDb load(Path file) throws IOException {
        byte[] all = Files.readAllBytes(file);
        ByteBuffer bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);

        byte[] magic = new byte[8];
        bb.get(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new IOException(file + " 不是 cards.bin（魔数不符）");
        }
        int version = bb.getInt();
        int count = bb.getInt();
        int record = bb.getInt();
        if (version != 1) {
            throw new IOException("cards.bin 版本不支持: " + version);
        }
        if (record != RECORD_BYTES) {
            throw new IOException("cards.bin 记录长度是 " + record
                    + "，期望 " + RECORD_BYTES + "（card_data 应当是 " + STRUCT_BYTES + " 字节）");
        }
        if (HEADER_BYTES + (long) count * record != all.length) {
            throw new IOException("cards.bin 头部与文件长度不自洽（count=" + count
                    + " record=" + record + " 实际=" + all.length + "）");
        }

        int[] codes = new int[count];
        byte[] blob = new byte[count * STRUCT_BYTES];
        int prev = Integer.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            codes[i] = bb.getInt();
            if (codes[i] <= prev) {
                throw new IOException("cards.bin 卡号未严格升序，第 " + i + " 项为 "
                        + codes[i] + "（前一项 " + prev + "）");
            }
            prev = codes[i];
            bb.get(blob, i * STRUCT_BYTES, STRUCT_BYTES);
        }
        return new CardDataDb(file, codes, blob);
    }

    /** 卡号数组，直接交给 {@code Ocg.putCards}。 */
    public int[] codes() {
        return codes;
    }

    /** 80 字节结构体首尾拼接，直接交给 {@code Ocg.putCards}。 */
    public byte[] blob() {
        return blob;
    }

    public int size() {
        return codes.length;
    }

    /** @return 该卡号在数组里的下标；不存在返回 -1 */
    public int indexOf(int code) {
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

    public boolean contains(int code) {
        return indexOf(code) >= 0;
    }

    private int u32(int index, int off) {
        int p = index * STRUCT_BYTES + off;
        return (blob[p] & 0xFF) | ((blob[p + 1] & 0xFF) << 8)
                | ((blob[p + 2] & 0xFF) << 16) | ((blob[p + 3] & 0xFF) << 24);
    }

    /**
     * 取出某张卡的字段视图。
     *
     * @return 卡号不在表里时返回 {@code null}
     */
    public Stats stats(int code) {
        int i = indexOf(code);
        if (i < 0) {
            return null;
        }
        int alias = u32(i, 4);
        return new Stats(
                code,
                alias,
                u32(i, 40),     // type
                // level 在 cards.bin 里已经是<b>纯星级</b>：cdb 把星级与灵摆刻度打包在同一个
                // level 列里，但生成脚本已按内核的做法把它们拆成了三个独立字段
                // （见 tools/mkdatapack.py）。这里若再去取 level 的高 16 位会恒得 0。
                u32(i, 44),     // level
                u32(i, 64),     // lscale
                u32(i, 68),     // rscale
                u32(i, 48),     // attribute
                u32(i, 52),     // race
                u32(i, 56),     // attack
                u32(i, 60),     // defense
                u32(i, 72),     // link_marker
                u32(i, 76));    // rule_code
    }

    /**
     * 一张卡的字段视图。
     *
     * <p>字段含义见 {@link CardDataDb} 的类注释。这里是<b>只读快照</b>，
     * 不持有任何 native 资源，可以自由缓存或跨线程传递。
     */
    public record Stats(int code, int alias, int type, int level, int lscale, int rscale,
                        int attribute, int race, int attack, int defense,
                        int linkMarker, int ruleCode) {

        /**
         * 规则别名目标（{@code card_data.h:76}），0 表示没有。
         *
         * <p><b>这是规则用途，不是显示用途。</b>它表示「这张卡的卡名视为 alias 那张卡」，
         * 例如 {@code 295517 传说之都 亚特兰蒂斯} 的 alias 是 {@code 22702055 海}。
         * 查卡名/卡文/卡图请直接用 {@link #code()}，不要用这个值，否则会显示成另一张卡。
         */
        public int originalCode() {
            return alias != 0 ? alias : code;
        }

        /** 决斗中实际使用的卡号，供引擎规则使用（{@code card_data.h:81}）。同样不要用于显示。 */
        public int duelCode() {
            return ruleCode != 0 ? ruleCode : originalCode();
        }

        public boolean isLink() {
            return (type & CardTypes.TYPE_LINK) != 0;
        }

        /** 连接怪兽的守备力字段恒为 0（内核写入时把 defense 换成了 link_marker）。 */
        public boolean hasDefense() {
            return !isLink();
        }
    }

    /** 卡种位掩码，取自内核 {@code common.h}。只列界面判断常用的那些。 */
    public static final class CardTypes {
        private CardTypes() {}

        public static final int TYPE_MONSTER = 0x1;
        public static final int TYPE_SPELL = 0x2;
        public static final int TYPE_TRAP = 0x4;
        public static final int TYPE_NORMAL = 0x10;
        public static final int TYPE_EFFECT = 0x20;
        public static final int TYPE_FUSION = 0x40;
        public static final int TYPE_RITUAL = 0x80;
        public static final int TYPE_TRAPMONSTER = 0x100;
        public static final int TYPE_SPIRIT = 0x200;
        public static final int TYPE_UNION = 0x400;
        public static final int TYPE_DUAL = 0x800;
        public static final int TYPE_TUNER = 0x1000;
        public static final int TYPE_SYNCHRO = 0x2000;
        public static final int TYPE_TOKEN = 0x4000;
        public static final int TYPE_QUICKPLAY = 0x10000;
        public static final int TYPE_CONTINUOUS = 0x20000;
        public static final int TYPE_EQUIP = 0x40000;
        public static final int TYPE_FIELD = 0x80000;
        public static final int TYPE_COUNTER = 0x100000;
        public static final int TYPE_FLIP = 0x200000;
        public static final int TYPE_TOON = 0x400000;
        public static final int TYPE_XYZ = 0x800000;
        public static final int TYPE_PENDULUM = 0x1000000;
        public static final int TYPE_SPSUMMON = 0x2000000;
        public static final int TYPE_LINK = 0x4000000;
    }

    @Override
    public String toString() {
        return "CardDataDb[" + file.getFileName() + ", " + codes.length + " 张, "
                + (blob.length / 1048576.0) + " MB]";
    }
}
