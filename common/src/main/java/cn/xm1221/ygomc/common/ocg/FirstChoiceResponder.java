package cn.xm1221.ygomc.common.ocg;

import cn.xm1221.ygomc.common.ocg.msg.Msg;
import cn.xm1221.ygomc.common.ocg.msg.MsgType;

/**
 * 「永远选第一个合法项」的应答策略。
 *
 * <h2>它的用途不是「会玩」，而是「不会卡住」</h2>
 * M1 的目标是证明引擎能在 Minecraft 里被推着走完一整局。要做到这一点，
 * 不需要一个会打牌的 AI，只需要一个<b>永不给出非法应答</b>的策略：
 * 一旦回了非法值，引擎会发 {@code MSG_RETRY} 让宿主重发，重发同样的非法值
 * 就变成死循环——表现为「卡住」，而不是任何一条错误日志。
 *
 * <p>所以这里每个分支都优先读消息里带的「合法范围」，而不是写死常量：
 * 选址读禁止位掩码、表示形式读允许位、宣言读可选集合、进阶段先看该阶段是否允许。
 *
 * <h2>哪些分支是被真实对局触发过的</h2>
 * 用内置的 40 张相同卡只能碰到十来种询问。换成卡组目录里的<b>真实卡组</b>之后，
 * 一局就走到 33 种消息类型（{@code CHAINING}/{@code CHAIN_SOLVING}/{@code SPSUMMONING}/
 * {@code BECOME_TARGET}/{@code SHUFFLE_HAND}/{@code CARD_HINT}/{@code CONFIRM_CARDS} 等
 * 内置卡组从没触发过）。所以「自检用真实卡组」不只是好看——它是这套策略的覆盖面测试。
 *
 * <p>尚未被触发过的分支每个都在下面单独标注了。一旦某个分支第一次被触发，
 * 应当回到这里把标注去掉，并说明是哪一局触发的。
 */
public final class FirstChoiceResponder implements Responder {

    /** 主要阶段：进结束阶段。 */
    private static final int IDLE_TO_EP = 7;
    /** 主要阶段：进战斗阶段。 */
    private static final int IDLE_TO_BP = 6;
    /** 主要阶段：发动效果（连锁下标 0）。 */
    private static final int IDLE_ACTIVATE_EFFECT = 5;
    /** 主要阶段：通常召唤第一只可召唤的怪兽。 */
    private static final int IDLE_SUMMON = 0;

    /** 战斗阶段：用第一只可攻击的怪兽攻击（直接攻击也算）。 */
    private static final int BATTLE_ATTACK = 1;

    /** 战斗阶段：进结束阶段。 */
    private static final int BATTLE_TO_EP = 3;
    /** 战斗阶段：进主要阶段 2。 */
    private static final int BATTLE_TO_M2 = 2;

    /** 表示形式的优先顺序：能攻击就攻击姿态。 */
    private static final int[] POSITION_PREFERENCE = {
            Msg.SelectPosition.FACEUP_ATTACK,
            Msg.SelectPosition.FACEUP_DEFENSE,
            Msg.SelectPosition.FACEDOWN_DEFENSE,
            Msg.SelectPosition.FACEDOWN_ATTACK,
    };

    @Override
    public Response answer(Msg msg) {
        return switch (msg) {
            case Msg.SelectIdleCmd m -> Response.of(idleCommand(m));
            case Msg.SelectBattleCmd m -> Response.of(battleCommand(m));
            case Msg.SelectChain m -> Response.of(m.hasForced() ? 0 : -1);
            case Msg.SelectEffectYn m -> Response.of(0);
            case Msg.SelectYesNo m -> Response.of(0);
            case Msg.SelectOption m -> Response.of(0);
            case Msg.SelectPosition m -> Response.of(firstPosition(m));
            case Msg.SelectCard m -> selectCards(m);
            case Msg.SelectTribute m -> selectTributes(m);
            case Msg.SelectUnselectCard m -> selectUnselectCard(m);
            case Msg.SelectPlace m -> selectPlaces(m);
            case Msg.AnnounceRace m -> Response.of(lowestBit(m.available()));
            case Msg.AnnounceAttrib m -> Response.of(lowestBit(m.available()));
            // 宣言类回的是「下标」而不是值本身，所以 0 就是选项表里的第一个。
            case Msg.AnnounceCard m -> Response.of(0);
            case Msg.AnnounceNumber m -> Response.of(0);
            case Msg.RockPaperScissors m -> Response.of(ROCK);
            default -> throw new UnsupportedOperationException(
                    "还没有实现 " + MsgType.name(msg.type()) + " 的应答策略："
                            + "请先在真实流里确认它的合法取值范围，再补进 FirstChoiceResponder。"
                            + "消息内容=" + msg);
        };
    }

