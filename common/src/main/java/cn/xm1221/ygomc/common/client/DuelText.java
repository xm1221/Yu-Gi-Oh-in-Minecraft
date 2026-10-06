package cn.xm1221.ygomc.common.client;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

/**
 * 决斗界面里所有由<b>模组自己</b>说的话。
 *
 * <h2>为什么要有这一层</h2>
 * 玩家原话：「提示信息直接来自 ygo 而和 gui 上的文本应该是 mod 自己的语言资源文件决定」。
 * 也就是说：内核给的文本（{@code strings.conf} 的标题、HINT、胜负原因）与玩家数据
 * （卡名/卡文）照旧用内核那份，而<b>界面自己组织的句子</b>——阶段名、按钮、行动名、
 * 进度行、各类提示——必须落在 {@code assets/ygomc/lang/*.json} 里，由我们自己的 key 取。
 *
 * <p>这一层就是把「key → 文本」收敛到一处：调用处只许用本类里的具名常量，
 * 任何地方再写一遍字符串 key，就等于给自己留了第二个真相。
 *
 * <h2>两条出口</h2>
 * <ul>
 *   <li>{@link #s(String, Object...)}：给要 {@code String} 的路径
 *       （{@code GuiGraphics.drawString(font, ...)}、宽度计算、{@code clip}）；</li>
 *   <li>{@link #c(String, Object...)}：给吃 {@code Component} 的路径
 *       （{@code Button.builder}、{@code Screen} 标题、聊天消息）——原版会照
 *       当前语言重新解析，也能跟着语言设置走。</li>
 * </ul>
 *
 * <h2>刻意<b>不</b>在这里的东西</h2>
 * 卡名、卡文、内核 {@code strings.conf} 的标题与 HINT、区域名里由内核给的那部分、
 * 玩家名、卡组名、{@code ？}、{@code ◆N}、{@code #卡号}、{@code EX}、
 * 内核消息类型名——这些是<b>参数或内核文本</b>，不是我们要翻译的句子。
 */
public final class DuelText {

    private DuelText() {
    }

    /** 取一段本地化文本（要 {@code String} 的路径）。 */
    public static String s(String key, Object... args) {
        return args.length == 0 ? I18n.get(key) : I18n.get(key, args);
    }

    /** 取一段本地化文本（要 {@code Component} 的路径）。 */
    public static Component c(String key, Object... args) {
        return Component.translatable(key, args);
    }

    // ── 界面标题 ──────────────────────────────────────────────────────────
    public static final String SCREEN_DUEL = "ygomc.duel.screen.duel";
    public static final String SCREEN_BROWSE = "ygomc.duel.screen.browse";

    // ── 常驻阶段条 ────────────────────────────────────────────────────────
    public static final String PHASE_DRAW = "ygomc.duel.phase.draw";
    public static final String PHASE_STANDBY = "ygomc.duel.phase.standby";
    public static final String PHASE_MAIN1 = "ygomc.duel.phase.main1";
    public static final String PHASE_BATTLE = "ygomc.duel.phase.battle";
    public static final String PHASE_MAIN2 = "ygomc.duel.phase.main2";
    public static final String PHASE_END = "ygomc.duel.phase.end";
    /** 阶段条六格，按内核阶段位（{@code 0x01/0x02/0x04/0x08/0x100/0x200}）的顺序。 */
    public static final String[] PHASES = {
            PHASE_DRAW, PHASE_STANDBY, PHASE_MAIN1, PHASE_BATTLE, PHASE_MAIN2, PHASE_END,
    };

