package cn.xm1221.ygomc.ocg.msg;

/**
 * ocgcore 的消息类型号常量。
 *
 * <p>取值与名称一一对应内核 {@code common.h} 里的 {@code #define MSG_*}（去掉 {@code MSG_} 前缀）。
 * 本文件只列内核里<b>真正定义了</b>的那些常量：被注释掉的 {@code MSG_WAITING} / {@code MSG_START} /
 * {@code MSG_UPDATE_DATA} / {@code MSG_UPDATE_CARD} / {@code MSG_REQUEST_DECK} / {@code MSG_REFRESH_DECK} /
 * {@code MSG_CARD_SELECTED} / {@code MSG_UNEQUIP} / {@code MSG_BE_CHAIN_TARGET} /
 * {@code MSG_CREATE_RELATION} / {@code MSG_RELEASE_RELATION} 不在此列。
 *
 * <h2>类型号不是连续的</h2>
 * 内核按用途分段编号（1/5 是控制类，10-26 是询问类，30-42 是卡组/回合类，50-56 是场地类，
 * 60-76 是召唤/连锁类，81-97 是单卡动作类，100-120 是数值/战斗类，130-143 是随机/宣言类，
 * 160-180 是提示/扩展类）。<b>不要</b>用 {@code values().length} 之类的方式推算范围或做数组下标，
 * 必须按常量查表。
 *
 * <h2>哪些类型会真的出现在消息流里</h2>
 * 内核里定义了 82 个常量，但只有 72 个有 {@code write_buffer8(MSG_*)} 写入点。
 * 从不出现在消息流里的 10 个是 {@link #SELECT_PLACE} 之外的纯保留/待实现项，
 * 见 {@link MsgCodec} 的类注释。
 */
public final class MsgType {

    private MsgType() {}

    // ── 控制类 ────────────────────────────────────────────────────────────────
    /** 上一条应答被拒，宿主必须重发上一次应答（见 05-protocol-verified.md 5.5）。负载长度 0。 */
    public static final int RETRY = 1;
    /** 提示文本。{@code u8 hintType, u8 player, u32 description}。 */
    public static final int HINT = 2;
    /** 胜负判定通知（权威收局信号）。{@code u8 winner, u8 reason}。 */
    public static final int WIN = 5;

    // ── 询问类：让玩家做选择 ──────────────────────────────────────────────────
    public static final int SELECT_BATTLECMD = 10;
    public static final int SELECT_IDLECMD = 11;
    public static final int SELECT_EFFECTYN = 12;
    public static final int SELECT_YESNO = 13;
    public static final int SELECT_OPTION = 14;
    public static final int SELECT_CARD = 15;
    public static final int SELECT_CHAIN = 16;
    public static final int SELECT_PLACE = 18;
    public static final int SELECT_POSITION = 19;
    public static final int SELECT_TRIBUTE = 20;
    public static final int SELECT_COUNTER = 22;
    public static final int SELECT_SUM = 23;
    public static final int SELECT_DISFIELD = 24;
    public static final int SORT_CARD = 25;
    public static final int SELECT_UNSELECT_CARD = 26;

    // ── 卡组 / 手牌 / 回合 ────────────────────────────────────────────────────
    public static final int CONFIRM_DECKTOP = 30;
    public static final int CONFIRM_CARDS = 31;
    public static final int SHUFFLE_DECK = 32;
    public static final int SHUFFLE_HAND = 33;
    public static final int SWAP_GRAVE_DECK = 35;
    public static final int SHUFFLE_SET_CARD = 36;
    public static final int REVERSE_DECK = 37;
    public static final int DECK_TOP = 38;
    public static final int SHUFFLE_EXTRA = 39;
    public static final int NEW_TURN = 40;
    public static final int NEW_PHASE = 41;
    public static final int CONFIRM_EXTRATOP = 42;

    // ── 场地动作 ──────────────────────────────────────────────────────────────
    public static final int MOVE = 50;
    public static final int POS_CHANGE = 53;
    public static final int SET = 54;
    public static final int SWAP = 55;
    public static final int FIELD_DISABLED = 56;

