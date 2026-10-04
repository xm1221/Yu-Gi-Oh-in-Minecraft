package cn.xm1221.ygomc.common.ocg;

import cn.xm1221.ygomc.common.data.CardDataDb;
import cn.xm1221.ygomc.common.data.DataPacks;

import java.util.Arrays;

/**
 * {@code MSG_ANNOUNCE_CARD}（宣言卡名）的合法应答怎么算。
 *
 * <h2>这条询问的应答是「卡号」，不是「下标」</h2>
 * 它和同族的 {@code MSG_ANNOUNCE_NUMBER} 长得一样（{@code u8 player, u8 count,
 * count × u32 option}），但内核读法完全不同：{@code announce_number} 把应答当
 * <b>下标</b>（{@code playerop.cpp:1046-1050}），而 {@code announce_card} 把它当
 * <b>卡号</b>——
 *
 * <pre>
 * playerop.cpp:1014-1026
 *   int32_t code = returns.ivalue[0];
 *   card_data data;
 *   ::read_card(code, &data);          // code=0 或未知卡号 → data.code == 0
 *   if(!data.code) { MSG_RETRY; }      // ← 于是「回 0」永远被拒
 *   else if(!is_declarable(data, core.select_options)) { MSG_RETRY; }
 * </pre>
 *
 * <p>{@code read_card(0)} 更是被显式短路成「空卡」（{@code ocgapi.cpp:43-49} 的
 * {@code TEMP_CARD_ID} 就是 0），所以「宣言类一律回 0」在这条询问上<b>必然</b>进
 * {@code MSG_RETRY}；而这个应答器是确定性的，重发同一个 0 就变成
 * 「询问 → RETRY → 再询问 → 再 RETRY」的死循环，跑满步数上限也不报错。
 *
 * <h2>option 列表是判据表达式，不是候选表</h2>
 * {@code core.select_options} 里装的是 Lua 侧 {@code Duel.AnnounceCard(tp, ...)}
 * 的<b>原始可变参数</b>，而 {@code is_declarable}（{@code playerop.cpp:871-1005}）
 * 把它们当作一个<b>后缀表达式</b>在这个栈上求值：
 *
 * <ul>
 *   <li>{@code 0x40000000..0x40000007} 是算术/逻辑运算（{@code common.h:387-394}）；</li>
 *   <li>{@code 0x40000100..0x40000104} 是把栈顶与候选卡的某个字段比较的谓词
 *       （{@code common.h:395-399}：{@code ISCODE/ISSETCARD/ISTYPE/ISRACE/ISATTRIBUTE}）；</li>
 *   <li>其余值一律<b>原样压栈</b>，也就是谓词的「立即数」。</li>
 * </ul>
 *
 * <p>最后要求栈恰好剩下一个元素且不为 0。所以
 * {@code [458748, ISCODE, OR]} 是「卡号等于 458748」，
 * {@code [TYPE_MONSTER, ISTYPE]} 是「是怪兽」，
 * {@code [0x7f, ISRACE, 0x1, ISRACE, 0x40000004]} 是「种族同时含 0x7f 与 0x1」。
 *
 * <h2>所以必须能查卡表</h2>
 * 判据里可以完全不含卡号（上面的「是怪兽」就是），这时只能<b>翻卡表找一张满足条件的</b>
 * ——这正是 ygopro 客户端的做法：它拿自己的卡池跑同一个判据，把命中的卡列给玩家选。
 * 本项目的卡池就是 {@code cards.bin}，而且它和灌进内核的是同一份
 * （{@code OcgEngine.prepareOrThrow} → {@code Ocg.putCards}），所以这里算出来的
 * 结果与内核一致。查不到就说清楚为什么失败，<b>绝不瞎猜一个值</b>——瞎猜的后果不是报错，
 * 而是几万条 RETRY 之后的一局跑不完。
 */
public final class DeclareCardName {

    private DeclareCardName() {
    }

    // ── 内核 opcode（common.h:387-399，逐条抄，不要凭印象改）────────────────