    // ── 行动名 ────────────────────────────────────────────────────────────
    public static final String ACTION_SUMMON = "ygomc.duel.action.summon";
    public static final String ACTION_SP_SUMMON = "ygomc.duel.action.sp_summon";
    public static final String ACTION_REPOSITION = "ygomc.duel.action.reposition";
    public static final String ACTION_FLIP_SUMMON = "ygomc.duel.action.flip_summon";
    public static final String ACTION_TO_DEFENSE = "ygomc.duel.action.to_defense";
    public static final String ACTION_TO_ATTACK = "ygomc.duel.action.to_attack";
    public static final String ACTION_MONSTER_SET = "ygomc.duel.action.monster_set";
    public static final String ACTION_SPELL_SET = "ygomc.duel.action.spell_set";
    public static final String ACTION_ACTIVATE = "ygomc.duel.action.activate";
    public static final String ACTION_ACTIVATE_FORCED = "ygomc.duel.action.activate_forced";
    public static final String ACTION_ATTACK = "ygomc.duel.action.attack";
    public static final String ACTION_TO_BP = "ygomc.duel.action.to_bp";
    public static final String ACTION_TO_EP = "ygomc.duel.action.to_ep";
    public static final String ACTION_TO_M2 = "ygomc.duel.action.to_m2";
    public static final String ACTION_CANCEL = "ygomc.duel.action.cancel";
    public static final String ACTION_YES = "ygomc.duel.action.yes";
    public static final String ACTION_NO = "ygomc.duel.action.no";
    public static final String ACTION_RPS_ROCK = "ygomc.duel.action.rps_rock";
    public static final String ACTION_RPS_SCISSORS = "ygomc.duel.action.rps_scissors";
    public static final String ACTION_RPS_PAPER = "ygomc.duel.action.rps_paper";
    /** 效果选项只给序号（内核不说每一项是什么）：{@code %d} 是第几项。 */
    public static final String ACTION_OPTION_ITEM = "ygomc.duel.action.option_item";

    // ── 卡面上那个两字标记 ────────────────────────────────────────────────
    public static final String TAG_SUMMON = "ygomc.duel.tag.summon";
    public static final String TAG_SP_SUMMON = "ygomc.duel.tag.sp_summon";
    public static final String TAG_ACTIVATE = "ygomc.duel.tag.activate";
    public static final String TAG_ATTACK = "ygomc.duel.tag.attack";
    public static final String TAG_SET = "ygomc.duel.tag.set";
    public static final String TAG_FLIP = "ygomc.duel.tag.flip";
    public static final String TAG_TO_DEFENSE = "ygomc.duel.tag.to_defense";
    public static final String TAG_TO_ATTACK = "ygomc.duel.tag.to_attack";
    public static final String TAG_REPOSITION = "ygomc.duel.tag.reposition";

    // ── 按钮 ──────────────────────────────────────────────────────────────
    public static final String BUTTON_CONFIRM = "ygomc.duel.button.confirm";
    public static final String BUTTON_CANCEL = "ygomc.duel.button.cancel";

    // ── 卡片菜单（额外卡组那一份） ────────────────────────────────────────
    public static final String MENU_SP_SUMMON = "ygomc.duel.menu.sp_summon";
    public static final String MENU_VIEW_LIST = "ygomc.duel.menu.view_list";

