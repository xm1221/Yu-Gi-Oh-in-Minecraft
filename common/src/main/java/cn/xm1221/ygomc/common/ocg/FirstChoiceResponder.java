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

    /**
     * 宣言卡名时要翻的卡表。没有它就答不了「判据里不含卡号」的那种宣言（例如「宣言一只怪兽」），
     * 见 {@link DeclareCardName}。
     */
    private final DeclareCardName.CardTable cardTable;

    /** 不带卡表：只认判据里字面写出的卡号。单测与离线比对用这个。 */
    public FirstChoiceResponder() {
        this(DeclareCardName.CardTable.EMPTY);
    }

    /** @param cardTable 数据包卡表；{@code null} 等同 {@link DeclareCardName.CardTable#EMPTY} */
    public FirstChoiceResponder(DeclareCardName.CardTable cardTable) {
        this.cardTable = cardTable == null ? DeclareCardName.CardTable.EMPTY : cardTable;
    }

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
            case Msg.SelectDisfield m -> selectDisFields(m);
            case Msg.SelectCounter m -> selectCounters(m);
            case Msg.SortCard m -> sortCard(m);
            case Msg.AnnounceRace m -> Response.of(declareBits(m.count(), m.available()));
            case Msg.AnnounceAttrib m -> Response.of(declareBits(m.count(), m.available()));
            // 宣言卡名回的是【卡号】，宣言数字回的才是【下标】——两者字段形状一样、
            // 读法完全不同，见 DeclareCardName 的类注释。
            case Msg.AnnounceCard m -> Response.of(DeclareCardName.choose(m.options(), cardTable));
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
     * 「先怪兽区再魔陷区」找一个没被禁止的格子。
     *
     * <h2>{@code count == 0} 不是「不用放」</h2>
     * 内核自己读应答时用的是 {@code len = max(1, count)}（{@code playerop.cpp:451}），
     * 也就是<b>无论 {@code count} 是几都至少要读一个三元组</b>。而真正会发
     * {@code count == 0} 的那一个调用点（{@code operations.cpp:2448}，{@code field::sset}）
     * 把应答的<b>第 2 字节</b>当「放不放」、第 3 字节当格号：
     *
     * <pre>
     * operations.cpp:2451-2455
     *   if(returns.bvalue[1] == 0) return TRUE;          // ← 回 (0,0,0) 就等于「放弃盖放」
     *   target->to_field_param = returns.bvalue[2];      // ← 格号
     * </pre>
     *
     * <p>所以「{@code count == 0} 就回 {@code (0,0,0)}」虽然能通过校验（{@code playerop.cpp:452}
     * 对 {@code count==0 && i==0 && l==0} 开绿灯），却让效果<b>静默失效</b>：
     * 不报错、不复位，调用方原地再来一次，于是同一格询问被问几千次
     * （实测「偏好盖魔陷」场景下 15000 步里 9996 次，一局永远打不完）。
     * 校验放行的是「形状」，不是「语义」——这一处正是「合法但无进展」。
     *
     * <p>所以这里按 {@code max(1, count)} 取格子：{@code count == 0} 时也真的放一张。
     * 只有真的一格都没有时才回 {@code (0,0,0)}（那是唯一还能通过校验的值）。
     */
    private static Response selectPlaces(Msg.SelectPlace m) {
        int need = Math.max(1, m.count());
        byte[] resp = new byte[3 * need];
        // 下标与 Msg#zoneBit 同一套：自己怪兽区 0-6、自己魔陷区 8-15、
        // 对方怪兽区 16-22、对方魔陷区 24-31。
        boolean[] used = new boolean[32];
        for (int i = 0; i < need; i++) {
            int[] pick = firstFreePlace(m, used);
            if (pick == null) {
                if (m.count() <= 0) {
                    // 一格都没有，而且调用方没要求放：回 (0,0,0) 是唯一合法值。
                    return Response.of(new byte[]{0, 0, 0});
                }
                // 与 selectDisFields 同样的理由：没格子可放时必须明确失败，
                // 不能返回一个非法位置——确定性应答器 + 非法值 = RETRY 死循环。
                throw new IllegalStateException(String.format(
                        "SELECT_PLACE 要求放置 %d 张，但 flag=0x%08X 里已没有任何可用区域"
                                + "（player=%d）", m.count(), m.flag(), m.player()));
            }
            used[pick[3]] = true;
            resp[3 * i] = (byte) pick[0];
            resp[3 * i + 1] = (byte) pick[1];
            resp[3 * i + 2] = (byte) pick[2];
        }
        return Response.of(resp);
    }

    /**
     * 找一个可用的格子，返回 {@code [归属, 区域, 序号, 位下标]}；没有则 {@code null}。
     *
     * <h2>为什么也要看对方的格子</h2>
     * {@code MSG_SELECT_PLACE} 不一定是「把卡放到自己场上」：{@code flag} 的低 16 位是
     * 自己的场、高 16 位是对方的场（位序见 {@link Msg#zoneBit}），
     * {@code Duel.SelectField}（{@code libduel.cpp:3965-3982}）就按调用方给的两个
     * {@code location} 参数分别放开自己的与对方的区域。实测真的会遇到
     * <b>只有对方怪兽区可用</b>的询问（{@code flag=0xFFE0FFFF}：自己两区全禁、
     * 对方怪兽区 0-4 可用），只看自己的场就会把它误判成「无处可放」而抛异常。
     *
     * <p>顺序是「自己怪兽区 → 自己魔陷区 → 对方怪兽区 → 对方魔陷区」：
     * 把卡放到对方场上属于少数派，排在最后就不会改变原先那些询问的答案。
     *
     * <p>怪兽区扫 0..6（大师规则下 5、6 是额外怪兽区，内核的校验接受），
     * 魔陷区扫 0..7。
     */
    private static int[] firstFreePlace(Msg.SelectPlace m, boolean[] used) {
        for (int k = 0; k < 7; k++) {
            if (m.ownMonsterZoneUsable(k) && !used[k]) {
                return new int[]{m.player(), Msg.Location.MZONE, k, k};
            }
        }
        for (int k = 0; k < 8; k++) {
            if (m.ownSpellZoneUsable(k) && !used[8 + k]) {
                return new int[]{m.player(), Msg.Location.SZONE, k, 8 + k};
            }
        }
        for (int k = 0; k < 7; k++) {
            if (m.oppMonsterZoneUsable(k) && !used[16 + k]) {
                return new int[]{1 - m.player(), Msg.Location.MZONE, k, 16 + k};
            }
        }
        for (int k = 0; k < 8; k++) {
            if (m.oppSpellZoneUsable(k) && !used[24 + k]) {
                return new int[]{1 - m.player(), Msg.Location.SZONE, k, 24 + k};
            }
        }
        return null;
    }

    /**
     * {@code MSG_SELECT_DISFIELD}：挑 {@code count} 个区域禁用掉。
     *
     * <p>应答格式与 {@link #selectPlaces} 完全相同（{@code count} 组
     * {@code owner/location/sequence}），但<b>扫描范围必须是 0..4</b>，不能照抄
     * {@code selectPlaces} 的 0..6。原因在内核：{@code processor.cpp:4748} 与
     * {@code :4787} 把应答里的位置按 {@code & 0x1f} 累加成 {@code mzone_flag}
     * 再或进 {@code player.disabled_location}，只有 5 位有意义。选到 5、6 号区
     * （额外怪兽区）会污染那个掩码，而症状是「区域莫名其妙被禁」，很难往这里想。
     *
     * <p>「第一选择」的含义是<b>禁用最左边那一格</b>——这是所有合法选择里
     * 对局面影响最小的一种。等有真正的玩家界面时，这里会变成让玩家点格子。
     */
    private static Response selectDisFields(Msg.SelectDisfield m) {
        if (m.count() <= 0) {
            // 内核的校验对 (count == 0, i == 0, location == 0) 这一组开绿灯，
            // 所以「不选」就用一个全 0 的三元组表示，而不是空数组。
            return Response.of(new byte[]{0, 0, 0});
        }
        byte[] resp = new byte[3 * m.count()];
        boolean[] used = new boolean[16];
        for (int i = 0; i < m.count(); i++) {
            int location = Msg.Location.MZONE;
            int sequence = 0;
            boolean found = false;
            for (int k = 0; k < 5; k++) {
                if (m.ownMonsterZoneUsable(k) && !used[k]) {
                    sequence = k;
                    found = true;
                    break;
                }
            }
            if (!found) {
                location = Msg.Location.SZONE;
                for (int k = 0; k < 5; k++) {
                    if (m.ownSpellZoneUsable(k) && !used[8 + k]) {
                        sequence = k;
                        found = true;
                        break;
                    }
                }
            }
            if (!found) {
                // 没有可用区域时【必须明确失败】，不能返回一个非法区域。
                //
                // 理由不是洁癖：这个应答器是确定性的，同一个询问永远给出同一个答案。
                // 返回非法值 → 内核回 MSG_RETRY → 重问 → 再给同一个非法值……
                // 于是表现为「卡住」而不是「报错」，最后靠 OcgDuel 的重试风暴计数
                // 兜底，而那时已经看不出是哪条询问的问题了。
                // 这里直接抛，让失败点就停在原因上。
                throw new IllegalStateException(String.format(
                        "SELECT_DISFIELD 要求禁用 %d 个区域，但 flag=0x%08X 里已没有任何可用区域"
                                + "（player=%d）", m.count(), m.flag(), m.player()));
            }
            used[sequence + (location == Msg.Location.MZONE ? 0 : 8)] = true;
            resp[3 * i] = (byte) m.player();
            resp[3 * i + 1] = (byte) location;
            resp[3 * i + 2] = (byte) sequence;
        }
        return Response.of(resp);
    }

    /**
     * {@code MSG_SELECT_COUNTER}：把 {@code count} 个指示物分配到候选卡上。
     *
     * <h2>应答不是「选哪些卡」，而是「每张各拿几个」</h2>
     * 这是本类型最容易写错的地方：内核读的是 {@code returns.svalue[i]}
     * （{@code playerop.cpp:626-636}），也就是一个<b>与消息里候选卡一一对应的
     * u16 数组</b>，而不是下标列表。{@code svalue} 与 {@code bvalue} 是同一个
     * union（{@code field.h:162-167}），所以按小端写 u16 就对了。
     *
     * <p>内核的两条校验：每张卡拿走的量不得超过它自己有的，
     * 且总和<b>恰好</b>等于 {@code count}（不等就 {@code MSG_RETRY}）。
     * 从前往后贪心地取 {@code min(剩余, 这张有的)} 同时满足两条：
     * 内核在发消息前已经保证 {@code count <= 候选指示物总数}，
     * 所以贪心到最后剩余量一定归零。
     */
    private static Response selectCounters(Msg.SelectCounter m) {
        int n = m.candidateCount();
        int remaining = m.count();
        byte[] resp = new byte[2 * n];
        for (int i = 0; i < n; i++) {
            int take = Math.max(0, Math.min(remaining, m.cardCounter(i)));
            remaining -= take;
            resp[2 * i] = (byte) (take & 0xFF);
            resp[2 * i + 1] = (byte) ((take >>> 8) & 0xFF);
        }
        return Response.of(resp);
    }

    /**
     * {@code MSG_SORT_CARD}：把 {@code n} 张卡排个序（{@code Duel.SortDecktop} 等）。
     *
     * <h2>应答是「裸排列」，没有长度前缀</h2>
     * 内核直接按下标读 {@code returns.bvalue[0..n-1]}（{@code playerop.cpp:780-787}），
     * 要求每个值 {@code < n} 且互不重复，而<b>不会</b>先读一个计数。
     * 多数多选类消息是要带计数前缀的（见 {@code check_response}），
     * 这一条是例外；写错的话内核会把排列的第一个字节当成数量，
     * 于是要么 {@code MSG_RETRY} 死循环，要么把顺序搞乱而不报错。
     *
     * <p>{@code bvalue[0] == 0xff} 是内核给简单 AI 留的「跳过排序」暗号
     * （{@code playerop.cpp:757-760}），正常情况下不该由应答方主动发。
     * 这里回恒等排列，语义是「保持引擎给的顺序」——对
     * {@code Duel.SortDecktop} 这类「自己决定牌堆顶顺序」的效果，
     * 这是所有合法选择里信息量最小、也最不会破坏局面的一种。
     */
    private static Response sortCard(Msg.SortCard m) {
        int n = m.count();
        if (n <= 0) {
            return Response.of(new byte[0]);
        }
        byte[] resp = new byte[n];
        for (int i = 0; i < n; i++) {
            resp[i] = (byte) i;
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

    /**
     * 宣言种族/属性的位掩码应答。
     *
     * <h2>不是「取最低位」，而是「取最低的 {@code count} 位」</h2>
     * 内核 {@code announce_race}/{@code announce_attribute} 的校验是
     * {@code sel != count → MSG_RETRY}（{@code playerop.cpp:820} 与 {@code :859}），
     * 其中 {@code sel} 是应答掩码里落在 {@code available} 内的位数。
     * 所以 {@code count > 1}（脚本要求「宣言两个种族」之类）时，回一个位就会被拒。
     * 这两个字段长得和「单选」一样，只有 {@code count} 区分得出来——
     * 这正是容易被顺手写成 {@code Integer.lowestOneBit} 的地方。
     *
     * <p>{@code count == 0} 时内核的判据是 {@code sel == 0}，也就是必须回 0
     * （{@code playerop.cpp:799-802} 会把 {@code count} 夹到可用的位数，可用位为 0 时就是 0）。
     */
    private static int declareBits(int count, int available) {
        if (count <= 0) {
            return 0;
        }
        int mask = 0;
        int taken = 0;
        for (int bit = 1; bit != 0 && taken < count; bit <<= 1) {
            if ((available & bit) != 0) {
                mask |= bit;
                taken++;
            }
        }
        if (taken < count) {
            // available 里的位数不够 count：内核在 step 0 已经把 count 夹过了，
            // 走到这里说明消息里的 count 与 available 不自洽（协议理解有偏差）。
            throw new IllegalStateException(String.format(
                    "宣言类要求选 %d 位，但 available=0x%X 里只有 %d 位",
                    count, available, taken));
        }
        return mask;
    }

    /**
     * 猜拳的结果。
     *
     * <p>内核只接受 1/2/3，<b>0 是非法的</b>——这一点与大多数单选类不同
     * （那些的下标从 0 开始），很容易顺手写成 0 然后陷入 RETRY 死循环。
     */
    private static final int ROCK = 1;
}