    private static final int OPCODE_ADD = 0x40000000;
    private static final int OPCODE_SUB = 0x40000001;
    private static final int OPCODE_MUL = 0x40000002;
    private static final int OPCODE_DIV = 0x40000003;
    private static final int OPCODE_AND = 0x40000004;
    private static final int OPCODE_OR = 0x40000005;
    private static final int OPCODE_NEG = 0x40000006;
    private static final int OPCODE_NOT = 0x40000007;

    private static final int OPCODE_ISCODE = 0x40000100;
    private static final int OPCODE_ISSETCARD = 0x40000101;
    private static final int OPCODE_ISTYPE = 0x40000102;
    private static final int OPCODE_ISRACE = 0x40000103;
    private static final int OPCODE_ISATTRIBUTE = 0x40000104;

    /** {@code common.h:105}。Token 不在 {@code second_code} 白名单里时不能被宣言。 */
    private static final int TYPE_TOKEN = 0x4000;

    // card_data.h:11-23 的 second_code：就这五张，别自作聪明扩充。
    private static final int CARD_MARINE_DOLPHIN = 78734254;
    private static final int CARD_TWINKLE_MOSS = 13857930;
    private static final int CARD_TIMAEUS = 1784686;
    private static final int CARD_CRITIAS = 11082056;
    private static final int CARD_HERMOS = 46232525;

    /**
     * 判据用到的那几个字段，正好是内核 {@code card_data} 的一个子集
     * （{@code card_data.h:46-59} 里的 {@code code/alias/type/race/attribute/rule_code/setcode}）。
     *
     * <p>只有这几个字段参与判据，所以这里只带它们：多带字段会诱使后来者
     * 去实现内核<b>不会</b>读的比较。
     *
     * @param ruleCode 内核 {@code card_data.h:59} 的 {@code rule_code}：
     *                 决斗中实际使用的卡号，非 0 时这张卡不能被宣言（见 {@link #declarable}）
     * @param setcodes 内核的 {@code u16 setcode[16]}；<b>末尾的 0 必须保留</b>，
     *                 因为 {@code is_setcode} 遇到第一个 0 就返回「假」
     */
    public record Fact(int code, int alias, int type, int race, int attribute, int ruleCode,
                       int[] setcodes) {
    }

    /**
     * 卡表视图。
     *
     * <p>刻意做成「问什么答什么」而不是直接依赖 {@link CardDataDb}：这一层只关心
     * 判据需要的五个字段，换成别的卡表实现（例如将来从服务端同步来的卡池）不影响这里。
     */
    public interface CardTable {

        /** 卡号的判据字段；卡号不在卡表里返回 {@code null}。 */
        Fact fact(int code);

        /** 卡表里全部卡号，用于「按条件搜一张」。顺序决定搜索的确定性，所以实现必须稳定。 */
        int[] codes();

        /** 什么都不知道的空表。用它时 {@link #choose} 只认 option 里字面写出的卡号。 */
        CardTable EMPTY = new CardTable() {
            @Override
            public Fact fact(int code) {
                return null;
            }

            @Override
            public int[] codes() {
                return new int[0];
            }

            @Override
            public String toString() {
                return "CardTable[空]";
            }
        };
    }

    /**
     * 用数据包的卡表做一个视图。
     *
     * @param db 可为 null（数据包缺失时）；此时返回 {@link CardTable#EMPTY}
     */
    public static CardTable table(CardDataDb db) {
        if (db == null) {
            return CardTable.EMPTY;
        }
        return new CardTable() {
            @Override
            public Fact fact(int code) {
                CardDataDb.Stats s = db.stats(code);
                if (s == null) {
                    return null;
                }
                return new Fact(s.code(), s.alias(), s.type(), s.race(), s.attribute(),
                        s.ruleCode(), db.setcodes(code));
            }

            @Override
            public int[] codes() {
                return db.codes();
            }

            @Override
            public String toString() {
                return "CardTable[" + db.size() + " 张]";
            }
        };
    }