    // ── 询问标题 ──────────────────────────────────────────────────────────
    /** 发动效果类问句：{@code %s} 是卡名（卡名是内核数据，只当参数）。 */
    public static final String TITLE_EFFECTYN = "ygomc.duel.title.effectyn";
    /** 同上，但拿不到卡名时用 —— 不要再拼出一个「null 的效果」。 */
    public static final String TITLE_EFFECTYN_GENERIC = "ygomc.duel.title.effectyn_generic";
    public static final String TITLE_YESNO = "ygomc.duel.title.yesno";
    public static final String TITLE_IDLE = "ygomc.duel.title.idle";
    public static final String TITLE_BATTLE = "ygomc.duel.title.battle";
    public static final String TITLE_CHAIN = "ygomc.duel.title.chain";
    public static final String TITLE_CHAIN_FORCED = "ygomc.duel.title.chain_forced";
    /**
     * 诱发效果的选择阶段（内核 {@code speCount == 0x7f}，ygopro 的 {@code select_trigger}）。
     *
     * <p>它和「空时点」必须分开：这一问摆的是<b>自己</b>触发的效果，
     * 时点略过在三种模式下都不许替玩家跳过它（见 {@code DuelScreenFlow.skipChain}）。
     */
    public static final String TITLE_CHAIN_TRIGGER = "ygomc.duel.title.chain_trigger";
    public static final String TITLE_SELECT_CARD = "ygomc.duel.title.select_card";
    public static final String TITLE_TRIBUTE = "ygomc.duel.title.tribute";
    public static final String TITLE_UNSELECT = "ygomc.duel.title.unselect";
    public static final String TITLE_PLACE = "ygomc.duel.title.place";
    public static final String TITLE_DISFIELD = "ygomc.duel.title.disfield";
    public static final String TITLE_POSITION = "ygomc.duel.title.position";
    public static final String TITLE_SUM = "ygomc.duel.title.sum";
    public static final String TITLE_COUNTER = "ygomc.duel.title.counter";
    public static final String TITLE_SORT = "ygomc.duel.title.sort";
    public static final String TITLE_ANNOUNCE_RACE = "ygomc.duel.title.announce_race";
    public static final String TITLE_ANNOUNCE_ATTRIB = "ygomc.duel.title.announce_attrib";
    public static final String TITLE_ANNOUNCE_CARD = "ygomc.duel.title.announce_card";
    public static final String TITLE_ANNOUNCE_NUMBER = "ygomc.duel.title.announce_number";
    public static final String TITLE_OPTION = "ygomc.duel.title.option";
    public static final String TITLE_RPS = "ygomc.duel.title.rps";
    public static final String TITLE_UNSUPPORTED = "ygomc.duel.title.unsupported";
    // ── 提示行 ────────────────────────────────────────────────────────────
    //
    // 每一条都要与【真实交互】对得上。这一族是玩家这次报的重点（「提示信息还很不完整，
    // 依旧有许多错误」），所以逐条写明它对应哪种交互：
    //   YESNO          是/否：确认＝同意、取消＝拒绝
    //   CHAIN_ASK      连锁第一段：先问要不要发动
    //   CHAIN_PICK     连锁第二段：进列表挑一个，点亮的是能发动的那些
    //   SORT           排序：点选的先后顺序就是答案，没有确认这一步
    //   SELECT_UNSELECT 点一下即提交，点中的是「这回要选的」，其余是取消选中
    //   COUNTERS       指示物：再点一次 +1，点满回到 0
    //   SUM            合计值：一张卡可能有两个可选值
    //   PLACING        选放置的格子
    //   DISFIELD       选要禁用的格子（与放置是两回事）
    //   POSITION       选这张卡的表示形式
    //   ANNOUNCE_*     宣言类，按钮上是卡名/种族的本名，不是「位 0x4」
    //   EXTRA_MENU     额外卡组有货：先点亮、点它再在菜单里挑
    //   UNSUPPORTED    本界面还答不了，别骗玩家去点
    //   CLICK          点一下即作答
    //   SELECT         多选：左键选、右下角确认
    public static final String HINT_YESNO = "ygomc.duel.hint.yesno";
    public static final String HINT_CHAIN_ASK = "ygomc.duel.hint.chain_ask";
    public static final String HINT_COUNTERS = "ygomc.duel.hint.counters";
    public static final String HINT_SUM = "ygomc.duel.hint.sum";
    public static final String HINT_POPUP = "ygomc.duel.hint.popup";
    public static final String HINT_SORT = "ygomc.duel.hint.sort";
    public static final String HINT_SELECT_UNSELECT = "ygomc.duel.hint.select_unselect";
    public static final String HINT_PLACING = "ygomc.duel.hint.placing";
    public static final String HINT_DISFIELD = "ygomc.duel.hint.disfield";
    public static final String HINT_POSITION = "ygomc.duel.hint.position";
    public static final String HINT_ANNOUNCE_RACE = "ygomc.duel.hint.announce_race";
    public static final String HINT_ANNOUNCE_ATTRIB = "ygomc.duel.hint.announce_attrib";
    public static final String HINT_ANNOUNCE_CARD = "ygomc.duel.hint.announce_card";
    public static final String HINT_ANNOUNCE_NUMBER = "ygomc.duel.hint.announce_number";
    public static final String HINT_CHAIN_PICK = "ygomc.duel.hint.chain_pick";
    public static final String HINT_EXTRA_MENU = "ygomc.duel.hint.extra_menu";
    public static final String HINT_UNSUPPORTED = "ygomc.duel.hint.unsupported";
    /** 有落点的选择类询问：点一下即作答。 */
    public static final String HINT_CLICK = "ygomc.duel.hint.click";
    /** 多选：左键选、右下角确认交出。 */
    public static final String HINT_SELECT = "ygomc.duel.hint.select";
    /** 多选且可取消：多一句「取消＝不选」。 */
    public static final String HINT_SELECT_CANCELABLE = "ygomc.duel.hint.select_cancelable";
    /** 没有任何落点、就是一个按钮列表：选一项。 */
    public static final String HINT_PICK = "ygomc.duel.hint.pick";
    /** 询问还没到（服务端还没发问题过来）。 */
    public static final String HINT_WAITING = "ygomc.duel.hint.waiting";