    // ── 各类型 ────────────────────────────────────────────────────────────

    /**
     * 主要阶段的行动选择。
     *
     * <p>应答编码是 {@code (子下标 << 16) | 类型}，<b>类型在低 16 位</b>：
     * 0..4 分别对应召唤/特召/变更表示/盖怪/盖魔陷（子下标是要用哪张卡），
     * 5 = 发动效果（子下标是连锁下标），6 = 进战斗阶段，7 = 进结束阶段。
     * 越界时内核写 {@code MSG_RETRY}，见 {@code playerop.cpp:69-79}。
     *
     * <p><b>为什么优先召唤而不是直接结束阶段</b>：结束阶段永远合法、最省事，
     * 但那样场上永远没有怪兽，战斗阶段永远无事可做，整局只能靠抽爆卡组收场——
     * 看起来「跑通了」，实际上战斗、伤害、表示形式变更这些路径一条都没走到。
     * 而「通常召唤」恰恰是类型 0、子下标 0，也就是字面意义上的第一个合法项。
     */
    private static int idleCommand(Msg.SelectIdleCmd m) {
        if (m.summonCount() > 0) {
            return (0 << 16) | IDLE_SUMMON;
        }
        if (m.chainCount() > 0) {
            return (0 << 16) | IDLE_ACTIVATE_EFFECT;
        }
        // 先打再结束：进了战斗阶段才有攻击可言。
        if (m.canGoBattlePhase()) {
            return (0 << 16) | IDLE_TO_BP;
        }
        if (m.canGoEndPhase()) {
            return (0 << 16) | IDLE_TO_EP;
        }
        // 到这一步说明引擎给了这一步但一个子项都不可选，只可能是协议理解有偏差。
        throw new UnsupportedOperationException("SELECT_IDLECMD 没有任何可选行动：" + m);
    }

    /**
     * 战斗阶段：有可攻击的怪兽就攻击（类型 1，子下标 0），否则依次退到主要阶段 2 和结束阶段。
     *
     * <p>攻击的合法性由内核保证：{@code playerop.cpp:74} 检查
     * {@code s < core.attackable_cards.size()}，所以只要 {@code attackableCount() > 0}，
     * 下标 0 一定有效。
     */
    private static int battleCommand(Msg.SelectBattleCmd m) {
        if (m.attackableCount() > 0) {
            return (0 << 16) | BATTLE_ATTACK;
        }
        if (m.canGoMain2()) {
            return (0 << 16) | BATTLE_TO_M2;
        }
        if (m.canGoEndPhase()) {
            return (0 << 16) | BATTLE_TO_EP;
        }
        throw new UnsupportedOperationException("SELECT_BATTLECMD 没有任何可选行动：" + m);
    }

    private static int firstPosition(Msg.SelectPosition m) {
        for (int pos : POSITION_PREFERENCE) {
            if (m.allows(pos)) {
                return pos;
            }
        }
        throw new UnsupportedOperationException("SELECT_POSITION 没有允许的表示形式：" + m);
    }

    /**
     * 选卡。应答形状 {@code [数量, 下标...]}，下标是 1 字节。
     *
     * <h2>为什么不「能取消就取消」</h2>
     * 这里踩过一次实打实的死循环，值得写下来：最初本方法在
     * {@code cancelable != 0} 时直接回 {@code -1}（取消），理由是「取消也是合法应答」。
     * 但<b>取消会把控制权退回同一个决策点</b>——战斗阶段问「攻击谁」，取消之后又回到
     * 战斗阶段，于是「发起攻击 → 取消 → 再发起 → 再取消」无限循环。
     * 实测跑满 20 万步、双方各 66653 次询问都没收局，而且<b>一条 RETRY 都没有</b>：
     * 两个应答各自都合法，引擎没有任何理由报错，所以只能表现为「卡住」。
     *
     * <p>所以规则改成：<b>只要有卡可选就一定选</b>，下限取 {@code max(min, 1)}；
     * 只有真的没得选（{@code count == 0}）才取消。对「可选但不必选」的询问来说，
     * 选一张同样是合法应答，而且能让对局真正往前走。
     */
    private static Response selectCards(Msg.SelectCard m) {
        if (m.count() == 0) {
            return Response.of(-1);
        }
        return Response.of(prefixIndices(m.min(), m.count()));
    }

    /** 解放（祭品）选择。与 {@link #selectCards} 同理：取消祭品 = 取消召唤 = 回到原决策点。 */
    private static Response selectTributes(Msg.SelectTribute m) {
        int count = m.codes().length;
        if (count == 0) {
            return Response.of(-1);
        }
        return Response.of(prefixIndices(m.min(), count));
    }

