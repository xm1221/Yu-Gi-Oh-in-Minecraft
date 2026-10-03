package cn.xm1221.ygomc.common.ocg.msg;

import java.nio.charset.StandardCharsets;

/**
 * ocgcore 消息流的解码器。
 *
 * <h2>消息流长什么样</h2>
 * 引擎的 {@code process()} / {@code get_message()} 交出来的是一整块缓冲，里面是
 * <b>多条消息首尾拼接</b>：
 *
 * <pre>
 * ┌────────────┬────────────┬────────────┬───
 * │ 消息 1      │ 消息 2      │ 消息 3      │ ...
 * │ u8 类型 + 负载 │ u8 类型 + 负载 │ u8 类型 + 负载 │
 * └────────────┴────────────┴────────────┴───
 * </pre>
 *
 * <ul>
 *   <li><b>没有长度前缀</b>，也没有条数前缀。<b>唯一</b>的走纸带方式是「解析一条 →
 *       得到它的长度 → 从长度处继续解析下一条」；</li>
 *   <li>每条消息的<b>第 1 字节是消息类型号</b>（{@link MsgType}），其余字段一律
 *       <b>小端</b>（内核用 {@code memcpy} 直接写 {@code uint8_t/16/32}，见
 *       {@code buffer.h:24-28}，而目标平台是 x86/x64）；</li>
 *   <li>数组类字段是「1 字节数量 + 数量 × 定长元素」。数量是 u8，所以一条消息里
 *       同类元素最多 255 个（内核在写入前会 {@code resize(UINT8_MAX)} 保证这点）。</li>
 * </ul>
 *
 * <h2>「解析即测长」</h2>
 * 因为缓冲里没有长度信息，所以<b>算出一条消息多长 == 把它完整解析一遍</b>。
 * 本类的 {@link #decode(byte[], int)} 和 {@link #length(byte[], int)} 因此是同一件事的两种包装：
 *
 * <pre>{@code
 * // 等价，且 length() 就是 decode().length()
 * Msg m = MsgCodec.decode(buf, off);
 * int n = MsgCodec.length(buf, off);
 * }</pre>
 *
 * 这带来一个必须严肃对待的后果：<b>任何一处把变长字段读错一个字节，从这里开始整条
 * 消息流会永久错位</b>，后续每一帧都会解析出垃圾。所以本类遵守两条硬规矩：
 *
 * <ol>
 *   <li>未知消息类型 → 立刻抛 {@link MsgCodecException}（带类型号与偏移），绝不猜长度；</li>
 *   <li>读字段越过 {@code buf} 末尾 → 立刻抛 {@link MsgCodecException}，
 *       绝不「尽力而为」返回一个可能错误的长度。</li>
 * </ol>
 *
 * <h2>典型用法</h2>
 * <pre>{@code
 * int len = Ocg.getMessage(handle, buf);   // 引擎写入的字节数
 * for (int off = 0; off < len; ) {
 *     Msg m = MsgCodec.decode(buf, off);   // 越界/未知类型会抛 MsgCodecException
 *     dispatch(m);
 *     off += m.length();
 * }
 * }</pre>
 *
 * <h2>哪些类型真的会出现</h2>
 * 内核 {@code common.h} 定义了 82 个 {@code MSG_*} 常量，但只有 72 个有写入点。
 * 本类认识全部 82 个中的 81 个（{@link MsgType#CUSTOM_MSG} 例外：内核从不写它，
 * 其负载布局由宿主自定，见 {@link Msg.CustomMsg}），并额外实现了
 * {@link MsgType#CONFIRM_DECKTOP} / {@link MsgType#CONFIRM_EXTRATOP} /
 * {@link MsgType#SHUFFLE_HAND} / {@link MsgType#SHUFFLE_EXTRA}
 * 这几条「有写入点但不在 72 种高频清单里」的消息。
 *
 * <h2>纯静态、无依赖</h2>
 * 本类及其同包类型不引用任何 Minecraft 类，也不引用任何第三方库，只用 JDK 标准库。
 */
public final class MsgCodec {

    private MsgCodec() {}

    /** {@code MSG_MOVE} 的固定长度：1 + 4 + 4 + 4 + 4。 */
    private static final int LEN_MOVE = 17;