    // ── 进度行 ────────────────────────────────────────────────────────────
    /** {@code %d} 已选、{@code %d} 下限、{@code %s} 上限（「~3」或「+」）。 */
    public static final String COUNTS_SELECTED = "ygomc.duel.counts.selected";
    /** {@code %d} 已分配、{@code %d} 总数。 */
    public static final String COUNTS_COUNTERS = "ygomc.duel.counts.counters";
    /** {@code %d} 已选张数、{@code %s} 当前合计、{@code %d} 目标合计。 */
    public static final String COUNTS_SUM = "ygomc.duel.counts.sum";
    public static final String COUNTS_SUM_ONLY = "ygomc.duel.counts.sum_only";

    // ── 零碎拼装 ──────────────────────────────────────────────────────────
    /** 弹窗按钮上「动作 + 卡名」的拼法：{@code %s} 动作、{@code %s} 卡名。 */
    public static final String LABEL_WITH_CARD = "ygomc.duel.label.with_card";

    // ── LP 徽章 ───────────────────────────────────────────────────────────
    public static final String LP_MINE = "ygomc.duel.lp.mine";
    public static final String LP_OPPONENT = "ygomc.duel.lp.opponent";

    // ── 牌堆标签 ──────────────────────────────────────────────────────────
    public static final String PILE_GRAVE = "ygomc.duel.pile.grave";
    public static final String PILE_EXTRA = "ygomc.duel.pile.extra";
    public static final String PILE_DECK = "ygomc.duel.pile.deck";
    public static final String PILE_REMOVED = "ygomc.duel.pile.removed";
    public static final String PILE_HAND = "ygomc.duel.pile.hand";

    // ── 手牌行标题 ────────────────────────────────────────────────────────
    public static final String HAND_MINE = "ygomc.duel.hand.mine";
    public static final String HAND_OPPONENT = "ygomc.duel.hand.opponent";

    // ── 状态条标题三态 ────────────────────────────────────────────────────
    public static final String STATUS_WAITING = "ygomc.duel.status.waiting";
    public static final String STATUS_SUBMITTED = "ygomc.duel.status.submitted";
    public static final String STATUS_NO_BOARD = "ygomc.duel.status.no_board";

    /**
     * 连锁数小牌子：「连锁 %s」。
     *
     * <p>数值是内核 {@code chainCount}（连锁已经有几环）。它是<b>观测值</b>，
     * 不是我们要翻译的句子，所以这里只给模板、数字当参数传。
     */
    public static final String STATUS_CHAIN = "ygomc.duel.status.chain";

    /**
     * 左上角「时点略过」键的三种模式。字面照 ygopro {@code strings.conf}：
     * 1292 忽略时点／1293 显示时点／1294 可用时点。
     */
    public static final String SKIP_IGNORE = "ygomc.duel.skip.ignore";
    public static final String SKIP_ALWAYS = "ygomc.duel.skip.always";
    public static final String SKIP_AVAIL = "ygomc.duel.skip.avail";

    // ── 收局画面 ──────────────────────────────────────────────────────────
    /** 收局标题三态：按视角说输赢。平局必须单独一句——两边都不是赢家。 */
    public static final String RESULT_WIN = "ygomc.duel.result.win";
    public static final String RESULT_LOSE = "ygomc.duel.result.lose";
    public static final String RESULT_DRAW = "ygomc.duel.result.draw";
    /** 「原因：%s」。{@code %s} 是内核 {@code !victory} 表的原文（内核文本，不翻）。 */
    public static final String RESULT_REASON = "ygomc.duel.result.reason";
    /**
     * 原因码不在内核 {@code !victory} 表里时的兜底：「胜负原因 %s」。
     *
     * <p>兜底句必须归语言资源管：{@code DataPacks.victoryName} 里那句写死的中文
     * 在英文环境下就是「服务端送来的中文句子」，与硬约束冲突。
     */
    public static final String RESULT_REASON_UNKNOWN = "ygomc.duel.result.reason_unknown";
    /** 「生命值　我方 %s ／ 对手 %s」——两个参数按<b>视角</b>排，不是座位号。 */
    public static final String RESULT_LP = "ygomc.duel.result.lp";
    /** 「回合数　%s」。 */
    public static final String RESULT_TURNS = "ygomc.duel.result.turns";

    // ── 右侧信息面板 ──────────────────────────────────────────────────────
    public static final String PANEL_NO_ART = "ygomc.duel.panel.no_art";
    public static final String PANEL_HOVER_CARD = "ygomc.duel.panel.hover_card";
    public static final String PANEL_SCROLL = "ygomc.duel.panel.scroll";
    public static final String PANEL_SCROLL_UP = "ygomc.duel.panel.scroll_up";

