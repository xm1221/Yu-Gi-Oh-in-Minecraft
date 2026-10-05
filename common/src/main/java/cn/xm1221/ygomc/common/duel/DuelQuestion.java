package cn.xm1221.ygomc.common.duel;

import cn.xm1221.ygomc.common.ocg.DeclareCardName;
import cn.xm1221.ygomc.common.ocg.Responder;
import cn.xm1221.ygomc.common.ocg.SumSelect;
import cn.xm1221.ygomc.common.ocg.msg.Msg;
import cn.xm1221.ygomc.common.ocg.msg.MsgType;

import cn.xm1221.ygomc.common.data.DescText;
import java.util.ArrayList;
import java.util.List;

/**
 * 一次询问的可点击形式：把引擎的问题摊成「选项列表」，玩家点完再拼回应答。
 *
 * <h2>为什么要有这一层</h2>
 * {@code FirstChoiceResponder} 直接「选第一个合法项」，不需要知道一共有哪些选项。
 * 而界面必须把<b>全部</b>选项摆出来，所以这一层是界面特有的需求，不是把
 * {@code FirstChoiceResponder} 抄一遍。
 *
 * <h2>应答编码集中在这一个文件里</h2>
 * 引擎对每种询问的应答形状都不一样，而且有几种是反直觉的（见各类的注释）：
 * 单选类回整数值、多选类回 {@code [数量, 下标…]}、选址类回每项 3 字节、
 * 指示物类回「每张卡各拿几个」的 u16 数组、排序类回<b>裸排列</b>。
 * 把这些分散到各个界面里，等于让每个界面各自去记一遍这些坑；
 * 集中在这里，{@link #response} 是唯一的出口。
 *
 * <h2>不依赖数据包</h2>
 * 选项里只带卡号，不带卡名——卡名由界面自己解析（界面本来就有卡图与卡名）。
 * 这样这个类可以在没有数据包的情况下被离线验证，而卡名的取法在界面层只有一处。
 *
 * <h2>单选类的下标语义各不相同，这是最容易错的地方</h2>
 * <ul>
 *   <li>行动类（{@code SELECT_IDLECMD/BATTLECMD}）回 {@code (子下标 << 16) | 类型}，
 *       <b>类型在低 16 位</b>；</li>
 *   <li>宣言类（{@code ANNOUNCE_RACE/ATTRIB}）回<b>位掩码</b>而不是下标；</li>
 *   <li>{@code ANNOUNCE_CARD} 回<b>卡号</b>、{@code ANNOUNCE_NUMBER} 回<b>下标</b>——
 *       两者的消息字段形状一模一样，读法却相反，见各自的注释；</li>
 *   <li>猜拳回 1/2/3，<b>0 非法</b>（大多数单选类下标从 0 开始，所以很容易顺手写错）；</li>
 *   <li>是/否回 0/1，且内核给简单 AI 的默认值是 1
 *       （{@code playerop.cpp:197-211}），即 <b>1 = 是</b>。</li>
 * </ul>
 */