    // ── 召唤 ──────────────────────────────────────────────────────────────────
    public static final int SUMMONING = 60;
    public static final int SUMMONED = 61;
    public static final int SPSUMMONING = 62;
    public static final int SPSUMMONED = 63;
    public static final int FLIPSUMMONING = 64;
    public static final int FLIPSUMMONED = 65;

    // ── 连锁 ──────────────────────────────────────────────────────────────────
    public static final int CHAINING = 70;
    public static final int CHAINED = 71;
    public static final int CHAIN_SOLVING = 72;
    public static final int CHAIN_SOLVED = 73;
    public static final int CHAIN_END = 74;
    public static final int CHAIN_NEGATED = 75;
    public static final int CHAIN_DISABLED = 76;

    // ── 单卡动作 ──────────────────────────────────────────────────────────────
    public static final int RANDOM_SELECTED = 81;
    public static final int BECOME_TARGET = 83;
    public static final int DRAW = 90;
    public static final int DAMAGE = 91;
    public static final int RECOVER = 92;
    public static final int EQUIP = 93;
    public static final int LPUPDATE = 94;
    public static final int CARD_TARGET = 96;
    public static final int CANCEL_TARGET = 97;

    // ── 数值 / 指示物 / 战斗 ──────────────────────────────────────────────────
    public static final int PAY_LPCOST = 100;
    public static final int ADD_COUNTER = 101;
    public static final int REMOVE_COUNTER = 102;
    public static final int ATTACK = 110;
    public static final int BATTLE = 111;
    public static final int ATTACK_DISABLED = 112;
    public static final int DAMAGE_STEP_START = 113;
    public static final int DAMAGE_STEP_END = 114;
    public static final int MISSED_EFFECT = 120;

    // ── 随机 / 宣言 ───────────────────────────────────────────────────────────
    public static final int TOSS_COIN = 130;
    public static final int TOSS_DICE = 131;
    public static final int ROCK_PAPER_SCISSORS = 132;
    public static final int HAND_RES = 133;
    public static final int ANNOUNCE_RACE = 140;
    public static final int ANNOUNCE_ATTRIB = 141;
    public static final int ANNOUNCE_CARD = 142;
    public static final int ANNOUNCE_NUMBER = 143;

    // ── 提示 / 扩展 ───────────────────────────────────────────────────────────
    public static final int CARD_HINT = 160;
    public static final int TAG_SWAP = 161;
    public static final int RELOAD_FIELD = 162;
    public static final int AI_NAME = 163;
    public static final int SHOW_HINT = 164;
    public static final int PLAYER_HINT = 165;
    public static final int MATCH_KILL = 170;
    /** 宿主自定义消息；内核<b>从不</b>写它，故无已知负载布局。 */
    public static final int CUSTOM_MSG = 180;