    // ── 区域名（我们自己的那一套说法） ────────────────────────────────────
    public static final String ZONE_MZONE = "ygomc.duel.zone.mzone";
    public static final String ZONE_SZONE = "ygomc.duel.zone.szone";
    public static final String ZONE_GRAVE = "ygomc.duel.zone.grave";
    public static final String ZONE_REMOVED = "ygomc.duel.zone.removed";
    public static final String ZONE_HAND = "ygomc.duel.zone.hand";
    public static final String ZONE_DECK = "ygomc.duel.zone.deck";
    public static final String ZONE_EXTRA = "ygomc.duel.zone.extra";
    public static final String ZONE_UNKNOWN = "ygomc.duel.zone.unknown";

    /**
     * 区域名的 key：{@code ygomc.duel.zone.<区域>}。
     *
     * <p>委托给 {@code PileBrowse.zoneKey}：取值只能有一份，否则「同一块区域在
     * 状态行和查看窗里叫两个名字」这种错会以最难看的方式出现（两处各自都「对」）。
     */
    public static String zoneKey(int location) {
        return cn.xm1221.ygomc.common.duel.PileBrowse.zoneKey(location);
    }

    /**
     * 「谁的哪个区」的 key，参数是格号（从 1 起）：选格/解放的标签要说清是五个格子里的哪一个。
     * 同样委托给 {@code PileBrowse}，取值只能有一份。
     */
    public static String sideZoneKey(int seat, int location, int viewerSeat) {
        return cn.xm1221.ygomc.common.duel.PileBrowse.sideZoneKey(seat, location, viewerSeat);
    }

    /** 查看牌堆窗口的标题 key，见 {@code PileBrowse.titleKey}。 */
    public static String pileTitleKey(int seat, int location, int viewerSeat) {
        return cn.xm1221.ygomc.common.duel.PileBrowse.titleKey(seat, location, viewerSeat);
    }

    // ── 己方 / 对方 ───────────────────────────────────────────────────────
    public static final String SIDE_MINE = "ygomc.duel.side.mine";
    public static final String SIDE_OPPONENT = "ygomc.duel.side.opponent";

    // ── 表示形式 ──────────────────────────────────────────────────────────
    public static final String POSITION_FACEUP_ATTACK = "ygomc.duel.position.faceup_attack";
    public static final String POSITION_FACEUP_DEFENSE = "ygomc.duel.position.faceup_defense";
    public static final String POSITION_FACEDOWN_DEFENSE = "ygomc.duel.position.facedown_defense";
    public static final String POSITION_FACEDOWN_ATTACK = "ygomc.duel.position.facedown_attack";

    // ── 「变更表示」按当前姿势改名 ────────────────────────────────────────
    //
    // 这四条 key 的字面量住在 FieldCodes 里：判「该说哪一种」的那段又是纯位运算、
    // 又在 common（服务端也会加载），它只能给 key、不能给词。两边共用同一份常量，
    // 免得这里写一份、那里写一份，改一处漏一处。
    public static final String REPOSITION_GENERIC = cn.xm1221.ygomc.common.duel.FieldCodes.KEY_REPOSITION_GENERIC;
    public static final String REPOSITION_FLIP = cn.xm1221.ygomc.common.duel.FieldCodes.KEY_REPOSITION_FLIP;
    public static final String REPOSITION_TO_DEFENSE = cn.xm1221.ygomc.common.duel.FieldCodes.KEY_REPOSITION_TO_DEFENSE;
    public static final String REPOSITION_TO_ATTACK = cn.xm1221.ygomc.common.duel.FieldCodes.KEY_REPOSITION_TO_ATTACK;

    // ── 求和项 / 指示物项 ─────────────────────────────────────────────────
    public static final String SUM_ONE = "ygomc.duel.sum.one";
    public static final String SUM_TWO = "ygomc.duel.sum.two";
    public static final String COUNTER_AVAILABLE = "ygomc.duel.counter.available";

    // ── 宣言数字 ──────────────────────────────────────────────────────────
    public static final String DECLARE_NUMBER = "ygomc.duel.declare.number";
    /** 卡号兜底（查不到卡名时）。卡号本身不是我们的文本，这是它的格式模板。 */
    public static final String DECLARE_CARD_CODE = "ygomc.duel.declare.card_code";