public record DuelQuestion(int type, int player, Mode mode, String title,
                           List<Option> options, int min, int max, boolean cancelable,
                           int sumTarget, int[] forcedParams) {

    /**
     * 除 {@link Mode#SUM} 之外的询问都用的构造器。
     *
     * <p>求和类的两个附加字段只对它自己有含义，所以给一个缺省的重载，
     * 免得十八处构建点每处都写一遍 {@code 0, EMPTY}——
     * 那种噪声会让「这个字段到底谁在用」变得看不出来。
     */
    public DuelQuestion(int type, int player, Mode mode, String title,
                        List<Option> options, int min, int max, boolean cancelable) {
        this(type, player, mode, title, options, min, max, cancelable, 0, EMPTY_PARAMS);
    }

    /** 空的强制卡参数表（不可变，供上面的缺省构造器共用）。 */
    private static final int[] EMPTY_PARAMS = new int[0];

    /**
     * 逐字段比较，其中 {@code forcedParams} 比的是<b>内容</b>而不是引用。
     *
     * <p>record 自动生成的 {@code equals} 对数组用引用相等，于是「同一道求和题」
     * 每次解码出来都不相等。界面靠 {@code equals} 判断「还是不是同一题」
     * （见 {@code DuelScreen.update}），判断不出来就会在每次牌桌刷新时
     * 把玩家已经选好的卡清空——这正是刚修掉的那个毛病，不能在求和类上重新长回来。
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DuelQuestion q)) {
            return false;
        }
        return type == q.type && player == q.player && mode == q.mode
                && min == q.min && max == q.max && cancelable == q.cancelable
                && sumTarget == q.sumTarget
                && java.util.Objects.equals(title, q.title)
                && java.util.Objects.equals(options, q.options)
                && java.util.Arrays.equals(forcedParams, q.forcedParams);
    }

    @Override
    public int hashCode() {
        int h = java.util.Objects.hash(type, player, mode, title, options, min, max,
                cancelable, sumTarget);
        return 31 * h + java.util.Arrays.hashCode(forcedParams);
    }

    /** 询问的作答形状。 */
    public enum Mode {
        /** 点一个选项就作答。 */
        SINGLE,
        /** 选若干项后确认，应答 {@code [数量, 下标…]}。 */
        MULTI,
        /** 选若干格后确认，应答每格 3 字节 {@code [归属, 区域, 序号]}。 */
        PLACES,
        /** 指示物分配：每张候选卡各拿几个，应答是 u16 数组（没有数量前缀）。 */
        COUNTERS,
        /** 排序：应答是<b>裸排列</b>，没有数量前缀（本类型是例外）。 */
        SORT,
        /**
         * 求和选择：选若干张使合计值恰好等于 {@link DuelQuestion#sumTarget()}。
         *
         * <p>应答形状与 {@link #MULTI} 相似但<b>多一段占位</b>，见
         * {@link DuelQuestion#response} 里 SUM 分支的说明。
         */
        SUM,
        /** 本项目还没实现应答的询问。调用方应显式回退并记录，不要静默当作已处理。 */
        UNSUPPORTED
    }

    /**
     * 一个可选项。
     *
     * @param label      纯文本项的名字；卡牌项这里只是占位（形如 {@code #12345}），
     *                   界面应优先用 {@code cardCode} 去查卡名
     * @param cardCode   卡号；非卡选项为 0
     * @param index      该项在<b>引擎选项表</b>里的下标，多选类用它拼应答
     * @param value      单选类直接回填的整数值
     * @param controller 选址类：区域归属方
     * @param location   选址类：区域种类
     * @param sequence   选址类：区域序号
     */
    public record Option(String label, int cardCode, int index, int value,
                         int controller, int location, int sequence) {

        static Option ofIndex(String label, int cardCode, int index) {
            return new Option(label, cardCode, index, 0, 0, 0, 0);
        }

        /**
         * 带位置的卡牌项：界面据此把选项挂到牌桌上的<b>具体格子</b>，玩家点那张卡就选中它。
         *
         * <p>内核在 {@code MSG_SELECT_CARD}/{@code SELECT_TRIBUTE}/{@code SELECT_UNSELECT_CARD}
         * 里本来就带着 {@code controller/location/sequence}，原先只用 {@code ofIndex}
         * 取了卡号，位置被丢掉，界面只好把选项拍平成按钮——「点卡」因此做不到。
         */
        static Option ofIndexAt(String label, int cardCode, int index,
                                int controller, int location, int sequence) {
            return new Option(label, cardCode, index, 0, controller, location, sequence);
        }

        /** 这个选项是否指向牌桌上的某个具体位置。 */
        public boolean hasPlace() {
            return location != 0;
        }

        static Option ofValue(String label, int cardCode, int value) {
            return new Option(label, cardCode, 0, value, 0, 0, 0);
        }

        /**
         * 带位置的<b>行动</b>项：应答回填 {@code value}，同时指向牌桌上的那张卡。
         *
         * <p>「召唤/盖放/攻击/发动」这些是<b>行动</b>而不是菜单项：ygo 客户端的做法是
         * 玩家点那张卡，再由界面给出这张卡当前可做的行动。所以行动项必须带位置，
         * 否则界面只能把它们倒成一个二十来项的按钮列表——那正是「把行动做成菜单」。
         */
        static Option ofAction(String label, int cardCode, int value,
                               int controller, int location, int sequence) {
            return new Option(label, cardCode, 0, value, controller, location, sequence);
        }

        static Option ofZone(String label, int controller, int location, int sequence) {
            return new Option(label, 0, 0, 0, controller, location, sequence);
        }

        /** 「取消 / 不选」项。多选类里用 {@code index == -1} 表示。 */
        static Option cancel() {
            return new Option("取消", 0, -1, -1, 0, 0, 0);
        }

        public boolean isCancel() {
            return index < 0 && value < 0;
        }
    }

    // ── 行动类的类型号（应答低 16 位）────────────────────────────────────────
    // 取值与语义见 FirstChoiceResponder.idleCommand 的注释（内核 playerop.cpp:69-79）。
    private static final int IDLE_SUMMON = 0;
    private static final int IDLE_SPSUMMON = 1;
    private static final int IDLE_REPOSITION = 2;
    private static final int IDLE_MONSTER_SET = 3;
    private static final int IDLE_SPELL_SET = 4;
    private static final int IDLE_ACTIVATE_EFFECT = 5;
    private static final int IDLE_TO_BP = 6;
    private static final int IDLE_TO_EP = 7;

    private static final int BATTLE_ATTACK = 1;
    private static final int BATTLE_TO_M2 = 2;
    private static final int BATTLE_TO_EP = 3;

    /** 猜拳：内核只接受 1/2/3（{@code playerop.cpp}，0 会被 RETRY）。 */
    public static final int RPS_ROCK = 1;
    public static final int RPS_SCISSORS = 2;
    public static final int RPS_PAPER = 3;

    /**
     * 由引擎的询问消息建出可点击的问题。
     *
     * @return 问题；遇到本项目还不能作答的类型时返回 {@link Mode#UNSUPPORTED} 的问题，
     *         而不是抛异常——调用方需要能显式地回退到自动应答并记录下来
     */
    public static DuelQuestion of(Msg msg) {
        return switch (msg) {
            case Msg.SelectIdleCmd m -> idle(m);
            case Msg.SelectBattleCmd m -> battle(m);
            case Msg.SelectChain m -> chain(m);
            case Msg.SelectEffectYn m -> yesNo(msg.type(), m.player(), "是否发动效果？",
                    m.code(), m.description(), locationName(m.location()));
            case Msg.SelectYesNo m -> yesNo(msg.type(), m.player(), "请选择：", 0, m.description(), null);
            case Msg.SelectOption m -> option(m);
            case Msg.SelectCard m -> selectCard(m);
            case Msg.SelectTribute m -> tribute(m);
            case Msg.SelectUnselectCard m -> unselect(m);
            case Msg.SelectPlace m -> place(m.type(), m.player(), m.count(), false, m::isDisabled);
            case Msg.SelectDisfield m -> place(m.type(), m.player(), m.count(), true, m::isDisabled);
            case Msg.SelectPosition m -> position(m);
            case Msg.SelectCounter m -> counter(m);
            case Msg.SortCard m -> sort(m);
            case Msg.SelectSum m -> sum(m);
            case Msg.AnnounceRace m -> announceBits(msg.type(), m.player(), "宣言种族", m.available());
            case Msg.AnnounceAttrib m -> announceBits(msg.type(), m.player(), "宣言属性", m.available());
            case Msg.AnnounceCard m -> announceCard(m);
            case Msg.AnnounceNumber m -> announceNumber(m);
            case Msg.RockPaperScissors m -> rps(m);
            default -> unsupported(msg);
        };
    }

    // ── 各类 ──────────────────────────────────────────────────────────────

    /**
     * 这个询问是不是「让玩家挑一项行动」——主要阶段/战斗阶段的指令菜单，以及连锁询问。
     *
     * <p>ygo 靠卡上的 {@code cmdFlag} 区分两件事：这张卡<b>有得选</b>（点卡弹
     * {@code ShowMenu}，`event_handler.cpp:2260-2299`）与这张卡<b>可以被选</b>
     * （{@code selectable_cards}，点卡直接勾选）。我们这里按询问类型区分。
     *
     * <p>之所以要单独抽成静态方法而不是写在界面里：它是纯逻辑，
     * 写在 {@code DuelScreen} 里就只能靠肉眼保证，抽出来才能离线断言。
     * 本轮前面两个「不报错、改动完全没生效」的 bug 都栽在这类地方。
     */
    /**
     * 这个询问要不要<b>弹窗</b>问——「是否发动效果」这一类。
     *
     * <p>包含 {@code SELECT_EFFECTYN}（单独一张卡问要不要发动效果）、
     * {@code SELECT_CHAIN}（连锁时问发动哪个效果）、{@code SELECT_YESNO}（一般的是/否）。
     * 三者的共同点是「答案是几个固定选项、跟牌桌上的位置无关」——
     * ygo 对这类询问也是弹对话框，而不是让玩家去场地上点。
     *
     * <p>与 {@link #isAction} 的关系：{@code SELECT_CHAIN} 两边都算。
     * 弹窗优先——它根本不会走到「点卡出菜单」那条路上，所以这个重叠是无害的，
     * 但必须写清楚，否则以后有人会以为其中一个是死代码。
     */
    public static boolean isPopup(int type) {
        return type == MsgType.SELECT_EFFECTYN || type == MsgType.SELECT_CHAIN
                || type == MsgType.SELECT_YESNO;
    }
    public static boolean isAction(int type) {
        return type == MsgType.SELECT_IDLECMD || type == MsgType.SELECT_BATTLECMD
                || type == MsgType.SELECT_CHAIN;
    }
    private static DuelQuestion idle(Msg.SelectIdleCmd m) {
        List<Option> opts = new ArrayList<>();
        addActions(opts, "召唤", IDLE_SUMMON, m.summon());
        addActions(opts, "特殊召唤", IDLE_SPSUMMON, m.spSummon());
        addActions(opts, "变更表示", IDLE_REPOSITION, m.reposition());
        addActions(opts, "盖放怪兽", IDLE_MONSTER_SET, m.monsterSet());
        addActions(opts, "盖放魔陷", IDLE_SPELL_SET, m.spellSet());
        for (int i = 0; i < m.chains().length; i++) {
            Msg.SelectChainEntry e = m.chains()[i];
            // select_idle_command: low 16 bits 5 = activate effect; high bits index chains[].
            opts.add(Option.ofAction("发动效果", e.code(), (i << 16) | IDLE_ACTIVATE_EFFECT,
                    e.controller(), e.location(), e.sequence()));
        }
        if (m.toBp() != 0) {
            opts.add(Option.ofValue("进入战斗阶段", 0, IDLE_TO_BP));
        }
        if (m.toEp() != 0) {
            opts.add(Option.ofValue("进入结束阶段", 0, IDLE_TO_EP));
        }
        return new DuelQuestion(MsgType.SELECT_IDLECMD, m.player(), Mode.SINGLE,
                "选择行动", opts, 1, 1, false);
    }

    private static DuelQuestion battle(Msg.SelectBattleCmd m) {
        List<Option> opts = new ArrayList<>();
        for (int i = 0; i < m.attackable().length; i++) {
            Msg.AttackableEntry e = m.attackable()[i];
            // 攻击是「点我这只怪」而不是「从菜单里挑一只怪」
            opts.add(Option.ofAction("攻击", e.code(), (i << 16) | BATTLE_ATTACK,
                    e.controller(), e.location(), e.sequence()));
        }
        for (int i = 0; i < m.chains().length; i++) {
            Msg.SelectChainEntry e = m.chains()[i];
            opts.add(Option.ofAction("发动效果", e.code(), i << 16,
                    e.controller(), e.location(), e.sequence()));
        }
        if (m.toM2() != 0) {
            opts.add(Option.ofValue("进入主要阶段 2", 0, BATTLE_TO_M2));
        }
        if (m.toEp() != 0) {
            opts.add(Option.ofValue("进入结束阶段", 0, BATTLE_TO_EP));
        }
        return new DuelQuestion(MsgType.SELECT_BATTLECMD, m.player(), Mode.SINGLE,
                "战斗阶段：选择行动", opts, 1, 1, false);
    }

    /** 行动类里「同一种行动有若干张卡可选」的部分：子下标就是卡在该表里的下标。 */
    private static void addActions(List<Option> opts, String what, int kind, Msg.CardEntry[] cards) {
        for (int i = 0; i < cards.length; i++) {
            opts.add(Option.ofAction(what, cards[i].code(), (i << 16) | kind,
                    cards[i].controller(), cards[i].location(), cards[i].sequence()));
        }
    }

    /**
     * 连锁。
     *
     * <p>应答是<b>连锁项下标</b>，{@code -1} 表示不发动——但只有在
     * 没有强制连锁时才被接受（{@code hasForced()}）。强制连锁时给出「不发动」
     * 会拿到 {@code MSG_RETRY}，所以那一项干脆不摆出来，而不是摆出来让玩家踩。
     */
    private static DuelQuestion chain(Msg.SelectChain m) {
        List<Option> opts = new ArrayList<>();
        List<Msg.SelectChain.ChainEntry> entries = m.entryList();
        for (int i = 0; i < entries.size(); i++) {
            Msg.SelectChain.ChainEntry e = entries.get(i);
            String label = e.isForced() ? "发动（强制）" : "发动";
            // 带位置：对手发动效果后，玩家应当能【点自己的那张卡】来连锁它
            opts.add(new Option(label, e.code(), i, i,
                    e.location().controller(), e.location().location(), e.location().sequence()));
        }
        boolean forced = m.hasForced();
        if (!forced) {
            opts.add(Option.cancel());
        }
        return new DuelQuestion(MsgType.SELECT_CHAIN, m.player(), Mode.SINGLE,
                forced ? "必须发动一个效果" : "是否发动效果？", opts, 1, 1, !forced);
    }

    /**
     * 是/否。{@code 1 = 是}（内核给简单 AI 的默认值就是 1）。
     *
     * <p>标题优先用内核的 {@code description} 文本（见 {@link DescText}），
     * 解析不出来才用中文兜底。旧写法是「兜底 + （说明 N）」——把字符串表的编号
     * 直接印给玩家看，界面上就会出现「是否发动效果？（说明 122）」。
     */
    private static DuelQuestion yesNo(int type, int player, String fallback, int cardCode, int desc,
                                      String locationName) {
        String title = type == MsgType.SELECT_EFFECTYN
                ? DescText.effectyn(desc, cardCode, locationName, fallback)
                : DescText.yesNo(desc, fallback);
        List<Option> opts = new ArrayList<>();
        opts.add(Option.ofValue("是", cardCode, 1));
        opts.add(Option.ofValue("否", cardCode, 0));
        return new DuelQuestion(type, player, Mode.SINGLE, title, opts, 1, 1, false);
    }

    /** {@code Msg.Location} → 区域名，填系统串里的 {@code %ls} 用。 */
    /**
     * 换一个标题。
     *
     * <p>给 {@code DuelRoom} 用：内核的提示（选择提示、时点）是另外的消息，
     * 只有它知道该把它们并到哪一问上。抛掉的那些字段原样带过去。
     */
    public DuelQuestion withTitle(String newTitle) {
        return new DuelQuestion(type, player, mode, newTitle, options, min, max, cancelable, sumTarget, forcedParams);
    }
    /**
     * 这一问里落在牌堆（墓地/卡组/额外/除外）上的选项有几个。
     *
     * <p>牌堆在牌桌上只占一个格子，所以「从墓地选一张」会产生 N 个落在<b>同一矩形</b>
     * 的目标。这种情况必须走卡名列表（ygo 的 {@code ClientField::ShowSelectCard}，
     * client_field.cpp:431-527），不能走「点卡弹菜单」——菜单里会摆出一列
     * 一模一样的「发动效果」，玩家根本分不出点的是哪一张。
     */
    public int pileOptionCount() {
        int n = 0;
        for (Option o : options) {
            if (!o.isCancel() && o.hasPlace() && FieldCodes.isPileLocation(o.location())) {
                n++;
            }
        }
        return n;
    }

    /** @return 要不要用卡名列表，见 {@link #pileOptionCount()} */
    public boolean needsCardList() {
        return pileOptionCount() > 0;
    }
    private static String locationName(Msg.Location l) {
        return l == null ? null : DescText.location(l.location(), l.sequence());
    }

    private static DuelQuestion option(Msg.SelectOption m) {
        List<Option> opts = new ArrayList<>();
        for (int i = 0; i < m.count(); i++) {
            opts.add(Option.ofValue("选项 " + (i + 1), 0, i));
        }
        return new DuelQuestion(MsgType.SELECT_OPTION, m.player(), Mode.SINGLE,
                "请选择一项", opts, 1, 1, false);
    }

    private static DuelQuestion selectCard(Msg.SelectCard m) {
        List<Option> opts = cardOptions(m.codes(), packed(m.locations()));
        int count = m.count();
        boolean canCancel = m.cancelable() != 0;
        if (canCancel) {
            opts.add(Option.cancel());
        }
        return new DuelQuestion(MsgType.SELECT_CARD, m.player(), Mode.MULTI,
                "选择卡牌", opts, m.min(), m.max(), canCancel);
    }

    private static DuelQuestion tribute(Msg.SelectTribute m) {
        List<Option> opts = new ArrayList<>();
        int[] codes = m.codes();
        int[] controllers = m.controllers();
        int[] locations = m.locations();
        int[] sequences = m.sequences();
        for (int i = 0; i < codes.length; i++) {
            opts.add(new Option(zoneLabel(controllers[i], locations[i], sequences[i]),
                    codes[i], i, m.releaseParam(i), controllers[i], locations[i], sequences[i]));
        }
        boolean canCancel = m.cancelable() != 0;
        if (canCancel) {
            opts.add(Option.cancel());
        }
        return new DuelQuestion(MsgType.SELECT_TRIBUTE, m.player(), Mode.MULTI,
                "选择解放的怪兽", opts, m.min(), m.max(), canCancel);
    }

    /**
     * 「选一些、取消选一些」。
     *
     * <p><b>下标是在 select 与 unselect 两张表<b>合并</b>后的列表里取的</b>
     * （{@code libgroup.cpp:319-323}）：小于 {@code selectCount} 落到 select 表，
     * 否则落到 unselect 表。所以这里给 unselect 组的 {@code index} 必须加上
     * {@code selectCount} 偏移，否则玩家点「取消选这张」会作用到另一张卡上。
     *
     * <p>另外内核对此类型的校验写死为「恰好 1 个」（{@code check_response(total,1,1)}），
     * 消息里的 {@code min}/{@code max} 只是给人看的进度提示。
     */
    private static DuelQuestion unselect(Msg.SelectUnselectCard m) {
        List<Option> opts = new ArrayList<>();
        int selectCount = m.selectCount();
        int[] selCodes = m.selectCodes();
        Msg.Location[] selLoc = m.selectLocations();
        for (int i = 0; i < selectCount; i++) {
            opts.add(Option.ofIndexAt(selLoc[i].toString(), selCodes[i], i,
                    selLoc[i].controller(), selLoc[i].location(), selLoc[i].sequence()));
        }
        int[] unselCodes = m.unselectCodes();
        Msg.Location[] unselLoc = m.unselectLocations();
        for (int i = 0; i < unselCodes.length; i++) {
            opts.add(Option.ofIndexAt(unselLoc[i].toString(), unselCodes[i], selectCount + i,
                    unselLoc[i].controller(), unselLoc[i].location(), unselLoc[i].sequence()));
        }
        boolean canCancel = m.cancelable() != 0;
        if (canCancel) {
            opts.add(Option.cancel());
        }
        return new DuelQuestion(MsgType.SELECT_UNSELECT_CARD, m.player(), Mode.MULTI,
                "选择要选中的卡（其余为取消选中）", opts, 1, 1, canCancel);
    }

    /**
     * 选址与禁用区域。
     *
     * <p>消息里<b>不给出可选列表</b>，只给禁止位掩码，所以这里自己按
     * 「先怪兽区再魔陷区」把可选格子摊出来。<b>两者的扫描范围不同</b>：
     * 放置是 0..6（含额外怪兽区），禁用必须只到 0..4——内核
     * （{@code processor.cpp:4748/4787}）把应答里的位置按 {@code & 0x1f} 累加成
     * {@code mzone_flag}，只有 5 位有意义，选到第 5、6 格会污染那个掩码，
     * 症状是「区域莫名其妙被禁」，很难往这里想。
     */
    private static DuelQuestion place(int type, int player, int count, boolean disable,
                                      ZoneDisabled disabled) {
        int monsterZones = disable ? 5 : 7;
        // 魔陷区的上限也【跟着变】：放置是 8 格，禁用只有 5 格。
        // 曾经把这里写成「两种都 5」，结果是放置时漏掉魔陷区 6–8 号格——
        // 一旦玩家自己的前几格都不可用，两边就会选到不同格子，且都合法。
        int spellZones = disable ? 5 : 8;
        List<Option> opts = new ArrayList<>();
        // 自己那一侧排在前面。这既符合界面上的直觉（先看自己的场），
        // 也让 defaultChoice() 与贪心策略「只考虑自己的区域」的选择一致。
        for (int k = 0; k < 2; k++) {
            int owner = k == 0 ? player : 1 - player;
            for (int seq = 0; seq < monsterZones; seq++) {
                if (!disabled.test(owner, Msg.Location.MZONE, seq)) {
                    opts.add(Option.ofZone(zoneLabel(owner, Msg.Location.MZONE, seq),
                            owner, Msg.Location.MZONE, seq));
                }
            }
            for (int seq = 0; seq < spellZones; seq++) {
                if (!disabled.test(owner, Msg.Location.SZONE, seq)) {
                    opts.add(Option.ofZone(zoneLabel(owner, Msg.Location.SZONE, seq),
                            owner, Msg.Location.SZONE, seq));
                }
            }
        }
        // ── count == 0 不是「一格都不选」──
        //
        // 这是把「盖放魔陷点不动」照出来的那一处。盖放时内核用 **count = 0**
        // 发这条询问（`operations.cpp:2448` 的 add_process 最后一个参数就是 0），
        // 然后照 `returns.bvalue[1]` 判结果：
        //     case 1: if(returns.bvalue[1] == 0) return TRUE;   // 当作没选位置，静默取消
        //             target->to_field_param = returns.bvalue[2];
        // （`operations.cpp:2451-2454`）
        // 而 bvalue[1] 是 **LOCATION**，`[0,0,0]` 正好让它等于 0。
        //
        // 所以 count==0 时若按「需要选 0 格」建题，界面就不会要求玩家点格子，
        // 应答落到 `[0,0,0]`，内核收下、不报 RETRY、也**什么都不做**——
        // 表现为「点了没反应、可以一直点」，实测能空转 998 次询问不收局。
        // 它恰恰是【必须选一格】的意思。
        //
        // 只有 SelectDisField（禁用区域）在 count==0 时才是真的「不用选」。
        int need = count > 0 ? count : (type == MsgType.SELECT_PLACE ? 1 : 0);
        return new DuelQuestion(type, player, Mode.PLACES,
                disable ? "选择要禁用的区域" : "选择放置的位置",
                opts, need, need, need == 0);
    }

    /**
     * 一格是否不可用。
     *
     * <p>{@code SelectPlace} 与 {@code SelectDisfield} 的字段布局完全相同但是两个不同的
     * record，所以只能这样把「同一件事」抽出来，而不是共用一个消息类型。
     */
    @FunctionalInterface
    private interface ZoneDisabled {
        boolean test(int owner, int location, int sequence);
    }

    /**
     * 求和选择（{@code SELECT_SUM}）：选若干张，使合计值<b>恰好</b>等于目标值。
     *
     * <p>内核出处 {@code playerop.cpp:650-717 select_with_sum_limit}。
     * 每张候选卡带一个 {@code sum_param}，拆开来是两个可选值
     * （{@code get_sum_params}，{@code field.cpp:2926}），所以「这张卡的值」
     * 本身可能有两种取法——界面只能把两个都写出来，让玩家自己看。
     *
     * <h2>为什么以前玩家看不到这条询问</h2>
     * 它的应答形状既不是单选值、也不是普通多选，塞不进原先的模型，
     * 于是被归到 {@link Mode#UNSUPPORTED} 由自动求解器代答。
     * 结果是「引擎要你送 6 张卡去墓地，界面却自己替你送了」。
     *
     * <h2>计数口径</h2>
     * {@code min}/{@code max} 是<b>可选张数</b>（不含强制卡），
     * 强制卡另计在 {@link #forcedParams()} 里。应答里的总数要把强制卡算进去，
     * 见 {@link #response} 的 SUM 分支。
     */
    private static DuelQuestion sum(Msg.SelectSum m) {
        if (m.unlimited()) {
            // flag != 0 = 内核的 max == 0 分支，校验规则是「落在区间内」而不是「恰好等于」，
            // 还没逐行核实。不能让玩家去答一条我还不确定对错的问题。
            return unsupported(m);
        }
        List<Option> opts = new ArrayList<>();
        for (int i = 0; i < m.selectableCount(); i++) {
            Msg.SelectSumEntry e = m.selectable()[i];
            int[] v = SumSelect.params(e.sumParam());
            String label = v[1] > 0 ? ("合计 " + v[0] + " 或 " + v[1]) : ("合计 " + v[0]);
            opts.add(new Option(label, e.pureCode(), i, e.sumParam(),
                    e.controller(), e.location(), e.sequence()));
        }
        int mcount = m.mustCount();
        int[] forced = new int[mcount];
        for (int i = 0; i < mcount; i++) {
            forced[i] = m.mustSelect()[i].sumParam();
        }
        String title = "选择合计值恰好为 " + m.acc() + " 的卡"
                + (mcount > 0 ? "（另有 " + mcount + " 张已被强制计入）" : "");
        return new DuelQuestion(MsgType.SELECT_SUM, m.player(), Mode.SUM, title,
                opts, m.min(), m.max(), false, m.acc(), forced);
    }

    private static DuelQuestion position(Msg.SelectPosition m) {
        List<Option> opts = new ArrayList<>();
        addPosition(opts, m, Msg.SelectPosition.FACEUP_ATTACK, "攻击表示（正面）");
        addPosition(opts, m, Msg.SelectPosition.FACEUP_DEFENSE, "守备表示（正面）");
        addPosition(opts, m, Msg.SelectPosition.FACEDOWN_DEFENSE, "守备表示（背面）");
        addPosition(opts, m, Msg.SelectPosition.FACEDOWN_ATTACK, "攻击表示（背面）");
        return new DuelQuestion(MsgType.SELECT_POSITION, m.player(), Mode.SINGLE,
                "选择表示形式", opts, 1, 1, false);
    }

    private static void addPosition(List<Option> opts, Msg.SelectPosition m, int bit, String label) {
        if (m.allows(bit)) {
            opts.add(Option.ofValue(label, m.code(), bit));
        }
    }

    /**
     * 指示物分配。
     *
     * <p>应答<b>不是「选哪些卡」而是「每张卡各拿几个」</b>：内核读
     * {@code returns.svalue[i]}，即与候选卡一一对应的 u16 数组
     * （{@code playerop.cpp:626-636}），且总和必须<b>恰好</b>等于 {@code count}。
     * 所以选项里带的 {@code value} 是「这张卡最多能拿几个」，
     * 由界面（或 {@link #defaultChoice}）决定实际分配。
     */
    private static DuelQuestion counter(Msg.SelectCounter m) {
        List<Option> opts = new ArrayList<>();
        int n = m.candidateCount();
        for (int i = 0; i < n; i++) {
            int max = m.cardCounter(i);
            opts.add(new Option("可移除 " + max + " 个", m.codes()[i], i, max,
                    m.controllers()[i], m.locations()[i], m.sequences()[i]));
        }
        return new DuelQuestion(MsgType.SELECT_COUNTER, m.player(), Mode.COUNTERS,
                "分配 " + m.count() + " 个指示物", opts, m.count(), m.count(), false);
    }

    private static DuelQuestion sort(Msg.SortCard m) {
        int n = m.count();
        List<Option> opts = cardOptions(codes(m), locations(m));
        return new DuelQuestion(MsgType.SORT_CARD, m.player(), Mode.SORT,
                "调整顺序", opts, n, n, false);
    }

    private static int[] codes(Msg.SortCard m) {
        int[] out = new int[m.count()];
        for (int i = 0; i < out.length; i++) {
            out[i] = m.card(i).code();
        }
        return out;
    }

    /** {@code CardEntry.location} 是打包过的 int，与 {@code SelectCard} 的 {@code Location[]} 不同形。 */
    private static int[] locations(Msg.SortCard m) {
        int[] out = new int[m.count()];
        for (int i = 0; i < out.length; i++) {
            out[i] = m.card(i).location();
        }
        return out;
    }

    private static DuelQuestion announceBits(int type, int player, String title, int available) {
        List<Option> opts = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            int bit = 1 << i;
            if ((available & bit) != 0) {
                // 宣言类回的是位掩码本身，不是下标。
                opts.add(Option.ofValue("位 0x" + Integer.toHexString(bit), 0, bit));
            }
        }
        return new DuelQuestion(type, player, Mode.SINGLE, title, opts, 1, 1, false);
    }

    /**
     * {@code MSG_ANNOUNCE_CARD}：宣言卡名。
     *
     * <h2>应答是<b>卡号</b></h2>
     * 内核把 {@code ivalue[0]} 当卡号送进 {@code read_card}，查不到就 {@code MSG_RETRY}
     * （{@code playerop.cpp:1014-1026}）。而消息里的 {@code options} 是判据<b>表达式</b>
     * （里面混着 {@code 0x40000100} 这类 opcode），所以能当答案的只有其中以
     * {@code OPCODE_ISCODE} 立即数形式出现的那些卡号，提取规则见
     * {@link DeclareCardName#candidates}。
     *
     * <p><b>这里曾经是错的，而且是「静默」的那种错</b>：原实现用
     * {@code Option.ofIndex(...)} 建选项，而 {@code ofIndex} 不设 {@code value}
     * （它把值放在 {@code cardCode} 里），于是 {@link #response} 无论玩家点哪个都回
     * {@code ivalue=0}——{@code read_card(0)} 直接是空卡，必然 {@code MSG_RETRY}，
     * 重发同一个 0 就是一个跑满步数上限也不报错的死循环。
     *
     * <p>判据里一个卡号都没写时（例如「宣言一只怪兽」：{@code [1, OPCODE_ISTYPE]}），
     * 合法答案要翻整个卡表才找得到，而问题模型手里没有卡表。这种询问标成
     * {@link Mode#UNSUPPORTED}，由 {@code PlayerResponder} 回退给带卡表的
     * {@link FirstChoiceResponder}；在这里硬凑一个值只会变成几千条 RETRY。
     */
    private static DuelQuestion announceCard(Msg.AnnounceCard m) {
        int[] codes = DeclareCardName.candidates(m.options());
        if (codes.length == 0) {
            return unsupported(m);
        }
        List<Option> opts = new ArrayList<>(codes.length);
        for (int i = 0; i < codes.length; i++) {
            // cardCode 与 value 都是这个卡号：前者让界面去查卡名/卡图，后者就是应答内容。
            opts.add(Option.ofValue("#" + codes[i], codes[i], codes[i]));
        }
        return new DuelQuestion(MsgType.ANNOUNCE_CARD, m.player(), Mode.SINGLE,
                "宣言卡名", opts, 1, 1, false);
    }

    /**
     * {@code MSG_ANNOUNCE_NUMBER}：宣言数字。
     *
     * <h2>应答是<b>下标</b>，不是 option 的值</h2>
     * 内核校验 {@code 0 <= ret < select_options.size()}，命中后取
     * {@code select_options[ret]}（{@code playerop.cpp:1046-1054}）。这与同族的
     * {@code ANNOUNCE_CARD} 正好相反，两者字段形状却一模一样——所以这里必须写清楚：
     * 回的是 {@code i}，不是 {@code options[i]}。数字恰好是 {@code 0..n-1} 时
     * 两种写法看不出差别，换个脚本（例如「宣言 3 或 5」）就会选错或越界成 RETRY。
     */
    private static DuelQuestion announceNumber(Msg.AnnounceNumber m) {
        if (m.count() == 0) {
            return unsupported(m);
        }
        List<Option> opts = new ArrayList<>(m.count());
        for (int i = 0; i < m.count(); i++) {
            // cardCode 留 0：这是数字不是卡，别让界面去查卡图。
            opts.add(Option.ofValue("#" + m.options()[i], 0, i));
        }
        return new DuelQuestion(MsgType.ANNOUNCE_NUMBER, m.player(), Mode.SINGLE,
                "宣言数字", opts, 1, 1, false);
    }

    private static DuelQuestion rps(Msg.RockPaperScissors m) {
        List<Option> opts = new ArrayList<>();
        opts.add(Option.ofValue("石头", 0, RPS_ROCK));
        opts.add(Option.ofValue("剪刀", 0, RPS_SCISSORS));
        opts.add(Option.ofValue("布", 0, RPS_PAPER));
        return new DuelQuestion(MsgType.ROCK_PAPER_SCISSORS, m.player(), Mode.SINGLE,
                "猜拳", opts, 1, 1, false);
    }

    /**
     * 本项目还没实现应答的询问。
     *
     * <h2>SELECT_SUM 的应答语义（已从内核核实，尚未实现）</h2>
     * 出处 {@code playerop.cpp:654-725 select_with_sum_limit}。它是「按合计值选卡」：
     * 每张候选卡带一个 {@code sum_param}，必须选出若干张，使某个取值之和恰好等于
     * {@code acc}。要点有四条，每条都能单独把实现对：
     *
     * <ol>
     *   <li><b>消息里那个 {@code flag} 不是标志位</b>，而是 {@code max == 0} 的<b>取反</b>编码
     *       （{@code if(max) write8(0) else write8(1)}）。它决定应答走两条完全不同的
     *       分支，所以「flag=0 表示普通情况」这种读法只是碰巧对。</li>
     *   <li><b>应答形状是普通多选</b> {@code [数量, 下标…]}，而且下标只索引
     *       {@code select_cards}——<b>不含</b> {@code must_select_cards}。强制选的卡
     *       在对局里已被算进去，不出现在应答里，但数量校验是
     *       {@code [min + must数, max + must数]}，所以「照 min 个回」会偏少。</li>
     *   <li>下标不许重复。</li>
     *   <li>最后用 {@code select_sum_check1} 做<b>子集和</b>校验：每张选中的卡可以取
     *       {@code o1} 或 {@code o2} 两个值之一（{@code get_sum_params} 拆出来），
     *       必须存在一种取法使总和<b>恰好</b>等于 {@code acc}。</li>
     * </ol>
     *
     * <p><b>为什么不能照第一项</b>：这条是确定性的，答错只会拿到 {@code MSG_RETRY}
     * 然后被重问，于是同一个错答案反复提交——表现为「卡住」而不是「报错」。
     * 例如 {@code acc=6} 而候选的和是 {@code [1,1,1,1,1,1,…]} 时，
     * 必须回 6 个下标而不是 1 个。
     *
     * <p>实测这条询问<b>真的会出现</b>（一次自检对局里出现过 1 次，
     * 消息形如 {@code acc=6 min=1 max=22 must=[] 候选=[7 张 sum=6, 15 张 sum=1]}）。
     * 上面那个例子恰好可以只选 1 张，所以它是这条询问里最容易被误判成
     * 「照第一项就行」的一种；换个局面就会卡住。
     */
    private static DuelQuestion unsupported(Msg msg) {
        return new DuelQuestion(msg.type(), -1, Mode.UNSUPPORTED,
                "本项目还不能作答的询问：" + MsgType.name(msg.type()), List.of(), 0, 0, false);
    }

    // ── 应答 ──────────────────────────────────────────────────────────────

    /**
     * 由玩家选中的选项下标构造应答。
     *
     * @param chosen {@link #options} 里的下标；单选类只用第一个
     * @throws IllegalStateException 选项数与 {@link Mode} 的要求不符时。
     *         宁可在这里明确失败，也不要拼出一个形状不对的字节串——
     *         引擎对形状错的应答只会回 {@code MSG_RETRY}，然后表现为「卡住」。
     */
    /**
     * 排序类：按<b>点击顺序</b>作答。
     *
     * <p>应答不是「点击顺序」而是「每张原候选卡排到第几位」——内核收下的是一个
     * <b>排列</b>：{@code sort_list[i]} 是原候选 {@code i} 的最终次序
     * （ygopro 客户端 {@code event_handler.cpp:777-778} 发的就是它）。
     * 界面上玩家点出来的是一串卡的先后，两者正好互为逆排列，
     * 少转这一步的症状是「排好的顺序和显示的不一样」，而且只在 3 张以上时看得出来。
     *
     * @param clickOrder 按点击先后排列的选项下标
     */
    public Responder.Response sortResponse(int[] clickOrder) {
        require(mode == Mode.SORT, "不是排序类询问");
        require(clickOrder.length == options.size(),
                "排序要有 " + options.size() + " 项，实得 " + clickOrder.length);
        int[] ranks = new int[clickOrder.length];
        boolean[] used = new boolean[clickOrder.length];
        for (int rank = 0; rank < clickOrder.length; rank++) {
            int idx = clickOrder[rank];
            require(idx >= 0 && idx < ranks.length && !used[idx], "排序下标无效或重复");
            used[idx] = true;
            ranks[idx] = rank;
        }
        return response(ranks);
    }

    public Responder.Response counterResponse(int[] amounts) {
        require(mode == Mode.COUNTERS && amounts.length == options.size(), "指示物数组长度不符");
        byte[] result = new byte[amounts.length * 2];
        long total = 0;
        for (int i = 0; i < amounts.length; i++) {
            int n = amounts[i];
            require(n >= 0 && n <= options.get(i).value() && n <= 65535, "指示物数量越界");
            total += n;
            result[2 * i] = (byte) n;
            result[2 * i + 1] = (byte) (n >>> 8);
        }
        require(total == min, "指示物总量须为 " + min);
        return Responder.Response.of(result);
    }

    public boolean canSubmit(int... chosen) {
        try { response(chosen); return true; }
        catch (RuntimeException e) { return false; }
    }

    public Responder.Response response(int... chosen) {
        return switch (mode) {
            case SINGLE -> {
                require(chosen.length == 1, "单选类需要恰好 1 个选项");
                yield Responder.Response.of(options.get(chosen[0]).value());
            }
            case MULTI -> {
                // 取消：内核只接受 -1（且仅在可取消时），并【不是】一个空列表。
                if (chosen.length == 1 && options.get(chosen[0]).isCancel()) {
                    yield Responder.Response.of(-1);
                }
                java.util.Set<Integer> unique = new java.util.HashSet<>();
                int weight = 0;
                for (int c : chosen) {
                    require(c >= 0 && c < options.size() && !options.get(c).isCancel()
                            && unique.add(c), "选卡下标无效或重复");
                    weight += type == MsgType.SELECT_TRIBUTE ? options.get(c).value() : 1;
                }
                require(weight >= min && chosen.length <= max, "选卡数量或解放值不符合要求");
                byte[] resp = new byte[1 + chosen.length];
                resp[0] = (byte) chosen.length;
                for (int i = 0; i < chosen.length; i++) {
                    int idx = options.get(chosen[i]).index();
                    require(idx >= 0 && idx <= 0xFF, "下标越界：" + idx);
                    resp[i + 1] = (byte) idx;
                }
                yield Responder.Response.of(resp);
            }
            case PLACES -> {
                if (chosen.length == 0 && (min == 0 || options.isEmpty())) {
                    // 「不选」用全 0 三元组表示，而不是空数组：内核的校验对
                    // (count==0, i==0, location==0) 这一组开绿灯（playerop.cpp:461-467），
                    // 而盖放类询问又靠 bvalue[1]==0 判「没选位置」（operations.cpp:2452）。
                    // options 为空时也只能这样回——否则 require 会直接抛，把整局打断。
                    yield Responder.Response.of(new byte[]{0, 0, 0});
                }
                require(chosen.length == min, "要选 " + min + " 个格子，实得 " + chosen.length);
                byte[] resp = new byte[3 * chosen.length];
                for (int i = 0; i < chosen.length; i++) {
                    Option o = options.get(chosen[i]);
                    resp[3 * i] = (byte) o.controller();
                    resp[3 * i + 1] = (byte) o.location();
                    resp[3 * i + 2] = (byte) o.sequence();
                }
                yield Responder.Response.of(resp);
            }
            case SUM -> {
                java.util.Set<Integer> unique = new java.util.HashSet<>();
                int[] all = new int[forcedParams.length + chosen.length];
                System.arraycopy(forcedParams, 0, all, 0, forcedParams.length);
                for (int i = 0; i < chosen.length; i++) {
                    int c = chosen[i];
                    require(c >= 0 && c < options.size() && unique.add(c),
                            "选卡下标无效或重复");
                    all[forcedParams.length + i] = options.get(c).value();
                }
                // 计数口径：内核比的是 bvalue[0] 与 [min+mcount, max+mcount]（playerop.cpp:697），
                // 而 min/max 是【可选张数】，所以玩家选几张就比几张。
                require(chosen.length >= min && chosen.length <= max,
                        "要选 " + min + "~" + max + " 张，实得 " + chosen.length);
                // 合计值必须是「恰好等于」，不是「不超过」——用内核那个校验函数判定。
                require(SumSelect.check(all, sumTarget), "合计值凑不出 " + sumTarget);
                // 形状：总数含强制卡，随后 mcount 个占位字节（内核不读），再是候选下标。
                byte[] resp = new byte[1 + forcedParams.length + chosen.length];
                resp[0] = (byte) (forcedParams.length + chosen.length);
                for (int i = 0; i < chosen.length; i++) {
                    int idx = options.get(chosen[i]).index();
                    require(idx >= 0 && idx <= 0xFF, "下标越界：" + idx);
                    resp[1 + forcedParams.length + i] = (byte) idx;
                }
                yield Responder.Response.of(resp);
            }
            case COUNTERS -> {
                // 形状是 u16[候选卡数]，不是下标列表，也没有数量前缀。
                byte[] resp = new byte[2 * options.size()];
                int remaining = min;
                for (int i = 0; i < options.size(); i++) {
                    int take = Math.max(0, Math.min(remaining, options.get(i).value()));
                    remaining -= take;
                    resp[2 * i] = (byte) (take & 0xFF);
                    resp[2 * i + 1] = (byte) ((take >>> 8) & 0xFF);
                }
                require(remaining == 0, "指示物没分完，还剩 " + remaining);
                yield Responder.Response.of(resp);
            }
            case SORT -> {
                // 裸排列：没有数量前缀（本类型是内核的例外），且值必须是 0..n-1 的一个排列。
                byte[] resp = new byte[chosen.length];
                for (int i = 0; i < chosen.length; i++) {
                    resp[i] = (byte) chosen[i];
                }
                yield Responder.Response.of(resp);
            }
            case UNSUPPORTED -> throw new IllegalStateException(
                    "这条询问还没有实现应答策略：" + MsgType.name(type));
        };
    }

    /**
     * 「照贪心策略作答」，用于离线验证与自动对局。
     *
     * <p><b>行动类不是取选项表第一项</b>，而是照 {@code FirstChoiceResponder} 的优先序：
     * {@code SELECT_IDLECMD} 是「召唤 → 发动效果 → 战斗 → 结束」，
     * {@code SELECT_BATTLECMD} 是「攻击 → 主要阶段 2 → 结束」。
     *
     * <p>这样刻意的对齐是为了让验证有意义：{@link #response} 与贪心应答的逐字节比对，
     * 只有在两边取向一致时，不一致才能作为「编码写错了」的信号；
     * 否则策略差异（两者都合法）会淹没真正的协议错误。
     *
     * <p>它<b>不是</b>给界面用的默认值——界面摆出全部选项，由玩家点。
     */
    public int[] defaultChoice() {
        return switch (mode) {
            case SINGLE -> new int[]{singleDefault()};
            case MULTI -> {
                // 「取消」不是一个可选的卡，不能算进可用数量——否则它会被当成
                // 第 N 个可选对象发给引擎，selection 里就多了一个不存在的东西。
                int available = 0;
                while (available < options.size() && !options.get(available).isCancel()) {
                    available++;
                }
                if (available == 0) {
                    // 一张都不能选。只有摆出了取消项时才有合法答案。
                    require(!options.isEmpty() && options.get(options.size() - 1).isCancel(),
                            "没有任何可选项，也没有取消项");
                    yield new int[]{options.size() - 1};
                }
                int need = Math.max(1, Math.min(min, available));
                int[] out = new int[need];
                for (int i = 0; i < need; i++) {
                    out[i] = i;
                }
                yield out;
            }
            case PLACES -> {
                int[] out = new int[Math.min(min, options.size())];
                for (int i = 0; i < out.length; i++) {
                    out[i] = i;
                }
                yield out;
            }
            case SUM -> {
                // 求和类没有「照第一项」这一说：随便选一张几乎必然凑不出目标值，
                // 只会拿到 MSG_RETRY 然后被重问，表现为「卡住」。
                // 所以这里调用【和自动求解器同一个】搜索函数——
                // 两边共用一个 pickIndices，构造上就不可能出现分歧。
                int[] cand = new int[options.size()];
                for (int i = 0; i < cand.length; i++) {
                    cand[i] = options.get(i).value();
                }
                int[] pick = SumSelect.pickIndices(forcedParams, cand, sumTarget, min, max);
                require(pick != null, "求和类找不到合计值恰好为 " + sumTarget + " 的组合");
                yield pick;
            }
            case COUNTERS -> {
                int[] out = new int[options.size()];
                for (int i = 0; i < out.length; i++) {
                    out[i] = i;
                }
                yield out;
            }
            case SORT -> {
                int[] out = new int[options.size()];
                for (int i = 0; i < out.length; i++) {
                    out[i] = i;
                }
                yield out;
            }
            case UNSUPPORTED -> new int[0];
        };
    }

    // ── 小工具 ────────────────────────────────────────────────────────────

    /** 单选类的默认取向；见 {@link #defaultChoice} 为什么不能一律取第一项。 */
    private int singleDefault() {
        if (type == MsgType.SELECT_IDLECMD) {
            return actionDefault(IDLE_SUMMON, IDLE_ACTIVATE_EFFECT, IDLE_TO_BP, IDLE_TO_EP);
        }
        if (type == MsgType.SELECT_BATTLECMD) {
            // 贪心在战斗阶段【不看】连锁（它的注释写明了只按攻击→M2→结束退让），
            // 这里也照着来，好让两边可比。玩家界面上连锁当然是要摆出来的。
            return actionDefault(BATTLE_ATTACK, BATTLE_TO_M2, BATTLE_TO_EP);
        }
        if (type == MsgType.SELECT_YESNO || type == MsgType.SELECT_EFFECTYN) {
            // 是/否必须【反查】而不是取第一项：界面上「是」在前更自然，
            // 而贪心一律回 0（否）。取第一项就等于把所有是/否都答成「是」——
            // 这不是小差别，它会让整局走向完全不同（实测步数 718→658）。
            return indexOfValue(0);
        }
        if (type == MsgType.SELECT_CHAIN) {
            // 同上，而且这一条是【靠录制流才定位到的】：贪心在非强制连锁时一律
            // 不发动（-1），而界面上「发动」在前更自然，取第一项等于把所有
            // 可选连锁都发动了。第一次离线比对（7422 条，来自 M1 的流）报全一致，
            // 因为那些流里 4650 次连锁恰好全是强制的；换成实时录制的这条流，
            // 9 次非强制连锁立刻把差异暴露出来。
            return cancelable ? indexOfValue(-1) : 0;
        }
        return 0;
    }

    /** 找出取值等于 {@code v} 的第一个选项；找不到就明确失败，不退回 0。 */
    private int indexOfValue(int v) {
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).value() == v) {
                return i;
            }
        }
        throw new IllegalStateException("选项表里没有取值为 " + v + " 的项：" + describe());
    }

    /** 在选项里找第一个「行动种类」为 {@code kinds} 中之一的项。 */
    private int actionDefault(int... kinds) {
        for (int kind : kinds) {
            for (int i = 0; i < options.size(); i++) {
                if ((options.get(i).value() & 0xFFFF) == kind) {
                    return i;
                }
            }
        }
        throw new IllegalStateException("行动类没有任何可选项：" + describe());
    }

    private static List<Option> cardOptions(int[] codes, int[] packedLocations) {
        List<Option> opts = new ArrayList<>(codes.length);
        for (int i = 0; i < codes.length; i++) {
            String label = i < packedLocations.length
                    ? zoneLabelOf(packedLocations[i])
                    : "#" + (codes[i] & 0x7fffffff);
            if (i < packedLocations.length) {
                Msg.Location l = Msg.Location.of(packedLocations[i]);
                opts.add(Option.ofIndexAt(label, codes[i], i,
                        l.controller(), l.location(), l.sequence()));
            } else {
                opts.add(Option.ofIndex(label, codes[i], i));
            }
        }
        return opts;
    }

    /** 位置字段在不同消息里形状不同：有的已经是 {@code Location}，有的是打包过的 int。 */
    private static String zoneLabelOf(int packedLocation) {
        Msg.Location l = Msg.Location.of(packedLocation);
        return zoneLabel(l.controller(), l.location(), l.sequence());
    }

    private static int[] packed(Msg.Location[] locations) {
        int[] out = new int[locations.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = locations[i].packed();
        }
        return out;
    }

    private static String zoneLabel(int controller, int location, int sequence) {
        String side = controller == 0 ? "己方" : "对方";
        String kind = location == Msg.Location.MZONE ? "怪兽区"
                : location == Msg.Location.SZONE ? "魔陷区"
                : location == Msg.Location.GRAVE ? "墓地"
                : location == Msg.Location.REMOVED ? "除外区"
                : location == Msg.Location.HAND ? "手牌"
                : location == Msg.Location.DECK ? "卡组"
                : location == Msg.Location.EXTRA ? "额外卡组"
                : "区域" + location;
        return side + kind + (sequence + 1);
    }



    private static void require(boolean ok, String why) {
        if (!ok) {
            throw new IllegalStateException("构造应答失败：" + why);
        }
    }

    /** 一行摘要，给日志用。 */
    public String describe() {
        return MsgType.name(type) + " p" + player + " " + mode + " 「" + title + "」 选项 "
                + options.size() + " 个" + (cancelable ? "（可取消）" : "");
    }
}