    /**
     * 返回人类可读的类型名（例如 {@code 50 -> "MOVE"}）；未知类型返回
     * {@code "UNKNOWN(<n>)"}，便于日志排查。
     */
    public static String name(int type) {
        return switch (type) {
            case RETRY -> "RETRY";
            case HINT -> "HINT";
            case WIN -> "WIN";
            case SELECT_BATTLECMD -> "SELECT_BATTLECMD";
            case SELECT_IDLECMD -> "SELECT_IDLECMD";
            case SELECT_EFFECTYN -> "SELECT_EFFECTYN";
            case SELECT_YESNO -> "SELECT_YESNO";
            case SELECT_OPTION -> "SELECT_OPTION";
            case SELECT_CARD -> "SELECT_CARD";
            case SELECT_CHAIN -> "SELECT_CHAIN";
            case SELECT_PLACE -> "SELECT_PLACE";
            case SELECT_POSITION -> "SELECT_POSITION";
            case SELECT_TRIBUTE -> "SELECT_TRIBUTE";
            case SELECT_COUNTER -> "SELECT_COUNTER";
            case SELECT_SUM -> "SELECT_SUM";
            case SELECT_DISFIELD -> "SELECT_DISFIELD";
            case SORT_CARD -> "SORT_CARD";
            case SELECT_UNSELECT_CARD -> "SELECT_UNSELECT_CARD";
            case CONFIRM_DECKTOP -> "CONFIRM_DECKTOP";
            case CONFIRM_CARDS -> "CONFIRM_CARDS";
            case SHUFFLE_DECK -> "SHUFFLE_DECK";
            case SHUFFLE_HAND -> "SHUFFLE_HAND";
            case SWAP_GRAVE_DECK -> "SWAP_GRAVE_DECK";
            case SHUFFLE_SET_CARD -> "SHUFFLE_SET_CARD";
            case REVERSE_DECK -> "REVERSE_DECK";
            case DECK_TOP -> "DECK_TOP";
            case SHUFFLE_EXTRA -> "SHUFFLE_EXTRA";
            case NEW_TURN -> "NEW_TURN";
            case NEW_PHASE -> "NEW_PHASE";
            case CONFIRM_EXTRATOP -> "CONFIRM_EXTRATOP";
            case MOVE -> "MOVE";
            case POS_CHANGE -> "POS_CHANGE";
            case SET -> "SET";
            case SWAP -> "SWAP";
            case FIELD_DISABLED -> "FIELD_DISABLED";
            case SUMMONING -> "SUMMONING";
            case SUMMONED -> "SUMMONED";
            case SPSUMMONING -> "SPSUMMONING";
            case SPSUMMONED -> "SPSUMMONED";
            case FLIPSUMMONING -> "FLIPSUMMONING";
            case FLIPSUMMONED -> "FLIPSUMMONED";
            case CHAINING -> "CHAINING";
            case CHAINED -> "CHAINED";
            case CHAIN_SOLVING -> "CHAIN_SOLVING";
            case CHAIN_SOLVED -> "CHAIN_SOLVED";
            case CHAIN_END -> "CHAIN_END";
            case CHAIN_NEGATED -> "CHAIN_NEGATED";
            case CHAIN_DISABLED -> "CHAIN_DISABLED";
            case RANDOM_SELECTED -> "RANDOM_SELECTED";
            case BECOME_TARGET -> "BECOME_TARGET";
            case DRAW -> "DRAW";
            case DAMAGE -> "DAMAGE";
            case RECOVER -> "RECOVER";
            case EQUIP -> "EQUIP";
            case LPUPDATE -> "LPUPDATE";
            case CARD_TARGET -> "CARD_TARGET";
            case CANCEL_TARGET -> "CANCEL_TARGET";
            case PAY_LPCOST -> "PAY_LPCOST";
            case ADD_COUNTER -> "ADD_COUNTER";
            case REMOVE_COUNTER -> "REMOVE_COUNTER";
            case ATTACK -> "ATTACK";
            case BATTLE -> "BATTLE";
            case ATTACK_DISABLED -> "ATTACK_DISABLED";
            case DAMAGE_STEP_START -> "DAMAGE_STEP_START";
            case DAMAGE_STEP_END -> "DAMAGE_STEP_END";
            case MISSED_EFFECT -> "MISSED_EFFECT";
            case TOSS_COIN -> "TOSS_COIN";
            case TOSS_DICE -> "TOSS_DICE";
            case ROCK_PAPER_SCISSORS -> "ROCK_PAPER_SCISSORS";
            case HAND_RES -> "HAND_RES";
            case ANNOUNCE_RACE -> "ANNOUNCE_RACE";
            case ANNOUNCE_ATTRIB -> "ANNOUNCE_ATTRIB";
            case ANNOUNCE_CARD -> "ANNOUNCE_CARD";
            case ANNOUNCE_NUMBER -> "ANNOUNCE_NUMBER";
            case CARD_HINT -> "CARD_HINT";
            case TAG_SWAP -> "TAG_SWAP";
            case RELOAD_FIELD -> "RELOAD_FIELD";
            case AI_NAME -> "AI_NAME";
            case SHOW_HINT -> "SHOW_HINT";
            case PLAYER_HINT -> "PLAYER_HINT";
            case MATCH_KILL -> "MATCH_KILL";
            case CUSTOM_MSG -> "CUSTOM_MSG";
            default -> "UNKNOWN(" + type + ")";
        };
    }
}