    /**
     * 解析 {@code buf} 中从 {@code off} 开始的一条消息。
     *
     * @param buf 存放消息流的缓冲（通常是 {@code Ocg.getMessage} 的接收缓冲）
     * @param off 本条消息的起始偏移
     * @return 解码后的消息；{@code msg.length()} 就是本条消息占用的字节数
     * @throws MsgCodecException 类型号未知，或读字段越界
     * @throws IndexOutOfBoundsException {@code off} 本身就不在 {@code [0, buf.length]} 内
     */
    public static Msg decode(byte[] buf, int off) {
        if (off < 0 || off > buf.length) {
            throw new IndexOutOfBoundsException(
                    "off=" + off + " 不在 [0, " + buf.length + "] 内");
        }
        if (off == buf.length) {
            throw new MsgCodecException("偏移已到缓冲末尾，没有类型字节可读",
                    MsgCodecException.NO_TYPE, off);
        }
        Cursor c = new Cursor(buf, off);
        int type = buf[off] & 0xff;
        try {
            return switch (type) {
                case MsgType.RETRY -> decodeRetry(c, type);
                case MsgType.HINT -> decodeHint(c, type);
                case MsgType.WIN -> decodeWin(c, type);
                case MsgType.SELECT_BATTLECMD -> decodeSelectBattleCmd(c, type);
                case MsgType.SELECT_IDLECMD -> decodeSelectIdleCmd(c, type);
                case MsgType.SELECT_EFFECTYN -> decodeSelectEffectYn(c, type);
                case MsgType.SELECT_YESNO -> decodeSelectYesNo(c, type);
                case MsgType.SELECT_OPTION -> decodeSelectOption(c, type);
                case MsgType.SELECT_CARD -> decodeSelectCard(c, type);
                case MsgType.SELECT_CHAIN -> decodeSelectChain(c, type);
                case MsgType.SELECT_PLACE, MsgType.SELECT_DISFIELD -> decodeSelectPlace(c, type);
                case MsgType.SELECT_POSITION -> decodeSelectPosition(c, type);
                case MsgType.SELECT_TRIBUTE -> decodeSelectTribute(c, type);
                case MsgType.SELECT_COUNTER -> decodeSelectCounter(c, type);
                case MsgType.SELECT_SUM -> decodeSelectSum(c, type);
                case MsgType.SORT_CARD -> decodeSortCard(c, type);
                case MsgType.SELECT_UNSELECT_CARD -> decodeSelectUnselectCard(c, type);
                case MsgType.CONFIRM_DECKTOP -> decodeConfirmCards(c, type);
                case MsgType.CONFIRM_CARDS -> decodeConfirmCards(c, type);
                case MsgType.CONFIRM_EXTRATOP -> decodeConfirmCards(c, type);
                case MsgType.SHUFFLE_DECK -> decodePlayerOnly(c, type);
                case MsgType.SHUFFLE_EXTRA, MsgType.SHUFFLE_HAND -> decodeShuffleCodes(c, type);
                case MsgType.SWAP_GRAVE_DECK -> decodePlayerOnly(c, type);
                case MsgType.SHUFFLE_SET_CARD -> decodeShuffleSetCard(c, type);
                case MsgType.REVERSE_DECK -> new Msg.ReverseDeck(type, c.messageOffset(), 1);
                case MsgType.DECK_TOP -> decodeDeckTop(c, type);
                case MsgType.NEW_TURN -> decodeNewTurn(c, type);
                case MsgType.NEW_PHASE -> decodeNewPhase(c, type);
                case MsgType.MOVE -> decodeMove(c, type);
                case MsgType.POS_CHANGE -> decodePosChange(c, type);
                case MsgType.SET -> decodeCodeLocation(c, type, true);
                case MsgType.SUMMONING -> decodeCodeLocation(c, type, false);
                case MsgType.SPSUMMONING -> decodeCodeLocation(c, type, false);
                case MsgType.FLIPSUMMONING -> decodeCodeLocation(c, type, false);
                case MsgType.SUMMONED, MsgType.SPSUMMONED, MsgType.FLIPSUMMONED,
                     MsgType.CHAIN_END, MsgType.ATTACK_DISABLED, MsgType.DAMAGE_STEP_START,
                     MsgType.DAMAGE_STEP_END -> decodeEmpty(c, type);
                case MsgType.SWAP -> decodeSwap(c, type);
                case MsgType.FIELD_DISABLED -> decodeFieldDisabled(c, type);
                case MsgType.CHAINING -> decodeChaining(c, type);
                case MsgType.CHAINED, MsgType.CHAIN_SOLVING, MsgType.CHAIN_SOLVED,
                     MsgType.CHAIN_NEGATED, MsgType.CHAIN_DISABLED -> decodeChainCount(c, type);
                case MsgType.RANDOM_SELECTED -> decodeRandomSelected(c, type);
                case MsgType.BECOME_TARGET -> decodeBecomeTarget(c, type);
                case MsgType.DRAW -> decodeDraw(c, type);
                case MsgType.DAMAGE -> decodePlayerAmount(c, type);
                case MsgType.RECOVER, MsgType.LPUPDATE, MsgType.PAY_LPCOST ->
                        decodePlayerAmount(c, type);
                case MsgType.EQUIP, MsgType.CARD_TARGET, MsgType.CANCEL_TARGET ->
                        decodeTwoLocations(c, type);
                case MsgType.ADD_COUNTER, MsgType.REMOVE_COUNTER -> decodeCounter(c, type);
                case MsgType.ATTACK -> decodeAttack(c, type);
                case MsgType.BATTLE -> decodeBattle(c, type);
                case MsgType.MISSED_EFFECT -> decodeMissedEffect(c, type);
                case MsgType.TOSS_COIN -> decodeToss(c, type, true);
                case MsgType.TOSS_DICE -> decodeToss(c, type, false);
                case MsgType.ROCK_PAPER_SCISSORS -> decodePlayerOnly(c, type);
                case MsgType.HAND_RES -> decodeHandRes(c, type);
                case MsgType.ANNOUNCE_RACE -> decodeAnnounceAvailable(c, type, true);
                case MsgType.ANNOUNCE_ATTRIB -> decodeAnnounceAvailable(c, type, false);
                case MsgType.ANNOUNCE_CARD -> decodeAnnounceOptions(c, type, true);
                case MsgType.ANNOUNCE_NUMBER -> decodeAnnounceOptions(c, type, false);
                case MsgType.CARD_HINT -> decodeCardHint(c, type);
                case MsgType.PLAYER_HINT -> decodePlayerHint(c, type);
                case MsgType.MATCH_KILL -> decodeMatchKill(c, type);
                case MsgType.TAG_SWAP -> decodeTagSwap(c, type);
                case MsgType.RELOAD_FIELD -> decodeReloadField(c, type);
                case MsgType.AI_NAME -> decodeText(c, type, true);
                case MsgType.SHOW_HINT -> decodeText(c, type, false);
                case MsgType.CUSTOM_MSG -> throw new MsgCodecException(
                        "MSG_CUSTOM_MSG 的负载布局由宿主自定义，内核从不写它，无法推断长度",
                        type, off);
                default -> throw MsgCodecException.unknownType(type, off);
            };
        } catch (ArrayIndexOutOfBoundsException e) {
            // 理论上不会到这里（Cursor 自带边界检查）；留作兜底，避免半路崩出原始异常。
            throw new MsgCodecException("解析越界（内部错误）", type, off, e);
        }
    }