    /**
     * 用运行期数据包（{@link DataPacks#get()}）建卡表。
     *
     * <p>这只是把「数据包在哪」这个平台相关的问句收在一处：{@link DeclareCardName}
     * 本身只认 {@link CardTable}，所以离线验证可以塞一份自己的卡表进来，
     * 不必启动 Minecraft。
     */
    public static CardTable packagedTable() {
        return table(DataPacks.get().cardData());
    }

    /**
     * 挑一个内核会接受的卡号。
     *
     * <p>先看判据里字面写出的卡号（最常见的形式就是「卡号等于 A 或 B 或 C」），
     * 都不行才翻整个卡表。找不到就抛 {@link IllegalStateException}：
     * 与其回一个必然被拒的值、让对局安静地跑到步数上限，不如把失败点停在这里。
     *
     * @throws IllegalStateException 判据里没有任何可宣言的卡号
     */
    public static int choose(int[] options, CardTable table) {
        CardTable t = table == null ? CardTable.EMPTY : table;
        for (int code : candidates(options)) {
            Fact f = t.fact(code);
            if (f != null && declarable(options, f)) {
                return code;
            }
        }
        for (int code : t.codes()) {
            Fact f = t.fact(code);
            if (f != null && declarable(options, f)) {
                return code;
            }
        }
        throw new IllegalStateException("MSG_ANNOUNCE_CARD 的判据里找不到任何可宣言的卡号："
                + "options=" + Arrays.toString(options) + "，卡表=" + t
                + "（字面候选 " + Arrays.toString(candidates(options)) + "）");
    }

    /**
     * 判据里以<b>字面量</b>给出的卡号，按出现顺序。
     *
     * <p>判据必须先用 {@code OPCODE_ISCODE} 把卡号压栈，所以「{@code ISCODE} 的上一个
     * 元素」就是要找的东西。只取上一个元素而不是「列表里所有不像 opcode 的数」，
     * 是因为后者会把 {@code TYPE_MONSTER}/{@code RACE_*} 这类掩码也当成卡号。
     */
    public static int[] candidates(int[] options) {
        int n = 0;
        for (int i = 1; i < options.length; i++) {
            if (options[i] == OPCODE_ISCODE && !isOpcode(options[i - 1])) {
                n++;
            }
        }
        int[] out = new int[n];
        int p = 0;
        for (int i = 1; i < options.length; i++) {
            if (options[i] == OPCODE_ISCODE && !isOpcode(options[i - 1])) {
                out[p++] = options[i - 1];
            }
        }
        return out;
    }

    /** 这个值是不是内核的 {@code OPCODE_*}。 */
    public static boolean isOpcode(int value) {
        return value >= OPCODE_ADD && value <= OPCODE_ISATTRIBUTE;
    }

