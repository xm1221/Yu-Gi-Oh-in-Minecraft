package cn.xm1221.ygomc.common.duel;

import cn.xm1221.ygomc.common.ocg.Responder;
import cn.xm1221.ygomc.common.ocg.msg.Msg;
import cn.xm1221.ygomc.common.ocg.msg.MsgType;

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
 *   <li>{@code ANNOUNCE_CARD/NUMBER} 回<b>下标</b>；</li>
 *   <li>猜拳回 1/2/3，<b>0 非法</b>（大多数单选类下标从 0 开始，所以很容易顺手写错）；</li>
 *   <li>是/否回 0/1，且内核给简单 AI 的默认值是 1
 *       （{@code playerop.cpp:197-211}），即 <b>1 = 是</b>。</li>
 * </ul>
 */
public record DuelQuestion(int type, int player, Mode mode, String title,
                           List<Option> options, int min, int max, boolean cancelable) {

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

        static Option ofValue(String label, int cardCode, int value) {
            return new Option(label, cardCode, 0, value, 0, 0, 0);
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
                    m.code(), m.description());
            case Msg.SelectYesNo m -> yesNo(msg.type(), m.player(), "请选择：", 0, m.description());
            case Msg.SelectOption m -> option(m);
            case Msg.SelectCard m -> selectCard(m);
            case Msg.SelectTribute m -> tribute(m);
            case Msg.SelectUnselectCard m -> unselect(m);
            case Msg.SelectPlace m -> place(m.type(), m.player(), m.count(), false, m::isDisabled);
            case Msg.SelectDisfield m -> place(m.type(), m.player(), m.count(), true, m::isDisabled);
            case Msg.SelectPosition m -> position(m);
            case Msg.SelectCounter m -> counter(m);
            case Msg.SortCard m -> sort(m);
            case Msg.AnnounceRace m -> announceBits(msg.type(), m.player(), "宣言种族", m.available());
            case Msg.AnnounceAttrib m -> announceBits(msg.type(), m.player(), "宣言属性", m.available());
            case Msg.AnnounceCard m -> announceIndex(msg.type(), m.player(), "宣言卡名", m.options());
            case Msg.AnnounceNumber m -> announceIndex(msg.type(), m.player(), "宣言数字", m.options());
            case Msg.RockPaperScissors m -> rps(m);
            default -> unsupported(msg);
        };
    }

    // ── 各类 ──────────────────────────────────────────────────────────────

    private static DuelQuestion idle(Msg.SelectIdleCmd m) {
        List<Option> opts = new ArrayList<>();
        addActions(opts, "召唤", IDLE_SUMMON, m.summon());
        addActions(opts, "特殊召唤", IDLE_SPSUMMON, m.spSummon());
        addActions(opts, "变更表示", IDLE_REPOSITION, m.reposition());
        addActions(opts, "盖放怪兽", IDLE_MONSTER_SET, m.monsterSet());
        addActions(opts, "盖放魔陷", IDLE_SPELL_SET, m.spellSet());
        for (int i = 0; i < m.chains().length; i++) {
            Msg.SelectChainEntry e = m.chains()[i];
            opts.add(Option.ofValue("发动效果", e.code(), (i << 16) | IDLE_ACTIVATE_EFFECT));
        }
        if (m.toBp() != 0) {
            opts.add(Option.ofValue("进入战斗阶段", 0, IDLE_TO_BP));
        }
        if (m.toEp() != 0) {
            opts.add(Option.ofValue("进入结束阶段", 0, IDLE_TO_EP));
        }
        return new DuelQuestion(MsgType.SELECT_IDLECMD, m.player(), Mode.SINGLE,
                "主要阶段：选择行动", opts, 1, 1, false);
    }

    private static DuelQuestion battle(Msg.SelectBattleCmd m) {
        List<Option> opts = new ArrayList<>();
        for (int i = 0; i < m.attackable().length; i++) {
            Msg.AttackableEntry e = m.attackable()[i];
            opts.add(Option.ofValue("攻击", e.code(), (i << 16) | BATTLE_ATTACK));
        }
        for (int i = 0; i < m.chains().length; i++) {
            Msg.SelectChainEntry e = m.chains()[i];
            opts.add(Option.ofValue("发动效果", e.code(), (i << 16) | IDLE_ACTIVATE_EFFECT));
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
            opts.add(Option.ofValue(what, cards[i].code(), (i << 16) | kind));
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
            opts.add(Option.ofIndex(label, e.code(), i));
        }
        boolean forced = m.hasForced();
        if (!forced) {
            opts.add(Option.cancel());
        }
        return new DuelQuestion(MsgType.SELECT_CHAIN, m.player(), Mode.SINGLE,
                forced ? "必须发动一个效果" : "是否发动效果？", opts, 1, 1, !forced);
    }

    /** 是/否。{@code 1 = 是}（内核给简单 AI 的默认值就是 1）。 */
    private static DuelQuestion yesNo(int type, int player, String title, int cardCode, int desc) {
        List<Option> opts = new ArrayList<>();
        opts.add(Option.ofValue("是", cardCode, 1));
        opts.add(Option.ofValue("否", cardCode, 0));
        return new DuelQuestion(type, player, Mode.SINGLE, title + descSuffix(desc), opts, 1, 1, false);
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
            opts.add(Option.ofIndex(zoneLabel(controllers[i], locations[i], sequences[i]),
                    codes[i], i));
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
            opts.add(Option.ofIndex(selLoc[i].toString(), selCodes[i], i));
        }
        int[] unselCodes = m.unselectCodes();
        Msg.Location[] unselLoc = m.unselectLocations();
        for (int i = 0; i < unselCodes.length; i++) {
            opts.add(Option.ofIndex(unselLoc[i].toString(), unselCodes[i], selectCount + i));
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
            for (int seq = 0; seq < 5; seq++) {
                if (!disabled.test(owner, Msg.Location.SZONE, seq)) {
                    opts.add(Option.ofZone(zoneLabel(owner, Msg.Location.SZONE, seq),
                            owner, Msg.Location.SZONE, seq));
                }
            }
        }
        int need = Math.max(0, count);
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
            Option base = Option.ofIndex("可放 " + max + " 个", m.codes()[i], i);
            opts.add(new Option(base.label(), base.cardCode(), i, max, 0, 0, 0));
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

    private static DuelQuestion announceIndex(int type, int player, String title, int[] options) {
        List<Option> opts = new ArrayList<>();
        for (int i = 0; i < options.length; i++) {
            // 宣言卡名/数字回的是【下标】而不是值本身。
            opts.add(Option.ofIndex(cardOrNumber(options[i]), options[i], i));
        }
        return new DuelQuestion(type, player, Mode.SINGLE, title, opts, 1, 1, false);
    }

    private static DuelQuestion rps(Msg.RockPaperScissors m) {
        List<Option> opts = new ArrayList<>();
        opts.add(Option.ofValue("石头", 0, RPS_ROCK));
        opts.add(Option.ofValue("剪刀", 0, RPS_SCISSORS));
        opts.add(Option.ofValue("布", 0, RPS_PAPER));
        return new DuelQuestion(MsgType.ROCK_PAPER_SCISSORS, m.player(), Mode.SINGLE,
                "猜拳", opts, 1, 1, false);
    }

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
                if (min == 0 && chosen.length == 0) {
                    // 「不选」用全 0 三元组表示，而不是空数组：内核的校验对
                    // (count==0, i==0, location==0) 这一组开绿灯（playerop.cpp:452-474）。
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

    /** 单选类的默认取向；见 {@link #defaultChoice} 为什么行动类要特殊对待。 */
    private int singleDefault() {
        if (type == MsgType.SELECT_IDLECMD) {
            return actionDefault(IDLE_SUMMON, IDLE_ACTIVATE_EFFECT, IDLE_TO_BP, IDLE_TO_EP);
        }
        if (type == MsgType.SELECT_BATTLECMD) {
            // 贪心在战斗阶段【不看】连锁（它的注释写明了只按攻击→M2→结束退让），
            // 这里也照着来，好让两边可比。玩家界面上连锁当然是要摆出来的。
            return actionDefault(BATTLE_ATTACK, BATTLE_TO_M2, BATTLE_TO_EP);
        }
        return 0;
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
            opts.add(Option.ofIndex(label, codes[i], i));
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

    private static String cardOrNumber(int value) {
        // 卡名宣言的候选是卡号，数字宣言的候选是小整数；两者都无法在这里确定地分辨，
        // 所以给出一个中性文本，由界面按类型替换。
        return "#" + value;
    }

    /**
     * 描述文本的补充。
     *
     * <p>引擎的 {@code description} 是脚本 {@code str1..str16} 的<b>字符串表编号</b>，
     * 不是文本本身。本项目的数据包里还没有导出这张表，所以现在只能显示编号——
     * 这是一个已知缺口：玩家会看到「是否发动效果？(42)」而不是「是否发动「强欲之壶」？」。
     */
    private static String descSuffix(int description) {
        return description == 0 ? "" : "（说明 " + description + "）";
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