    /**
     * 返回 {@code off} 处那条消息的总字节数（含类型字节）。
     *
     * <p>恒等于 {@code decode(buf, off).length()} —— 因为「算长度」就是「按布局解析一遍」。
     * 实现上就是解析一次然后取长度；对性能敏感的场景请复用 {@link #decode} 的返回值。
     */
    public static int length(byte[] buf, int off) {
        return decode(buf, off).length();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 各消息的解析实现
    // 每个方法内部都写入与内核一致的顺序；返回前把 c.position() 作为长度，
    // 因此「字段读了多少」与「消息有多长」天然一致。
    // ══════════════════════════════════════════════════════════════════════════

    private static Msg decodeRetry(Cursor c, int t) {
        return new Msg.Retry(t, c.messageOffset(), c.position() - c.messageOffset());
    }

    private static Msg decodeHint(Cursor c, int t) {
        int hintType = c.u8("hintType");
        int player = c.u8("player");
        int description = c.u32("description");
        return new Msg.Hint(t, c.messageOffset(), c.position() - c.messageOffset(),
                hintType, player, description);
    }

    private static Msg decodeWin(Cursor c, int t) {
        int winner = c.u8("winner");
        int reason = c.u8("reason");
        return new Msg.Win(t, c.messageOffset(), c.position() - c.messageOffset(), winner, reason);
    }

    private static Msg decodeSelectBattleCmd(Cursor c, int t) {
        int player = c.u8("player");
        int chainCount = c.u8("chainCount");
        Msg.SelectChainEntry[] chains = new Msg.SelectChainEntry[chainCount];
        for (int i = 0; i < chainCount; i++) {
            chains[i] = new Msg.SelectChainEntry(
                    c.u32("chain.code"), c.u8("chain.controller"),
                    c.u8("chain.location"), c.u8("chain.sequence"), c.u32("chain.description"));
        }
        int atkCount = c.u8("attackableCount");
        Msg.AttackableEntry[] attackable = new Msg.AttackableEntry[atkCount];
        for (int i = 0; i < atkCount; i++) {
            attackable[i] = new Msg.AttackableEntry(
                    c.u32("attackable.code"), c.u8("attackable.controller"),
                    c.u8("attackable.location"), c.u8("attackable.sequence"),
                    c.u8("attackable.directAttackable"));
        }
        int toM2 = c.u8("toM2");
        int toEp = c.u8("toEp");
        return new Msg.SelectBattleCmd(t, c.messageOffset(), c.position() - c.messageOffset(),
                player, chains, attackable, toM2, toEp);
    }

    private static Msg decodeSelectIdleCmd(Cursor c, int t) {
        int player = c.u8("player");
        Msg.CardEntry[] summon = c.cardEntries("summon");
        Msg.CardEntry[] spSummon = c.cardEntries("spSummon");
        Msg.CardEntry[] reposition = c.cardEntries("reposition");
        Msg.CardEntry[] monsterSet = c.cardEntries("monsterSet");
        Msg.CardEntry[] spellSet = c.cardEntries("spellSet");
        int chainCount = c.u8("chainCount");
        Msg.SelectChainEntry[] chains = new Msg.SelectChainEntry[chainCount];
        for (int i = 0; i < chainCount; i++) {
            chains[i] = new Msg.SelectChainEntry(
                    c.u32("chain.code"), c.u8("chain.controller"),
                    c.u8("chain.location"), c.u8("chain.sequence"), c.u32("chain.description"));
        }
        int toBp = c.u8("toBp");
        int toEp = c.u8("toEp");
        int canShuffle = c.u8("canShuffle");
        return new Msg.SelectIdleCmd(t, c.messageOffset(), c.position() - c.messageOffset(),
                player, summon, spSummon, reposition, monsterSet, spellSet, chains,
                toBp, toEp, canShuffle);
    }

    private static Msg decodeSelectEffectYn(Cursor c, int t) {
        int player = c.u8("player");
        int code = c.u32("code");
        Msg.Location location = c.location("location");
        int description = c.u32("description");
        return new Msg.SelectEffectYn(t, c.messageOffset(), c.position() - c.messageOffset(),
                player, code, location, description);
    }

    private static Msg decodeSelectYesNo(Cursor c, int t) {
        int player = c.u8("player");
        int description = c.u32("description");
        return new Msg.SelectYesNo(t, c.messageOffset(), c.position() - c.messageOffset(),
                player, description);
    }

    private static Msg decodeSelectOption(Cursor c, int t) {
        int player = c.u8("player");
        int count = c.u8("count");
        int[] options = c.u32Array("options", count);
        return new Msg.SelectOption(t, c.messageOffset(), c.position() - c.messageOffset(),
                player, options);
    }

    private static Msg decodeSelectCard(Cursor c, int t) {
        int player = c.u8("player");
        int cancelable = c.u8("cancelable");
        int min = c.u8("min");
        int max = c.u8("max");
        int count = c.u8("count");
        int[] codes = new int[count];
        Msg.Location[] locations = new Msg.Location[count];
        for (int i = 0; i < count; i++) {
            codes[i] = c.u32("card.code");
            locations[i] = c.location("card.location");
        }
        return new Msg.SelectCard(t, c.messageOffset(), c.position() - c.messageOffset(),
                player, cancelable, min, max, codes, locations);
    }

    private static Msg decodeSelectChain(Cursor c, int t) {
        int player = c.u8("player");
        int count = c.u8("count");
        int speCount = c.u8("speCount");
        int hintSelf = c.u32("hintTimingSelf");
        int hintOther = c.u32("hintTimingOther");
        Msg.SelectChain.ChainEntry[] entries = new Msg.SelectChain.ChainEntry[count];
        for (int i = 0; i < count; i++) {
            // ★ 顺序必须是 edesc / forced 在前，code / loc / desc 在后。
            int edesc = c.u8("chain.edesc");
            int forced = c.u8("chain.forced");
            int code = c.u32("chain.code");
            Msg.Location location = c.location("chain.location");
            int description = c.u32("chain.description");
            entries[i] = new Msg.SelectChain.ChainEntry(edesc, forced, code, location, description);
        }
        return new Msg.SelectChain(t, c.messageOffset(), c.position() - c.messageOffset(),
                player, speCount, hintSelf, hintOther, entries);
    }

    /** {@code MSG_SELECT_PLACE} 与 {@code MSG_SELECT_DISFIELD} 布局完全相同。 */
    private static Msg decodeSelectPlace(Cursor c, int t) {
        int player = c.u8("player");
        int count = c.u8("count");
        int flag = c.u32("flag");
        return new Msg.SelectPlace(t, c.messageOffset(), c.position() - c.messageOffset(),
                player, count, flag);
    }

    private static Msg decodeSelectPosition(Cursor c, int t) {
        int player = c.u8("player");
        int code = c.u32("code");
        int positions = c.u8("positions");
        return new Msg.SelectPosition(t, c.messageOffset(), c.position() - c.messageOffset(),
                player, code, positions);
    }

    private static Msg decodeSelectTribute(Cursor c, int t) {
        int player = c.u8("player");
        int cancelable = c.u8("cancelable");
        int min = c.u8("min");
        int max = c.u8("max");
        int count = c.u8("count");
        int[] codes = new int[count];
        int[] controllers = new int[count];
        int[] locations = new int[count];
        int[] sequences = new int[count];
        int[] releaseParams = new int[count];
        for (int i = 0; i < count; i++) {
            // ★ 每项 7 字节（不是 SELECT_CARD 的 8 字节）。
            codes[i] = c.u32("card.code");
            controllers[i] = c.u8("card.controller");
            locations[i] = c.u8("card.location");
            sequences[i] = c.u8("card.sequence");
            releaseParams[i] = c.u8("card.releaseParam");
        }
        return new Msg.SelectTribute(t, c.messageOffset(), c.position() - c.messageOffset(),
                player, cancelable, min, max, codes, controllers, locations, sequences, releaseParams);
    }

    private static Msg decodeSelectCounter(Cursor c, int t) {
        int player = c.u8("player");
        int counterType = c.u16("counterType");
        int count = c.u16("count");
        int n = c.u8("cardCount");
        int[] codes = new int[n];
        int[] controllers = new int[n];
        int[] locations = new int[n];
        int[] sequences = new int[n];
        int[] counters = new int[n];
        for (int i = 0; i < n; i++) {
            // ★ 每项 11 字节：u32 + u8 + u8 + u8 + u16。
            codes[i] = c.u32("card.code");
            controllers[i] = c.u8("card.controller");
            locations[i] = c.u8("card.location");
            sequences[i] = c.u8("card.sequence");
            counters[i] = c.u16("card.counter");
        }
        return new Msg.SelectCounter(t, c.messageOffset(), c.position() - c.messageOffset(),
                player, counterType, count, codes, controllers, locations, sequences, counters);
    }

    private static Msg decodeSelectSum(Cursor c, int t) {
        int flag = c.u8("flag");
        int player = c.u8("player");
        int acc = c.u32("acc");
        int min = c.u8("min");
        int max = c.u8("max");
        Msg.SelectSumEntry[] must = c.sumEntries("must");
        Msg.SelectSumEntry[] selectable = c.sumEntries("selectable");
        return new Msg.SelectSum(t, c.messageOffset(), c.position() - c.messageOffset(),
                flag, player, acc, min, max, must, selectable);
    }

    private static Msg decodeSortCard(Cursor c, int t) {
        int player = c.u8("player");
        Msg.CardEntry[] cards = c.cardEntries("cards");
        return new Msg.SortCard(t, c.messageOffset(), c.position() - c.messageOffset(), player, cards);
    }

    private static Msg decodeSelectUnselectCard(Cursor c, int t) {
        int player = c.u8("player");
        // ★ finishable 在 cancelable 之前。
        int finishable = c.u8("finishable");
        int cancelable = c.u8("cancelable");
        int min = c.u8("min");
        int max = c.u8("max");
        int selCount = c.u8("selectCount");
        int[] selectCodes = new int[selCount];
        Msg.Location[] selectLocations = new Msg.Location[selCount];
        for (int i = 0; i < selCount; i++) {
            selectCodes[i] = c.u32("select.code");
            selectLocations[i] = c.location("select.location");
        }
        int unselCount = c.u8("unselectCount");
        int[] unselectCodes = new int[unselCount];
        Msg.Location[] unselectLocations = new Msg.Location[unselCount];
        for (int i = 0; i < unselCount; i++) {
            unselectCodes[i] = c.u32("unselect.code");
            unselectLocations[i] = c.location("unselect.location");
        }
        return new Msg.SelectUnselectCard(t, c.messageOffset(), c.position() - c.messageOffset(),
                player, finishable, cancelable, min, max,
                selectCodes, selectLocations, unselectCodes, unselectLocations);
    }

    /**
     * {@code MSG_CONFIRM_DECKTOP} / {@code MSG_CONFIRM_EXTRATOP} / {@code MSG_CONFIRM_CARDS}。
     *
     * <p><b>三者的版式并不相同</b>，差别就在那个 {@code skipPanel} 字节：
     * <ul>
     *   <li>{@code MSG_CONFIRM_DECKTOP}（{@code libduel.cpp:1008-1015}）：
     *       {@code u8 player, u8 count, count × (u32 code, u8 + u8 + u8)} —— <b>没有 skipPanel</b>；</li>
     *   <li>{@code MSG_CONFIRM_EXTRATOP}（{@code libduel.cpp:1030-1038}）：同上，<b>没有 skipPanel</b>；</li>
     *   <li>{@code MSG_CONFIRM_CARDS}（{@code libduel.cpp:1067-1072}）：
     *       {@code u8 player, u8 skipPanel, u8 count, count × (...)} —— 只有它有。</li>
     * </ul>
     *
     * <p>把三者当成同一种版式，会把 {@code count} 误读成 {@code skipPanel}，
     * 再拿紧随其后的第一个卡号低字节当数量，于是读出个位数倍于真实长度的候选表，
     * 直接把缓冲区读穿。实测量：{@code CONFIRM_DECKTOP} 的
     * {@code 1E 00 03 ...}(count=3) 被读成 count=7，随即越界。
     */
    private static Msg decodeConfirmCards(Cursor c, int t) {
        int player = c.u8("player");
        int skipPanel = (t == MsgType.CONFIRM_CARDS) ? c.u8("skipPanel") : 0;
        Msg.CardEntry[] cards = c.cardEntries("cards");
        int len = c.position() - c.messageOffset();
        return switch (t) {
            case MsgType.CONFIRM_DECKTOP ->
                    new Msg.ConfirmDeckTop(t, c.messageOffset(), len, player, cards);
            case MsgType.CONFIRM_EXTRATOP ->
                    new Msg.ConfirmExtraTop(t, c.messageOffset(), len, player, cards);
            default -> new Msg.ConfirmCards(t, c.messageOffset(), len, player, skipPanel, cards);
        };
    }

    private static Msg decodePlayerOnly(Cursor c, int t) {
        int player = c.u8("player");
        int len = c.position() - c.messageOffset();
        return switch (t) {
            case MsgType.SHUFFLE_DECK -> new Msg.ShuffleDeck(t, c.messageOffset(), len, player);
            case MsgType.SWAP_GRAVE_DECK -> new Msg.SwapGraveDeck(t, c.messageOffset(), len, player);
            case MsgType.ROCK_PAPER_SCISSORS ->
                    new Msg.RockPaperScissors(t, c.messageOffset(), len, player);
            default -> throw new IllegalStateException("decodePlayerOnly 不支持 " + MsgType.name(t));
        };
    }

    /** {@code MSG_SHUFFLE_HAND} / {@code MSG_SHUFFLE_EXTRA}：{@code u8 player, u8 count, count × u32 code}。 */
    private static Msg decodeShuffleCodes(Cursor c, int t) {
        int player = c.u8("player");
        int count = c.u8("count");
        int[] codes = c.u32Array("codes", count);
        int len = c.position() - c.messageOffset();
        return t == MsgType.SHUFFLE_HAND
                ? new Msg.ShuffleHand(t, c.messageOffset(), len, player, codes)
                : new Msg.ShuffleExtra(t, c.messageOffset(), len, player, codes);
    }

    private static Msg decodeShuffleSetCard(Cursor c, int t) {
        int location = c.u8("location");
        int count = c.u8("count");
        Msg.Location[] zones = new Msg.Location[count];
        for (int i = 0; i < count; i++) zones[i] = c.location("zone");
        return new Msg.ShuffleSetCard(t, c.messageOffset(), c.position() - c.messageOffset(),
                location, zones);
    }

    private static Msg decodeDeckTop(Cursor c, int t) {
        int player = c.u8("player");
        int sequence = c.u8("sequence");
        int code = c.u32("code");
        return new Msg.DeckTop(t, c.messageOffset(), c.position() - c.messageOffset(),
                player, sequence, code);
    }

    private static Msg decodeNewTurn(Cursor c, int t) {
        int turnPlayer = c.u8("turnPlayer");
        return new Msg.NewTurn(t, c.messageOffset(), c.position() - c.messageOffset(), turnPlayer);
    }

    private static Msg decodeNewPhase(Cursor c, int t) {
        int phase = c.u16("phase");
        return new Msg.NewPhase(t, c.messageOffset(), c.position() - c.messageOffset(), phase);
    }

    /** 所有「{@code u32 code, u32 location}」形态的消息（MOVE 之外的 SET / 三种召唤宣言）。 */
    private static Msg decodeCodeLocation(Cursor c, int t, boolean isSet) {
        int code = c.u32("code");
        Msg.Location location = c.location("location");
        int len = c.position() - c.messageOffset();
        if (isSet) return new Msg.Set(t, c.messageOffset(), len, code, location);
        return switch (t) {
            case MsgType.SUMMONING -> new Msg.Summoning(t, c.messageOffset(), len, code, location);
            case MsgType.SPSUMMONING -> new Msg.SpSummoning(t, c.messageOffset(), len, code, location);
            case MsgType.FLIPSUMMONING -> new Msg.FlipSummoning(t, c.messageOffset(), len, code, location);
            default -> throw new IllegalStateException("decodeCodeLocation 不支持 " + MsgType.name(t));
        };
    }

    private static Msg decodeEmpty(Cursor c, int t) {
        int len = c.position() - c.messageOffset();
        return switch (t) {
            case MsgType.SUMMONED -> new Msg.Summoned(t, c.messageOffset(), len);
            case MsgType.SPSUMMONED -> new Msg.SpSummoned(t, c.messageOffset(), len);
            case MsgType.FLIPSUMMONED -> new Msg.FlipSummoned(t, c.messageOffset(), len);
            case MsgType.CHAIN_END -> new Msg.ChainEnd(t, c.messageOffset(), len);
            case MsgType.ATTACK_DISABLED -> new Msg.AttackDisabled(t, c.messageOffset(), len);
            case MsgType.DAMAGE_STEP_START -> new Msg.DamageStepStart(t, c.messageOffset(), len);
            case MsgType.DAMAGE_STEP_END -> new Msg.DamageStepEnd(t, c.messageOffset(), len);
            default -> throw new IllegalStateException("decodeEmpty 不支持 " + MsgType.name(t));
        };
    }

    private static Msg decodeMove(Cursor c, int t) {
        int code = c.u32("code");
        Msg.Location from = c.location("from");
        Msg.Location to = c.location("to");
        int reason = c.u32("reason");
        int len = c.position() - c.messageOffset();
        if (len != LEN_MOVE) {
            // 防御性自检：布局改动一旦破坏定长，立刻在这里炸掉而不是让流错位。
            throw new MsgCodecException("MSG_MOVE 长度自检失败：期望 " + LEN_MOVE + "，实得 " + len,
                    t, c.messageOffset());
        }
        return new Msg.Move(t, c.messageOffset(), len, code, from, to, reason);
    }

    private static Msg decodePosChange(Cursor c, int t) {
        int code = c.u32("code");
        int controller = c.u8("controller");
        int location = c.u8("location");
        int sequence = c.u8("sequence");
        int prevPos = c.u8("previousPosition");
        int curPos = c.u8("currentPosition");
        return new Msg.PosChange(t, c.messageOffset(), c.position() - c.messageOffset(),
                code, controller, location, sequence, prevPos, curPos);
    }

    private static Msg decodeSwap(Cursor c, int t) {
        int code1 = c.u32("code1");
        Msg.Location info1 = c.location("info1");
        int code2 = c.u32("code2");
        Msg.Location info2 = c.location("info2");
        return new Msg.Swap(t, c.messageOffset(), c.position() - c.messageOffset(),
                code1, info1, code2, info2);
    }

    private static Msg decodeFieldDisabled(Cursor c, int t) {
        int flag = c.u32("flag");
        return new Msg.FieldDisabled(t, c.messageOffset(), c.position() - c.messageOffset(), flag);
    }

    private static Msg decodeChaining(Cursor c, int t) {
        int code = c.u32("code");
        Msg.Location location = c.location("location");
        int trigController = c.u8("triggeringController");
        int trigLocation = c.u8("triggeringLocation");
        int trigSequence = c.u8("triggeringSequence");
        int description = c.u32("description");
        int chainCount = c.u8("chainCount");
        return new Msg.Chaining(t, c.messageOffset(), c.position() - c.messageOffset(),
                code, location, trigController, trigLocation, trigSequence, description, chainCount);
    }

    private static Msg decodeChainCount(Cursor c, int t) {
        int chainCount = c.u8("chainCount");
        int len = c.position() - c.messageOffset();
        return switch (t) {
            case MsgType.CHAINED -> new Msg.Chained(t, c.messageOffset(), len, chainCount);
            case MsgType.CHAIN_SOLVING -> new Msg.ChainSolving(t, c.messageOffset(), len, chainCount);
            case MsgType.CHAIN_SOLVED -> new Msg.ChainSolved(t, c.messageOffset(), len, chainCount);
            case MsgType.CHAIN_NEGATED -> new Msg.ChainNegated(t, c.messageOffset(), len, chainCount);
            case MsgType.CHAIN_DISABLED -> new Msg.ChainDisabled(t, c.messageOffset(), len, chainCount);
            default -> throw new IllegalStateException("decodeChainCount 不支持 " + MsgType.name(t));
        };
    }

    private static Msg decodeRandomSelected(Cursor c, int t) {
        int player = c.u8("player");
        int count = c.u8("count");
        Msg.Location[] locations = c.locationArray("locations", count);
        return new Msg.RandomSelected(t, c.messageOffset(), c.position() - c.messageOffset(),
                player, locations);
    }

    private static Msg decodeBecomeTarget(Cursor c, int t) {
        int count = c.u8("count");
        Msg.Location[] locations = c.locationArray("locations", count);
        return new Msg.BecomeTarget(t, c.messageOffset(), c.position() - c.messageOffset(), locations);
    }

    private static Msg decodeDraw(Cursor c, int t) {
        int player = c.u8("player");
        int count = c.u8("count");
        int[] codes = c.u32Array("codes", count);
        return new Msg.Draw(t, c.messageOffset(), c.position() - c.messageOffset(), player, codes);
    }

    /** {@code u8 player, u32 amount} 形态（DAMAGE / RECOVER / LPUPDATE / PAY_LPCOST 布局相同）。 */
    private static Msg decodePlayerAmount(Cursor c, int t) {
        int player = c.u8("player");
        int amount = c.u32("amount");
        int len = c.position() - c.messageOffset();
        return switch (t) {
            case MsgType.DAMAGE -> new Msg.Damage(t, c.messageOffset(), len, player, amount);
            case MsgType.RECOVER -> new Msg.Recover(t, c.messageOffset(), len, player, amount);
            case MsgType.LPUPDATE -> new Msg.LpUpdate(t, c.messageOffset(), len, player, amount);
            case MsgType.PAY_LPCOST -> new Msg.PayLpCost(t, c.messageOffset(), len, player, amount);
            default -> throw new IllegalStateException("decodePlayerAmount 不支持 " + MsgType.name(t));
        };
    }

    private static Msg decodeTwoLocations(Cursor c, int t) {
        Msg.Location first = c.location("first");
        Msg.Location second = c.location("second");
        int len = c.position() - c.messageOffset();
        return switch (t) {
            case MsgType.EQUIP -> new Msg.Equip(t, c.messageOffset(), len, first, second);
            case MsgType.CARD_TARGET -> new Msg.CardTarget(t, c.messageOffset(), len, first, second);
            case MsgType.CANCEL_TARGET -> new Msg.CancelTarget(t, c.messageOffset(), len, first, second);
            default -> throw new IllegalStateException("decodeTwoLocations 不支持 " + MsgType.name(t));
        };
    }

    private static Msg decodeCounter(Cursor c, int t) {
        int counterType = c.u16("counterType");
        int controller = c.u8("controller");
        int location = c.u8("location");
        int sequence = c.u8("sequence");
        int count = c.u16("count");
        int len = c.position() - c.messageOffset();
        return t == MsgType.ADD_COUNTER
                ? new Msg.AddCounter(t, c.messageOffset(), len, counterType, controller, location, sequence, count)
                : new Msg.RemoveCounter(t, c.messageOffset(), len, counterType, controller, location, sequence, count);
    }

    private static Msg decodeAttack(Cursor c, int t) {
        Msg.Location attacker = c.location("attacker");
        Msg.Location target = c.location("target");
        return new Msg.Attack(t, c.messageOffset(), c.position() - c.messageOffset(), attacker, target);
    }

    private static Msg decodeBattle(Cursor c, int t) {
        Msg.Location attacker = c.location("attacker");
        int aa = c.u32("attackerAtk");
        int ad = c.u32("attackerDef");
        int bd0 = c.u8("attackerDestroyed");
        Msg.Location target = c.location("target");
        int da = c.u32("targetAtk");
        int dd = c.u32("targetDef");
        int bd1 = c.u8("targetDestroyed");
        return new Msg.Battle(t, c.messageOffset(), c.position() - c.messageOffset(),
                attacker, aa, ad, bd0, target, da, dd, bd1);
    }

    private static Msg decodeMissedEffect(Cursor c, int t) {
        Msg.Location location = c.location("location");
        int code = c.u32("code");
        return new Msg.MissedEffect(t, c.messageOffset(), c.position() - c.messageOffset(),
                location, code);
    }

    private static Msg decodeToss(Cursor c, int t, boolean isCoin) {
        int player = c.u8("player");
        int count = c.u8("count");
        int[] results = new int[count];
        for (int i = 0; i < count; i++) results[i] = c.u8("result");
        int len = c.position() - c.messageOffset();
        return isCoin
                ? new Msg.TossCoin(t, c.messageOffset(), len, player, results)
                : new Msg.TossDice(t, c.messageOffset(), len, player, results);
    }

    private static Msg decodeHandRes(Cursor c, int t) {
        int result = c.u8("result");
        return new Msg.HandRes(t, c.messageOffset(), c.position() - c.messageOffset(), result);
    }

    private static Msg decodeAnnounceAvailable(Cursor c, int t, boolean isRace) {
        int player = c.u8("player");
        int count = c.u8("count");
        int available = c.u32("available");
        int len = c.position() - c.messageOffset();
        return isRace
                ? new Msg.AnnounceRace(t, c.messageOffset(), len, player, count, available)
                : new Msg.AnnounceAttrib(t, c.messageOffset(), len, player, count, available);
    }

    private static Msg decodeAnnounceOptions(Cursor c, int t, boolean isCard) {
        int player = c.u8("player");
        int count = c.u8("count");
        int[] options = c.u32Array("options", count);
        int len = c.position() - c.messageOffset();
        return isCard
                ? new Msg.AnnounceCard(t, c.messageOffset(), len, player, options)
                : new Msg.AnnounceNumber(t, c.messageOffset(), len, player, options);
    }

    private static Msg decodeCardHint(Cursor c, int t) {
        Msg.Location location = c.location("location");
        int hintType = c.u8("hintType");
        int value = c.u32("value");
        return new Msg.CardHint(t, c.messageOffset(), c.position() - c.messageOffset(),
                location, hintType, value);
    }

    private static Msg decodePlayerHint(Cursor c, int t) {
        int player = c.u8("player");
        int hintType = c.u8("hintType");
        int description = c.u32("description");
        return new Msg.PlayerHint(t, c.messageOffset(), c.position() - c.messageOffset(),
                player, hintType, description);
    }

    private static Msg decodeMatchKill(Cursor c, int t) {
        int code = c.u32("code");
        return new Msg.MatchKill(t, c.messageOffset(), c.position() - c.messageOffset(), code);
    }

    private static Msg decodeTagSwap(Cursor c, int t) {
        int player = c.u8("player");
        int mainCount = c.u8("mainCount");
        int extraCount = c.u8("extraCount");
        int extraPCount = c.u8("extraPCount");
        int handCount = c.u8("handCount");
        // ★ 这 4 字节是无条件写出的（不公开卡组顶时写 0）。
        int deckTopCode = c.u32("deckTopCode");
        int[] handCodes = c.u32Array("handCodes", handCount);
        int[] extraCodes = c.u32Array("extraCodes", extraCount);
        return new Msg.TagSwap(t, c.messageOffset(), c.position() - c.messageOffset(),
                player, mainCount, extraCount, extraPCount, deckTopCode, handCodes, extraCodes);
    }

    private static Msg decodeReloadField(Cursor c, int t) {
        int duelRule = c.u8("duelRule");
        Msg.ReloadPlayer p0 = reloadPlayer(c, 0);
        Msg.ReloadPlayer p1 = reloadPlayer(c, 1);
        int chainCount = c.u8("chainCount");
        Msg.ReloadChainEntry[] chain = new Msg.ReloadChainEntry[chainCount];
        for (int i = 0; i < chainCount; i++) {
            int code = c.u32("chain.code");
            Msg.Location location = c.location("chain.location");
            int trigController = c.u8("chain.triggeringController");
            int trigLocation = c.u8("chain.triggeringLocation");
            int trigSequence = c.u8("chain.triggeringSequence");
            int description = c.u32("chain.description");
            chain[i] = new Msg.ReloadChainEntry(code, location, trigController, trigLocation,
                    trigSequence, description);
        }
        return new Msg.ReloadField(t, c.messageOffset(), c.position() - c.messageOffset(),
                duelRule, p0, p1, chain);
    }

    /** 解析 {@code MSG_RELOAD_FIELD} 里一个玩家的区块：1 个 u32 lp + 7/8 个区域 + 6 个 u8 计数。 */
    private static Msg.ReloadPlayer reloadPlayer(Cursor c, int index) {
        int lp = c.u32("lp");
        // 怪兽区固定 7 格（field.cpp:68 list_mzone.resize(7)）。
        Msg.ReloadZone[] mzones = new Msg.ReloadZone[7];
        for (int i = 0; i < mzones.length; i++) {
            int present = c.u8("mzone.present");
            if (present != 0) {
                int position = c.u8("mzone.position");
                int overlay = c.u8("mzone.xyzMaterials");
                mzones[i] = new Msg.ReloadZone(present, position, overlay);
            } else {
                mzones[i] = new Msg.ReloadZone(0, 0, 0);
            }
        }
        // 魔陷区固定 8 格（field.cpp:69 list_szone.resize(8)）。
        Msg.ReloadZone[] szones = new Msg.ReloadZone[8];
        for (int i = 0; i < szones.length; i++) {
            int present = c.u8("szone.present");
            if (present != 0) {
                int position = c.u8("szone.position");
                szones[i] = new Msg.ReloadZone(present, position, 0);
            } else {
                szones[i] = new Msg.ReloadZone(0, 0, 0);
            }
        }
        int deck = c.u8("deckCount");
        int hand = c.u8("handCount");
        int grave = c.u8("graveCount");
        int removed = c.u8("removedCount");
        int extra = c.u8("extraCount");
        int extraP = c.u8("extraPCount");
        return new Msg.ReloadPlayer(lp, mzones, szones, deck, hand, grave, removed, extra, extraP);
    }

    /**
     * {@code MSG_AI_NAME} / {@code MSG_SHOW_HINT}：{@code u16 length, length 字节内容, u8 0}。
     * 内核保证 {@code length} 已排除结尾的 NUL，且结尾 NUL 一定写出，所以总长 = 3 + length。
     */
    private static Msg decodeText(Cursor c, int t, boolean isAiName) {
        int length = c.u16("length");
        String text = c.utf8("text", length);
        c.u8("nul");
        int len = c.position() - c.messageOffset();
        return isAiName
                ? new Msg.AiName(t, c.messageOffset(), len, text)
                : new Msg.ShowHint(t, c.messageOffset(), len, text);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 游标
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * 带边界检查的小端读取游标。
     *
     * <p>所有读取都先检查剩余字节数，不够就抛 {@link MsgCodecException} —— 这样
     * 「越界」永远不会退化成「读了一半然后返回一个错误的长度」。
     */
    private static final class Cursor {

        private final byte[] buf;
        private final int start;
        private final int type;
        private int p;

        Cursor(byte[] buf, int start) {
            this.buf = buf;
            this.start = start;
            this.p = start + 1; // 类型字节已消费
            this.type = buf[start] & 0xff;
        }

        /** 本条消息的起始偏移。 */
        int messageOffset() {
            return start;
        }

        /** 当前已读到的绝对偏移。 */
        int position() {
            return p;
        }

        private void need(int n, String field) {
            if (p + n > buf.length) {
                throw MsgCodecException.truncatedField(type, start, field, n, buf.length - p);
            }
        }

        int u8(String field) {
            need(1, field);
            return buf[p++] & 0xff;
        }

        int u16(String field) {
            need(2, field);
            int v = (buf[p] & 0xff) | ((buf[p + 1] & 0xff) << 8);
            p += 2;
            return v;
        }

        int u32(String field) {
            need(4, field);
            int v = (buf[p] & 0xff)
                    | ((buf[p + 1] & 0xff) << 8)
                    | ((buf[p + 2] & 0xff) << 16)
                    | ((buf[p + 3] & 0xff) << 24);
            p += 4;
            return v;
        }

        Msg.Location location(String field) {
            return new Msg.Location(u32(field));
        }

        int[] u32Array(String field, int count) {
            if (count < 0) {
                throw new MsgCodecException("数组数量为负：" + count, type, start);
            }
            int[] a = new int[count];
            for (int i = 0; i < count; i++) a[i] = u32(field);
            return a;
        }

        Msg.Location[] locationArray(String field, int count) {
            Msg.Location[] a = new Msg.Location[count];
            for (int i = 0; i < count; i++) a[i] = location(field);
            return a;
        }

        /**
         * 读取「{@code u8 count, count × (u32 code, u8 controller, u8 location, u8 sequence)}」。
         * 每项 7 字节。
         */
        Msg.CardEntry[] cardEntries(String field) {
            int count = u8(field + ".count");
            Msg.CardEntry[] a = new Msg.CardEntry[count];
            for (int i = 0; i < count; i++) {
                a[i] = new Msg.CardEntry(
                        u32(field + ".code"), u8(field + ".controller"),
                        u8(field + ".location"), u8(field + ".sequence"));
            }
            return a;
        }

        /**
         * 读取「{@code u8 count, count × (u32 code, u8 controller, u8 location, u8 sequence, u32 sumParam)}」。
         * 每项 11 字节。
         */
        Msg.SelectSumEntry[] sumEntries(String field) {
            int count = u8(field + ".count");
            Msg.SelectSumEntry[] a = new Msg.SelectSumEntry[count];
            for (int i = 0; i < count; i++) {
                a[i] = new Msg.SelectSumEntry(
                        u32(field + ".code"), u8(field + ".controller"),
                        u8(field + ".location"), u8(field + ".sequence"),
                        u32(field + ".sumParam"));
            }
            return a;
        }

        /** 读取 {@code n} 字节并按 UTF-8 解码（内核写的是 Lua 字符串的原始字节）。 */
        String utf8(String field, int n) {
            if (n < 0) {
                throw new MsgCodecException("字符串长度为负：" + n, type, start);
            }
            need(n, field);
            String s = new String(buf, p, n, StandardCharsets.UTF_8);
            p += n;
            return s;
        }
    }
}