    // ── 查看窗（{@code PileBrowse}） ──────────────────────────────────────
    public static final String LIST_TITLE = "ygomc.duel.list.title";
    public static final String LIST_TITLE_NO_MAX = "ygomc.duel.list.title_no_max";
    public static final String LIST_CANCEL_ROW = "ygomc.duel.list.cancel_row";
    /**
     * 列表下方的交卷提示。
     *
     * <p>原名是 {@code confirm_outside}（「点空白处确认」）——那条手势已经去掉了
     * （见 {@code DuelScreen.mouseClicked}：空白处点击不再产生应答，交卷只走确认键），
     * 文案随之改成指「右下角的确认键」，key 也一并改，免得读代码的人以为还有那条路。
     */
    public static final String LIST_CONFIRM_HINT = "ygomc.duel.list.confirm_hint";
    public static final String LIST_PICKED = "ygomc.duel.list.picked";

    // ── 查看牌堆内容 ──────────────────────────────────────────────────────
    //
    // 标题不在这里：它的 key 由 {@code PileBrowse.titleKey(...)} 拼出来，落在
    // {@code ygomc.duel.zone.<mine|opponent>_<区域>} 那一族里（见 {@link #ZONE_MZONE} 下面）。
    public static final String BROWSE_COLLAPSE = "ygomc.duel.browse.collapse";
    public static final String BROWSE_COLLAPSE_HINT = "ygomc.duel.browse.collapse_hint";
    public static final String BROWSE_EMPTY = "ygomc.duel.browse.empty";
    public static final String BROWSE_UNKNOWN = "ygomc.duel.browse.unknown";

    // ── 出错 ──────────────────────────────────────────────────────────────
    public static final String ERROR_CANNOT_SEND = "ygomc.duel.error.cannot_send";

    // ── 卡图浏览界面（{@code CardBrowserScreen}） ─────────────────────────
    public static final String BROWSER_TITLE = "ygomc.browser.title";
    public static final String BROWSER_PREV = "ygomc.browser.prev";
    public static final String BROWSER_NEXT = "ygomc.browser.next";
    public static final String BROWSER_PREV_100 = "ygomc.browser.prev100";
    public static final String BROWSER_NEXT_100 = "ygomc.browser.next100";
    public static final String BROWSER_NO_PACK = "ygomc.browser.no_pack";
    public static final String BROWSER_NO_PACK_DETAIL = "ygomc.browser.no_pack_detail";
    public static final String BROWSER_NO_ART = "ygomc.browser.no_art";
    public static final String BROWSER_NO_NAME = "ygomc.browser.no_name";
    public static final String BROWSER_NO_DESC = "ygomc.browser.no_desc";
    public static final String BROWSER_PAGE = "ygomc.browser.page";
    public static final String BROWSER_PAGE_NOTE = "ygomc.browser.page_note";
    public static final String BROWSER_ELLIPSIS = "ygomc.browser.ellipsis";
    // ── 卡片数值行（{@code CardTips}） ────────────────────────────────────
    public static final String CARD_SPELL = "ygomc.card.spell";
    public static final String CARD_TRAP = "ygomc.card.trap";
    public static final String CARD_RANK = "ygomc.card.rank";
    public static final String CARD_LINK = "ygomc.card.link";
    public static final String CARD_LEVEL = "ygomc.card.level";
    /** 怪兽数值行整行模板：{@code %s} 等级/阶级/连接前缀、{@code %d} 数值、ATK、DEF。 */
    public static final String CARD_STATS_MONSTER = "ygomc.card.stats.monster";
    /** 连接怪兽没有守备力，用不带 DEF 的那一条。 */
    public static final String CARD_STATS_LINK = "ygomc.card.stats.link";
    // ── 怪兽上「变了才显示」的当前数值（{@code DuelScreen.statMarks}） ──────────
    public static final String STAT_ATK = "ygomc.duel.stat.atk";
    public static final String STAT_DEF = "ygomc.duel.stat.def";
    public static final String STAT_ATTRIBUTE = "ygomc.duel.stat.attribute";
    public static final String STAT_RACE = "ygomc.duel.stat.race";
    public static final String STAT_LEVEL = "ygomc.duel.stat.level";
    public static final String STAT_RANK = "ygomc.duel.stat.rank";
    public static final String STAT_LINK = "ygomc.duel.stat.link";
}
