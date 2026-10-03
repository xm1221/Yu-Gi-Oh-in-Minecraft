package cn.xm1221.ygomc.common.ocg.msg;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 一条已解码的 ocgcore 消息。
 *
 * <p>消息流由多条消息首尾拼接而成，每条消息第 1 字节是类型号，其余字段小端。
 * {@link MsgCodec#decode(byte[], int)} 返回的就是这里的某个实现。
 *
 * <h2>访问方式</h2>
 * 每个具体类型都是 {@link Msg.Body} 的嵌套 {@code record}，字段具名且带类型，例如：
 * <pre>{@code
 * if (m instanceof Msg.Move move) {
 *     int code = move.code();
 *     Msg.Location from = move.from();   // 已从 u32 解包成 controler / location / sequence / position
 *     Msg.Location to   = move.to();
 *     int reason = move.reason();
 * }
 * }</pre>
 *
 * <h2>公共三件套</h2>
 * 任何消息都能拿到 {@link #type()} / {@link #offset()} / {@link #length()}。
 * {@code length()} <b>恒等于</b>该消息在缓冲里占用的字节数（含类型字节），
 * 也就是「解析即测长」的结果。
 *
 * <h2>时间量约定</h2>
 * 所有字段按字节原样读出：{@code u8}/{@code u16} 存进 {@code int} 时是零扩展的无符号值；
 * {@code u32} 存进 {@code int} 时保持 32 位补码原样（例如卡号会带 {@code 0x80000000} 标志位，
 * 判断时请用 {@code (code & 0x80000000) != 0} 而不是 {@code code < 0} 以外的方式）。
 * 带符号语义的字段（生命值、攻击力、守备力、损伤量）用 {@code int} 直接读，就是内核里的
 * {@code int32_t}。
 */
public sealed interface Msg permits Msg.Body {

    /** 消息类型号，见 {@link MsgType}。 */
    int type();

    /** 在 {@code buf} 中的起始偏移（含类型字节）。 */
    int offset();

    /** 该消息总字节数（含类型字节）。 */
    int length();

    /**
     * 所有具体消息类型的公共父接口。
     *
     * <p>实现类都是嵌套 {@code record}。{@code type()} / {@code length()} 由解码时填入的
     * {@link #_type} / {@link #_length} 提供，保证「字段解析」和「长度计算」永远出自同一份布局。
     */
    sealed interface Body extends Msg permits Retry, Hint, Win, SelectBattleCmd, SelectIdleCmd,
            SelectEffectYn, SelectYesNo, SelectOption, SelectCard, SelectChain, SelectPlace,
            SelectPosition, SelectTribute, SelectCounter, SelectSum, SortCard, SelectUnselectCard,
            ConfirmDeckTop, ConfirmCards, ShuffleDeck, ShuffleHand, SwapGraveDeck, ShuffleSetCard,
            ReverseDeck, DeckTop, ShuffleExtra, NewTurn, NewPhase, ConfirmExtraTop, Move, PosChange,
            Set, Swap, FieldDisabled, Summoning, Summoned, SpSummoning, SpSummoned, FlipSummoning,
            FlipSummoned, Chaining, Chained, ChainSolving, ChainSolved, ChainEnd, ChainNegated,
            ChainDisabled, RandomSelected, BecomeTarget, Draw, Damage, Recover, Equip, LpUpdate,
            CardTarget, CancelTarget, PayLpCost, AddCounter, RemoveCounter, Attack, Battle,
            AttackDisabled, DamageStepStart, DamageStepEnd, MissedEffect, TossCoin, TossDice,
            RockPaperScissors, HandRes, AnnounceRace, AnnounceAttrib, AnnounceCard, AnnounceNumber,
            CardHint, TagSwap, ReloadField, AiName, ShowHint, PlayerHint, MatchKill, CustomMsg {

        /** 消息类型号。 */
        int _type();

        /** 消息总字节数（含类型字节）。 */
        int _length();

        @Override
        default int type() { return _type(); }

        @Override
        default int length() { return _length(); }

        /** 统一的调试/日志输出：{@code [offset] NAME length=N 字段...}。 */
        /**
         * 统一的日志前缀：{@code [offset] NAME length=N}。
         *
         * <p>各具体消息的 {@code toString()} 都以它开头，再追加自己的字段。
         * 之所以不直接写成接口上的 {@code default toString()}：{@code record} 继承自
         * {@code java.lang.Record}，无法在 {@code head()} 里拿到接口默认实现
         * （record 的 {@code toString()} 不能显式调用 {@code super}），所以改用这个具名方法。
         */
        default String head() {
            return "[" + offset() + "] " + MsgType.name(_type()) + " length=" + _length();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 公共值类型
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 内核的「信息位置」打包值（{@code card::get_info_location()}）。
     *
     * <pre>
     * byte 0 (bits  0-7 ): controller  ─ 控制者 0/1（PLAYER_NONE = 2 表示不在场/无）
     * byte 1 (bits  8-15): location    ─ LOCATION_*；叠放素材会带 LOCATION_OVERLAY(0x80)
     * byte 2 (bits 16-23): sequence    ─ 该区域内的序号；叠放素材这里是「素材所在怪兽的序号」
     * byte 3 (bits 24-31): position    ─ POS_*（叠放素材这里是「该素材在叠放组里的层号」）
     * </pre>
     *
     * <p>注意：{@code 0} 是一个合法取值，表示「不存在」（内核在无攻击目标时写 {@code 0}）。
     * 用 {@link #isNone()} 判断。
     */
    record Location(int packed) {

        /** 全零，表示「无」——内核在 {@code core.attack_target == 0} 等处写这个值。 */
        public static final Location NONE = new Location(0);

        public static Location of(int packed) { return new Location(packed); }

        /** 控制者（{@code PLAYER_NONE = 2} 表示不属于任何一方）。 */
        public int controller() { return packed & 0xff; }

        /** {@code LOCATION_*}，可能带 {@link #OVERLAY} 位。 */
        public int location() { return (packed >>> 8) & 0xff; }

        /** 区域内的序号。 */
        public int sequence() { return (packed >>> 16) & 0xff; }

        /** {@code POS_*}（叠放素材时是层号）。 */
        public int position() { return (packed >>> 24) & 0xff; }

        /** 是否为「不存在」（{@code packed == 0}）。 */
        public boolean isNone() { return packed == 0; }

        /** 是否是叠放素材（{@link #location()} 带 {@link #OVERLAY} 位）。 */
        public boolean isOverlay() { return (location() & OVERLAY) != 0; }

        /** 是否在场地区（怪兽区或魔陷区）。 */
        public boolean isOnField() { return (location() & ON_FIELD) != 0; }

        /** 是否是表侧（{@code POS_FACEUP}）。 */
        public boolean isFaceUp() { return (position() & FACEUP) != 0; }

        /** 是否表侧表示且在内核标记了「公开」（{@code POS_REVEAL}）。 */
        public boolean isRevealed() { return (position() & REVEAL) != 0; }

        /** 渲染友好的形式，例如 {@code p0/MZONE#3/ATK/0x03040100}。 */
        @Override
        public String toString() {
            if (isNone()) return "NONE";
            String loc = switch (location() & 0x7f) {
                case DECK -> "DECK";
                case HAND -> "HAND";
                case MZONE -> "MZONE";
                case SZONE -> "SZONE";
                case GRAVE -> "GRAVE";
                case REMOVED -> "REMOVED";
                case EXTRA -> "EXTRA";
                default -> "LOC" + (location() & 0x7f);
            };
            String pos = switch (position() & 0xf) {
                case 0x1 -> "ATK";
                case 0x2 -> "SETATK";
                case 0x4 -> "DEF";
                case 0x8 -> "SETDEF";
                default -> "pos" + (position() & 0xf);
            };
            StringBuilder sb = new StringBuilder();
            sb.append('p').append(controller()).append('/').append(loc).append('#').append(sequence());
            if (isOverlay()) sb.append("(overlay layer ").append(position()).append(')');
            else {
                sb.append('/').append(pos);
                if (isRevealed()) sb.append("+REVEAL");
            }
            sb.append(String.format(" [0x%08X]", packed));
            return sb.toString();
        }

        // ── 位标志常量（与内核 common.h 一致）────────────────────────────────
        public static final int DECK = 0x01;
        public static final int HAND = 0x02;
        public static final int MZONE = 0x04;
        public static final int SZONE = 0x08;
        public static final int GRAVE = 0x10;
        public static final int REMOVED = 0x20;
        public static final int EXTRA = 0x40;
        public static final int OVERLAY = 0x80;
        public static final int ON_FIELD = MZONE | SZONE;
        public static final int FACEUP = 0x5;
        public static final int REVEAL = 0x80;
    }

    /**
     * 消息里的「卡号」字段。
     *
     * <p>{@link #code()} 在多数消息里可能带 {@code 0x80000000} 高位标志，含义随消息而不同
     * （DRAW 里表示「表侧」、SELECT_* 里表示 {@code EFFECT_FLAG_FIELD_ONLY}、
     * DECK_TOP 里表示「里侧」等），所以这里把它单独解出来而不是让调用方记位掩码。
     */
    record CardCode(int raw) {

        public static CardCode of(int raw) { return new CardCode(raw); }

        /** 原始 32 位值（低位是卡号，高位可能带标志）。 */
        public int raw() { return raw; }

        /** 纯卡号（剥掉高位标志）。 */
        public int code() { return raw & 0x7fffffff; }

        /** 高位标志 {@code 0x80000000} 是否置位。含义随消息而定。 */
        public boolean flag() { return (raw & 0x80000000) != 0; }

        @Override
        public String toString() {
            return flag() ? code() + "(flag)" : Integer.toString(code());
        }
    }

    /**
     * 卡组/手牌/场上的一张卡的三元组：{@code u32 code, u8 controller, u8 location, u8 sequence}。
     * 共 7 字节。
     */
    record CardEntry(int code, int controller, int location, int sequence) {

        /** 以「打包位置」形式返回，便于直接当 {@link Location} 用（position 填 0）。 */
        public Location asLocation() {
            return new Location((controller & 0xff) | ((location & 0xff) << 8) | ((sequence & 0xff) << 16));
        }

        /** 卡号（已剥掉高位标志）。 */
        public int pureCode() { return code & 0x7fffffff; }

        /** 高位标志 {@code 0x80000000} 是否置位。 */
        public boolean flag() { return (code & 0x80000000) != 0; }

        @Override
        public String toString() {
            return pureCode() + "@p" + controller + "/loc" + location + "#" + sequence
                    + (flag() ? "(flag)" : "");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 控制类
    // ══════════════════════════════════════════════════════════════════════════

    /** {@code u8 MSG_RETRY}（1 字节）。上一条应答被拒，宿主必须重发上一次应答。 */
    record Retry(int _type, int offset, int _length) implements Body {
        @Override public String toString() {
            return head() + " (应答被拒，请重发上一条应答)";
        }
    }

    /** {@code u8 MSG_HINT, u8 hintType, u8 player, u32 description}（7 字节）。 */
    record Hint(int _type, int offset, int _length, int hintType, int player, int description) implements Body {
        @Override public String toString() {
            return head() + " hint=" + hintType + " player=" + player + " desc=" + description;
        }
    }

    /** {@code u8 MSG_WIN, u8 winner, u8 reason}（3 字节）。权威收局信号。 */
    record Win(int _type, int offset, int _length, int winner, int reason) implements Body {

        /** winner 取值 2 = {@code PLAYER_NONE} = 平局。 */
        public static final int PLAYER_NONE = 2;
        /** reason = 生命值归零。 */
        public static final int REASON_LP = 1;
        /** reason = 卡组耗尽。 */
        public static final int REASON_OVERDRAW = 2;

        public boolean isDraw() { return winner == PLAYER_NONE; }
        public boolean isLpZero() { return reason == REASON_LP; }
        public boolean isDeckOut() { return reason == REASON_OVERDRAW; }

        @Override public String toString() {
            return head() + " winner=" + (isDraw() ? "DRAW" : winner)
                    + " reason=" + (reason == REASON_LP ? "LP" : reason == REASON_OVERDRAW ? "DECKOUT" : reason);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 询问类
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 可发动效果的一项：{@code u32 code, u8 controller, u8 location, u8 sequence, u32 description}
     * （11 字节）。{@code code} 的高位标志表示 {@code EFFECT_FLAG_FIELD_ONLY}。
     */
    record SelectChainEntry(int code, int controller, int location, int sequence, int description) {
        /** 纯卡号。 */
        public int pureCode() { return code & 0x7fffffff; }
        /** {@code EFFECT_FLAG_FIELD_ONLY} 标志。 */
        public boolean fieldOnly() { return (code & 0x80000000) != 0; }
        @Override public String toString() {
            return pureCode() + "@p" + controller + "/loc" + location + "#" + sequence
                    + " desc=" + description + (fieldOnly() ? "(fieldOnly)" : "");
        }
    }

    /** 可攻击怪兽的一项：{@code u32 code, u8 controller, u8 location, u8 sequence, u8 directAttackable}（8 字节）。 */
    record AttackableEntry(int code, int controller, int location, int sequence, int directAttackable) {
        public int pureCode() { return code & 0x7fffffff; }
        public boolean canDirectAttack() { return directAttackable != 0; }
        @Override public String toString() {
            return pureCode() + "@p" + controller + "/loc" + location + "#" + sequence
                    + (canDirectAttack() ? " direct" : "");
        }
    }

    /** 战斗阶段（连锁 = 发动效果，怪兽 = 攻击）。 */
    record SelectBattleCmd(int _type, int offset, int _length,
                           int player, SelectChainEntry[] chains, AttackableEntry[] attackable,
                           int toM2, int toEp) implements Body {
        public int chainCount() { return chains.length; }
        public int attackableCount() { return attackable.length; }
        public List<SelectChainEntry> chainList() { return List.of(chains); }
        public List<AttackableEntry> attackableList() { return List.of(attackable); }
        public boolean canGoMain2() { return toM2 != 0; }
        public boolean canGoEndPhase() { return toEp != 0; }
        @Override public String toString() {
            return head() + " player=" + player + " chains=" + chains.length
                    + " attackable=" + attackable.length + " toM2=" + toM2 + " toEp=" + toEp;
        }
    }

    /**
     * 主要阶段的可选行动。
     *
     * <p>数组顺序与内核 {@code select_idle_command} 里写出的顺序一致：
     * summon / spSummon / reposition / monsterSet / spellSet / chains。
     * 应答时「类型」号就是这里的下标：0..4 对应五个数组，5 = 发动效果（chains 下标），
     * 6 = 进战斗阶段，7 = 进结束阶段，8 = 洗牌手牌。
     */
    record SelectIdleCmd(int _type, int offset, int _length, int player,
                         CardEntry[] summon, CardEntry[] spSummon, CardEntry[] reposition,
                         CardEntry[] monsterSet, CardEntry[] spellSet, SelectChainEntry[] chains,
                         int toBp, int toEp, int canShuffle) implements Body {

        public int summonCount() { return summon.length; }
        public int spSummonCount() { return spSummon.length; }
        public int repositionCount() { return reposition.length; }
        public int monsterSetCount() { return monsterSet.length; }
        public int spellSetCount() { return spellSet.length; }
        public int chainCount() { return chains.length; }

        public List<CardEntry> summonList() { return List.of(summon); }
        public List<CardEntry> spSummonList() { return List.of(spSummon); }
        public List<CardEntry> repositionList() { return List.of(reposition); }
        public List<CardEntry> monsterSetList() { return List.of(monsterSet); }
        public List<CardEntry> spellSetList() { return List.of(spellSet); }
        public List<SelectChainEntry> chainList() { return List.of(chains); }

        public boolean canGoBattlePhase() { return toBp != 0; }
        public boolean canGoEndPhase() { return toEp != 0; }
        public boolean canShuffleHand() { return canShuffle != 0; }

        @Override public String toString() {
            return head() + " player=" + player
                    + " summon=" + summon.length + " spSummon=" + spSummon.length
                    + " reposition=" + reposition.length + " mset=" + monsterSet.length
                    + " sset=" + spellSet.length + " chains=" + chains.length
                    + " toBp=" + toBp + " toEp=" + toEp + " canShuffle=" + canShuffle;
        }
    }

    /** {@code u8 player, u32 code, u32 location, u32 description}（14 字节）。 */
    record SelectEffectYn(int _type, int offset, int _length, int player,
                          int code, Location location, int description) implements Body {
        @Override public String toString() {
            return head() + " player=" + player + " code=" + (code & 0x7fffffff)
                    + " " + location + " desc=" + description;
        }
    }

    /** {@code u8 player, u32 description}（6 字节）。 */
    record SelectYesNo(int _type, int offset, int _length, int player, int description) implements Body {
        @Override public String toString() {
            return head() + " player=" + player + " desc=" + description;
        }
    }

    /** {@code u8 player, u8 count, u32 option[count]}。 */
    record SelectOption(int _type, int offset, int _length, int player, int[] options) implements Body {
        public int count() { return options.length; }
        public int option(int i) { return options[i]; }
        public List<Integer> optionList() {
            Integer[] boxed = new Integer[options.length];
            for (int i = 0; i < options.length; i++) boxed[i] = options[i];
            return List.of(boxed);
        }
        @Override public String toString() {
            return head() + " player=" + player + " options=" + Arrays.toString(options);
        }
    }

    /**
     * 让玩家选卡。
     *
     * <p>{@code u8 player, u8 cancelable, u8 min, u8 max, u8 count, count × (u32 code, u32 location)}。
     * 每项 8 字节。{@code location} 是 {@link Location}（卡组里的卡其 sequence 可能是隐藏序号）。
     */
    record SelectCard(int _type, int offset, int _length, int player, int cancelable,
                      int min, int max, int[] codes, Location[] locations) implements Body {

        public int count() { return codes.length; }
        public int code(int i) { return codes[i]; }
        public Location location(int i) { return locations[i]; }
        /** {@code cancelable != 0}（{@code cancelable()} 是 record 自带访问器，返回原始 u8）。 */
        public boolean canCancel() { return cancelable != 0; }
        public List<Location> locationList() { return List.of(locations); }

        /** 遍历用：第 {@code i} 项合成为 {@link CardEntry}（position 不再单独存在，故填 0）。 */
        public CardEntry entry(int i) {
            Location l = locations[i];
            return new CardEntry(codes[i], l.controller(), l.location(), l.sequence());
        }

        @Override public String toString() {
            StringBuilder sb = new StringBuilder(head());
            sb.append(" player=").append(player).append(" cancelable=").append(cancelable)
              .append(" min=").append(min).append(" max=").append(max).append(" cards=[");
            for (int i = 0; i < codes.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(codes[i] & 0x7fffffff).append('@').append(locations[i]);
            }
            return sb.append(']').toString();
        }
    }

    /**
     * 连锁选择。
     *
     * <p>{@code u8 player, u8 count, u8 speCount, u32 hintTimingSelf, u32 hintTimingOther,
     * count × (u8 edesc, u8 forced, u32 code, u32 location, u32 description)}。
     * 每项 14 字节 —— 顺序是「先 flag 再 code/loc/desc」，见实测踩坑表第 9 条。
     */
    record SelectChain(int _type, int offset, int _length, int player, int speCount,
                       int hintTimingSelf, int hintTimingOther, ChainEntry[] entries) implements Body {

        /** 连锁一项：14 字节。 */
        public record ChainEntry(int edesc, int forced, int code, Location location, int description) {
            /** {@code EFFECT_FLAG_FIELD_ONLY} 项（EDESC_OPERATION = 1）。 */
            public static final int EDESC_OPERATION = 1;
            /** 非行动类效果、无可发动时点（EDESC_RESET = 2）。 */
            public static final int EDESC_RESET = 2;
            public boolean isFieldOnly() { return edesc == EDESC_OPERATION; }
            public boolean isReset() { return edesc == EDESC_RESET; }
            /** 强制发动（{@code CHAIN_FORCED}）：不可放弃连锁。 */
            public boolean isForced() { return forced != 0; }
            public int pureCode() { return code & 0x7fffffff; }
            @Override public String toString() {
                return pureCode() + "@" + location + " desc=" + description
                        + (isForced() ? " FORCED" : "") + " edesc=" + edesc;
            }
        }

        public int count() { return entries.length; }
        public ChainEntry entry(int i) { return entries[i]; }
        public List<ChainEntry> entryList() { return List.of(entries); }
        /** 是否存在强制发动的项（存在则不能选「不连锁」）。 */
        public boolean hasForced() {
            for (ChainEntry e : entries) if (e.isForced()) return true;
            return false;
        }
        @Override public String toString() {
            return head() + " player=" + player + " speCount=" + speCount
                    + " hintSelf=" + hintTimingSelf + " hintOther=" + hintTimingOther
                    + " chains=" + Arrays.toString(entries);
        }
    }

    /**
     * 选址。
     *
     * <p>{@code u8 player, u8 count, u32 flag}。<b>消息体里没有可选位置列表</b>，
     * {@code flag} 是<b>禁止位掩码</b>：
     * <pre>
     * bit  0-7 : 该玩家怪兽区 序号 0-6
     * bit  8-15: 该玩家魔陷区 序号 0-7
     * bit 16-23: 对方怪兽区
     * bit 24-31: 对方魔陷区
     * </pre>
     * 应答 = 每项 3 字节 {@code [归属, 区域, 序号]}；{@code count == 0} 时用 {@code (0,0,0)} 表示不选。
     */
    record SelectPlace(int _type, int offset, int _length, int player, int count, int flag) implements Body {

        /** 该位置是否被禁止（{@code owner} 相对 {@code player} 的归属 0/1，{@code location} 用 LOCATION_MZONE/SZONE）。 */
        public boolean isDisabled(int owner, int location, int sequence) {
            int bit = sequence
                    + (owner == player ? 0 : 16)
                    + (location == Location.MZONE ? 0 : 8);
            return (flag & (1 << bit)) != 0;
        }

        /** 自己怪兽区第 {@code seq} 格是否可用。 */
        public boolean ownMonsterZoneUsable(int seq) { return !isDisabled(player, Location.MZONE, seq); }

        /** 自己魔陷区第 {@code seq} 格是否可用。 */
        public boolean ownSpellZoneUsable(int seq) { return !isDisabled(player, Location.SZONE, seq); }

        /** 对方怪兽区第 {@code seq} 格是否可用。 */
        public boolean oppMonsterZoneUsable(int seq) { return !isDisabled(1 - player, Location.MZONE, seq); }

        /** 对方魔陷区第 {@code seq} 格是否可用。 */
        public boolean oppSpellZoneUsable(int seq) { return !isDisabled(1 - player, Location.SZONE, seq); }

        @Override public String toString() {
            return head() + " player=" + player + " count=" + count
                    + String.format(" flag=0x%08X", flag);
        }
    }

    /** {@code u8 player, u32 code, u8 positions}（7 字节）。{@code positions} 是允许的表示形式位掩码。 */
    record SelectPosition(int _type, int offset, int _length, int player, int code, int positions) implements Body {
        public static final int FACEUP_ATTACK = 0x1;
        public static final int FACEDOWN_ATTACK = 0x2;
        public static final int FACEUP_DEFENSE = 0x4;
        public static final int FACEDOWN_DEFENSE = 0x8;
        public boolean allows(int pos) { return (positions & pos) != 0; }
        @Override public String toString() {
            return head() + " player=" + player + " code=" + (code & 0x7fffffff)
                    + String.format(" positions=0x%X", positions);
        }
    }

    /**
     * 解放（祭品）选择。每项 7 字节：{@code u32 code, u8 controller, u8 location, u8 sequence, u8 releaseParam}。
     * 注意与 {@link SelectCard} 的 8 字节不同。
     */
    record SelectTribute(int _type, int offset, int _length, int player, int cancelable,
                         int min, int max, int[] codes, int[] controllers, int[] locations,
                         int[] sequences, int[] releaseParams) implements Body {

        public int count() { return codes.length; }
        /** {@code cancelable != 0}。 */
        public boolean canCancel() { return cancelable != 0; }

        public CardEntry entry(int i) {
            return new CardEntry(codes[i], controllers[i], locations[i], sequences[i]);
        }

        /** 第 {@code i} 项的解放点数（{@code release_param}）。 */
        public int releaseParam(int i) { return releaseParams[i]; }

        public List<CardEntry> entryList() {
            CardEntry[] a = new CardEntry[codes.length];
            for (int i = 0; i < a.length; i++) a[i] = entry(i);
            return List.of(a);
        }

        @Override public String toString() {
            StringBuilder sb = new StringBuilder(head());
            sb.append(" player=").append(player).append(" cancelable=").append(cancelable)
              .append(" min=").append(min).append(" max=").append(max).append(" cards=[");
            for (int i = 0; i < codes.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(codes[i] & 0x7fffffff).append("@p").append(controllers[i])
                  .append("/loc").append(locations[i]).append('#').append(sequences[i])
                  .append(" rel=").append(releaseParams[i]);
            }
            return sb.append(']').toString();
        }
    }

    /**
     * 指示物选择。每项 11 字节：{@code u32 code, u8 controller, u8 location, u8 sequence, u16 counter}。
     * 应答是「每个候选各自加多少」的列表（{@code returns.svalue[i]}）。
     */
    record SelectCounter(int _type, int offset, int _length, int player, int counterType,
                         int count, int[] codes, int[] controllers, int[] locations,
                         int[] sequences, int[] counters) implements Body {

        public int candidateCount() { return codes.length; }
        public int cardCounter(int i) { return counters[i]; }
        public CardEntry entry(int i) {
            return new CardEntry(codes[i], controllers[i], locations[i], sequences[i]);
        }
        @Override public String toString() {
            StringBuilder sb = new StringBuilder(head());
            sb.append(" player=").append(player).append(" counterType=").append(counterType)
              .append(" need=").append(count).append(" cards=[");
            for (int i = 0; i < codes.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(codes[i] & 0x7fffffff).append("@p").append(controllers[i])
                  .append("/loc").append(locations[i]).append('#').append(sequences[i])
                  .append(" counter=").append(counters[i]);
            }
            return sb.append(']').toString();
        }
    }

    /** 求和选择的一项：{@code u32 code, u8 controller, u8 location, u8 sequence, u32 sumParam}（11 字节）。 */
    record SelectSumEntry(int code, int controller, int location, int sequence, int sumParam) {
        public int pureCode() { return code & 0x7fffffff; }
        public CardEntry entry() { return new CardEntry(code, controller, location, sequence); }
        @Override public String toString() {
            return pureCode() + "@p" + controller + "/loc" + location + "#" + sequence
                    + " sum=" + sumParam;
        }
    }

    /**
     * 带合计值限制的选择。
     *
     * <pre>
     * u8 MSG_SELECT_SUM, u8 flag, u8 player, u32 acc, u8 min, u8 max,
     * u8 mustCount,  mustCount  × (u32 code, u8 controller, u8 location, u8 sequence, u32 sumParam),
     * u8 selCount,   selCount   × (u32 code, u8 controller, u8 location, u8 sequence, u32 sumParam)
     * </pre>
     *
     * <p><b>关于 flag</b>：内核写的是 {@code if(max) write(0); else write(1);}，
     * 也就是说 {@code flag == 1} 恰好对应 <b>max == 0</b>（无上限模式）。
     * 这里原样保留为 {@link #flag()}，另提供 {@link #unlimited()} 表达该语义。
     */
    record SelectSum(int _type, int offset, int _length, int flag, int player, int acc,
                     int min, int max, SelectSumEntry[] mustSelect, SelectSumEntry[] selectable) implements Body {
        public int mustCount() { return mustSelect.length; }
        public int selectableCount() { return selectable.length; }
        /** {@code flag == 0} 表示有上限。 */
        public boolean limited() { return flag == 0; }
        /** {@code flag == 1}，对应内核的 {@code max == 0}（无上限）。 */
        public boolean unlimited() { return flag != 0; }
        public List<SelectSumEntry> mustList() { return List.of(mustSelect); }
        public List<SelectSumEntry> selectableList() { return List.of(selectable); }
        @Override public String toString() {
            return head() + " flag=" + flag + " player=" + player + " acc=" + acc
                    + " min=" + min + " max=" + max
                    + " must=" + Arrays.toString(mustSelect)
                    + " selectable=" + Arrays.toString(selectable);
        }
    }

    /** {@code u8 player, u8 count, count × (u32 code, u8 controller, u8 location, u8 sequence)}。 */
    record SortCard(int _type, int offset, int _length, int player, CardEntry[] cards) implements Body {
        public int count() { return cards.length; }
        public CardEntry card(int i) { return cards[i]; }
        public List<CardEntry> cardList() { return List.of(cards); }
        @Override public String toString() {
            return head() + " player=" + player + " cards=" + Arrays.toString(cards);
        }
    }

    /**
     * 「选卡 / 取消选卡」两段式选择。
     *
     * <pre>
     * u8 player, u8 finishable, u8 cancelable, u8 min, u8 max,
     * u8 selectCount,   selectCount   × (u32 code, u32 location),
     * u8 unselectCount, unselectCount × (u32 code, u32 location)
     * </pre>
     *
     * <p><b>注意字段顺序是 finishable 在 cancelable 之前</b>（内核
     * {@code playerop.cpp:303-304}），与文件中其它 {@code SELECT_*} 消息的 cancelable 在前不同。
     */
    record SelectUnselectCard(int _type, int offset, int _length, int player, int finishable,
                              int cancelable, int min, int max,
                              int[] selectCodes, Location[] selectLocations,
                              int[] unselectCodes, Location[] unselectLocations) implements Body {

        public int selectCount() { return selectCodes.length; }
        public int unselectCount() { return unselectCodes.length; }
        /** {@code finishable != 0}。 */
        public boolean canFinish() { return finishable != 0; }
        /** {@code cancelable != 0}。 */
        public boolean canCancel() { return cancelable != 0; }
        public int selectCode(int i) { return selectCodes[i]; }
        public Location selectLocation(int i) { return selectLocations[i]; }
        public int unselectCode(int i) { return unselectCodes[i]; }
        public Location unselectLocation(int i) { return unselectLocations[i]; }
        public List<Location> selectLocationList() { return List.of(selectLocations); }
        public List<Location> unselectLocationList() { return List.of(unselectLocations); }

        @Override public String toString() {
            return head() + " player=" + player + " finishable=" + finishable
                    + " cancelable=" + cancelable + " min=" + min + " max=" + max
                    + " select=" + Arrays.toString(selectLocations)
                    + " unselect=" + Arrays.toString(unselectLocations);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 卡组 / 手牌 / 回合
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * {@code u8 player, u8 count, count × (u32 code, u8 controller, u8 location, u8 sequence)}。
     * 展示卡组顶若干张。
     */
    record ConfirmDeckTop(int _type, int offset, int _length, int player, CardEntry[] cards) implements Body {
        public int count() { return cards.length; }
        public List<CardEntry> cardList() { return List.of(cards); }
        @Override public String toString() {
            return head() + " player=" + player + " cards=" + Arrays.toString(cards);
        }
    }

    /**
     * {@code u8 player, u8 skipPanel, u8 count, count × (u32 code, u8 controller, u8 location, u8 sequence)}。
     * 「给对方看这些卡」。{@code skipPanel} 是 Lua 侧 {@code Duel.ConfirmCards} 的第 3 参。
     */
    record ConfirmCards(int _type, int offset, int _length, int player, int skipPanel,
                        CardEntry[] cards) implements Body {
        public int count() { return cards.length; }
        /** {@code skipPanel != 0}。 */
        public boolean skipPanelSet() { return skipPanel != 0; }
        public CardEntry card(int i) { return cards[i]; }
        public List<CardEntry> cardList() { return List.of(cards); }
        @Override public String toString() {
            return head() + " player=" + player + " skipPanel=" + skipPanel
                    + " cards=" + Arrays.toString(cards);
        }
    }

    /** {@code u8 player}（2 字节）。洗主卡组。 */
    record ShuffleDeck(int _type, int offset, int _length, int player) implements Body {
        @Override public String toString() { return head() + " player=" + player; }
    }

    /** {@code u8 player, u8 count, count × u32 code}（4 字节 × count）。洗额外卡组。 */
    record ShuffleExtra(int _type, int offset, int _length, int player, int[] codes) implements Body {
        public int count() { return codes.length; }
        public int code(int i) { return codes[i]; }
        @Override public String toString() {
            return head() + " player=" + player + " codes=" + Arrays.toString(codes);
        }
    }

    /** {@code u8 player, u8 count, count × u32 code}。洗对方随机抽到的卡（公开手牌）。 */
    record ShuffleHand(int _type, int offset, int _length, int player, int[] codes) implements Body {
        public int count() { return codes.length; }
        public int code(int i) { return codes[i]; }
        @Override public String toString() {
            return head() + " player=" + player + " codes=" + Arrays.toString(codes);
        }
    }

    /** {@code u8 player}（2 字节）。墓地与卡组对调。 */
    record SwapGraveDeck(int _type, int offset, int _length, int player) implements Body {
        @Override public String toString() { return head() + " player=" + player; }
    }

    /**
     * 魔陷区盖卡洗位。
     *
     * <pre>
     * u8 MSG_SHUFFLE_SET_CARD, u8 location(= LOCATION_SZONE 0x08), u8 count, count × u32 infoLocation
     * </pre>
     *
     * <p>{@code infoLocation} 是 {@link Location} 的打包值（{@code 0} 表示该格为空）。
     * 内核保证总共有恰好 {@code count} 个 4 字节项（见 {@code operations.cpp:2657-2674}：
     * 对 {@code TYPE_FIELD} 字段卡它跳过写入，最后再补足 {@code count} 个 0）。
     */
    record ShuffleSetCard(int _type, int offset, int _length, int location, Location[] zones) implements Body {
        public int count() { return zones.length; }
        public Location zone(int i) { return zones[i]; }
        public List<Location> zoneList() { return List.of(zones); }
        @Override public String toString() {
            return head() + " location=" + location + " zones=" + Arrays.toString(zones);
        }
    }

    /** {@code u8 MSG_REVERSE_DECK}（1 字节）。卡组整体翻转，之后的 {@code MSG_DECK_TOP} 才有意义。 */
    record ReverseDeck(int _type, int offset, int _length) implements Body {
        @Override public String toString() { return head(); }
    }

    /**
     * 卡组顶（或顶数第 {@code sequence} 张）公开。
     * {@code u8 player, u8 sequence, u32 code}；{@code code} 高位 {@code 0x80000000} 表示<b>里侧</b>。
     */
    record DeckTop(int _type, int offset, int _length, int player, int sequence, int code) implements Body {
        public int pureCode() { return code & 0x7fffffff; }
        /** 该卡是否是里侧（高位标志置位）。 */
        public boolean faceDown() { return (code & 0x80000000) != 0; }
        @Override public String toString() {
            return head() + " player=" + player + " seq=" + sequence
                    + " code=" + pureCode() + (faceDown() ? "(里侧)" : "");
        }
    }

    /** {@code u8 turnPlayer}（2 字节）。 */
    record NewTurn(int _type, int offset, int _length, int turnPlayer) implements Body {
        @Override public String toString() { return head() + " turnPlayer=" + turnPlayer; }
    }

    /** {@code u16 phase}（3 字节）。取值 {@code PHASE_*}，见本记录里的常量。 */
    record NewPhase(int _type, int offset, int _length, int phase) implements Body {
        public static final int DRAW = 0x01;
        public static final int STANDBY = 0x02;
        public static final int MAIN1 = 0x04;
        public static final int BATTLE_START = 0x08;
        public static final int BATTLE_STEP = 0x10;
        public static final int DAMAGE = 0x20;
        public static final int DAMAGE_CAL = 0x40;
        public static final int BATTLE = 0x80;
        public static final int MAIN2 = 0x100;
        public static final int END = 0x200;

        public String phaseName() {
            return switch (phase) {
                case DRAW -> "DRAW";
                case STANDBY -> "STANDBY";
                case MAIN1 -> "MAIN1";
                case BATTLE_START -> "BATTLE_START";
                case BATTLE_STEP -> "BATTLE_STEP";
                case DAMAGE -> "DAMAGE";
                case DAMAGE_CAL -> "DAMAGE_CAL";
                case BATTLE -> "BATTLE";
                case MAIN2 -> "MAIN2";
                case END -> "END";
                default -> "PHASE_" + phase;
            };
        }
        @Override public String toString() {
            return head() + " phase=" + phaseName();
        }
    }

    /** {@code u8 player, u8 count, count × (u32 code, u8 controller, u8 location, u8 sequence)}。展示额外卡组顶。 */
    record ConfirmExtraTop(int _type, int offset, int _length, int player, CardEntry[] cards) implements Body {
        public int count() { return cards.length; }
        public List<CardEntry> cardList() { return List.of(cards); }
        @Override public String toString() {
            return head() + " player=" + player + " cards=" + Arrays.toString(cards);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 场地动作（渲染重点）
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 卡片移动。表现类最重要的消息。
     *
     * <pre>
     * u8  MSG_MOVE
     * u32 code        ─ 卡号（可能带高位标志）
     * u32 fromInfo    ─ 移动前位置（{@link Location}）
     * u32 toInfo      ─ 移动后位置（{@link Location}）
     * u32 reason      ─ REASON_* 位掩码
     * </pre>
     *
     * <p>共 17 字节。<b>注意</b>：内核另有两处「退化版」MSG_MOVE（{@code processor.cpp:690} 与
     * {@code field.cpp:300} 的分支）只写 {@code code + u32 location} 就往下走，但那两处
     * {@code code} 写的是 {@code 0}，其后再补 {@code u32 from + u32 to + u32 reason}，
     * 拼起来仍然是 17 字节——布局不变，不需要分支处理。
     */
    record Move(int _type, int offset, int _length, int code, Location from, Location to,
                int reason) implements Body {
        public int pureCode() { return code & 0x7fffffff; }
        public boolean codeFlag() { return (code & 0x80000000) != 0; }
        /** 卡号是否是 0（内核用 0 表示「不透露卡号的移动」，例如回到卡组）。 */
        public boolean anonymous() { return (code & 0x7fffffff) == 0; }
        @Override public String toString() {
            return head() + " code=" + (anonymous() ? "?" : pureCode())
                    + " " + from + " -> " + to + String.format(" reason=0x%X", reason);
        }
    }

    /** {@code u32 code, u8 controller, u8 location, u8 sequence, u8 prevPos, u8 curPos}（12 字节）。 */
    record PosChange(int _type, int offset, int _length, int code, int controller, int location,
                     int sequence, int previousPosition, int currentPosition) implements Body {
        public int pureCode() { return code & 0x7fffffff; }
        /** 由里侧变为表侧（翻面）时为 true。 */
        public boolean flipped() { return (previousPosition & 0xa) != 0 && (currentPosition & 0x5) != 0; }
        @Override public String toString() {
            return head() + " code=" + pureCode() + "@p" + controller + "/loc" + location
                    + "#" + sequence + String.format(" pos=0x%X -> 0x%X", previousPosition, currentPosition);
        }
    }

    /**
     * 盖放。{@code u32 code, u32 location}（9 字节）。
     * 怪兽盖放（{@code MSG_SET}）与魔陷盖放都会走这条。
     */
    record Set(int _type, int offset, int _length, int code, Location location) implements Body {
        public int pureCode() { return code & 0x7fffffff; }
        @Override public String toString() {
            return head() + " code=" + pureCode() + " " + location;
        }
    }

    /**
     * 两张卡交换位置。{@code u32 code1, u32 info1, u32 code2, u32 info2}（17 字节）。
     */
    record Swap(int _type, int offset, int _length, int code1, Location info1,
                int code2, Location info2) implements Body {
        @Override public String toString() {
            return head() + " code1=" + (code1 & 0x7fffffff) + "@" + info1
                    + " <-> code2=" + (code2 & 0x7fffffff) + "@" + info2;
        }
    }

    /**
     * 场地禁用位变化。{@code u32 flag}（5 字节）。
     *
     * <p>位布局与 {@link SelectPlace} 的 {@code flag} 相同，但这里是<b>当前禁用状态</b>
     * （不是「禁止你选」而是「这些格子被效果封了」）。
     */
    record FieldDisabled(int _type, int offset, int _length, int flag) implements Body {

        public boolean isDisabled(int owner, int location, int sequence) {
            int bit = sequence + (owner == 0 ? 0 : 16) + (location == Location.MZONE ? 0 : 8);
            return (flag & (1 << bit)) != 0;
        }
        public boolean monsterZoneDisabled(int player, int seq) {
            return isDisabled(player, Location.MZONE, seq);
        }
        public boolean spellZoneDisabled(int player, int seq) {
            return isDisabled(player, Location.SZONE, seq);
        }
        @Override public String toString() {
            return head() + String.format(" flag=0x%08X", flag);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 召唤
    // ══════════════════════════════════════════════════════════════════════════

    /** {@code u32 code, u32 location}（9 字节）。通常召唤宣言。 */
    record Summoning(int _type, int offset, int _length, int code, Location location) implements Body {
        public int pureCode() { return code & 0x7fffffff; }
        @Override public String toString() {
            return head() + " code=" + pureCode() + " " + location;
        }
    }

    /** {@code u8 MSG_SUMMONED}（1 字节）。通常召唤成功。 */
    record Summoned(int _type, int offset, int _length) implements Body {
        @Override public String toString() { return head(); }
    }

    /** {@code u32 code, u32 location}（9 字节）。特殊召唤宣言（location 带 {@code POS_REVEAL} 可能置位）。 */
    record SpSummoning(int _type, int offset, int _length, int code, Location location) implements Body {
        public int pureCode() { return code & 0x7fffffff; }
        @Override public String toString() {
            return head() + " code=" + pureCode() + " " + location;
        }
    }

    /** {@code u8 MSG_SPSUMMONED}（1 字节）。特殊召唤成功。 */
    record SpSummoned(int _type, int offset, int _length) implements Body {
        @Override public String toString() { return head(); }
    }

    /** {@code u32 code, u32 location}（9 字节）。翻转召唤宣言。 */
    record FlipSummoning(int _type, int offset, int _length, int code, Location location) implements Body {
        public int pureCode() { return code & 0x7fffffff; }
        @Override public String toString() {
            return head() + " code=" + pureCode() + " " + location;
        }
    }

    /** {@code u8 MSG_FLIPSUMMONED}（1 字节）。翻转召唤成功。 */
    record FlipSummoned(int _type, int offset, int _length) implements Body {
        @Override public String toString() { return head(); }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 连锁
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 连锁发动。表现类消息。
     *
     * <pre>
     * u32 code, u32 location, u8 triggeringController, u8 triggeringLocation,
     * u8 triggeringSequence, u32 description, u8 chainCount
     * </pre>
     *
     * <p>共 20 字节。<b>注意</b>：{@code triggeringLocation} 是内核 {@code clit.triggering_location}
     * 截成 u8，不是完整的 {@code LOCATION_*} 组合（可能与 {@code location} 不同）。
     * {@code chainCount} 是 1 起的连锁序号。
     */
    record Chaining(int _type, int offset, int _length, int code, Location location,
                    int triggeringController, int triggeringLocation, int triggeringSequence,
                    int description, int chainCount) implements Body {
        public int pureCode() { return code & 0x7fffffff; }
        @Override public String toString() {
            return head() + " chain=" + chainCount + " code=" + pureCode() + " " + location
                    + " trig=p" + triggeringController + "/loc" + triggeringLocation
                    + "#" + triggeringSequence + " desc=" + description;
        }
    }

    /** {@code u8 chainCount}（2 字节）。连锁已组成。 */
    record Chained(int _type, int offset, int _length, int chainCount) implements Body {
        @Override public String toString() { return head() + " chain=" + chainCount; }
    }

    /** {@code u8 chainCount}（2 字节）。开始处理该连锁。 */
    record ChainSolving(int _type, int offset, int _length, int chainCount) implements Body {
        @Override public String toString() { return head() + " chain=" + chainCount; }
    }

    /** {@code u8 chainCount}（2 字节）。该连锁处理完毕。 */
    record ChainSolved(int _type, int offset, int _length, int chainCount) implements Body {
        @Override public String toString() { return head() + " chain=" + chainCount; }
    }

    /** {@code u8 MSG_CHAIN_END}（1 字节）。整条连锁结束。 */
    record ChainEnd(int _type, int offset, int _length) implements Body {
        @Override public String toString() { return head(); }
    }

    /** {@code u8 chainCount}（2 字节）。该连锁被无效。 */
    record ChainNegated(int _type, int offset, int _length, int chainCount) implements Body {
        @Override public String toString() { return head() + " chain=" + chainCount; }
    }

    /** {@code u8 chainCount}（2 字节）。该连锁的效果被无效（但仍处理）。 */
    record ChainDisabled(int _type, int offset, int _length, int chainCount) implements Body {
        @Override public String toString() { return head() + " chain=" + chainCount; }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 单卡动作
    // ══════════════════════════════════════════════════════════════════════════

    /** {@code u8 player, u8 count, count × u32 infoLocation}（2 + 4×count 字节）。随机选中若干张。 */
    record RandomSelected(int _type, int offset, int _length, int player, Location[] locations) implements Body {
        public int count() { return locations.length; }
        public Location location(int i) { return locations[i]; }
        public List<Location> locationList() { return List.of(locations); }
        @Override public String toString() {
            return head() + " player=" + player + " picked=" + Arrays.toString(locations);
        }
    }

    /**
     * 成为效果对象。{@code u8 count, count × u32 infoLocation}。
     *
     * <p>内核目前恒写 {@code count = 1}（共 6 字节），但仍按 count 循环解析以保长度正确。
     */
    record BecomeTarget(int _type, int offset, int _length, Location[] locations) implements Body {
        public int count() { return locations.length; }
        public Location location(int i) { return locations[i]; }
        public List<Location> locationList() { return List.of(locations); }
        @Override public String toString() {
            return head() + " targets=" + Arrays.toString(locations);
        }
    }

    /** {@code u8 player, u8 count, count × u32 code}。抽卡。{@code code} 高位表示是否表侧。 */
    record Draw(int _type, int offset, int _length, int player, int[] codes) implements Body {
        public int count() { return codes.length; }
        public int code(int i) { return codes[i] & 0x7fffffff; }
        /** 第 {@code i} 张是否是表侧抽到（高位 {@code 0x80000000}）。 */
        public boolean faceUp(int i) { return (codes[i] & 0x80000000) != 0; }
        @Override public String toString() {
            StringBuilder sb = new StringBuilder(head());
            sb.append(" player=").append(player).append(" draw=[");
            for (int i = 0; i < codes.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(codes[i] & 0x7fffffff).append((codes[i] & 0x80000000) != 0 ? "(表)" : "(里)");
            }
            return sb.append(']').toString();
        }
    }

    /** {@code u8 player, u32 amount}（6 字节）。{@code amount} 是带符号 int32。 */
    record Damage(int _type, int offset, int _length, int player, int amount) implements Body {
        @Override public String toString() {
            return head() + " player=" + player + " amount=" + amount;
        }
    }

    /** {@code u8 player, u32 value}（6 字节）。{@code value} 是带符号 int32。 */
    record Recover(int _type, int offset, int _length, int player, int value) implements Body {
        @Override public String toString() {
            return head() + " player=" + player + " value=" + value;
        }
    }

    /** {@code u32 equipInfo, u32 targetInfo}（9 字节）。装备卡与被装备者。 */
    record Equip(int _type, int offset, int _length, Location equipLocation, Location targetLocation) implements Body {
        @Override public String toString() {
            return head() + " equip=" + equipLocation + " target=" + targetLocation;
        }
    }

    /** {@code u8 player, u32 lp}（6 字节）。{@code Duel.SetLP} 的直接状态覆盖，不是损伤/回复。 */
    record LpUpdate(int _type, int offset, int _length, int player, int lp) implements Body {
        @Override public String toString() {
            return head() + " player=" + player + " lp=" + lp;
        }
    }

    /** {@code u32 from, u32 target}（9 字节）。效果建立指定关系。 */
    record CardTarget(int _type, int offset, int _length, Location from, Location target) implements Body {
        @Override public String toString() {
            return head() + " from=" + from + " target=" + target;
        }
    }

    /** {@code u32 from, u32 target}（9 字节）。取消指定关系。 */
    record CancelTarget(int _type, int offset, int _length, Location from, Location target) implements Body {
        @Override public String toString() {
            return head() + " from=" + from + " target=" + target;
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 数值 / 指示物 / 战斗（渲染重点）
    // ══════════════════════════════════════════════════════════════════════════

    /** {@code u8 player, u32 cost}（6 字节）。支付生命值。 */
    record PayLpCost(int _type, int offset, int _length, int player, int cost) implements Body {
        @Override public String toString() {
            return head() + " player=" + player + " cost=" + cost;
        }
    }

    /**
     * 放置指示物。{@code u16 counterType, u8 controller, u8 location, u8 sequence, u16 count}（8 字节）。
     */
    record AddCounter(int _type, int offset, int _length, int counterType, int controller,
                      int location, int sequence, int count) implements Body {
        @Override public String toString() {
            return head() + " type=" + counterType + "@p" + controller + "/loc" + location
                    + "#" + sequence + " +" + count;
        }
    }

    /** {@code u16 counterType, u8 controller, u8 location, u8 sequence, u16 count}（8 字节）。 */
    record RemoveCounter(int _type, int offset, int _length, int counterType, int controller,
                         int location, int sequence, int count) implements Body {
        @Override public String toString() {
            return head() + " type=" + counterType + "@p" + controller + "/loc" + location
                    + "#" + sequence + " -" + count;
        }
    }

    /**
     * 攻击宣言。{@code u32 attackerInfo, u32 targetInfo}（9 字节）。
     *
     * <p><b>没有显式的「直击」标志位</b>：{@code targetInfo == 0} 即表示直接攻击
     * （内核写 {@code write_buffer32(0)}，见 {@code processor.cpp:2703-2704}）。
     */
    record Attack(int _type, int offset, int _length, Location attacker, Location target) implements Body {
        /** 是否是直接攻击（无攻击目标）。 */
        public boolean isDirect() { return target.isNone(); }
        @Override public String toString() {
            return head() + " attacker=" + attacker
                    + (isDirect() ? " DIRECT ATTACK" : " -> " + target);
        }
    }

    /**
     * 伤害计算（战斗结果）。表现类消息。固定 32 字节：
     *
     * <pre>
     * u8  MSG_BATTLE
     * u32 attackerInfo
     * u32 aa      ─ 攻击方攻击力（结算后的值）
     * u32 ad      ─ 攻击方守备力
     * u8  bd0     ─ 攻击方是否被战斗破坏（非 0 = 破坏）
     * u32 targetInfo   ─ 攻击目标（不存在时全 0）
     * u32 da      ─ 目标攻击力
     * u32 dd      ─ 目标守备力
     * u8  bd1     ─ 目标是否被战斗破坏
     * </pre>
     *
     * <p>不存在攻击目标时，内核仍然补齐这 4 个字段（写 0），所以长度恒为 32。
     */
    record Battle(int _type, int offset, int _length, Location attacker, int attackerAtk,
                  int attackerDef, int attackerDestroyed, Location target, int targetAtk,
                  int targetDef, int targetDestroyed) implements Body {

        /** 是否有攻击目标（不是直接攻击）。 */
        public boolean hasTarget() { return !target.isNone(); }
        public boolean attackerBroken() { return attackerDestroyed != 0; }
        public boolean targetBroken() { return targetDestroyed != 0; }
        /** 造成的战斗损伤（正数表示攻击方给对方造成的伤害；无目标时为 0）。 */
        public int battleDamageToTarget() {
            if (!hasTarget()) return Math.max(attackerAtk, 0);
            return attackerAtk - targetAtk;
        }
        @Override public String toString() {
            return head() + " " + attacker + "(" + attackerAtk + "/" + attackerDef + ")"
                    + (attackerBroken() ? "[破]" : "")
                    + (hasTarget()
                        ? " vs " + target + "(" + targetAtk + "/" + targetDef + ")"
                          + (targetBroken() ? "[破]" : "")
                        : " DIRECT");
        }
    }

    /** {@code u8 MSG_ATTACK_DISABLED}（1 字节）。攻击被无效。 */
    record AttackDisabled(int _type, int offset, int _length) implements Body {
        @Override public String toString() { return head(); }
    }

    /** {@code u8 MSG_DAMAGE_STEP_START}（1 字节）。 */
    record DamageStepStart(int _type, int offset, int _length) implements Body {
        @Override public String toString() { return head(); }
    }

    /** {@code u8 MSG_DAMAGE_STEP_END}（1 字节）。 */
    record DamageStepEnd(int _type, int offset, int _length) implements Body {
        @Override public String toString() { return head(); }
    }

    /** {@code u32 infoLocation, u32 code}（9 字节）。错过了发动时点。 */
    record MissedEffect(int _type, int offset, int _length, Location location, int code) implements Body {
        public int pureCode() { return code & 0x7fffffff; }
        @Override public String toString() {
            return head() + " " + location + " code=" + pureCode();
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 随机 / 宣言
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 抛硬币。{@code u8 player, u8 count, count × u8 result}（3 + count 字节）。
     * {@code result} 是 0 或 1。内核上限 {@code MAX_COIN_COUNT = 20}。
     */
    record TossCoin(int _type, int offset, int _length, int player, int[] results) implements Body {
        public int count() { return results.length; }
        public int result(int i) { return results[i]; }
        /** 正面的张数（{@code result == 1}）。 */
        public int heads() {
            int n = 0;
            for (int r : results) if (r != 0) n++;
            return n;
        }
        @Override public String toString() {
            return head() + " player=" + player + " results=" + Arrays.toString(results);
        }
    }

    /** 掷骰。{@code u8 player, u8 count, count × u8 result}（3 + count 字节）。{@code result} 是 1..6。 */
    record TossDice(int _type, int offset, int _length, int player, int[] results) implements Body {
        public int count() { return results.length; }
        public int result(int i) { return results[i]; }
        @Override public String toString() {
            return head() + " player=" + player + " results=" + Arrays.toString(results);
        }
    }

    /** {@code u8 player}（2 字节）。石头剪刀布：0 表示先手玩家出拳，1 表示后手玩家出拳。 */
    record RockPaperScissors(int _type, int offset, int _length, int player) implements Body {
        @Override public String toString() { return head() + " player=" + player; }
    }

    /**
     * 石头剪刀布结果。{@code u8 result}（2 字节）。
     * {@code result = hand0 + (hand1 << 2)}，即低 2 位是玩家 0 的手势、次 2 位是玩家 1 的。
     * 手势取值：0 = 石头，1 = 剪刀，2 = 布（{@code Duel.RockPaperScissors}）。
     */
    record HandRes(int _type, int offset, int _length, int result) implements Body {

        /** 取 {@code player} 的手势：0 = 石头，1 = 剪刀，2 = 布。 */
        public int hand(int player) { return (result >>> (player * 2)) & 0x3; }

        public static String handName(int hand) {
            return switch (hand) {
                case 0 -> "石头";
                case 1 -> "剪刀";
                case 2 -> "布";
                default -> "?";
            };
        }

        @Override public String toString() {
            return head() + " p0=" + handName(hand(0)) + " p1=" + handName(hand(1))
                    + String.format(" (raw=0x%02X)", result);
        }
    }

    /** {@code u8 player, u8 count, u32 available}（7 字节）。宣言种族；{@code count} 是须选个数。 */
    record AnnounceRace(int _type, int offset, int _length, int player, int count, int available) implements Body {
        /** {@code available} 里是否允许该种族位。 */
        public boolean allows(int raceBit) { return (available & raceBit) != 0; }
        @Override public String toString() {
            return head() + " player=" + player + " count=" + count
                    + String.format(" available=0x%X", available);
        }
    }

    /** {@code u8 player, u8 count, u32 available}（7 字节）。宣言属性；{@code count} 是须选个数。 */
    record AnnounceAttrib(int _type, int offset, int _length, int player, int count, int available) implements Body {
        /** {@code available} 里是否允许该属性位。 */
        public boolean allows(int attrBit) { return (available & attrBit) != 0; }
        @Override public String toString() {
            return head() + " player=" + player + " count=" + count
                    + String.format(" available=0x%X", available);
        }
    }

    /** {@code u8 player, u8 count, u32 option × count}（卡号）。宣言卡名。 */
    record AnnounceCard(int _type, int offset, int _length, int player, int[] options) implements Body {
        public int count() { return options.length; }
        public int option(int i) { return options[i]; }
        @Override public String toString() {
            return head() + " player=" + player + " options=" + Arrays.toString(options);
        }
    }

    /** {@code u8 player, u8 count, u32 option × count}。宣言数字。 */
    record AnnounceNumber(int _type, int offset, int _length, int player, int[] options) implements Body {
        public int count() { return options.length; }
        public int option(int i) { return options[i]; }
        @Override public String toString() {
            return head() + " player=" + player + " options=" + Arrays.toString(options);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 提示 / 扩展
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 单卡提示。{@code u32 infoLocation, u8 hintType, u32 value}（10 字节）。
     *
     * <p>{@code hintType} 见 {@link #CHINT_DESC_ADD} / {@link #CHINT_DESC_REMOVE} 等。
     */
    record CardHint(int _type, int offset, int _length, Location location, int hintType, int value) implements Body {
        public static final int CHINT_TURN = 1;
        public static final int CHINT_CARD = 2;
        public static final int CHINT_RACE = 3;
        public static final int CHINT_ATTRIBUTE = 4;
        public static final int CHINT_NUMBER = 5;
        public static final int CHINT_DESC_ADD = 6;
        public static final int CHINT_DESC_REMOVE = 7;
        @Override public String toString() {
            return head() + " " + location + " hint=" + hintType + " value=" + value;
        }
    }

    /**
     * 组队/换装换人。
     *
     * <pre>
     * u8  player
     * u8  mainCount, u8 extraCount, u8 extraPCount, u8 handCount
     * u32 deckTopCode            ─ 0 表示不公开
     * u32 handCode × handCount   ─ 高位 0x80000000 表示表侧
     * u32 extraCode × extraCount ─ 高位 0x80000000 表示表侧
     * </pre>
     *
     * <p><b>注意</b>：{@code deckTopCode} 那 4 字节是<b>无条件</b>写出的（内核用 0 占位），
     * 即使卡组不需要公开也不能省。
     */
    record TagSwap(int _type, int offset, int _length, int player, int mainCount, int extraCount,
                   int extraPCount, int deckTopCode, int[] handCodes, int[] extraCodes) implements Body {
        public int handCount() { return handCodes.length; }
        public int handCode(int i) { return handCodes[i] & 0x7fffffff; }
        public boolean handFaceUp(int i) { return (handCodes[i] & 0x80000000) != 0; }
        public int extraCode(int i) { return extraCodes[i] & 0x7fffffff; }
        public boolean extraFaceUp(int i) { return (extraCodes[i] & 0x80000000) != 0; }
        /** 卡组顶是否公开（{@code deckTopCode != 0}）。 */
        public boolean deckTopRevealed() { return (deckTopCode & 0x7fffffff) != 0; }
        @Override public String toString() {
            return head() + " player=" + player + " main=" + mainCount
                    + " extra=" + extraCount + " extraP=" + extraPCount
                    + " deckTop=" + (deckTopCode & 0x7fffffff)
                    + " hand=" + Arrays.toString(handCodes) + " extraCards=" + Arrays.toString(extraCodes);
        }
    }

    /** {@code MSG_RELOAD_FIELD} 里的一格：{@code u8 present, [u8 position, u8 overlayCount]}（怪兽区）或 {@code u8 present, [u8 position]}（魔陷区）。 */
    record ReloadZone(int present, int position, int overlayCount) {
        public boolean occupied() { return present != 0; }
        @Override public String toString() {
            return present == 0 ? "-" : String.format("pos0x%X/ovl%d", position, overlayCount);
        }
    }

    /** {@code MSG_RELOAD_FIELD} 里的一个玩家区块。 */
    record ReloadPlayer(int lp, ReloadZone[] monsterZones, ReloadZone[] spellZones,
                        int deckCount, int handCount, int graveCount, int removedCount,
                        int extraCount, int extraPCount) {
        @Override public String toString() {
            return "lp=" + lp + " mzone=" + Arrays.toString(monsterZones)
                    + " szone=" + Arrays.toString(spellZones)
                    + " deck=" + deckCount + " hand=" + handCount + " grave=" + graveCount
                    + " removed=" + removedCount + " extra=" + extraCount + " extraP=" + extraPCount;
        }
    }

    /** {@code MSG_RELOAD_FIELD} 里的一个连锁项（与 {@link Chaining} 前 6 个字段同构）。 */
    record ReloadChainEntry(int code, Location location, int triggeringController,
                            int triggeringLocation, int triggeringSequence, int description) {
        @Override public String toString() {
            return (code & 0x7fffffff) + "@" + location + " trig=p" + triggeringController
                    + "/loc" + triggeringLocation + "#" + triggeringSequence + " desc=" + description;
        }
    }

    /**
     * 整场快照。{@code query_field_info()} 也返回这个布局。
     *
     * <pre>
     * u8  duelRule
     * ×2 玩家：
     *     u32 lp
     *     ×7 怪兽区：u8 present, 若非 0 则 u8 position + u8 xyzMaterialCount
     *     ×8 魔陷区：u8 present, 若非 0 则 u8 position
     *     u8 mainCount, u8 handCount, u8 graveCount, u8 removedCount, u8 extraCount, u8 extraPCount
     * u8  chainCount, chainCount × (u32 code, u32 location, u8 trigController, u8 trigLocation,
     *                               u8 trigSequence, u32 description)
     * </pre>
     *
     * <p>字段跨度较大：兽陷区尺寸写死在 {@code field.cpp:68-69}（7 怪兽区 / 8 魔陷区）。
     */
    record ReloadField(int _type, int offset, int _length, int duelRule,
                       ReloadPlayer player0, ReloadPlayer player1,
                       ReloadChainEntry[] chain) implements Body {

        /** 取第 {@code i} 个玩家的区块（0 或 1）。 */
        public ReloadPlayer playerAt(int i) { return i == 0 ? player0 : player1; }
        public int chainCount() { return chain.length; }
        public List<ReloadChainEntry> chainList() { return List.of(chain); }

        @Override public String toString() {
            return head() + " rule=" + duelRule + " chain=" + chain.length
                    + "\n  P0 " + player0 + "\n  P1 " + player1;
        }
    }

    /** {@code u16 length, u8 bytes[length], u8 0}（NUL 结尾，长度含结尾 0）。AI 名字。 */
    record AiName(int _type, int offset, int _length, String name) implements Body {
        @Override public String toString() { return head() + " name=\"" + name + "\""; }
    }

    /** {@code u16 length, u8 bytes[length], u8 0}（NUL 结尾，长度含结尾 0）。调试提示文本。 */
    record ShowHint(int _type, int offset, int _length, String text) implements Body {
        @Override public String toString() { return head() + " text=\"" + text + "\""; }
    }

    /**
     * 玩家提示。{@code u8 player, u8 hintType, u32 description}（7 字节）。
     * 与 {@link CardHint} 的区别是它不带卡的位置。
     */
    record PlayerHint(int _type, int offset, int _length, int player, int hintType, int description) implements Body {
        public static final int PHINT_DESC_ADD = 6;
        public static final int PHINT_DESC_REMOVE = 7;
        @Override public String toString() {
            return head() + " player=" + player + " hint=" + hintType + " desc=" + description;
        }
    }

    /** {@code u32 code}（5 字节）。{@code EFFECT_MATCH_KILL}：一击决胜。 */
    record MatchKill(int _type, int offset, int _length, int code) implements Body {
        @Override public String toString() {
            return head() + " code=" + (code & 0x7fffffff);
        }
    }

    /**
     * 宿主自定义消息。{@code u8 MSG_CUSTOM_MSG, u8 payload...}。
     *
     * <p><b>内核从不写这条消息</b>（{@code common.h} 里只定义了号，没有任何
     * {@code write_buffer8(MSG_CUSTOM_MSG)} 调用点），因此它的负载布局由宿主自己决定。
     * 解码时读到它就<b>无法</b>知道该吃多少字节——{@link MsgCodec#decode} 会抛
     * {@link MsgCodecException}，除非调用方显式给出实参 {@code payloadLength}。
     */
    record CustomMsg(int _type, int offset, int _length, byte[] payload) implements Body {
        @Override public String toString() {
            return head() + " payload=" + (payload == null ? "?" : payload.length + "B");
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 便捷判断
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 这条消息是否是在「等玩家应答」。
     *
     * <p>只有询问类消息需要宿主回 {@code setResponseI/B}；配合 {@code PROCESSOR_WAITING}
     * 使用（前者是权威信号，本方法是按消息类型的补充判断）。
     */
    default boolean isQuery() {
        return switch (type()) {
            case MsgType.SELECT_BATTLECMD, MsgType.SELECT_IDLECMD, MsgType.SELECT_EFFECTYN,
                 MsgType.SELECT_YESNO, MsgType.SELECT_OPTION, MsgType.SELECT_CARD,
                 MsgType.SELECT_CHAIN, MsgType.SELECT_PLACE, MsgType.SELECT_POSITION,
                 MsgType.SELECT_TRIBUTE, MsgType.SELECT_COUNTER, MsgType.SELECT_SUM,
                 MsgType.SELECT_DISFIELD, MsgType.SORT_CARD, MsgType.SELECT_UNSELECT_CARD,
                 MsgType.ANNOUNCE_RACE, MsgType.ANNOUNCE_ATTRIB, MsgType.ANNOUNCE_CARD,
                 MsgType.ANNOUNCE_NUMBER, MsgType.ROCK_PAPER_SCISSORS -> true;
            default -> false;
        };
    }

    /** 便捷：把 {@link Body} 当作某个具体类型时做类型检查的短路写法。 */
    static <T extends Body> T cast(Class<T> kind, Msg msg) {
        Objects.requireNonNull(msg, "msg");
        return kind.cast(msg);
    }
}