    /**
     * 「选一些、取消选一些」的询问（内核 {@code playerop.cpp:284-332}）。
     *
     * <h2>下标是两个列表合并后算的</h2>
     * 消息里带 select 与 unselect 两张表，而应答的下标在<b>合并列表</b>里取：
     * 小于 {@code selectCount} 落到 select 表，否则落到 unselect 表
     * （{@code libgroup.cpp:319-323} 就是这么分的）。所以下标 0 永远是 select 表的第一张。
     *
     * <h2>min/max 是给人看的，内核只认「恰好 1 个」</h2>
     * 消息里的 {@code min}/{@code max} 看起来像「要选几个」，但内核的校验是
     * {@code check_response(total, 1, 1)}——<b>写死的 1 到 1</b>。多回一个下标就会被
     * {@code MSG_RETRY} 打回。也就是说这两个字段只是给客户端做进度提示用的，
     * 真正的「选够几个」由 Lua 脚本自己循环调用若干次来实现。
     * 照 {@code min}/{@code max} 去回多个下标是个很难查的错。
     *
     * <p>{@code -1}（取消/结束）只在 {@code finishable}/{@code cancelable} 为真时被接受，
     * 否则同样是 {@code MSG_RETRY}。
     */
    private static Response selectUnselectCard(Msg.SelectUnselectCard m) {
        int total = m.selectCount() + m.unselectCount();
        if (total == 0) {
            // 走不到这里：内核在 step 0 发现两张表都空时会直接返回 TRUE，不发这条消息。
            // 留着是为了万一哪天内核改了，得到一个明确的结果而不是越界。
            return Response.of(-1);
        }
        return Response.of(prefixIndices(1, total));
    }

    /**
     * 选址。应答是每项 3 字节 {@code [归属, 区域, 序号]}。
     *
     * <p>消息里<b>不给出可选列表</b>，只给禁止位掩码，所以这里自己按
     * 「先怪兽区再魔陷区」找一个没被禁止的格子。{@code count == 0} 表示不选。
     */
    private static Response selectPlaces(Msg.SelectPlace m) {
        if (m.count() <= 0) {
            return Response.of(new byte[]{0, 0, 0});
        }
        byte[] resp = new byte[3 * m.count()];
        boolean[] used = new boolean[16];
        for (int i = 0; i < m.count(); i++) {
            int location = Msg.Location.MZONE;
            int sequence = 0;
            boolean found = false;
            for (int k = 0; k < 7; k++) {
                if (m.ownMonsterZoneUsable(k) && !used[k]) {
                    sequence = k;
                    found = true;
                    break;
                }
            }
            if (!found) {
                location = Msg.Location.SZONE;
                for (int k = 0; k < 8; k++) {
                    if (m.ownSpellZoneUsable(k) && !used[8 + k]) {
                        sequence = k;
                        break;
                    }
                }
            }
            used[sequence + (location == Msg.Location.MZONE ? 0 : 8)] = true;
            resp[3 * i] = (byte) m.player();
            resp[3 * i + 1] = (byte) location;
            resp[3 * i + 2] = (byte) sequence;
        }
        return Response.of(resp);
    }

    // ── 小工具 ────────────────────────────────────────────────────────────

    /**
     * 多选类应答的通用形状：{@code [数量, 下标0, 下标1, ...]}，下标是 1 字节。
     *
     * <p>选前 {@code need} 个，其中 {@code need = max(min, 1)}——
     * 回得比 {@code min} 少会被引擎拒绝，而回 0 个在「取消 = 回到原决策点」的场景下
     * 会变成死循环（见 {@link #selectCards}）。回得比 {@code max} 多同样会被拒绝。
     */
    private static byte[] prefixIndices(int min, int available) {
        int need = Math.max(1, Math.min(min, available));
        byte[] resp = new byte[1 + need];
        resp[0] = (byte) need;
        for (int i = 0; i < need; i++) {
            resp[i + 1] = (byte) i;
        }
        return resp;
    }

    /** 取掩码里最低的那个 1；掩码为 0 时退到 1（调用方保证不了的情况下至少给个非零值）。 */
    private static int lowestBit(int mask) {
        return mask == 0 ? 1 : Integer.lowestOneBit(mask);
    }

    /**
     * 猜拳的结果。
     *
     * <p>内核只接受 1/2/3，<b>0 是非法的</b>——这一点与大多数单选类不同
     * （那些的下标从 0 开始），很容易顺手写成 0 然后陷入 RETRY 死循环。
     */
    private static final int ROCK = 1;
}