    /**
     * 内核 {@code is_declarable}（{@code playerop.cpp:871-1005}）的等价实现。
     *
     * <p>逐条对应，包括「栈不足两个元素时运算静默不做」和「最后要求恰好剩一个非零元素」。
     * 运算一律用 32 位有符号（内核是 {@code int32_t}），除法按 C++ 的向零截断——Java 的
     * {@code /} 同样是向零截断，所以不必额外处理。
     */
    public static boolean declarable(int[] opcodes, Fact f) {
        // 内核第一步：带 alias 的卡【不能】被宣言（playerop.cpp:872）。
        // 这一条与「read_card 查不查得到」无关，是独立的拒绝条件。
        if (f.alias() != 0) {
            return false;
        }
        int[] stack = new int[opcodes.length + 1];
        int top = 0;
        for (int it : opcodes) {
            switch (it) {
                case OPCODE_ADD -> {
                    if (top >= 2) {
                        int rhs = stack[--top];
                        int lhs = stack[--top];
                        stack[top++] = lhs + rhs;
                    }
                }
                case OPCODE_SUB -> {
                    if (top >= 2) {
                        int rhs = stack[--top];
                        int lhs = stack[--top];
                        stack[top++] = lhs - rhs;
                    }
                }
                case OPCODE_MUL -> {
                    if (top >= 2) {
                        int rhs = stack[--top];
                        int lhs = stack[--top];
                        stack[top++] = lhs * rhs;
                    }
                }
                case OPCODE_DIV -> {
                    if (top >= 2) {
                        int rhs = stack[--top];
                        int lhs = stack[--top];
                        stack[top++] = rhs != 0 ? lhs / rhs : 0;
                    }
                }
                case OPCODE_AND -> {
                    if (top >= 2) {
                        int rhs = stack[--top];
                        int lhs = stack[--top];
                        stack[top++] = (lhs != 0 && rhs != 0) ? 1 : 0;
                    }
                }
                case OPCODE_OR -> {
                    if (top >= 2) {
                        int rhs = stack[--top];
                        int lhs = stack[--top];
                        stack[top++] = (lhs != 0 || rhs != 0) ? 1 : 0;
                    }
                }
                case OPCODE_NEG -> {
                    if (top >= 1) {
                        stack[top - 1] = -stack[top - 1];
                    }
                }
                case OPCODE_NOT -> {
                    if (top >= 1) {
                        stack[top - 1] = stack[top - 1] != 0 ? 0 : 1;
                    }
                }
                case OPCODE_ISCODE -> {
                    if (top >= 1) {
                        int code = stack[--top];
                        stack[top++] = f.code() == code ? 1 : 0;
                    }
                }
                case OPCODE_ISSETCARD -> {
                    if (top >= 1) {
                        int setCode = stack[--top];
                        stack[top++] = isSetcode(f.setcodes(), setCode) ? 1 : 0;
                    }
                }
                case OPCODE_ISTYPE -> {
                    if (top >= 1) {
                        int mask = stack[--top];
                        stack[top++] = f.type() & mask;
                    }
                }
                case OPCODE_ISRACE -> {
                    if (top >= 1) {
                        int mask = stack[--top];
                        stack[top++] = f.race() & mask;
                    }
                }
                case OPCODE_ISATTRIBUTE -> {
                    if (top >= 1) {
                        int mask = stack[--top];
                        stack[top++] = f.attribute() & mask;
                    }
                }
                default -> stack[top++] = it;
            }
        }
        if (top != 1 || stack[0] == 0) {
            return false;
        }
        // 判据为真【还不够】：内核还有最后一道闸（playerop.cpp:1002-1003）——
        // 不在 second_code 白名单里、而且是「规则卡号」或 Token 的，同样不接受。
        //
        // 漏掉它的后果不是「选得不够好」，而是又回到 RETRY：翻卡表找「是怪兽」
        // 这类候选时，卡池里靠前的怪兽完全可能是一张 Token 或规则卡。
        return isSecondCode(f.code()) || (f.ruleCode() == 0 && (f.type() & TYPE_TOKEN) == 0);
    }

    /** {@code card_data.h:17-23} 的 {@code second_code} 白名单。 */
    private static boolean isSecondCode(int code) {
        return code == CARD_MARINE_DOLPHIN || code == CARD_TWINKLE_MOSS || code == CARD_TIMAEUS
                || code == CARD_CRITIAS || code == CARD_HERMOS;
    }

    private static boolean isSetcode(int[] setcodes, int value) {
        // card_data.h:65-73：遇到第一个 0 就返回「假」，不是「跳过」。
        if (setcodes == null) {
            return false;
        }
        for (int sc : setcodes) {
            if (sc == 0) {
                return false;
            }
            if (checkSetcode(sc, value)) {
                return true;
            }
        }
        return false;
    }

    /** {@code card_data.h:25-29} 的 {@code check_setcode}。参数是那个 16 位槽。 */
    private static boolean checkSetcode(int setcode, int value) {
        int settype = value & 0x0fff;
        int setsubtype = value & 0xf000;
        return setcode != 0 && (setcode & 0x0fff) == settype
                && (setcode & setsubtype) == setsubtype;
    }
}
