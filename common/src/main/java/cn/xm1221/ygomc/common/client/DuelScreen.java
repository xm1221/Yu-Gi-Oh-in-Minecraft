package cn.xm1221.ygomc.common.client;

import cn.xm1221.ygomc.common.duel.DuelBoard;
import cn.xm1221.ygomc.common.duel.PileBrowse;
import cn.xm1221.ygomc.common.duel.FieldCodes;
import cn.xm1221.ygomc.common.duel.DuelQuestion;
import cn.xm1221.ygomc.common.ocg.SumSelect;
import cn.xm1221.ygomc.common.ocg.msg.MsgType;
import cn.xm1221.ygomc.common.ocg.msg.Msg;
import cn.xm1221.ygomc.common.duel.ChainNotice;
import cn.xm1221.ygomc.common.duel.DuelWire;
import cn.xm1221.ygomc.common.data.DataPacks;
import cn.xm1221.ygomc.common.data.DescText;
import cn.xm1221.ygomc.common.net.YgomcNet;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 对局界面：牌桌 + 当前询问。
 *
 * <h2>交互是「点牌桌」，不是「点按钮」</h2>
 * 选卡、选格子一律<b>直接点那张卡、那个格子</b>：左键选择/取消选择，
 * 右键确认（多选）或取消。这与 ygo 客户端和 YDM 一致——YDM 的对局界面
 * 除了聊天框几乎没有按钮，交互全在 {@code ZoneWidget} 上，
 * 点「动作源」再点「动作目标」，第二次点击本身就是确认。
 *
 * <p>只有在询问<b>没有空间落点</b>时才退化成列表：是/否、发动哪个效果、
 * 攻击还是守备、宣言种族属性。这些本来就没有卡可以点，官方客户端也是弹一个小窗。
 * 把它们做成按钮不是「多此一举」，而是它们确实没有别的地方可放。
 *
 * <h2>这个类不做决策</h2>
 * 它只把已经解码好的 {@link DuelQuestion} 摊成可点目标，把点击翻译成
 * {@link DuelQuestion#response(int...)}，再交给网络层发出去。
 * <b>所有规则判断都在 {@code response} 里</b>——它和内核的编码约定一起
 * 被 7855 条真实询问验过，界面这边再写一遍判断就等于绕开那套验证。
 *
 * <h2>坐标只有一个来源</h2>
 * 所有矩形来自 {@link FieldLayout}，落点映射来自 {@link DuelTargets}，
 * 这两个类都不依赖 Minecraft，因此可以脱离游戏断言（996 + 894 条）。
 * 坐标原先按 {@code height} 加减常数写死在 render 里，后果是魔陷行整行
 * 落到屏幕外、问题标题压在我方牌桌上——编译期看不出来，只能靠盯画面发现。
 *
 * <h2>本地玩家不一定在下面</h2>
 * 界面底部恒为「我」、顶部恒为「对手」，但座位号不由位置决定：
 * 我可能是 P0 也可能是 P1。原先无条件把 {@code player0} 画在下面，
 * 一旦本地玩家是 P1，自己的卡就会出现在对手那半边。这里按
 * {@link DuelQuestion#player()} 定座位，再取对应的一方来画。
 *
 * <h2>格子数按【现行大师规则】</h2>
 * 怪兽区 5、魔陷区 5（<b>灵摆区已并入 szone 0/4</b>，见
 * {@code field.cpp:499-512} 的 {@code get_pzone_sequence}）、场地区 1
 * （{@code szone 5}，{@code field.cpp:564}）、额外怪兽区 2（{@code mzone 5,6}，
 * 中线中间列、<b>双方共用</b>）。内核魔陷槽数组长 8（{@code ocgapi.cpp:91-92}），
 * 但 szone 6/7 是旧规则灵摆余位，<b>不画</b>。
 * 只有行序与左右相对位置参考 YDM（GPLv3，{@code Copyright (C) CAS_ual_TY}）
 * 的 {@code duel/playfield/PlayFieldTypes.java}；它的独立灵摆列不符合现行规则，未照抄。
 */
public class DuelScreen extends net.minecraft.client.gui.screens.Screen {

    private DuelBoard board;
    private DuelQuestion question;
    /** 多选/选址类里已勾选的选项下标。 */
    private final Set<Integer> chosen = new LinkedHashSet<>();
    private Button confirm;
    /** 取消键。只在询问真的带了「取消」项时才摆出来（咩咩：仅限于可以取消的操作）。 */
    private Button cancelBtn;
    private int[] counterAmounts = new int[0];
    /** 已提交、在等下个询问。这期间界面留着但按钮全灭。 */
    private boolean submitted;
    /** 本地玩家座位。询问到达时更新；没询问时沿用上一次。 */
    private int mySeat;

    /**
     * 光标处的行动菜单：里面是选项下标。
     *
     * <p>点一张卡而这张卡当前有<b>多个</b>可做的行动时才弹（例如手牌既能通常召唤
     * 又能盖放）。这就是 ygo 的做法——行动挂在卡上，玩家点卡，由界面列出这张卡
     * 现在能做什么；而不是把整个回合的所有行动倒成一片按钮。
     */
    private final List<Integer> menu = new ArrayList<>();

    /**
     * 额外卡组那份菜单里的两项：「特殊召唤」与「查看列表」。
     *
     * <p>它们<b>不是选项下标</b>，是这个菜单自己的动作，所以给两个具名哨兵。
     * 直接在别处写 -1/-2 会让「菜单第几项」与「选项第几项」再也分不清——
     * 而 {@code menu} 里存的正是选项下标，两者混在一起没有任何东西拦得住。
     */
    private static final int MENU_SUMMON = -2;
    private static final int MENU_VIEW_LIST = -1;

    /** 上一份「特殊召唤 / 查看列表」菜单挂在哪一堆上；不是这类菜单时为 null。 */
    private PileRef extraMenuRef;

    /**
     * 额外卡组的卡名列表被玩家点开过没有（点的是菜单里的「特殊召唤」）。
     *
     * <p>刻意<b>不</b>在 {@link #rebuild()} 里清零：点「特殊召唤」本身就会触发一次
     * rebuild，在那里重置的话列表刚摊开就又被收走。只在<b>询问换了</b>时重置
     * （{@link #update} 里），新一轮询问总是从「没点开」开始。
     */
    private boolean extraListOpen;
    /**
     * 连锁「第一段」答过没有：先问「XX时，是否发动效果？」，同意之后才让候选亮起来。
     *
     * <p>ygo 里这两件事是两步：非必发连锁先弹询问窗（duelclient.cpp:1871-1879），
     * 点「是」之后才收窗、才继续去点卡，并给取消键（event_handler.cpp:219-224）；
     * 必发连锁压根不问（那个分支外面就是 {@code if(!chain_forced)}）。
     * 换了一问就回到没答过的状态——只认「这一问」，不跨问残留。
     */
    private boolean chainAgreed;

    /**
     * 「不在场上、只能靠列表选」的那些选项——墓地/卡组/额外/除外。
     *
     * <p>这些区域在牌桌上只有一堆，所有卡共用一个格子，所以点它等于同时点中 N 项；
     * 以前这里会弹出一个 N 项、彼此看不出区别的菜单，等于选不了。
     * ygo 对这种情况弹 {@code wCardSelect} 卡名列表（{@code client_field.cpp:431-527}）。
     *
     * <p>顺序必须与 {@link #list} 的行号一一对应，所以在这里算一次就不再变。
     */
    private List<DuelTargets.Target> piles = List.of();

    /** 卡列表；没有可列的东西时为 null。 */
    private CardList list;

    /** 正在查看的牌堆（自由行动时点牌堆打开）。与选择用的 {@link #list} 不是同一件事。 */
    private Browse browse;

    /** 本帧画过的牌堆格子：点牌堆查看内容时要知道点的是哪一堆。每帧重填。 */
    private final List<PileRef> pileRefs = new ArrayList<>();

    /** 画在侧格上的一堆：矩形 + 是谁的 + 哪个区域。 */
    private record PileRef(FieldLayout.Rect rect, int seat, int location) {
    }

    /**
     * 查看牌堆内容的窗口状态。
     *
     * <p>标题不存字符串，只存「谁的、哪一堆」：标题要跟着语言设置走，
     * 存下来的那一刻就定死了（换成英文后旧窗口还是中文）。
     */
    private record Browse(int seat, int location, List<PileBrowse.Row> rows, CardList list) {
    }

    /**
     * 要弹窗问的询问的窗口矩形；没有弹窗时为 null。
     *
     * <p>非 null 时界面是<b>模态</b>的：只由弹窗里的按钮作答，阶段条与牌桌都不响应。
     */
    private FieldLayout.Rect popup;

    /** 弹窗内边距。 */
    private static final int POPUP_PAD = 6;

    /** 底部状态条里留给「我方 LP」徽章的宽度。 */
    private static final int LP_BADGE_W = 62;

    /** LP 徽章再挤也要留的宽度：只够画那串数字（「8000」）。 */
    private static final int MIN_LP_W = 30;

    /** 状态条正文至少要留出的宽度，否则标题会被截断成没意义的一小截。 */
    private static final int MIN_TEXT_W = 40;
    /** 右下角「确认 / 取消」两颗键的尺寸。状态条让位的宽度也照这个算，不许两处各写一份。 */
    private static final int ANSWER_BTN_W = 54;
    private static final int ANSWER_BTN_H = 18;
    private static final int ANSWER_BTN_GAP = 4;

    /** 右面板卡文滚到第几行。换一张卡就归零。 */
    private int descScroll;

    /** 卡文一共几行、面板放得下几行。由绘制时算出，滚动照着它夹取。 */
    private int descLines;
    private int descMaxLines;

    /** 上一张显示在右面板里的卡，用来判断「换卡了」并把滚动归零。 */
    private int lastDescCode;

    private int menuX;
    private int menuY;

    /**
     * 内核自己发动的必发效果那条一次性提示；null 表示没有。
     *
     * <p>它<b>不是询问</b>：不挡任何按钮，玩家按「确认」只把它收起来，
     * 不回任何应答给内核（见 {@link ChainNotice}）。
     */
    private ChainNotice notice;

    /**
     * @param viewerSeat 服务器给的视角座位（0/1）。<b>不能</b>只从 {@code question} 推：
     *        没有询问的那一帧（对手回合里每一步末尾都会推一帧）{@code question} 是 null，
     *        那时就只能默认 0 号席——后手玩家会看到对手的牌桌，也看不见自己的手牌。
     *        判据在 {@link ClientSeat#of}，可离线断言。
     * @param notice 随这一帧来的必发提示（{@link ChainNotice}）；没有给 null。
     */
    public DuelScreen(DuelBoard board, DuelQuestion question, int viewerSeat, ChainNotice notice) {
        super(DuelText.c(DuelText.SCREEN_DUEL));
        this.board = board;
        this.question = question;
        this.notice = notice;
        this.mySeat = ClientSeat.of(viewerSeat, question == null ? -1 : question.player());
    }

    /** 服务器推来新状态时调用。 */
    /**
     * 服务器推来新状态时调用。
     *
     * <h2>「同一题」按内容判断，不按对象身份</h2>
     * 每次解码都会造出一个新的 {@code DuelQuestion} 对象，所以「对象不是同一个」
     * 不等于「问题变了」。原先用 {@code !=} 判断，后果是：引擎重发同一题
     * （{@code MSG_RETRY}、或者只是又推了一次牌桌）就把玩家已经勾好的卡<b>全部清空</b>，
     * 表现为「刚选好就没了」，而玩家完全不知道为什么。
     * {@code DuelQuestion} 与 {@code Option} 都是 record，{@code equals} 是逐字段比较，
     * 所以这里直接用内容相等来判断「还是不是同一题」。
     */
    public void update(DuelBoard board, DuelQuestion question, int viewerSeat, ChainNotice notice) {
        boolean same = java.util.Objects.equals(this.question, question);
        // 「上次已经作答、现在又来了一个询问」= 引擎重问（MSG_RETRY 或重新推送）。
        // 这时必须把界面重新放开，否则玩家被自己上一次的提交永久锁住：
        // 勾选还在、按钮全灭，看起来就是「界面死了」。
        boolean reAsked = submitted;
        // 视角座位每帧都跟着来（见 ClientSeat）：只从询问推的话，
        // 没有询问的帧就只能默认 0 号席——后手玩家会看到对手的牌桌。
        this.mySeat = ClientSeat.of(viewerSeat, question == null ? -1 : question.player());
        this.board = board;
        this.question = question;
        if (!same) {
            chosen.clear();
            counterAmounts = question == null ? new int[0] : new int[question.options().size()];
            menu.clear();
            // 额外卡组的列表是「这一问才点开」的状态，换了一问就回到没点开。
            // 放在这里而不是 rebuild() 里：点「特殊召唤」也会触发 rebuild。
            extraListOpen = false;
            chainAgreed = false;
            extraMenuRef = null;
        }
        // 必发提示的生命周期走 DuelScreenFlow.nextNotice（纯判据，可离线断言）：
        // 来了新的覆盖旧的；没来的话停在同一问上就留着，换了询问就收掉。
        // 玩家一作答也会收掉（见 dispatch）。它<b>只影响本地显示</b>，不产生任何应答。
        boolean noticeChanged = !java.util.Objects.equals(this.notice, notice);
        this.notice = DuelScreenFlow.nextNotice(this.notice, notice, same);
        if (!same || reAsked) {
            submitted = false;
            rebuild();
        } else if (noticeChanged) {
            // 只是来了一条提示：不能走上面那条路——那会把 submitted 放开，
            // 让已经答完的询问又能再答一次。这里只重摆一次控件。
            rebuild();
        }
    }

    private FieldLayout field() {
        return FieldLayout.compute(width, height);
    }

    private DuelBoard.PlayerBoard me() {
        return board == null ? null : board.playerAt(mySeat);
    }

    private DuelBoard.PlayerBoard opponent() {
        return board == null ? null : board.playerAt(1 - mySeat);
    }

    /**
     * 当前询问在牌桌上的落点。
     *
     * <p>手牌张数由这里从牌桌上取真实的数字传下去：选项表里可能只列出一部分手牌
     * （只有 3 张能选），照着选项表去排就会把这 3 张摊满整行，画出来的位置和
     * 点得到的位置对不上。
     */
    private List<DuelTargets.Target> targets() {
        // 是/否类不摆可点目标：选项上带的位置只用来把那张卡点亮，
        // 作答走「确认/取消」（咩咩 2026-10-05）。否则点一下那张卡就等于替玩家按了「是」。
        if (chainAskStage()) {
            // 第一段只问「要不要做」，还没到挑哪一张的时候：候选一律不亮、也点不动。
            // 咩咩 2026-10-05：「同意后让卡/墓地/除外亮起」——亮得太早就是替玩家先做了半个决定。
            return List.of();
        }
        if (question != null && DuelQuestion.isYesNo(question.type())) {
            return List.of();
        }
        if (board == null) {
            return DuelTargets.of(question, field(), mySeat);
        }
        int[] handCounts = {board.player0().handCount(), board.player1().handCount()};
        return DuelTargets.of(question, field(), mySeat, handCounts);
    }

    /**
     * 常驻阶段条的六格。
     *
     * <p>取值是内核 {@code common.h} 的阶段位：{@code 0x01 抽卡 / 0x02 准备 /
     * 0x04 主要1 / 0x08 战斗 / 0x100 主要2 / 0x200 结束}。注意 {@code 0x80}
     * 是「战斗阶段结束」而<b>不是</b>战斗阶段本身，写成 0x80 会让战斗条永远点不亮。
     */
    private static final int[] PHASES = {0x01, 0x02, 0x04, 0x08, 0x100, 0x200};
    // 阶段名走语言资源（DuelText.PHASES），不在这里写一份中文——原先这里有一份
    // PHASE_NAMES，于是「阶段条上的字」是全界面唯一不跟着语言设置变的地方。

    private boolean actionQuestion() {
        return question != null && (question.type() == 11 || question.type() == 10);
    }

    private int phaseOption(int phase) {
        // 弹窗开着的时候阶段条是死的：模态就该是模态。
        if (!actionQuestion() || submitted || popup != null) return -1;
        int value = question.type() == 11 ? (phase == 0x08 ? 6 : phase == 0x200 ? 7 : -1)
                : (phase == 0x100 ? 2 : phase == 0x200 ? 3 : -1);
        if (value < 0) return -1;
        for (int i = 0; i < question.options().size(); i++) {
            var o = question.options().get(i);
            if (!o.hasPlace() && !o.isCancel() && o.value() == value) return i;
        }
        return -1;
    }

    private void drawPhases(GuiGraphics g, FieldLayout layout) {
        for (int i = 0; i < PHASES.length; i++) {
            var r = layout.phase(i);
            boolean enabled = phaseOption(PHASES[i]) >= 0;
            g.fill(r.x(), r.y(), r.right(), r.bottom(), enabled ? 0xFF35634B : 0xFF25313B);
            g.drawCenteredString(font, phaseName(i), r.x() + r.w() / 2, r.y() + 4,
                    enabled ? 0xFFFFFF : 0x889099);
        }
    }

    private boolean spatial() {
        return !targets().isEmpty();
    }

    /** 这个位置是不是「一堆」而不是「一格」。 */
    private static boolean isPile(int location) {
        return FieldCodes.isPileLocation(location);
    }

    /**
     * 只能靠列表选的选项。行动类询问不算——那时候点卡是「做这个行动」，
     * 不是「从一堆里挑一张」，弹列表反而是挡路。
     */
    /**
     * 落在牌堆上的可点目标。
     *
     * <p><b>行动询问同样要收</b>：以前这里对 {@code SELECT_IDLECMD/SELECT_BATTLECMD/
     * SELECT_CHAIN} 直接返回空，于是「墓地有好几张卡的效果可以发动」「额外卡组有好几只
     * 可以特殊召唤」这两件事都落到了「点卡弹菜单」那条路上——而牌堆只占一个格子，
     * 菜单里就摆出一列一模一样的「发动效果」，玩家没法挑。
     * ygo 对这类是摆卡名列表（{@code ClientField::ShowSelectCard}），不是点卡菜单。
     */
    private List<DuelTargets.Target> pileTargets() {
        List<DuelTargets.Target> out = new ArrayList<>();
        for (DuelTargets.Target t : targets()) {
            if (isPile(t.option().location())) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * 牌桌格子上可点的目标。卡列表开着时，列表负责的那些不再走格子——
     * 否则点那一堆还是会弹出一个看不出区别的菜单，等于留了条错路。
     */
    private List<DuelTargets.Target> fieldTargets() {
        List<DuelTargets.Target> all = targets();
        List<DuelTargets.Target> out = new ArrayList<>(all.size());
        for (DuelTargets.Target t : all) {
            // 卡列表开着时，列表负责的那些堆不再走格子——否则点那一堆还是会弹出
            // 一个看不出区别的菜单，等于留了条错路。
            if (list != null && isPile(t.option().location())) {
                continue;
            }
            // 额外卡组有可特殊召唤的怪兽时，它也不走「点格子弹菜单」这条路：
            // 它有自己的菜单（特殊召唤 / 查看列表），见 mouseClicked。分流必须在这里做，
            // 否则点它会被当成普通目标——只有一个选项时还会直接作答，那是替玩家做决定。
            if (extraPileTarget(t)) {
                continue;
            }
            out.add(t);
        }
        return out;
    }

    /**
     * 这个目标是不是「额外卡组那一堆，且现在该走特殊召唤菜单」。
     *
     * <p>列表已经摊开（玩家点过「特殊召唤」）就不再算——那时候走的是列表，不是菜单。
     */
    private boolean extraPileTarget(DuelTargets.Target t) {
        return question != null && !extraListOpen
                && t.option().location() == FieldCodes.LOCATION_EXTRA
                && question.extraNeedsMenu(t.option().controller());
    }
    /**
     * 额外卡组那份「特殊召唤 / 查看列表」菜单现在开着吗。
     *
     * <p>开着的时候提示要换成「在菜单里选一项」：这时候牌桌是被菜单压住的，
     * 玩家再点卡不会有反应，光写「点一下即可」会让他一直点那里。
     */
    private boolean extraMenuPending() {
        return question != null && !submitted && extraMenuRef != null
                && extraMenuRef.location() == FieldCodes.LOCATION_EXTRA
                && question.extraNeedsMenu(extraMenuRef.seat());
    }

    /**
     * 卡列表窗口放在哪。左侧、纵向居中，宽度只占场地的一小条：
     * 右边要留给信息面板（悬停列表项时卡图就显示在那里）。
     */
    private FieldLayout.Rect listRect() {
        FieldLayout L = field();
        int w = Math.min(230, Math.max(130, L.fieldW() / 3));
        int h = Math.min(L.height() - 70, Math.max(70, L.height() * 3 / 4));
        return new FieldLayout.Rect(L.x0(), Math.max(2, (L.height() - h) / 2), w, h);
    }

    /**
     * 这个询问是不是「让玩家挑一项行动」——主要阶段/战斗阶段的指令菜单，以及连锁询问。
     *
     * <p>ygo 靠卡上的 {@code cmdFlag} 区分两件事：这张卡<b>有得选</b>（点卡弹
     * {@code ShowMenu}，`event_handler.cpp:2260-2299`）与这张卡<b>可以被选</b>
     * （{@code selectable_cards}，点卡直接勾选）。我们这里按询问类型区分：
     * 上面这三类是「行动」，点卡只负责把菜单打开，行动本身必须从菜单里挑；
     * 其余（选卡/选祭品/选攻击目标/选位置…）是「选择」，点卡即生效。
     *
     * <p>之所以连只有一项的也弹菜单：发动效果是不可逆的，误触一次就下去了；
     * ygo 里根本没有「点一下直接发动」这条路。
     */
    private boolean actionMenu() {
        return DuelQuestion.isAction(question.type());
    }

    @Override
    protected void init() {
        rebuild();
    }

    /**
     * 只给「没有落点」的询问生成按钮，而且是居中的<b>小窗</b>，不是铺满底部的网格。
     *
     * <p>行动类询问（这一回合能做什么、这只怪能做什么）现在全部挂到卡上，
     * 所以它们一条按钮都不会生成——那片二十来项的网格因此彻底消失。
     * 这里剩下的只有本来就无处可挂的：是/否、发动哪个效果、攻击还是守备、宣言种族属性。
     * 这些连官方客户端也是弹小窗，因为确实没有卡可以点。
     */
    private void rebuild() {
        clearWidgets();
        // 「查看牌堆内容」的窗口不属于询问：界面重建时它可能还开着，
        // 那颗「收起」键就得跟着重摆，否则窗口还在、键没了。
        if (browse != null) {
            addBrowseCloseButton();
        }
        // 必发提示条不再显示（咩咩 2026-10-05：「这个不用显示」）。服务端仍会把那条
        // 一次性告知送过来（线格式 v3 的帧尾），客户端只收下不用；要彻底去掉得连服务端
        // 与线格式一起改，等咩咩发话。
        confirm = null;
        cancelBtn = null;
        popup = null;
        if (question == null || submitted) {
            piles = List.of();
            list = null;
            return;
        }
        // 「是否发动效果」一律弹窗问：不摆底部按钮、也不靠点卡。
        // 发动是不可逆的，得先看清是【哪张卡】再按。
        if (popupQuestion()) {
            piles = List.of();
            list = null;
            buildPopupButtons();
            return;
        }
        // 卡列表与下面那排按钮是互斥的两条路：有列表就以列表为准。
        piles = pileTargets();
        // 常驻的「确认 / 取消」键（右下角）。摆在这里，下面三条路
        // （卡名列表 / 点场地 / 网格按钮）都覆盖得到。
        buildAnswerButtons();
        if (DuelQuestion.isYesNo(question.type())) {
            // 是/否类只由右下角那两颗键作答：不再弹窗、也不铺一排「是/否」按钮
            // （咩咩 2026-10-05）。那张卡已经用黄框点亮，玩家看得到问的是谁。
            return;
        }
        // 判据是「有没有落在牌堆上的选项」，不再看询问类型——行动询问同样可能
        // 有好几张墓地的卡可以发动，那同样得给列表（见 pileTargets 的注释）。
        // 额外卡组的选项不自动摊开：它要先由「特殊召唤 / 查看列表」菜单入场
        // （咩咩 2026-10-05）。墓地/除外照旧——那是上一轮验证过的行为。
        boolean autoList = question.pileOptionCountExcept(FieldCodes.LOCATION_EXTRA) > 0;
        // 答完（submitted）就不要再摆卡名列表：ygo 交出应答时会把选择窗口收起来
        // （duelclient.cpp:2517-2520）。以前列表会赖在屏幕上，看着像「还没答」。
        list = !submitted && question.needsCardList() && !piles.isEmpty() && (autoList || extraListOpen)
                ? new CardList(listRect(), piles.size() + (cancelIndex() >= 0 ? 1 : 0)) : null;
        if (list != null || spatial() || actionQuestion()) {
            return;
        }
        // 网格里只摆「牌桌上点不到」的选项：带位置的选项一律去点那张卡/那个格子。
        // 同一条答复不该有两条路——有按钮摆在旁边时，玩家会以为「从这排按钮发动」
        // 才是正常玩法（咩咩 2026-10-05：「点了之后会发动一些效果，但是实际上不需要
        // 它们来发动，有其他很正常方法」）。取消也不进网格：它由右下角常驻那颗负责。
        //
        // 这一条同时堵住了<b>连锁第一段</b>的漏洞：那一段的候选是故意不亮、也点不动的
        // （见 {@link #targets()}），于是「没有可点目标」会让整排候选变成按钮，
        // 等于绕开「先问要不要发动」，直接把效果发出去。
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < question.options().size(); i++) {
            DuelQuestion.Option o = question.options().get(i);
            if (!o.isCancel() && !o.hasPlace()) {
                indices.add(i);
            }
        }
        if (indices.isEmpty()) {
            // 兜底：万一某个带位置的选项既没算出可点矩形（{@code TargetCheck} 有一条
            // 「每个带位置的选项都点得到」的不变量在守着），又没有「确认/取消」可按，
            // 就退回原来的样子——宁可多一条路，也不能让玩家无处可答。
            //
            // 连锁第一段不会落到这里：那一段有常驻的「确认/取消」（{@code cancelable}），
            // 玩家正是靠它表态，所以上面这一串判断会直接 return。
            if (spatial() || question.soleOption() >= 0 || question.cancelable()
                    || question.needsConfirm() || DuelQuestion.isYesNo(question.type())) {
                return;
            }
            for (int i = 0; i < question.options().size(); i++) {
                if (!question.options().get(i).isCancel()) {
                    indices.add(i);
                }
            }
            if (indices.isEmpty()) {
                return;
            }
        }
        FieldLayout L = field();
        int gap = 2;
        int bh = 16;
        int bw = 60;
        for (int index : indices) {
            bw = Math.max(bw, font.width(shortLabel(question.options().get(index))) + 18);
        }
        bw = Math.min(bw, Math.max(60, width / 2));
        int n = indices.size();
        // 项数多（宣言种族/属性）就折成两列，别顶到屏幕上边
        int cols = n * (bh + gap) > height - 70 ? 2 : 1;
        int rows = (n + cols - 1) / cols;
        int totalH = rows * (bh + gap);
        int x0 = (width - (cols * bw + (cols - 1) * gap)) / 2;
        int y0 = Math.max(4, L.panel().y() - totalH - 8);

        for (int i = 0; i < n; i++) {
            final int index = indices.get(i);
            int x = x0 + (i % cols) * (bw + gap);
            int y = y0 + (i / cols) * (bh + gap);
            addRenderableWidget(Button.builder(
                            Component.literal(shortLabel(question.options().get(index))),
                            b -> onOption(index)).bounds(x, y, bw, bh).build());
        }
    }

    /**
     * 菜单里的按钮文字。
     *
     * <p><b>不带卡名</b>：这排按钮是「点了那张卡之后弹出来的」，是哪张卡本来就一目了然。
     * ygopro 的 {@code ClientField::ShowMenu}（event_handler.cpp:2260-2299）摆的就是
     * 「召唤」「攻击」「发动」这种光板标签，卡名是靠菜单挂在哪张卡上来体现的。
     * 之前无脑拼卡名，于是战斗阶段弹出「攻击 红莲共鸣者」——读起来像另一条指令。
     *
     * <p>需要卡名的地方是**连锁询问**那种一列同字选项（每项 label 都是「发动效果」），
     * 那由 {@link #popupLabel} 单独补，不走这里。
     *
     * <p><b>哪些项按「当前姿势」改名</b>不再看中文：原先写的是
     * {@code "变更表示".equals(o.label())}，换个语言就静默失效。现在只看
     * {@link DuelQuestion.Option#action()}。
     */
    private String shortLabel(DuelQuestion.Option o) {
        String s = o.action() == DuelQuestion.IDLE_REPOSITION ? repositionLabel(o) : optionLabel(o);
        return s.length() > 28 ? s.substring(0, 27) + "…" : s;
    }

    /**
     * 选项该显示的那句话：按 {@code labelKind} 从语言资源取。
     *
     * <p>以前这里直接把 {@code o.label()}（中文）上屏，于是「界面文案由语言资源决定」
     * 这条要求在这些地方全部落空——玩家看到的菜单文字是写死在代码里的中文。
     * 现在 {@code label} 只当兜底：语义标记取不出词时才用它。
     */
    private String optionLabel(DuelQuestion.Option o) {
        return switch (o.labelKind()) {
            case CARD -> cardNameLabel(o.cardCode(), o.label());
            case ACTION -> actionName(o.action());
            case YES_NO -> DuelText.s(o.value() == 1 ? DuelText.ACTION_YES : DuelText.ACTION_NO);
            case CANCEL -> DuelText.s(DuelText.ACTION_CANCEL);
            case ZONE -> zoneLabel(o.controller(), o.location(), o.sequence());
            case POSITION -> positionName(o.value());
            case SUM -> sumLabel(o.value());
            case COUNTER -> DuelText.s(DuelText.COUNTER_AVAILABLE, o.value());
            case BIT -> bitName(o.value());
            case NUMBER -> DuelText.s(DuelText.DECLARE_NUMBER, o.index());
            case OPTION_ITEM -> DuelText.s(DuelText.ACTION_OPTION_ITEM, o.index() + 1);
            case RPS_ITEM -> rpsName(o.value());
            case RAW -> o.label() == null || o.label().isEmpty()
                    ? DuelQuestion.Option.FALLBACK_LABEL : o.label();
        };
    }

    /** 卡名；查不到就用 {@code #卡号} 模板（卡号本身是内核数据，不是我们的文案）。 */
    private String cardNameLabel(int code, String fallback) {
        String n = CardTips.name(code);
        if (n != null && !n.isEmpty()) {
            return n;
        }
        return code != 0 ? DuelText.s(DuelText.DECLARE_CARD_CODE, code) : fallback;
    }

    /**
     * 「变更表示」这一项按<b>当前表示形式</b>改名。判定本身在
     * {@link FieldCodes#repositionKey(int)}（纯位运算，离线可断言），
     * 这里只负责把 key 取成词。
     */
    private String repositionLabel(DuelQuestion.Option o) {
        return DuelText.s(FieldCodes.repositionKey(zonePosition(o)));
    }

    /**
     * 区域名：{@code ygomc.duel.zone.*} 那一族（取值由 {@code PileBrowse} 定）。
     *
     * <p>有格号的区（怪兽区/魔陷区）用带归属的那条 key，并把格号当参数：一次「选格」
     * 要是不说清「谁的、第几格」，五个格子就分不出是哪一个——这正是玩家说的
     * 「提示很不完整」。归属按<b>看的人</b>算（{@code mySeat}），不是按选项里的 controller。
     */
    private String zoneLabel(int controller, int location, int sequence) {
        if (location == Msg.Location.MZONE || location == Msg.Location.SZONE) {
            return DuelText.s(DuelText.sideZoneKey(controller, location, mySeat), sequence + 1);
        }
        return DuelText.s(DuelText.zoneKey(location));
    }

    /** 常驻阶段条上的阶段名，按 {@link #PHASES} 的下标取。 */
    private String phaseName(int i) {
        return DuelText.s(DuelText.PHASES[i]);
    }

    /**
     * 行动名。
     *
     * <p><b>必须带询问类型一起判</b>：行动编号是「每个询问内部自洽」的，
     * 主要阶段的 1 是「特殊召唤」、战斗阶段的 1 是「攻击」，Idle 的 3 是「盖放怪兽」、
     * 战斗的 3 是「进入结束阶段」。光看编号会张冠李戴，而且是大面积地错——
     * 所以这里按 {@code type} 分派，而不是把两套编号并成一个 switch。
     */
    private String actionName(int action) {
        if (question == null) {
            return DuelText.s(DuelText.ACTION_REPOSITION);
        }
        if (question.type() == MsgType.SELECT_BATTLECMD) {
            return switch (action) {
                case DuelQuestion.BATTLE_ATTACK -> DuelText.s(DuelText.ACTION_ATTACK);
                case DuelQuestion.BATTLE_TO_M2 -> DuelText.s(DuelText.ACTION_TO_M2);
                case DuelQuestion.BATTLE_TO_EP -> DuelText.s(DuelText.ACTION_TO_EP);
                default -> DuelText.s(DuelText.ACTION_ACTIVATE);
            };
        }
        return switch (action) {
            case DuelQuestion.IDLE_SUMMON -> DuelText.s(DuelText.ACTION_SUMMON);
            case DuelQuestion.IDLE_SPSUMMON -> DuelText.s(DuelText.ACTION_SP_SUMMON);
            case DuelQuestion.IDLE_REPOSITION -> DuelText.s(DuelText.ACTION_REPOSITION);
            case DuelQuestion.IDLE_MONSTER_SET -> DuelText.s(DuelText.ACTION_MONSTER_SET);
            case DuelQuestion.IDLE_SPELL_SET -> DuelText.s(DuelText.ACTION_SPELL_SET);
            case DuelQuestion.IDLE_ACTIVATE_EFFECT -> DuelText.s(DuelText.ACTION_ACTIVATE);
            case DuelQuestion.IDLE_TO_BP -> DuelText.s(DuelText.ACTION_TO_BP);
            case DuelQuestion.IDLE_TO_EP -> DuelText.s(DuelText.ACTION_TO_EP);
            default -> DuelText.s(DuelText.ACTION_REPOSITION);
        };
    }

    /** 表示形式的位 → 名字；认不出来时退回「变更表示」这句中性话。 */
    private String positionName(int bit) {
        if (bit == Msg.SelectPosition.FACEUP_ATTACK) {
            return DuelText.s(DuelText.POSITION_FACEUP_ATTACK);
        }
        if (bit == Msg.SelectPosition.FACEUP_DEFENSE) {
            return DuelText.s(DuelText.POSITION_FACEUP_DEFENSE);
        }
        if (bit == Msg.SelectPosition.FACEDOWN_DEFENSE) {
            return DuelText.s(DuelText.POSITION_FACEDOWN_DEFENSE);
        }
        if (bit == Msg.SelectPosition.FACEDOWN_ATTACK) {
            return DuelText.s(DuelText.POSITION_FACEDOWN_ATTACK);
        }
        return DuelText.s(DuelText.REPOSITION_GENERIC);
    }

    /** 猜拳：内核只认 1/2/3，其它值不该出现，真出现了也别把数字印给玩家。 */
    private String rpsName(int value) {
        return switch (value) {
            case DuelQuestion.RPS_ROCK -> DuelText.s(DuelText.ACTION_RPS_ROCK);
            case DuelQuestion.RPS_SCISSORS -> DuelText.s(DuelText.ACTION_RPS_SCISSORS);
            case DuelQuestion.RPS_PAPER -> DuelText.s(DuelText.ACTION_RPS_PAPER);
            default -> DuelQuestion.Option.FALLBACK_LABEL;
        };
    }

    /**
     * 合计值项的两种取法。
     *
     * <p>{@code sum_param} 由 {@code SumSelect.params} 拆成可选的一或两个值
     * （内核 {@code get_sum_params}），两个都写出来让玩家自己看——一张卡可能有两个可选值。
     */
    private String sumLabel(int sumParam) {
        int[] v = SumSelect.params(sumParam);
        return v[1] > 0 ? DuelText.s(DuelText.SUM_TWO, v[0], v[1])
                : DuelText.s(DuelText.SUM_ONE, v[0]);
    }

    /**
     * 宣言种族/属性的名字。
     *
     * <p>名字来自内核 {@code strings.conf}（{@code DataPacks.raceName/attributeName}），
     * <b>不是我们要翻的东西</b>，所以它是参数而不是模板。以前这里显示的是
     * {@code label} 里的「位 0x4」——把内核位掩码印给玩家看，等于没告诉他选的是什么。
     */
    private String bitName(int bit) {
        String n = question != null && question.type() == MsgType.ANNOUNCE_RACE
                ? DataPacks.raceName(bit) : DataPacks.attributeName(bit);
        return n != null && !n.isEmpty() ? n : DuelQuestion.Option.FALLBACK_LABEL;
    }

    /**
     * 选项指向的场上卡当前的表示形式；查不到返回 {@code -1}。
     *
     * <p><b>位置码是 ocgcore 的位标志，不是 1/2/3</b>：
     * {@code LOCATION_MZONE = 0x04}、{@code LOCATION_SZONE = 0x08}
     * （{@code common.h:55-64}）。按 0x01/0x02 去查会查到卡组和手牌上，
     * 结果是这个函数永远返回 -1、文案永远退回「变更表示」——
     * <b>不报错、不崩溃，只是那一处改动完全没生效</b>。
     */
    private int zonePosition(DuelQuestion.Option o) {
        if (board == null) {
            return -1;
        }
        int c = o.controller();
        if (c < 0 || c > 1) {
            return -1;
        }
        DuelBoard.PlayerBoard pb = board.playerAt(c);
        List<DuelBoard.Zone> zones;
        if (o.location() == FieldCodes.LOCATION_MZONE) {
            zones = pb.monsterZones();
        } else if (o.location() == FieldCodes.LOCATION_SZONE) {
            zones = pb.spellZones();
        } else {
            // 手牌、卡组、墓地、除外、灵摆区上的卡都谈不上「变更表示」。
            return -1;
        }
        if (o.sequence() < 0 || o.sequence() >= zones.size()) {
            return -1;
        }
        return zones.get(o.sequence()).position();
    }

    /**
     * 这张卡现在能做什么，用来在卡面上压一个两字标记；没动作返回 {@code null}。
     *
     * <h2>为什么非要打这个标记</h2>
     * 「攻击」和「改变表示形式」是<b>挂在怪兽上的弹出菜单项</b>，只有在点了那张卡
     * 之后才出现（ygo 也一样：{@code event_handler.cpp:1159-1186} 按
     * {@code cmdFlag} 调 {@code ShowMenu}）。于是不点就完全看不出来这张卡能动——
     * 玩家的结论会是「这个功能没写」。实测这两项一直都是通的
     * （攻击 480/480、变更表示 287/287 个选项都能映射到怪兽格），
     * 缺的只是<b>事前可见性</b>：把动作名直接写在卡面上。
     */
    private String actionTag(int controller, int location, int sequence) {
        if (question == null || submitted) {
            return null;
        }
        for (DuelQuestion.Option o : question.options()) {
            // 位置码 0 的选项不指向任何格子（「进入战斗阶段」这类）。
            if (o.location() == FieldCodes.LOCATION_HAND || o.location() == 0) {
                continue;
            }
            if (o.controller() != controller || o.location() != location
                    || o.sequence() != sequence) {
                continue;
            }
            return shortTag(o);
        }
        return null;
    }

    /**
     * 卡面上那个两字标记。卡宽只有四十来像素，四个字会溢出到邻格上。
     *
     * <p><b>判据是语义编号，不是中文前缀</b>：原先这里是一串
     * {@code label.startsWith("特殊召唤")}、{@code startsWith("盖放")}。
     * 那种写法把「界面显示什么」和「代码怎么判断」绑在了一起——换成英文资源之后
     * 这些分支会全部落到 {@code null}，症状是「卡面上什么标记都不显示」，
     * 而且不报错。现在只看 {@link DuelQuestion.Option#action()}。
     */
    private String shortTag(DuelQuestion.Option o) {
        if (question == null) {
            return null;
        }
        // 同 actionName：编号只在各自的询问里自洽，必须带上类型一起判。
        if (question.type() == MsgType.SELECT_BATTLECMD) {
            return o.action() == DuelQuestion.BATTLE_ATTACK
                    ? DuelText.s(DuelText.TAG_ATTACK) : DuelText.s(DuelText.TAG_ACTIVATE);
        }
        return switch (o.action()) {
            // 与「攻击」区分开：一个是宣告攻击，一个是转成攻击表示。
            case DuelQuestion.IDLE_REPOSITION -> DuelText.s(repositionTagKey(o));
            case DuelQuestion.IDLE_SPSUMMON -> DuelText.s(DuelText.TAG_SP_SUMMON);
            case DuelQuestion.IDLE_MONSTER_SET -> DuelText.s(DuelText.TAG_SET);
            case DuelQuestion.IDLE_SPELL_SET -> DuelText.s(DuelText.TAG_SET);
            case DuelQuestion.IDLE_SUMMON -> DuelText.s(DuelText.TAG_SUMMON);
            case DuelQuestion.IDLE_ACTIVATE_EFFECT -> DuelText.s(DuelText.TAG_ACTIVATE);
            default -> null;
        };
    }

    /** 「变更表示」那四种说法各自对应的<b>两字</b>标记。 */
    private String repositionTagKey(DuelQuestion.Option o) {
        return switch (FieldCodes.repositionKey(zonePosition(o))) {
            case FieldCodes.KEY_REPOSITION_FLIP -> DuelText.TAG_FLIP;
            case FieldCodes.KEY_REPOSITION_TO_DEFENSE -> DuelText.TAG_TO_DEFENSE;
            case FieldCodes.KEY_REPOSITION_TO_ATTACK -> DuelText.TAG_TO_ATTACK;
            default -> DuelText.TAG_REPOSITION;
        };
    }

    /**
     * 这个询问要不要「确认」才作答。判据在 {@link DuelQuestion#needsConfirm()}
     * （纯逻辑，可离线断言），这里只加一层空值保护。
     */
    private boolean needsConfirm() {
        return question != null && question.needsConfirm();
    }

    /**
     * 求和类当前选中的卡能凑出的合计值区间（最小值, 最大值）。
     *
     * <p>每张卡可能有两个可选值，所以「已选合计」不是一个数而是一段区间——
     * 只报一个数会让玩家以为自己算对了，实际内核按另一种取法判定会拒收。
     * 显示成区间既诚实又好用：只要目标值落在区间里，就存在一种取法成立。
     * 这里只是给玩家看的提示，真正的判定仍然在内核移植过来的
     * {@code SumSelect.check} 里（它按子集和精确判断，不只是看区间）。
     */
    private int[] sumRange() {
        int lo = 0;
        int hi = 0;
        for (int idx : chosen) {
            if (question == null || idx >= question.options().size()) {
                continue;
            }
            int[] v = cn.xm1221.ygomc.common.ocg.SumSelect
                    .params(question.options().get(idx).value());
            lo += Math.min(v[0], v[1] > 0 ? v[1] : v[0]);
            hi += Math.max(v[0], v[1]);
        }
        return new int[]{lo, hi};
    }

    /**
     * 鼠标点击。
     *
     * <p>左键：点到的卡/格上若只有一个行动就立刻做；有多个就在光标处弹菜单让玩家挑。
     * 落在空白处且已经选够数则确认——「点外面的空地」是最自然的确认手势。
     * <p>右键：确认（够数时），否则取消。
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 控件永远在最上层：先把这一下给它们，命中就到此为止。
        // 以前左键从没先问过控件——底部那两颗「确认 / 取消」的点击会掉进下面那条
        // 「点空白处＝确认」的路：点「取消」有时变成确认、有时什么都不发生，
        // 正是咩咩说的「取消键时灵时不灵」。
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (question == null || submitted || board == null) {
            return false;
        }
        if (button == 1) {
            // 右键不再当作答（咩咩 2026-10-05）：确认与取消都有独立按键了，
            // 让右键去猜一个内核未必接受的答案只会招来 MSG_RETRY。
            // 它现在只用来收起已经打开的窗口。
            if (!menu.isEmpty()) {
                menu.clear();
                extraMenuRef = null;
                return true;
            }
            if (browse != null) {
                closeBrowse();
            }
            return true;
        }
        if (button != 0) return super.mouseClicked(mouseX, mouseY, button);
        // 弹窗是模态的：它自己的按钮已经在上面那句里吃掉了这一下，这里直接到此为止
        // （再转交一次会绕过下面所有判定）。
        if (popup != null) {
            return true;
        }
        // 阶段条的判定在下面（卡名列表 / 查看窗 / 卡片菜单之后）——
        // 绘制顺序是 阶段条 < 列表/查看窗/菜单，命中顺序必须跟着一样。
        // 菜单开着的时候，点击只作用于菜单
        if (!menu.isEmpty()) {
            int picked = menuHit(mouseX, mouseY);
            menu.clear();
            PileRef extra = extraMenuRef;
            extraMenuRef = null;
            if (picked == MENU_VIEW_LIST) {
                // 「查看列表」＝只读的 browse 窗口（咩咩要的第二项）。
                if (extra != null) {
                    openBrowse(extra);
                }
            } else if (picked == MENU_SUMMON) {
                // 「特殊召唤」＝把卡名列表摊开，让玩家自己挑一只。
                // 这里绝不自作主张挑一张——额外卡组特殊召唤不是强制的（咩咩：不许替玩家做决定）。
                extraListOpen = true;
                rebuild();
            } else if (picked >= 0) {
                onOption(picked);
            }
            return true;
        }
        // 卡列表开着时，它范围内一律由列表处理：面板底下的格子不该还能点到。
        if (list != null && list.panel().contains(mouseX, mouseY)) {
            int row = list.indexAt(mouseX, mouseY);
            if (row >= 0 && row < piles.size()) {
                onOption(piles.get(row).optionIndex());
            } else if (row == piles.size() && cancelIndex() >= 0) {
                // 列表末尾那一行是「取消」。ygo 的选择窗口左下角也留了一个取消键
                // （ClientField::ShowSelectCard，client_field.cpp:431-527）：
                // 从额外卡组特殊召唤不是强制的，不给退路就等于逼玩家挑一只。
                cancel();
            }
            return true;
        }
        // 正在查看牌堆：点窗口外收起（窗口右上角有「收起」键，右键也能收）；
        // 点窗口内只当「在看」。
        if (browse != null) {
            if (!browse.list().panel().contains(mouseX, mouseY)) {
                closeBrowse();
            } else {
                return true;
            }
        }

        // 阶段条在绘制上比场地高、比卡名列表/查看窗/卡片菜单低，命中顺序照同一个次序来。
        for (int i = 0; i < PHASES.length; i++) {
            int option = phaseOption(PHASES[i]);
            if (option >= 0 && field().phase(i).contains(mouseX, mouseY)) {
                onOption(option);
                return true;
            }
        }
        List<DuelTargets.Target> targets = fieldTargets();
        List<Integer> hits = DuelTargets.optionIndicesAt(targets, mouseX, mouseY);
        if (!hits.isEmpty()) {
            if (hits.size() == 1 && !actionMenu()) {
                onOption(hits.get(0));
            } else {
                // ── 行动一律从菜单里选出来，哪怕这张卡只有一项可做 ──
                //
                // ygo 的 `ClientField::ShowMenu(int flag, ...)`（event_handler.cpp:2260-2299）
                // 是按被点那张卡的 `cmdFlag` 摆菜单的：发动(COMMAND_ACTIVATE)、召唤、
                // 特殊召唤、盖放、变更表示、攻击——**没有「点一下就直接发动」这条路**。
                // 效果发动必须是玩家从菜单里挑出来的，误触不该直接生效。
                //
                // 但**选择类**询问（选墓地/选祭品/选攻击目标…）不走菜单：那在 ygo 里是
                // `selectable_cards`，点卡就是勾选。两者在 ygo 里由 cmdFlag 有无区分，
                // 在我们这里由询问类型区分。
                menu.clear();
                menu.addAll(hits);
                menuX = (int) mouseX;
                menuY = (int) mouseY;
            }
            return true;
        }
        // 自由行动时点牌堆：额外卡组有可特殊召唤的怪兽就先弹它自己的菜单
        // （特殊召唤 / 查看列表），其余情况＝查看那一堆的内容（墓地/除外/额外卡组）。
        // 只在没有选择列表时这么做：有列表时那一堆正是给你挑的，
        // 「挑」和「看」同时弹两个窗口只会互相挡。
        PileRef ref = pileAt(mouseX, mouseY);
        if (button == 0 && ref != null && ref.location() == FieldCodes.LOCATION_EXTRA
                && question.extraNeedsMenu(ref.seat())) {
            // 咩咩 2026-10-05：额外卡组有可特殊召唤的怪兽时不要直接把列表摊开，
            // 先让玩家自己挑「特殊召唤」还是「查看列表」。ygo 那边点额外卡组是直接摆
            // 卡名列表（ClientField::ShowSelectCard，client_field.cpp:431-527），
            // 这里多一道菜单是咩咩要的：先把「能做什么」摆出来，再让玩家决定。
            // 这里刻意不吃 list == null 这个条件：亮了黄框就得点得动，
            // 否则会出现「这一堆亮着却什么也不发生」。
            menu.clear();
            menu.add(MENU_SUMMON);
            menu.add(MENU_VIEW_LIST);
            menuX = (int) mouseX;
            menuY = (int) mouseY;
            extraMenuRef = ref;
            return true;
        }
        if (list == null && button == 0 && ref != null && PileBrowse.browsable(ref.location())) {
            openBrowse(ref);
            return true;
        }
        if (!spatial()) {
            // 无落点的询问（是/否、发动哪个效果、宣言种族属性）交给小窗按钮处理
            return super.mouseClicked(mouseX, mouseY, button);
        }
        // 点在牌桌空白处：选够了就确认。「点外面的空地」是最自然的确认手势，
        // 也是 ygo 客户端的做法；没选够则什么都不做——不弹窗、不催。
        if (needsConfirm() && countsOk()) {
            submit();
        }
        return true;
    }

    /**
     * 常驻的「确认 / 取消」两个键，摆在右下角。
     *
     * <p>咩咩 2026-10-05：确认键要单独做出来、不再用右键；取消也一样，
     * <b>只给真的能退的操作</b>。
     *
     * <p>ygo 那边是<b>一个</b>会变字的 {@code btnCancelOrFinish}
     * （{@code ClientField::ShowCancelOrFinishButton}，<b>event_handler.cpp:2348-2367</b>
     * ——上一条注释把文件写成了 client_field.cpp，是错的：函数定义在 event_handler.cpp）：
     * op=1 取消 / op=2 完成 / op=0 藏起来。它的可见性由询问自带的 {@code select_cancelable}
     * 与「选了几个」共同决定（duelclient.cpp:1675-1681、event_handler.cpp:1320-1326），
     * 我们按咩咩要的做两个键，判据沿用同一套，见 {@link DuelScreenFlow#showFinish} /
     * {@link DuelScreenFlow#showCancel}。
     */
    private void buildAnswerButtons() {
        if (question == null || submitted) {
            return;
        }
        int bw = ANSWER_BTN_W;
        int bh = ANSWER_BTN_H;
        int gap = ANSWER_BTN_GAP;
        int y = height - bh - 4;
        int x = width - bw - 6;
        int yes = yesOptionIndex();
        int no = declineOptionIndex();
        int sole = question.soleOption();
        boolean ask = chainAskStage();
        // 取消键要交出的那一项：取消项（取值 -1）优先，其次「否」。
        int cancelPick = cancelIndex() >= 0 ? cancelIndex() : no;

        if (ask || sole >= 0 || yes >= 0 || needsConfirm()) {
            confirm = Button.builder(DuelText.c(DuelText.BUTTON_CONFIRM), b -> {
                if (ask) {
                    // 连锁第一段：同意＝要发动，之后才去点亮候选。
                    agreeChain();
                } else if (sole >= 0) {
                    onOption(sole);
                } else if (yes >= 0) {
                    onOption(yes);
                } else {
                    submit();
                }
            }).bounds(x, y, bw, bh).build();
            addRenderableWidget(confirm);
            x -= bw + gap;
        }
        if (ask || yes >= 0 || question.cancelable()) {
            // 只有询问真的给了退路才造这颗键。判据是【消息自带的可取消标志】
            // （{@code select_cancelable}，duelclient.cpp:1623/1688），不是「选项表里有没有取消项」：
            // 后者只在部分询问类型里成立，正是「取消键时灵时不灵」的来源。
            cancelBtn = Button.builder(DuelText.c(DuelText.BUTTON_CANCEL), b -> {
                if (ask) {
                    cancel();
                } else if (cancelPick >= 0) {
                    onOption(cancelPick);
                } else {
                    cancel();
                }
            }).bounds(x, y, bw, bh).build();
            addRenderableWidget(cancelBtn);
        }
        refreshAnswerButtons();
    }

    /**
     * 连锁的第一段：先问「XX时，是否发动效果？」，同意之后才让候选亮起来。
     *
     * <p>只管<b>非必发</b>的连锁（{@code DuelQuestion.cancelable()} 就是 {@code !forced}，
     * 见 {@code DuelQuestion.chain}）。必发连锁不摆这一问：ygo 那边弹窗那一段
     * 整个包在 {@code if(!chain_forced)} 里（duelclient.cpp:1871-1879），
     * 必发只用提示条写「请选择要发动的效果」（:1858-1862）。
     */
    private boolean chainAskStage() {
        return question != null && !submitted && question.isChainQuestion()
                && question.cancelable() && !chainAgreed;
    }

    private void agreeChain() {
        chainAgreed = true;
        rebuild();
    }

    /**
     * 按 ygo 那颗会变字的按钮，决定这两颗键此刻该不该出现。
     *
     * <p>两处要点（都是咩咩 2026-10-05 报的「取消键时灵时不灵」）：
     * <ul>
     *   <li>确认键<b>够条件才出现</b>，不是「摆着但是灰的」；</li>
     *   <li>取消键在<b>已经选了东西之后消失</b>（ygo event_handler.cpp:1320-1326）。</li>
     * </ul>
     */
    private void refreshAnswerButtons() {
        boolean ask = chainAskStage();
        boolean yesNo = question != null && DuelQuestion.isYesNo(question.type());
        boolean ready = question != null && (countsOk() || question.soleOption() >= 0);
        if (confirm != null) {
            confirm.visible = DuelScreenFlow.showFinish(ask, yesNo, ready);
            confirm.active = confirm.visible;
        }
        if (cancelBtn != null) {
            cancelBtn.visible = DuelScreenFlow.showCancel(ask, yesNo,
                    question != null && question.cancelable(), chosen.isEmpty());
            cancelBtn.active = cancelBtn.visible;
        }
    }

    /** 是/否类里「是」那一项的下标（取值 1）；不是是/否类则 -1。 */
    private int yesOptionIndex() {
        if (question == null || !DuelQuestion.isYesNo(question.type())) {
            return -1;
        }
        return question.indexOfValueOr(1);
    }

    /** 是/否类里「否」那一项的下标（取值 0）；不是是/否类则 -1。取消键让它当「拒绝」。 */
    private int declineOptionIndex() {
        if (question == null || !DuelQuestion.isYesNo(question.type())) {
            return -1;
        }
        return question.indexOfValueOr(0);
    }

    /**
     * 这一格上现在有可做的事吗——有就把那张卡点亮。
     *
     * <p>ygo 把可选卡画成高亮框（{@code DrawSelectionLine(..., 0xffffff00)}，
     * drawing.cpp:583-588），牌堆/区域同理（drawing.cpp:342-357）。咩咩 2026-10-05：
     * 「同意后让卡/墓地/除外亮起」。判据只问「这一格上有没有选项」，与卡面那个两字标记
     * （{@link #actionTag}）分开：标记要挑得出两字短名，点亮只看有没有。
     */
    private boolean hasOptionAt(int controller, int location, int sequence) {
        if (question == null || submitted) {
            return false;
        }
        for (DuelQuestion.Option o : question.options()) {
            if (o.location() == 0) {
                continue;
            }
            if (o.controller() == controller && o.location() == location
                    && o.sequence() == sequence) {
                return true;
            }
        }
        return false;
    }

    /** 这一堆（墓地/除外/额外）上有没有选项。取消项不算——它不是那一堆里的卡。 */
    private boolean hasPileOption(int seat, int location) {
        if (question == null || submitted) {
            return false;
        }
        // 判据在 DuelQuestion 里（可离线断言）。界面与「点得出菜单」共用同一条规则，
        // 所以亮起来的堆一定点得出东西，不会出现「亮了却点不动」。
        return question.pileOptionCountAt(seat, location) > 0;
    }

    /** 菜单每行的宽度（由最长的行动名决定）。 */
    /**
     * 菜单某一行的字。
     *
     * <p>{@code menu} 里既可能是选项下标，也可能是这份菜单自己的动作
     * （{@link #MENU_SUMMON}/{@link #MENU_VIEW_LIST}，负数）。算宽度与画字两处都走这里，
     * 免得哨兵只在其中一处被认出来——那会画成一条「?」，或者量出一个错的宽度。
     */
    private String menuLabel(int idx) {
        if (idx == MENU_SUMMON) {
            return DuelText.s(DuelText.MENU_SP_SUMMON);
        }
        if (idx == MENU_VIEW_LIST) {
            return DuelText.s(DuelText.MENU_VIEW_LIST);
        }
        List<DuelQuestion.Option> options = question == null ? List.of() : question.options();
        return idx >= 0 && idx < options.size() ? shortLabel(options.get(idx))
                : DuelQuestion.Option.FALLBACK_LABEL;
    }

    /**
     * 卡片菜单的左上角。
     *
     * <p>绘制与命中共用这两个数：各写一套的症状是「看得见但点不中」，
     * 而那和「这个操作不合法」在界面上长得一模一样。
     *
     * <p>横向夹在<b>场地</b>内，不夹到整屏：夹到整屏的话，贴右边缘弹出的菜单会滑到
     * 右侧信息面板底下，画在面板下面看不见、点也点不到。
     */
    private int menuOriginX(int w) {
        return Math.min(Math.max(2, menuX), Math.max(2, field().fieldW() - w - 2));
    }

    private int menuOriginY(int rows) {
        int rowH = DuelTargets.MENU_ROW_H + DuelTargets.MENU_ROW_GAP;
        return Math.min(Math.max(2, menuY), Math.max(2, height - rows * rowH - 2));
    }

    private int menuWidth() {
        int w = 60;
        for (int idx : menu) {
            w = Math.max(w, font.width(menuLabel(idx)) + 14);
        }
        return Math.min(w, Math.max(60, width - 8));
    }

    /** 命中菜单第几行；没命中返回 -1。几何与 {@link #drawMenu} 用的是同一份。 */
    private int menuHit(double mx, double my) {
        int w = menuWidth();
        int x = menuOriginX(w);
        int y = menuOriginY(menu.size());
        for (int i = 0; i < menu.size(); i++) {
            FieldLayout.Rect r = DuelTargets.menuRow(x, y, w, i);
            if (mx >= r.x() && mx < r.right() && my >= r.y() && my < r.bottom()) {
                return menu.get(i);
            }
        }
        return -1;
    }

    private boolean countsOk() {
        if (question == null) {
            return false;
        }
        if (question.mode() == DuelQuestion.Mode.COUNTERS) {
            return java.util.Arrays.stream(counterAmounts).sum() == question.min();
        }
        if (question.mode() == DuelQuestion.Mode.SORT) return chosen.size() == question.options().size();
        return question.canSubmit(chosen.stream().mapToInt(Integer::intValue).toArray());
    }

    private void onOption(int index) {
        DuelQuestion q = question;
        if (q == null || submitted) {
            return;
        }
        if (!needsConfirm()) {
            send(q, new int[]{index});
            return;
        }
        if (q.mode() == DuelQuestion.Mode.COUNTERS) {
            if (counterAmounts.length != q.options().size()) counterAmounts = new int[q.options().size()];
            counterAmounts[index] = counterAmounts[index] >= q.options().get(index).value()
                    ? 0 : counterAmounts[index] + 1;
            // 指示物这类也要刷新确认键的可用状态：以前这里提前 return，
            // 于是加满/清零之后那颗键一直是老样子。
            if (confirm != null) {
                confirm.active = countsOk();
            }
            return;
        }
        if (!chosen.remove(index)) {
            chosen.add(index);
        }
        // ygo：到上限、或到下限且候选已被全选完 →【直接送出】，不再让玩家多按一次
        // （event_handler.cpp:1307-1320）。UNSELECT 更干脆：点一下就送（:1365-1369）。
        if (q.mode() == DuelQuestion.Mode.MULTI
                && (chosen.size() >= q.max()
                    || (chosen.size() >= q.min() && chosen.size() == q.selectableCount())
                    || (q.sendsOnClick() && !chosen.isEmpty()))) {
            submit();
            return;
        }
        refreshAnswerButtons();
    }

    private void submit() {
        DuelQuestion q = question;
        if (q == null || submitted) {
            return;
        }
        List<Integer> list = new ArrayList<>(chosen);
        int[] picked = new int[list.size()];
        for (int i = 0; i < picked.length; i++) {
            picked[i] = list.get(i);
        }
        if (q.mode() == DuelQuestion.Mode.SORT) {
            // 点击顺序 -> 每张原候选的最终次序，这一步在数据模型里（那里能离线验证）
            dispatchOrReport(q, () -> q.sortResponse(picked));
            return;
        }
        send(q, picked);
    }

    /** 交出数据模型已经拼好的应答。<b>编码仍然只发生在 {@code DuelQuestion} 里。</b> */
    private void dispatch(DuelQuestion q, cn.xm1221.ygomc.common.ocg.Responder.Response r) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        YgomcNet.sendAnswer(
                DuelWire.encodeAnswer(r.isBytes()
                        ? DuelWire.Responder2.of(r.bytes())
                        : DuelWire.Responder2.of(r.value())),
                mc.level == null ? null : mc.level.registryAccess());
        submitted = true;
        rebuild();
    }

    /** 交出一个「怎么拼」可能失败的应答：失败必须说出来，而且不能把客户端带崩。 */
    private void dispatchOrReport(DuelQuestion q,
                                  java.util.function.Supplier<cn.xm1221.ygomc.common.ocg.Responder.Response> build) {
        try {
            dispatch(q, build.get());
        } catch (RuntimeException e) {
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        DuelText.c(DuelText.ERROR_CANNOT_SEND, e.getMessage()), false);
            }
        }
    }

    /**
     * 「取消」对应的<b>选项下标</b>，没有则 -1。
     *
     * <p>原先直接把字面量 {@code -1} 当选项下标传下去。取消在编码上不是
     * 「下标 -1」，而是「某个取值为 -1 的选项的下标」——传错的下场实测是
     * 「这个操作发不出去：构造应答失败：要选 0 个格子，实得 1」。
     */
    private int cancelIndex() {
        if (question == null) {
            return -1;
        }
        List<DuelQuestion.Option> options = question.options();
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).isCancel()) {
                return i;
            }
        }
        return -1;
    }

    private void cancel() {
        int ci = cancelIndex();
        if (ci >= 0) {
            send(question, new int[]{ci});
            return;
        }
        // 不能取消：右键就没有出口，什么都没发生。这里刻意不静默发一个
        // 必然被打回的应答——那会触发内核重发，最后以 RETRY_STORM_LIMIT 收场。
        chosen.clear();
        java.util.Arrays.fill(counterAmounts, 0);
    }

    /**
     * 唯一的出口：问 {@code response} 要字节，再交给网络层。
     *
     * <p>刻意<b>不</b>自己拼字节——每种询问的编码约定都不一样
     * （有下标、有取值、有 3 字节坐标、有计数器数组），
     * 在这里重写一遍就是给自己造第二个编码器。
     */
    private void send(DuelQuestion q, int... picked) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        cn.xm1221.ygomc.common.ocg.Responder.Response r;
        try {
            r = q.mode() == DuelQuestion.Mode.COUNTERS ? q.counterResponse(counterAmounts) : q.response(picked);
        } catch (RuntimeException e) {
            // 构造失败要说出来。静默什么都不发，症状就是「点了没反应」，
            // 和网络不通长得一模一样。
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        DuelText.c(DuelText.ERROR_CANNOT_SEND, e.getMessage()), false);
            }
            return;
        }
        YgomcNet.sendAnswer(
                DuelWire.encodeAnswer(r.isBytes()
                        ? DuelWire.Responder2.of(r.bytes())
                        : DuelWire.Responder2.of(r.value())),
                mc.level == null ? null : mc.level.registryAccess());
        submitted = true;
        rebuild();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * 铺一层半透明底，<b>不调 super</b>。
     *
     * <p>1.20.5 起 {@code Screen.renderBackground} 会给整个屏幕加一层模糊后处理。
     * 牌桌要看的是卡面，模糊在这里是纯反效果，还会把格子边缘糊掉，
     * 看起来像「渲染坏了」。
     */
    /**
     * 原版背景（压暗 + 高斯模糊）一律不画。
     *
     * <p><b>这里就是「整体蒙了一层膜」的根因。</b>1.21 的 {@code Screen.render}
     * 自己会在<b>最后</b>调一次这个方法（4 参签名就是为此加的），而这里原先填的是
     * {@code 0xA03C5566}——63% 不透明的蓝灰。在旧版（背景由各界面自己先画）它是
     * 铺在底下的底色，到了 1.21 就变成盖在<b>整个界面之上</b>的一层膜：
     * 牌桌、卡图、文字全被洗白一层，看着就是「渲染很奇怪」。
     *
     * <p>所以覆写留空，底色改由 {@link #render} 开头<b>自己先铺</b>——
     * 位置对了，和世界背景的对比度也还在。
     */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 有意为空：见上面注释，这层现在会盖在最上面。
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 底色自己铺。不能靠 renderBackground——它现在被 Screen.render 放在最后调用，
        // 会盖在整个界面之上（见那个方法的注释）。不压成近乎全黑：牌桌本身有牌垫，
        // 底色只需要把世界背景压下去一点。
        g.fill(0, 0, width, height, 0xA03C5566);

        FieldLayout L = field();
        if (board == null) {
            // 面板现在在右侧、panel().y() 恒为 0，沿用旧写法会画到屏幕外。
            g.drawString(font, DuelText.s(DuelText.STATUS_NO_BOARD), L.x0(), L.oppHand().y() + 20, 0xFFFFFF);
        } else {
            drawField(g, L);
        }
        drawPhases(g, L);
        drawOverlay(g, L, mouseX, mouseY);
        drawCardList(g, mouseX, mouseY);
        drawBrowse(g, mouseX, mouseY);
        drawStatusBar(g, L);
        drawLifeBadges(g, L);
        // 图层顺序（咩咩 2026-10-05 报的「图层问题」）：
        //   底色 → 场地 → 阶段条 → 场内高亮 → 卡名列表 → 查看窗 → 状态条 → LP 徽章
        //   → 卡片菜单 → 右侧信息面板 → 弹窗 → 控件（按钮永远最上）。
        // 修掉的三处：弹窗原来画在信息面板【之前】（面板压掉弹窗右半边）；卡片菜单画得
        // 比状态条还早（贴着手牌行弹出的菜单被压住，看着像「点了没反应」）；
        // 必发提示条已按咩咩要求不再显示。
        drawMenu(g);
        drawInfoPanel(g, L, mouseX, mouseY);
        drawPopup(g);
        super.render(g, mouseX, mouseY, partialTick);
    }

    /**
     * 画整张场地。纵向从对手到我是：
     * <pre>
     *   对手手牌（卡背）
     *   对手魔陷行   [额外卡组] [S/T×5] [卡组]
     *   对手怪兽行   [场地区]   [M×5]   [墓地]
     *   额外怪兽区（中线，双方共用）＋ 双方除外区
     *   我方怪兽行   [场地区]   [M×5]   [墓地]
     *   我方魔陷行   [额外卡组] [S/T×5] [卡组]
     *   我方手牌
     * </pre>
     */
    /**
     * 这个格子用哪张材质。
     *
     * <p>位置常量直接用内核的值（{@code MZONE = 0x04}、{@code SZONE = 0x08}）：
     * 这里只是为了一张图分个类，不值得为此把 {@code Msg.Location} 引进来。
     * 场地/灵摆区是魔陷区的序号 5、6（{@code playerop.cpp} 与 {@code FieldLayout}
     * 都按这个分），它们跟普通魔陷区形状不同，单独给一张。
     */
    private static String zoneTexture(int location, int sequence) {
        if (location == 0x04) {
            return FieldTextureSpec.ZONE_MONSTER;
        }
        if (location == 0x08 && sequence >= 5) {
            return FieldTextureSpec.ZONE_FIELD;
        }
        return FieldTextureSpec.ZONE_SPELL;
    }

    private void drawField(GuiGraphics g, FieldLayout L) {
        // 牌堆格子每帧重记：布局随窗口变，旧矩形会点不准。
        pileRefs.clear();
        // 牌垫：把整张场地垫在一层布面上，而不是让格子直接浮在暗背景上。
        // 格子本身是半透明的，没有垫子就会和世界背景糊在一起，边界看不出来。
        FieldLayout.Rect[] bands = L.bands();
        int left = L.x0() - 4;
        int right = L.x0() + bands[0].w() + 4;
        int top = bands[0].y() - 4;
        int bottom = bands[bands.length - 1].bottom() + 4;
        if (!FieldTextures.tile(g, FieldTextureSpec.MAT, new FieldLayout.Rect(left, top, right - left, bottom - top))) {
            g.fill(left, top, right, bottom, 0xFF356B52);
        }
        outline(g, new FieldLayout.Rect(left, top, right - left, bottom - top), 0xFF78C8A4);
        // 中线：分隔双方场地，也让额外怪兽区看起来是「两边共用」
        int mid = L.extraMonsterRow().y() + L.extraMonsterRow().h() / 2;
        g.fill(left + 2, mid, right - 2, mid + 1, 0x60357A5A);

        graphicHand(g, L, opponent(), L.oppHand(), true);
        spellRow(g, L, opponent(), L.oppSpellRow());
        monsterRow(g, L, opponent(), L.oppMonsterRow());
        extraMonsterZones(g, L, opponent());
        removedPiles(g, L);
        monsterRow(g, L, me(), L.myMonsterRow());
        spellRow(g, L, me(), L.mySpellRow());
        graphicHand(g, L, me(), L.myHand(), false);
    }

    private void monsterRow(GuiGraphics g, FieldLayout L, DuelBoard.PlayerBoard p,
                            FieldLayout.Rect band) {
        if (p == null) {
            return;
        }
        List<DuelBoard.Zone> zones = p.monsterZones();
        boolean mine = p == me();
        // 座位号必须用 mySeat：me() 是 playerAt(mySeat)，玩家不一定是 0 号座。
        // 写死 0/1 的话，坐 1 号座那半边一张动作标签都不会出现。
        int side = mine ? mySeat : 1 - mySeat;
        zone(g, L, at(p.spellZones(), 5), L.col(band, mine ? 0 : 6),
                0xFF3E7392, 0x88FFFFFF, false, side, FieldCodes.LOCATION_SZONE, 5);
        for (int i = 0; i < FieldLayout.MAIN_ZONES; i++) {
            zone(g, L, at(zones, i), L.col(band, p == me() ? 1 + i : 5 - i), 0x33FFFFFF, 0x66FFFFFF, true,
                    side, FieldCodes.LOCATION_MZONE, i);
        }
        pile(g, L, DuelText.s(DuelText.PILE_GRAVE, p.graveCount()), L.col(band, mine ? 6 : 0), 0xFF24485C, topCard(p.grave()),
                mine ? mySeat : 1 - mySeat, FieldCodes.LOCATION_GRAVE);
    }

    private void spellRow(GuiGraphics g, FieldLayout L, DuelBoard.PlayerBoard p,
                          FieldLayout.Rect band) {
        if (p == null) {
            return;
        }
        List<DuelBoard.Zone> zones = p.spellZones();
        int side = p == me() ? mySeat : 1 - mySeat;
        pile(g, L, DuelText.s(DuelText.PILE_EXTRA, p.extraCount()), L.col(band, p == me() ? 0 : 6), 0xFF24485C, BACK_ART,
                p == me() ? mySeat : 1 - mySeat, FieldCodes.LOCATION_EXTRA);
        for (int i = 0; i < FieldLayout.MAIN_ZONES; i++) {
            zone(g, L, at(zones, i), L.col(band, p == me() ? 1 + i : 5 - i), 0x33DFFFD8, 0x66DFFFD8, false,
                    side, FieldCodes.LOCATION_SZONE, i);
        }
        pile(g, L, DuelText.s(DuelText.PILE_DECK, p.deckCount()), L.col(band, p == me() ? 6 : 0), 0xFF24485C, BACK_ART,
                p == me() ? mySeat : 1 - mySeat, FieldCodes.LOCATION_DECK);
    }

    /**
     * 额外怪兽区：中线中间列 2 格。
     *
     * <p>双方共用，所以不按归属方取——哪一方占了就画谁的。
     */
    private void extraMonsterZones(GuiGraphics g, FieldLayout L, DuelBoard.PlayerBoard first) {
        for (int i = 0; i < FieldLayout.EXTRA_MONSTER_ZONES; i++) {
            FieldLayout.Rect r = L.extraMonster(i);
            // 额外怪兽区是【双方共用】的，哪一方占了就画谁的：只读一方的
            // mzone 5/6 会漏掉对手摆在那里的怪，看起来就像卡图没画出来。
            DuelBoard.Zone z = null;
            int zc = -1;
            int zs = -1;
            for (DuelBoard.PlayerBoard pb : new DuelBoard.PlayerBoard[]{first, me(), opponent()}) {
                if (pb == null) {
                    continue;
                }
                int seq = pb == me() ? 5 + i : 6 - i;
                DuelBoard.Zone c = at(pb.monsterZones(), seq);
                if (c != null && c.occupied()) {
                    z = c;
                    zc = pb == me() ? 0 : 1;
                    zs = seq;
                    break;
                }
            }
            if (z != null) {
                cardFace(g, L, z, r, true, zc, FieldCodes.LOCATION_MZONE, zs);
            } else {
                g.fill(r.x(), r.y(), r.right(), r.bottom(), 0x33C8A0E8);
                outline(g, r, 0x80C8A0E8);
                g.drawString(font, "EX", r.x() + 2, r.y() + 1, 0xB0E0C8FF);
            }
        }
    }

    /** 双方除外区：摆在中线行的两端（中线行只有中间两格有内容，两端是空的）。 */
    private void removedPiles(GuiGraphics g, FieldLayout L) {
        FieldLayout.Rect band = L.extraMonsterRow();
        DuelBoard.PlayerBoard me = me();
        DuelBoard.PlayerBoard op = opponent();
        if (op != null) {
            pile(g, L, DuelText.s(DuelText.PILE_REMOVED, op.removedCount()), L.col(band, 0), 0xFF3A4256, topCard(op.removed()),
                    1 - mySeat, FieldCodes.LOCATION_REMOVED);
        }
        if (me != null) {
            pile(g, L, DuelText.s(DuelText.PILE_REMOVED, me.removedCount()), L.col(band, 6), 0xFF3A4256, topCard(me.removed()),
                    mySeat, FieldCodes.LOCATION_REMOVED);
        }
    }

    private void zone(GuiGraphics g, FieldLayout L, DuelBoard.Zone z,
                      FieldLayout.Rect r, int emptyFill, int emptyBorder, boolean monster,
                      int controller, int location, int sequence) {
        if (z == null || !z.occupied()) {
            // 空格子画成半透明的「槽」而不是实心暗块：实心块在牌垫上看着像
            // 已经有卡了，会让人以为格子被占着。
            // 有材质就用材质（半透明照旧由 PNG 自己的 alpha 决定），没有才退回色块。
            if (!FieldTextures.slot(g, zoneTexture(location, sequence), r)) {
                g.fill(r.x(), r.y(), r.right(), r.bottom(), emptyFill);
                outline(g, r, emptyBorder);
            }
            return;
        }
        cardFace(g, L, z, r, monster, controller, location, sequence);
        if (hasOptionAt(controller, location, sequence)) {
            // 黄框＝这里现在能做点什么（ygo 的高亮色就是 0xffffff00）。
            outline(g, r, 0xFFFFFF00);
        }
    }

    /**
     * 画一张场上的卡。
     *
     * <p>卡号来自 {@code DuelBoard.Zone.code()}（由 {@code Ocg.queryFieldCard}
     * 按可见性过滤后填好）。卡号为 0 有两种情形，都不该画卡面：
     * 里侧盖牌，以及对手的隐藏卡——后者连卡号都没有，所以不可能泄出去。
     *
     * <p>守备表示<b>横放</b>（旋转 90°），与 ygo 客户端一致：
     * 光在卡面上压一条横杠看不出是「守备」还是「这张卡长这样」。
     */
    private void cardFace(GuiGraphics g, FieldLayout L, DuelBoard.Zone z, FieldLayout.Rect r,
                          boolean monster, int controller, int location, int sequence) {
        int code = z.code() & 0x7fffffff;
        if (monster && !z.attack()) {
            rotated(g, z.faceUp() ? code : 0, r);
        } else if (z.faceUp() && code != 0) {
            CardArt.draw(g, code, r.x(), r.y(), r.w(), r.h(), null);
            outline(g, r, 0xFFFFFFFF);
        } else if (z.faceUp()) {
            // 表侧但卡号未知（旧线格式的帧）：色块 + 边框，至少能看出表示形式
            g.fill(r.x(), r.y(), r.right(), r.bottom(), 0xFF3A70C0);
            outline(g, r, 0xFFFFFFFF);
        } else {
            CardArt.drawBack(g, r.x(), r.y(), r.w(), r.h());
        }
        String tag = actionTag(controller, location, sequence);
        if (tag != null && r.w() >= 12) {
            // 在卡面下沿压一条深色带写动作名。不做这件事，玩家只能靠「挨个点一遍」
            // 才能发现这张卡现在能攻击／能改变表示形式。
            int ty = r.bottom() - 9;
            g.fill(r.x(), ty - 1, r.right(), r.bottom(), 0xC8101820);
            g.drawCenteredString(font, tag, r.x() + r.w() / 2, ty, 0xFFFFE060);
        }
        if (z.overlayCount() > 0) {
            // 有动作标签时往上让一行：那颗菱形和两字标签都挤在卡下沿会糊成一团。
            g.drawString(font, "◆" + z.overlayCount(), r.x() + 1,
                    r.bottom() - (tag != null ? 19 : 10), 0xFFD060);
        }
    }

    /** 横放（守备表示）：绕格子中心转 90°，卡按「宽=格高、高=格宽」画。 */
    private void rotated(GuiGraphics g, int code, FieldLayout.Rect r) {
        int cw = r.w();
        int ch = r.h();
        g.pose().pushPose();
        g.pose().translate(r.x() + r.w() / 2f, r.y() + r.h() / 2f, 0f);
        g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(90f));
        if (code != 0) {
            CardArt.draw(g, code, -cw / 2, -ch / 2, cw, ch, null);
        } else {
            CardArt.drawBack(g, -cw / 2, -ch / 2, cw, ch);
        }
        g.pose().popPose();
        outline(g, r, 0xFFFFFFFF);
    }

    /**
     * 手牌行。
     *
     * <p>我方手牌是<b>明牌</b>、对手手牌是卡背。卡号来自
     * {@code PlayerBoard.hand()}：本地座位每张都是真卡号，对手的每张都是 0
     * ——对手手牌卡号在内核里<b>根本没被查出来</b>（那一整块用
     * {@code FLAG_HIDDEN} 只问表示形式），所以这里即使写错也泄不出去。
     *
     * <p>旧线格式（v1）的帧里这个列表是空的，此时退回按 {@code handCount()}
     * 画卡背，而不是当作「没有手牌」——那会让人以为手牌丢了。
     */
    private void graphicHand(GuiGraphics g, FieldLayout L, DuelBoard.PlayerBoard p,
                             FieldLayout.Rect band, boolean opponent) {
        if (p == null) {
            return;
        }
        int ty = band.y() + Math.max(0, (band.h() - 8) / 2);
        g.drawString(font, DuelText.s(opponent ? DuelText.HAND_OPPONENT : DuelText.HAND_MINE, p.handCount()),
                band.x(), ty, opponent ? 0xFFB0B0B0 : 0xFFFFFFFF);
        int w = Math.max(6, (int) (band.h() / (86f / 59f)));
        List<DuelBoard.Zone> hand = p.hand();
        int n = hand.isEmpty() ? p.handCount() : hand.size();
        int room = n;
        for (int i = 0; i < n && i < room; i++) {
            FieldLayout.Rect r = DuelTargets.handCard(L, band, i, Math.min(n, room));
            // 对手手牌恒为卡背：连卡号都不取
            int code = opponent || i >= hand.size() ? 0 : hand.get(i).code() & 0x7fffffff;
            if (code != 0) {
                CardArt.draw(g, code, r.x(), r.y(), r.w(), r.h(), null);
            } else {
                CardArt.drawBack(g, r.x(), r.y(), r.w(), r.h());
            }
            // 可选的牌也点亮黄框（咩咩：让卡亮起）。
            if (!opponent && hasOptionAt(mySeat, FieldCodes.LOCATION_HAND, i)) {
                outline(g, r, 0xFFFFFF00);
            }
            // 手牌也一样要标出「现在能做它什么」：召唤、盖放、发动三个动作
            // 全是从手牌出发的，不标的话同样只能靠挨个点一遍才能发现。
            // 对手手牌不标——那些动作不归我们决定。
            if (!opponent) {
                String tag = actionTag(mySeat, FieldCodes.LOCATION_HAND, i);
                // 手牌是叠着排的，只有露出足够宽的那几张才写得下两个字。
                if (tag != null && r.w() >= 18) {
                    int tagY = r.bottom() - 9;
                    g.fill(r.x(), tagY - 1, r.right(), r.bottom(), 0xC8101820);
                    g.drawCenteredString(font, tag, r.x() + r.w() / 2, tagY, 0xFFFFE060);
                }
            }
        }
        if (n > room) {
            g.drawString(font, "+" + (n - room), band.x() + 78 + room * (w + 1) + 2, ty, 0xFFD0D0D0);
        }
    }

    /** {@code pile} 的 art 参数：不画牌面，只留色块。 */
    private static final int NO_ART = -1;

    /** {@code pile} 的 art 参数：画牌背（看不见正面的堆）。 */
    private static final int BACK_ART = 0;

    /**
     * 侧格 + 画在框里的那张牌。
     *
     * <p>牌堆放一张牌上去是为了「一眼看出这是什么堆、里面是什么」：卡组与额外卡组放
     * <b>牌背</b>（本来就不该看见正面，额外卡组连自己也是盖着的），墓地与除外放
     * <b>最顶上那张</b>——ygo 也是把墓地/除外的顶端卡摊在堆上的。
     *
     * @param art      {@link #NO_ART} 不画，{@link #BACK_ART} 画牌背，其余当成卡号画正面
     * @param seat     这是谁的堆（查看内容时要按座位取那一份）
     * @param location {@code FieldCodes.LOCATION_*}
     */
    private void pile(GuiGraphics g, FieldLayout L, String label, FieldLayout.Rect r, int fill, int art,
                      int seat, int location) {
        // 记下来：点它可以查看内容（墓地/除外/额外卡组）。
        pileRefs.add(new PileRef(r, seat, location));
        if (FieldTextures.slot(g, FieldTextureSpec.ZONE_PILE, r)) {
            // 贴图之上压一层薄色：牌堆之间本来靠颜色区分（除外是灰的、卡组是蓝的…），
            // 只贴图会把这份区别抹掉。压 31% 的色，既留住区别又看得见底纹。
            g.fill(r.x(), r.y(), r.right(), r.bottom(), (fill & 0x00FFFFFF) | 0x50000000);
        } else {
            g.fill(r.x(), r.y(), r.right(), r.bottom(), fill);
        }
        outline(g, r, 0x70FFFFFF);
        if (hasPileOption(seat, location)) {
            // 墓地/除外/额外上有可做的事（选一张发动、特殊召唤）就点亮这一堆。
            outline(g, r, 0xFFFFFF00);
        }
        if (art != NO_ART) {
            // 内缩 2 像素，别把边框盖掉。
            int ax = r.x() + 2;
            int ay = r.y() + 2;
            int aw = r.w() - 4;
            int ah = r.h() - 4;
            if (aw > 4 && ah > 4) {
                if (art > 0) {
                    CardArt.draw(g, art, ax, ay, aw, ah, null);
                } else {
                    CardArt.drawBack(g, ax, ay, aw, ah);
                }
            }
        }
        if (r.w() >= 34) {
            if (art != NO_ART) {
                // 牌堆上压了牌面之后，白字直接写在卡图上会看不清——先压一条暗底。
                g.fill(r.x() + 1, r.y() + 1, r.right() - 1, r.y() + 11, 0xC8101820);
            }
            g.drawString(font, label, r.x() + 2, r.y() + 2, 0xE0FFFFFF);
        }
    }

    /**
     * 牌堆最顶上那张，用来画在堆上。
     *
     * <p>ocgcore 报墓地/除外时是<b>按放入顺序</b>给的，最后一张就是最上面那张
     * （ygo 客户端也是拿最后一张摊在堆上）。卡号为 0 表示看不见正面——对手的
     * 里侧除外、或尚未翻开的里侧除外——那就画牌背。
     *
     * @return 卡号、{@link #BACK_ART}（看不见正面）或 {@link #NO_ART}（空堆）
     */
    private static int topCard(List<DuelBoard.Zone> pile) {
        // 判据挪进了纯类（PileBrowse.topArt），好离线断言：
        // 「里侧除外画牌背」这条以前漏了——只看卡号不看表示形式，
        // 而我方里侧的除外卡号对我们【是】已知的（visible(mine=true) 恒真），
        // 于是牌堆顶上直接画出了卡面。
        return PileBrowse.topArt(pile);
    }

    private void outline(GuiGraphics g, FieldLayout.Rect r, int color) {
        g.fill(r.x(), r.y(), r.right(), r.y() + 1, color);
        g.fill(r.x(), r.bottom() - 1, r.right(), r.bottom(), color);
        g.fill(r.x(), r.y(), r.x() + 1, r.bottom(), color);
        g.fill(r.right() - 1, r.y(), r.right(), r.bottom(), color);
    }

    private static DuelBoard.Zone at(List<DuelBoard.Zone> zones, int i) {
        return i >= 0 && i < zones.size() ? zones.get(i) : null;
    }

    /**
     * 高亮、悬停提示与状态行。
     *
     * <p>可点目标要<b>看得出来可以点</b>：不然玩家不知道该点哪张卡，
     * 只会去屏幕上找按钮——而按钮已经没有了。
     */
    private void drawOverlay(GuiGraphics g, FieldLayout L, int mouseX, int mouseY) {
        if (question != null && !submitted && board != null) {
            List<DuelTargets.Target> targets = targets();
            boolean counters = question.mode() == DuelQuestion.Mode.COUNTERS;
            for (DuelTargets.Target t : targets) {
                boolean picked = counters
                        ? t.optionIndex() < counterAmounts.length && counterAmounts[t.optionIndex()] > 0
                        : chosen.contains(t.optionIndex());
                FieldLayout.Rect r = t.rect();
                if (picked) {
                    // 已选：亮黄框 + 压暗一层
                    g.fill(r.x(), r.y(), r.right(), r.bottom(), 0x60FFE060);
                    outline(g, r, 0xFFFFE060);
                } else {
                    outline(g, r, 0x70A0FFA0);
                }
                if (counters && picked) {
                    // 指示物类要看得见「这张卡放了几个」，否则玩家只能靠数点了几下
                    g.drawString(font, String.valueOf(counterAmounts[t.optionIndex()]),
                            r.x() + 2, r.y() + 2, 0xFFFFE060, true);
                }
            }
            int hov = DuelTargets.hit(targets, mouseX, mouseY);
            if (hov >= 0) {
                outline(g, targets.get(hov).rect(), 0xFFFFFFFF);
            }
        }
        // 菜单不在这里画：它要压在状态条与卡列表之上，见 render() 的图层顺序。
    }

    /**
     * 光标下那张卡的卡号（{@code 0} 表示没有卡，或卡号不可见）。
     *
     * <p>和 {@link #targets()} 是<b>两件事</b>：那里只列「现在能操作的选项」，
     * 这里要认出「光标下画着哪张卡」，包括完全不能操作的卡。
     * 两处都用 {@link FieldLayout} 与 {@link DuelTargets} 的矩形，不另算坐标。
     */
    private int hoveredCode(double mx, double my) {
        // 列表里悬停的那一项优先：光标在列表上，想看的就是那一张。
        if (list != null) {
            int row = list.indexAt(mx, my);
            if (row >= 0 && row < piles.size()) {
                int c = piles.get(row).option().cardCode() & 0x7fffffff;
                if (c != 0) {
                    return c;
                }
            }
        }
        if (board == null) {
            return 0;
        }
        // 正在查看的牌堆优先：鼠标停在列表哪一行，右栏就显示那一张。
        if (browse != null) {
            int i = browse.list().indexAt(mx, my);
            if (i >= 0 && i < browse.rows().size()) {
                PileBrowse.Row row = browse.rows().get(i);
                return row.known() ? row.code() : 0;
            }
        }
        FieldLayout L = field();
        for (int side = 0; side < 2; side++) {
            boolean mine = side == 0;
            DuelBoard.PlayerBoard p = mine ? me() : opponent();
            if (p == null) {
                continue;
            }
            FieldLayout.Rect mBand = mine ? L.myMonsterRow() : L.oppMonsterRow();
            FieldLayout.Rect sBand = mine ? L.mySpellRow() : L.oppSpellRow();
            for (int i = 0; i < FieldLayout.MAIN_ZONES; i++) {
                DuelBoard.Zone z = at(p.monsterZones(), i);
                if (z != null && z.occupied()
                        && L.col(mBand, mine ? 1 + i : 5 - i).contains(mx, my)) {
                    return z.code() & 0x7fffffff;
                }
            }
            for (int i = 0; i < FieldLayout.MAIN_ZONES; i++) {
                DuelBoard.Zone z = at(p.spellZones(), i);
                if (z != null && z.occupied()
                        && L.col(sBand, mine ? 1 + i : 5 - i).contains(mx, my)) {
                    return z.code() & 0x7fffffff;
                }
            }
            // 场地区（szone 5）：画在怪兽行的一端，与点击映射同一格
            DuelBoard.Zone fz = at(p.spellZones(), 5);
            if (fz != null && fz.occupied()
                    && L.col(mBand, mine ? 0 : 6).contains(mx, my)) {
                return fz.code() & 0x7fffffff;
            }
            // 手牌：只有看得见正面的那几张才有卡号（对手的手牌卡号恒为 0）
            List<DuelBoard.Zone> hand = p.hand();
            int count = p.handCount();
            for (int i = 0; i < hand.size() && i < count; i++) {
                if (DuelTargets.handCard(L, mine ? L.myHand() : L.oppHand(), i, count)
                        .contains(mx, my)) {
                    return hand.get(i).code() & 0x7fffffff;
                }
            }
        }
        // 额外怪兽区由双方共用，两边的 mzone 5/6 是同一格
        for (int i = 0; i < FieldLayout.EXTRA_MONSTER_ZONES; i++) {
            if (!L.extraMonster(i).contains(mx, my)) {
                continue;
            }
            for (int side = 0; side < 2; side++) {
                DuelBoard.PlayerBoard p = side == 0 ? me() : opponent();
                if (p == null) {
                    continue;
                }
                DuelBoard.Zone z = at(p.monsterZones(), FieldLayout.MAIN_ZONES + i);
                if (z != null && z.occupied()) {
                    return z.code() & 0x7fffffff;
                }
            }
        }
        return 0;
    }

    /**
     * 行动菜单：在光标处列出这张卡现在能做的事。
     *
     * <p>几何与 {@link #menuHit} 共用 {@code DuelTargets.menuRow}——
     * 渲染和命中各写一套坐标，症状就是「看得见但点不中」，而那和
     * 「这个操作不合法」在界面上长得一模一样。
     */
    private void drawMenu(GuiGraphics g) {
        if (menu.isEmpty() || question == null) {
            return;
        }
        int w = menuWidth();
        int rowH = DuelTargets.MENU_ROW_H + DuelTargets.MENU_ROW_GAP;
        // 坐标与命中判定共用 menuOriginX/Y：各写一套的症状是「看得见但点不中」。
        int x = menuOriginX(w);
        int y = menuOriginY(menu.size());
        int totalH = menu.size() * rowH - DuelTargets.MENU_ROW_GAP;
        g.fill(x - 2, y - 2, x + w + 2, y + totalH + 2, 0xFF2B3A4A);
        outline(g, new FieldLayout.Rect(x - 2, y - 2, w + 4, totalH + 4), 0xFF8A9AC0);
        for (int i = 0; i < menu.size(); i++) {
            FieldLayout.Rect r = DuelTargets.menuRow(x, y, w, i);
            // 字走 menuLabel：额外卡组那份菜单里有两项是动作（特殊召唤 / 查看列表），
            // 不是选项下标。
            g.drawString(font, menuLabel(menu.get(i)), r.x() + 4, r.y() + 3, 0xFFFFFFFF);
        }
    }

    /** 卡名：选项的 label 对卡牌项只是占位，卡名要用卡号去查。 */
    private String optionName(DuelQuestion.Option o) {
        int code = o.cardCode() & 0x7fffffff;
        if (code != 0) {
            var pack = cn.xm1221.ygomc.common.data.DataPacks.get();
            String name = pack == null ? null : pack.nameOf(code);
            if (name != null && !name.isEmpty()) {
                return name;
            }
            return "#" + code;
        }
        return o.label();
    }

    /** 指示物已分配的总数。 */
    private int counterTotal() {
        int t = 0;
        for (int n : counterAmounts) {
            t += n;
        }
        return t;
    }

    /**
     * 右侧信息面板：<b>大卡图 + 卡名 + 数值 + 卡文</b>，下面再接当前询问的标题、提示与进度。
     *
     * <h2>为什么把「看卡」从鼠标跟随挪到固定面板</h2>
     * 原先卡文是跟着光标画的小窗，正好盖在牌桌上——而玩家要看的卡
     * 恰恰就是他要点的卡，于是「读卡」和「点卡」互相挡。
     * 挪到场地右侧之后，光标停在哪张卡上都不会遮住那张卡本身。
     *
     * <h2>显示哪张卡</h2>
     * 优先光标下的卡；光标不在任何卡上时，显示<b>这道询问在问的那张卡</b>。
     * 后者是「询问发动效果」这类问题的关键：以前弹一句「是否发动效果？」，
     * 玩家不知道问的是哪一张；现在面板上就摆着那张卡的卡图和卡文。
     */
    /**
     * 要弹窗问的询问：「是否发动效果」这一类。
     *
     * <p>包含 {@code SELECT_EFFECTYN}（单独一张卡问要不要发动）与
     * {@code SELECT_CHAIN}（连锁时问发动哪个效果）。两者标题都是
     * 「是否发动效果？」，本来就是同一件事的两个来源。
     */
    private boolean popupQuestion() {
        return question != null && DuelQuestion.isPopup(question.type());
    }

    /**
     * 询问标题。发动效果这类要在标题里点名是哪张卡。
     *
     * <p>光秃秃一句「是否发动效果？」没有信息量：同一个时点可能有好几张卡能发动。
     * 官方就是在标题里点名道姓的——{@code strings.conf:200}
     * 「是否在[%ls]发动[%ls]的效果？」。
     *
     * <h2>标题从哪来</h2>
     * 先看 {@link DuelQuestion.Title}：{@code RAW} 表示这是内核文本（{@code HINT_EVENT}
     * 时点、系统串），照原文显示；否则用 {@code key} 去语言资源取词，参数是编号而不是句子。
     * 以前这里拿 {@code "是否发动效果？".equals(title)} 当中文判据，换语言就静默失效。
     */
    private String questionTitle() {
        if (question == null) {
            return DuelText.s(DuelText.STATUS_WAITING);
        }
        if (submitted) {
            return DuelText.s(DuelText.STATUS_SUBMITTED);
        }
        DuelQuestion.Title t = question.titleText();
        if (!t.fromLang()) {
            return question.title();
        }
        String key = t.key();
        String[] args = t.args();
        // 发动效果的问句要点名是哪张卡：系统串里是「%ls」，我们这里自己填。
        if (DuelText.TITLE_EFFECTYN.equals(key)) {
            String name = CardTips.name(subjectCode());
            if (name != null && !name.isEmpty()) {
                return DuelText.s(key, name);
            }
            return DuelText.s(DuelText.TITLE_EFFECTYN_GENERIC);
        }
        String body = args.length > 0 ? DuelText.s(key, (Object[]) args) : DuelText.s(key);
        // 内核提示（时点/选择提示）不翻，挂在前面，两行读起来才是「什么时候，要不要发动」。
        return t.rawHint() == null || t.rawHint().isEmpty() ? body : t.rawHint() + "\n" + body;
    }

    /**
     * 操作提示。
     *
     * <p>每一条都要与<b>真实交互</b>对得上，这是这一轮返工的主因：原先
     * 排序类提示写「右下角确认」（其实点选顺序就是答案）、
     * 选卡类与「取消选中」类共用一条、选格子类只说「选卡」。写错了比不写更糟——
     * 玩家会照着一句错话去做。
     */
    private String questionHint() {
        if (question == null || submitted || board == null) {
            return "";
        }
        if (DuelQuestion.isYesNo(question.type())) {
            return DuelText.s(DuelText.HINT_YESNO);
        }
        if (chainAskStage()) {
            return DuelText.s(DuelText.HINT_CHAIN_ASK);
        }
        if (question.isChainQuestion()) {
            // 第二段：已经同意发动，点亮的就是能发动的那些，让玩家点一张。
            // 这一步以前没有提示——玩家看到的就是「一堆卡突然亮了」。
            return DuelText.s(DuelText.HINT_CHAIN_PICK);
        }
        if (extraMenuPending()) {
            return DuelText.s(DuelText.HINT_EXTRA_MENU);
        }
        if (question.mode() == DuelQuestion.Mode.SORT) {
            // 排序没有「确认」这一步：点选的先后顺序就是答案。
            return DuelText.s(DuelText.HINT_SORT);
        }
        if (question.mode() == DuelQuestion.Mode.COUNTERS) {
            return DuelText.s(DuelText.HINT_COUNTERS);
        }
        if (question.mode() == DuelQuestion.Mode.SUM) {
            return DuelText.s(DuelText.HINT_SUM);
        }
        if (popup != null) {
            return DuelText.s(DuelText.HINT_POPUP);
        }
        // 「选一些 / 取消选一些」点一下就把这一下交出去（sendsOnClick），没有确认键。
        if (question.sendsOnClick()) {
            return DuelText.s(DuelText.HINT_SELECT_UNSELECT);
        }
        if (question.mode() == DuelQuestion.Mode.PLACES) {
            return question.type() == MsgType.SELECT_DISFIELD
                    ? DuelText.s(DuelText.HINT_DISFIELD)
                    : DuelText.s(DuelText.HINT_PLACING);
        }
        if (question.mode() == DuelQuestion.Mode.SINGLE
                && question.type() == MsgType.SELECT_POSITION) {
            return DuelText.s(DuelText.HINT_POSITION);
        }
        if (question.type() == MsgType.ANNOUNCE_RACE) {
            return DuelText.s(DuelText.HINT_ANNOUNCE_RACE);
        }
        if (question.type() == MsgType.ANNOUNCE_ATTRIB) {
            return DuelText.s(DuelText.HINT_ANNOUNCE_ATTRIB);
        }
        if (question.type() == MsgType.ANNOUNCE_CARD) {
            return DuelText.s(DuelText.HINT_ANNOUNCE_CARD);
        }
        if (question.type() == MsgType.ANNOUNCE_NUMBER) {
            return DuelText.s(DuelText.HINT_ANNOUNCE_NUMBER);
        }
        if (question.mode() == DuelQuestion.Mode.UNSUPPORTED) {
            return DuelText.s(DuelText.HINT_UNSUPPORTED);
        }
        if (spatial()) {
            return needsConfirm()
                    ? (question.cancelable()
                            ? DuelText.s(DuelText.HINT_SELECT_CANCELABLE)
                            : DuelText.s(DuelText.HINT_SELECT))
                    : DuelText.s(DuelText.HINT_CLICK);
        }
        return DuelText.s(DuelText.HINT_PICK);
    }

    /** 进度：已选几张 / 已分配多少指示物。 */
    private String questionCounts() {
        if (question == null || submitted || board == null) {
            return "";
        }
        if (question.mode() == DuelQuestion.Mode.COUNTERS) {
            return DuelText.s(DuelText.COUNTS_COUNTERS, counterTotal(), question.min());
        }
        if (question.mode() == DuelQuestion.Mode.SUM) {
            int[] range = sumRange();
            String cur = range[0] == range[1] ? String.valueOf(range[0])
                    : range[0] + "~" + range[1];
            return DuelText.s(DuelText.COUNTS_SUM, chosen.size(), cur, question.sumTarget());
        }
        if (needsConfirm()) {
            String max = question.max() > 0 ? "~" + question.max() : "+";
            return DuelText.s(DuelText.COUNTS_SELECTED, chosen.size(), question.min(), max);
        }
        return "";
    }

    /**
     * 场地底部的状态条：双方 LP 的两个角、以及询问标题与提示。
     *
     * <p>这些字以前都挤在右侧信息面板的底部（占掉 58 像素），卡文被顶掉之后
     * 只能截断——「文字显示不全」的根因就在这。现在右面板只放卡片信息，
     * 询问相关的字全部搬到这条状态条上，而且**换行**，不截断。
     */
    private void drawStatusBar(GuiGraphics g, FieldLayout L) {
        FieldLayout.Rect s = L.status();
        g.fill(s.x(), s.y(), s.right(), s.bottom(), 0xFF101A24);
        g.fill(s.x(), s.y(), s.right(), s.y() + 1, 0xFF6E90B4);

        // 左下角是我方 LP，正文从徽章右边开始，两者不重叠。
        int mineW = myLpWidth(L);
        drawLifeBadge(g, s.x() + 2, s.y() + 4, mineW, me() == null ? 0 : me().lp(),
                DuelText.s(DuelText.LP_MINE), 0xFF7FD8A0, false);

        // 正文右侧要让出来的宽度：对手 LP ＋ 必发提示条 ＋ 右下角那两颗键。
        // 少让任何一项，提示尾巴都会被压在下面——这就是咩咩说的「提示显示不全」的一部分。
        // 正文右侧要让出来的宽度：对手 LP ＋ 右下角那两颗键（见 statusReserve）。
        // 少让任何一项，提示尾巴都会被压在下面——这就是咩咩说的「提示显示不全」的一部分。
        int reserve = statusReserve(L);
        int textX = s.x() + mineW + 8;
        int w = s.right() - 4 - textX - reserve;
        if (w < MIN_TEXT_W) {
            return;
        }
        int ty = s.y() + 3;
        for (String line : CardTips.wrap(font, questionTitle(), w)) {
            if (ty + 9 > s.bottom() - 1) {
                return;
            }
            g.drawString(font, line, textX, ty, 0xFFFFD060, true);
            ty += 9;
        }
        String hint = questionHint();
        String counts = questionCounts();
        String tail = counts.isEmpty() ? hint : hint + "　" + counts;
        if (!tail.isEmpty() && ty + 9 <= s.bottom() - 1) {
            // 提示行也换行，不再 clip：截断的尾巴看着就是「提示不全」。
            // 行数受状态条高度限制，画不下就到此为止（宁可少一行，也不压到牌上）。
            for (String line : CardTips.wrap(font, tail, w)) {
                if (ty + 9 > s.bottom() - 1) {
                    break;
                }
                g.drawString(font, line, textX, ty, 0xFFA8E8B0);
                ty += 9;
            }
        }
    }

    /**
     * 双方 LP：对手在场地右上角，自己在左下角。
     *
     * <p>放在这两个角上而不是右面板里：右面板只放卡片信息；而且 LP 是场上的状态，
     * 跟卡文抢地方只会让卡文显示不全。
     *
     * <p>右上角那片空位是现成的：阶段条在场地里居中，两端本来就空着。
     */
    private void drawLifeBadges(GuiGraphics g, FieldLayout L) {
        if (board == null) {
            return;
        }
        int w = LP_BADGE_W;
        int lp = opponent() == null ? 0 : opponent().lp();
        int inStatus = oppLpInStatusWidth(L);
        if (inStatus == 0) {
            // 首选右上角：阶段条在场地里居中，两端本来就是空的。
            int room = L.fieldW() - 6 - (L.phase(5).right() + 2);
            int bw = Math.min(w, room);
            drawLifeBadge(g, L.fieldW() - 4 - bw, 4, bw, lp, DuelText.s(DuelText.LP_OPPONENT), 0xFFE07A7A, true);
            return;
        }
        // 窄屏上阶段条顶到右端，退到状态条右端。位置差一点，
        // 也比对手的 LP 干脆看不见强。
        FieldLayout.Rect s = L.status();
        drawLifeBadge(g, s.right() - 4 - inStatus, s.y() + 4, inStatus, lp,
                DuelText.s(DuelText.LP_OPPONENT), 0xFFE07A7A, true);
    }

    /**
     * 右上角放不下时，对手 LP 退回状态条右端要占多宽；放得下就是 0。
     *
     * <p>状态条的正文与这个徽章必须照着同一个数排版，所以只在这里算一次——
     * 两边各算一遍迟早算岔，表现就是文字和徽章叠在一起。
     */
    /**
     * 状态条正文右侧被别的东西占掉的宽度（对手 LP ／ 必发提示条 ／ 右下角那两颗键）。
     *
     * <p>只在这里算一次，排版与徽章共用同一个数：两边各算一遍迟早算岔，
     * 表现就是文字和徽章叠在一起。
     */
    private int statusReserve(FieldLayout L) {
        int reserve = 0;
        int opp = oppLpInStatusWidth(L);
        if (opp > 0) {
            reserve += opp + 6;
        }
        if (confirm != null) {
            reserve += ANSWER_BTN_W + ANSWER_BTN_GAP;
        }
        if (cancelBtn != null) {
            reserve += ANSWER_BTN_W + ANSWER_BTN_GAP;
        }
        return reserve;
    }

    private int oppLpInStatusWidth(FieldLayout L) {
        if (board == null) {
            return 0;
        }
        int headerRoom = L.fieldW() - 6 - (L.phase(5).right() + 2);
        if (headerRoom >= MIN_LP_W) {
            return 0;
        }
        FieldLayout.Rect s = L.status();
        // 这里用 MIN_LP_W 而不是 LP_BADGE_W：我方的徽章自己也可能会被压窄
        // （见 myLpWidth），拿 LP_BADGE_W 来算就会两处互相依赖、算岔。
        int room = s.right() - 4 - (s.x() + MIN_LP_W + 6 + MIN_TEXT_W + 6);
        return room >= MIN_LP_W ? Math.min(LP_BADGE_W, room) : 0;
    }

    /**
     * 状态条里「我方 LP」徽章占多宽。
     *
     * <p>窄屏上 62 像素的徽章会把标题挤成没意义的一小截，所以按剩余空间自适应，
     * 下限是只画数字的 {@link #MIN_LP_W}——少两个字，也不能让标题看不见。
     */
    private int myLpWidth(FieldLayout L) {
        FieldLayout.Rect s = L.status();
        int reserve = oppLpInStatusWidth(L);
        int room = s.right() - 4 - (reserve == 0 ? 0 : reserve + 6)
                - (s.x() + 8 + MIN_TEXT_W);
        return Math.max(MIN_LP_W, Math.min(LP_BADGE_W, room));
    }

    /**
     * 画一个 LP 徽章。字太宽就丢掉「我方/对手」前缀只留数字——
     * 窄屏上宁可少两个字，也不能让数字被截掉一半。
     */
    private void drawLifeBadge(GuiGraphics g, int x, int y, int w, int lp,
                               String who, int color, boolean rightAlign) {
        String num = String.valueOf(lp);
        String text = who + " " + num;
        if (font.width(text) + 6 > w) {
            text = num;
        }
        g.fill(x, y - 1, x + w, y + 10, 0x90101820);
        int tx = rightAlign ? x + w - 3 - font.width(text) : x + 3;
        g.drawString(font, text, tx, y, color, true);
    }

    /** 弹窗里每个选项舒服时占的高度。 */
    private static final int POPUP_ROW_H = 20;

    /** 弹窗窗口：在场地那半边居中，高度按标题行数与选项数算出来。 */
    private FieldLayout.Rect popupRect(List<DuelQuestion.Option> options) {
        FieldLayout L = field();
        int w = Math.min(300, Math.max(170, L.fieldW() * 2 / 3));
        int rows = Math.max(1, CardTips.wrap(font, questionTitle(), w - 2 * POPUP_PAD).size());
        int n = Math.max(1, options.size());
        int maxH = Math.max(40, L.height() - 16);
        int h = POPUP_PAD + rows * 10 + 4 + n * POPUP_ROW_H + POPUP_PAD;
        if (h > maxH) {
            h = maxH;
        }
        int x = Math.max(4, (L.fieldW() - w) / 2);
        int y = Math.max(4, (L.height() - h) / 2);
        return new FieldLayout.Rect(x, y, w, h);
    }

    /**
     * 弹窗里每个选项实际占多高。
     *
     * <p>原来是写死的 20，而窗口高度会被屏幕裁——连锁长起来能到十几项，
     * 结果<b>窗口裁了、按钮照旧往下摆</b>，最后几个按钮摆到框外面去了。
     * 现在先算窗口里还剩多少地方，再决定每项占多高：算得下就 20，
     * 算不下压到 12（字体 9 像素，再低就挤在一起了）。
     */
    private int popupRowH(List<DuelQuestion.Option> options, FieldLayout.Rect win) {
        int rows = Math.max(1, CardTips.wrap(font, questionTitle(), win.w() - 2 * POPUP_PAD).size());
        int n = Math.max(1, options.size());
        int room = win.h() - POPUP_PAD - rows * 10 - 4 - POPUP_PAD;
        return Math.max(12, Math.min(POPUP_ROW_H, room / n));
    }

    /** 弹窗按钮上的字。 */
    private String popupLabel(int i, List<DuelQuestion.Option> options) {
        DuelQuestion.Option o = options.get(i);
        String label = shortLabel(o);
        int code = o.cardCode() & 0x7fffffff;
        // 连锁询问里每一项的 label 都是同一个「发动效果」（DuelQuestion.java:285），
        // 光看按钮分不出是哪张卡，所以补上卡名。是/否那种（yesNo，:334-335）
        // 两个标签本来就不同、标题也已经点了卡名，就不用再补。
        //
        // 判据是 labelKind 而不是「是」「否」这两个中文：以前那两处字面量比较
        // 在英文环境下会一直不成立，于是每个「是 / 否」按钮后面都被挂上一个卡名。
        if (code == 0 || o.labelKind() == DuelQuestion.Option.LabelKind.YES_NO) {
            return label;
        }
        return DuelText.s(DuelText.LABEL_WITH_CARD, label, cardNameLabel(code, label));
    }

    /** 按弹窗布局摆按钮。走的是现成的按钮机制，所以 hover/焦点都是原版行为。 */
    private void buildPopupButtons() {
        List<DuelQuestion.Option> options = question.options();
        if (options.isEmpty()) {
            return;
        }
        FieldLayout.Rect win = popupRect(options);
        popup = win;
        int rows = CardTips.wrap(font, questionTitle(), win.w() - 2 * POPUP_PAD).size();
        int bw = win.w() - 2 * POPUP_PAD;
        int rowH = popupRowH(options, win);
        int bh = Math.min(18, Math.max(10, rowH - 2));
        int y = win.y() + POPUP_PAD + rows * 10 + 4;
        for (int i = 0; i < options.size(); i++) {
            final int index = i;
            String text = popupLabel(i, options);
            addRenderableWidget(Button.builder(Component.literal(clip(text, bw - 8)),
                    b -> onOption(index)).bounds(win.x() + POPUP_PAD, y, bw, bh).build());
            y += rowH;
        }
    }

    /** 弹窗的底与标题。画在 {@code super.render} 之前，按钮才会压在它上面。 */
    private void drawPopup(GuiGraphics g) {
        FieldLayout.Rect w = popup;
        if (w == null) {
            return;
        }
        g.fill(w.x() - 3, w.y() - 3, w.right() + 3, w.bottom() + 3, 0x80000000);
        g.fill(w.x(), w.y(), w.right(), w.bottom(), 0xFF1A2A38);
        outline(g, w, 0xFFE0C060);
        int ty = w.y() + POPUP_PAD;
        for (String line : CardTips.wrap(font, questionTitle(), w.w() - 2 * POPUP_PAD)) {
            g.drawString(font, line, w.x() + POPUP_PAD, ty, 0xFFFFD060, true);
            ty += 10;
        }
    }

    private void drawInfoPanel(GuiGraphics g, FieldLayout L, int mouseX, int mouseY) {
        FieldLayout.Rect p = L.panel();
        // 底色调亮：原来的 0xE0101018 几乎全黑，卡面放在上面整体发闷。
        g.fill(p.x(), p.y(), p.right(), p.bottom(), 0xFF27384A);
        g.fill(p.x(), p.y(), p.x() + 2, p.bottom(), 0xFF6E90B4);

        int code = panelCode(mouseX, mouseY);
        FieldLayout.Rect art = L.panelArt();
        if (code != 0) {
            boolean hasArt = drawPanelArt(g, code, art);
            outline(g, art, 0xFFB8CEE4);
            if (!hasArt) {
                g.drawCenteredString(font, DuelText.s(DuelText.PANEL_NO_ART), art.x() + art.w() / 2,
                        art.bottom() - 12, 0xFFD0D8E0);
            }
        } else {
            // 没有卡可显示时把卡文的行数也清零，否则滚轮会拿着上一次那张卡的
            // 行数在空面板上「滚」，虽然看不见，但状态是错的。
            descLines = 0;
            descMaxLines = 0;
            g.fill(art.x(), art.y(), art.right(), art.bottom(), 0xFF1C2836);
            outline(g, art, 0xFF3E5266);
            g.drawCenteredString(font, DuelText.s(DuelText.PANEL_HOVER_CARD), art.x() + art.w() / 2,
                    art.y() + art.h() / 2 - 4, 0xFF8FA4B8);
        }

        // 右面板只放卡片信息：询问标题/提示/LP 都搬去底部状态条与场地两角了。
        // 问句块原先在这里占掉 58 像素，卡文被它顶掉之后只能截断——
        // 这就是「文字总显示不全」的根因。
        FieldLayout.Rect text = L.panelText();
        int textBottom = Math.max(text.y() + 10, p.bottom() - 4);

        if (code != 0) {
            if (code != lastDescCode) {
                // 换了一张卡：卡文滚回顶部。不归零的话会停在上一次的滚动位置，
                // 看起来就像「这张卡的说明从中间开始」。
                descScroll = 0;
                lastDescCode = code;
            }
            int ty = text.y();
            // 卡名换行，不截断：名字被切掉一半是最没道理的「信息不全」。
            for (String line : CardTips.wrap(font, CardTips.name(code), text.w())) {
                if (ty + 10 > textBottom) {
                    break;
                }
                g.drawString(font, line, text.x(), ty, 0xFFFFFFFF, true);
                ty += 12;
            }
            // 数值行也换行：窄屏上「地属性／战士族／★4／ATK 1800／DEF 1200」一行放不下，
            // 截断就等于把攻守切掉一半——那也是「信息不全」。
            String stats = CardTips.statsLine(code);
            if (!stats.isEmpty()) {
                for (String line : CardTips.wrap(font, stats, text.w())) {
                    if (ty + 10 > textBottom) {
                        break;
                    }
                    g.drawString(font, line, text.x(), ty, 0xFF9EE0B0);
                    ty += 12;
                }
            }
            ty += 2;
            // 卡文放不下就滚动，绝不截断——滚轮能看全，截断就永远看不到了。
            List<String> lines = CardTips.wrap(font, CardTips.desc(code), text.w());
            int lineH = 10;
            int maxLines = Math.max(0, (textBottom - ty) / lineH);
            descLines = lines.size();
            descMaxLines = maxLines;
            int from = Math.min(descScroll, Math.max(0, lines.size() - maxLines));
            for (int i = from; i < lines.size() && i < from + maxLines; i++) {
                g.drawString(font, lines.get(i), text.x(), ty, 0xFFDCE4EC);
                ty += lineH;
            }
            if (lines.size() > from + maxLines) {
                g.drawString(font, DuelText.s(DuelText.PANEL_SCROLL), text.right() - 34, textBottom - 9, 0xFF9EE0B0, true);
            } else if (from > 0) {
                g.drawString(font, "▲", text.right() - 11, textBottom - 9, 0xFF9EE0B0, true);
            }
        }
    }

    /**
     * 画卡片本身。
     *
     * <p>稀有度传 {@code null}，与牌桌上、卡组界面里的每一处调用一致：
     * 数据包里目前没有「卡号 → 稀有度分层」的查表，界面各处就都只画卡图。
     * 面板这里单独去查一个不存在的表，只会让同一张卡在两个地方长得不一样。
     *
     * @return 是否画出了真实卡图
     */
    private boolean drawPanelArt(GuiGraphics g, int code, FieldLayout.Rect art) {
        return CardArt.draw(g, code, art.x(), art.y(), art.w(), art.h(), null);
    }

    /** 面板要显示的卡号：优先光标下的卡，其次这道询问在问的卡；{@code 0} 表示都没有。 */
    /**
     * 滚轮翻列表。列表是唯一需要翻页的地方，所以只在这里拦。
     *
     * <p>用 {@code @Override} 是为了让编译器核实签名——签名写错的话这个方法是「死」的，
     * 编译通过、运行永远不触发，是那种最不容易发现的问题。
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (browse != null && browse.list().panel().contains(mouseX, mouseY)) {
            browse.list().scrollBy(scrollY > 0 ? -1 : 1);
            return true;
        }
        if (list != null && list.panel().contains(mouseX, mouseY)) {
            list.scrollBy(scrollY > 0 ? -1 : 1);
            return true;
        }
        // 光标在右侧卡片信息上：滚卡文。放不下时只有这一条路能看全，
        // 所以这两个数直接取绘制时算出来的那一份，不另算一遍（另算迟早算岔）。
        if (field().panel().contains(mouseX, mouseY) && descLines > descMaxLines) {
            descScroll = CardList.clamp(descScroll + (scrollY > 0 ? -1 : 1),
                    0, descLines - descMaxLines);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /**
     * 画卡列表——对应 ygo 的 {@code wCardSelect}。
     *
     * <p>只画卡名不画卡图：卡图交给右侧信息面板（悬停哪一行就显示哪一张），
     * 这样窄屏上也不会挤成一片看不清的小图。
     */
    /** 光标下的牌堆格子；不在任何牌堆上返回 null。 */
    private PileRef pileAt(double mx, double my) {
        for (PileRef ref : pileRefs) {
            if (ref.rect().contains(mx, my)) {
                return ref;
            }
        }
        return null;
    }

    /**
     * 打开「查看牌堆内容」。
     *
     * <p>拿到的就是牌桌里那几张：可见性早在服务端（{@code FieldCodes.visible}）
     * 定死了——对手的里侧除外、对手的额外卡组到客户端时卡号就是 0。
     * 这里只把 0 呈现成「盖着的卡」，<b>不再补一层过滤</b>：两边都滤会让人
     * 误以为边界在界面这一层，而真正的边界在服务端。
     */
    private void openBrowse(PileRef ref) {
        DuelBoard.PlayerBoard pb = board == null ? null : board.playerAt(ref.seat());
        if (pb == null) {
            return;
        }
        List<DuelBoard.Zone> zones = switch (ref.location()) {
            case FieldCodes.LOCATION_GRAVE -> pb.grave();
            case FieldCodes.LOCATION_REMOVED -> pb.removed();
            case FieldCodes.LOCATION_EXTRA -> pb.extra();
            default -> List.of();
        };
        List<PileBrowse.Row> rows = PileBrowse.rows(zones, ref.seat() == mySeat);
        browse = new Browse(ref.seat(), ref.location(), rows, new CardList(listRect(), rows.size()));
        // 「收起」键：咩咩说查看额外卡组的列表不好关掉——以前只有右键一条路。
        addBrowseCloseButton();
    }

    /**
     * 把「查看牌堆内容」窗口右上角那颗「收起」键摆上。
     *
     * <p>单独抽出来是因为它<b>不属于询问</b>：界面每次 rebuild 都会 clearWidgets，
     * 而窗口自己还开着，不重摆的话就成了「窗口还在、键没了」——那正是
     * 「查看额外不好关掉」的一半原因。
     */
    private void addBrowseCloseButton() {
        if (browse == null) {
            return;
        }
        FieldLayout.Rect p = browse.list().panel();
        addRenderableWidget(Button.builder(DuelText.c(DuelText.BROWSE_COLLAPSE), b -> closeBrowse())
                .bounds(p.right() - 38, p.y() + 1, 36, 12).build());
    }

    /**
     * 收起「查看牌堆内容」的窗口。
     *
     * <p>要连窗口上那颗「收起」键一起清掉，所以走 {@link #rebuild()}：
     * 只把 {@code browse} 置空的话，那颗键会留在屏幕上，下次开窗口还会多一颗。
     */
    private void closeBrowse() {
        browse = null;
        rebuild();
    }

    /**
     * 摆上必发提示里那颗唯一的「确认」。
     *
     * <p>整条提示<b>只有</b>这一颗键：它没有第二个答案，也不需要第二个
     * （提示不是询问）。位置取 {@link FieldLayout#notice()}，与提示框同一个来源。
     */
    private void addNoticeConfirmButton() {
        // 提示条不再显示，这颗键也就不摆了（同上）。
    }

    /**
     * 收掉必发提示。
     *
     * <p><b>只清本地这一份</b>：这不是询问，回一个答案给内核会真的影响对局
     * ——那就成了替玩家做决定。服务端那边也是取走即清空（{@link ChainNotice.Slot}），
     * 同一条提示不会再来第二次。
     */
    private void dismissNotice() {
        notice = null;
        rebuild();
    }

    /**
     * 画必发提示条：一行字 + 一颗「确认」，压在状态条右端（坐标见
     * {@link FieldLayout#notice()}）。
     *
     * <p>卡名与效果文案都在客户端合成（{@code CardTips} / {@link DescText}）：
     * 服务端只送卡号与描述号，所以玩家看到的名字跟他自己的语言与数据包一致。
     */
    private void drawNotice(GuiGraphics g, FieldLayout L) {
        // 必发提示条不再显示（咩咩 2026-10-05：「这个不用显示」）。
        // 那条金黄边就是它——挂在状态条右端，还压掉提示尾巴。
        // 服务端仍会送（线格式 v3 的帧尾），客户端只收下不用。
    }

    /**
     * 查看牌堆内容：只读的列表窗口。
     *
     * <p>与选择用的卡列表刻意长得像（同一套行高、滚动条），但语义不同：
     * 这里点行不选中任何东西，纯粹是「看」。所以标题带「右键收起」，
     * 行里也不画勾。
     */
    private void drawBrowse(GuiGraphics g, int mouseX, int mouseY) {
        Browse b = browse;
        if (b == null) {
            return;
        }
        CardList l = b.list();
        FieldLayout.Rect p = l.panel();
        // 同 drawCardList：查看窗要盖住场地，底色必须不透明。
        g.fill(p.x(), p.y(), p.right(), p.bottom(), 0xFF162534);
        outline(g, p, 0xFF78C8A4);
        g.drawString(font, clip(DuelText.s(DuelText.pileTitleKey(b.seat(), b.location(), mySeat),
                        b.rows().size())
                        + DuelText.s(DuelText.BROWSE_COLLAPSE_HINT), p.w() - 8),
                p.x() + CardList.PAD, p.y() + 3, 0xFFFFE060, true);
        if (b.rows().isEmpty()) {
            g.drawString(font, clip(DuelText.s(DuelText.BROWSE_EMPTY), p.w() - 8), p.x() + CardList.PAD,
                    p.y() + CardList.TITLE_H + 2, 0xFF9AA8B4, true);
            return;
        }
        int hover = l.indexAt(mouseX, mouseY);
        for (int i = 0; i < b.rows().size(); i++) {
            FieldLayout.Rect r = l.row(i);
            if (r == null) {
                continue;
            }
            if (i == hover) {
                g.fill(r.x(), r.y(), r.right(), r.bottom() - 1, 0xFF3E6E8C);
            }
            PileBrowse.Row row = b.rows().get(i);
            String name = row.known() ? CardTips.name(row.code()) : DuelText.s(PileBrowse.unknownKey());
            if (name == null || name.isEmpty()) {
                name = DuelText.s(DuelText.DECLARE_CARD_CODE, row.code());
            }
            String line = clip("  " + name, r.w() - 4);
            if (PileBrowse.italic(row)) {
                // 我方里侧除外：卡是我们自己盖的，名字当然知道，但它在场上是盖着的。
                // 斜体就是这个意思（咩咩定）。判据在纯类里，可离线断言。
                g.drawString(font,
                        Component.literal(line).withStyle(
                                net.minecraft.ChatFormatting.ITALIC),
                        r.x() + 2, r.y() + 1, 0xFFE8F0F8, true);
            } else {
                g.drawString(font, line, r.x() + 2, r.y() + 1,
                        row.known() ? 0xFFE8F0F8 : 0xFF9AA8B4, true);
            }
        }
        // 末尾的「取消」行：可取消的询问必须留一条不选的路。
        if (cancelIndex() >= 0 && l.count() > piles.size()) {
            FieldLayout.Rect cr = l.row(piles.size());
            if (cr != null) {
                if (l.indexAt(mouseX, mouseY) == piles.size()) {
                    g.fill(cr.x(), cr.y(), cr.right(), cr.bottom() - 1, 0xFF3E6E8C);
                }
                g.drawString(font, clip(DuelText.s(DuelText.LIST_CANCEL_ROW), cr.w() - 4), cr.x() + 2, cr.y() + 1,
                        0xFFFFB070, true);
            }
        }
        if (l.scrollable()) {
            FieldLayout.Rect bd = l.body();
            int trackX = p.right() - CardList.PAD - 3;
            g.fill(trackX, bd.y(), trackX + 3, bd.bottom(), 0xFF0E1A24);
            int h = Math.max(6, bd.h() * l.visibleRows() / Math.max(1, l.count()));
            int y = bd.y() + (bd.h() - h) * l.scroll() / Math.max(1, l.maxScroll());
            g.fill(trackX, y, trackX + 3, y + h, 0xFF78C8A4);
        }
    }

    private void drawCardList(GuiGraphics g, int mouseX, int mouseY) {
        CardList l = list;
        if (l == null) {
            return;
        }
        FieldLayout.Rect p = l.panel();
        g.fill(p.x(), p.y(), p.right(), p.bottom(), 0xFF162534);
        g.fill(p.x(), p.y(), p.right(), p.y() + 1, 0xFF78C8A4);
        g.fill(p.x(), p.bottom() - 1, p.right(), p.bottom(), 0xFF78C8A4);
        g.fill(p.x(), p.y(), p.x() + 1, p.bottom(), 0xFF78C8A4);
        g.fill(p.right() - 1, p.y(), p.right(), p.bottom(), 0xFF78C8A4);

        int picked = 0;
        for (DuelTargets.Target t : piles) {
            if (chosen.contains(t.optionIndex())) {
                picked++;
            }
        }
        String title = question != null && question.max() > 0
                ? DuelText.s(DuelText.LIST_TITLE, picked, question.max())
                : DuelText.s(DuelText.LIST_TITLE_NO_MAX, picked);
        g.drawString(font, clip(title, p.w() - 8), p.x() + CardList.PAD, p.y() + 3,
                0xFFFFE060, true);

        int hover = l.indexAt(mouseX, mouseY);
        for (int i = 0; i < Math.min(l.count(), piles.size()); i++) {
            FieldLayout.Rect r = l.row(i);
            if (r == null) {
                continue;
            }
            boolean sel = chosen.contains(piles.get(i).optionIndex());
            if (i == hover) {
                g.fill(r.x(), r.y(), r.right(), r.bottom() - 1, 0xFF3E6E8C);
            } else if (sel) {
                g.fill(r.x(), r.y(), r.right(), r.bottom() - 1, 0xFF2E5E4C);
            }
            String name = cardName(piles.get(i).option());
            g.drawString(font, clip((sel ? "√ " : "  ") + name, r.w() - 4),
                    r.x() + 2, r.y() + 1, sel ? 0xFFFFE060 : 0xFFE8F0F8, true);
        }
        // 末尾的「取消」行：可取消的询问必须留一条不选的路。
        if (cancelIndex() >= 0 && l.count() > piles.size()) {
            FieldLayout.Rect cr = l.row(piles.size());
            if (cr != null) {
                if (l.indexAt(mouseX, mouseY) == piles.size()) {
                    g.fill(cr.x(), cr.y(), cr.right(), cr.bottom() - 1, 0xFF3E6E8C);
                }
                g.drawString(font, clip(DuelText.s(DuelText.LIST_CANCEL_ROW), cr.w() - 4), cr.x() + 2, cr.y() + 1,
                        0xFFFFB070, true);
            }
        }
        if (l.scrollable()) {
            FieldLayout.Rect b = l.body();
            int trackX = p.right() - CardList.PAD - 3;
            g.fill(trackX, b.y(), trackX + 3, b.bottom(), 0xFF0E1A24);
            int h = Math.max(6, b.h() * l.visibleRows() / Math.max(1, l.count()));
            int y = b.y() + (b.h() - h) * l.scroll() / Math.max(1, l.maxScroll());
            g.fill(trackX, y, trackX + 3, y + h, 0xFF78C8A4);
        }
        if (needsConfirm() && countsOk()) {
            g.drawString(font, clip(DuelText.s(DuelText.LIST_CONFIRM_OUTSIDE), p.w() - 8), p.x() + CardList.PAD,
                    p.bottom() - CardList.PAD - 9, 0xFF9FE0C0, true);
        }
    }

    /**
     * 列表里那一行显示什么。
     *
     * <p>卡号拿不到（里侧、未知）时退回选项自带的区域标签——
     * 至少「我方墓地 #3」还能让玩家分得清是哪一行，总好过一列空字符串。
     */
    private String cardName(DuelQuestion.Option o) {
        int c = o.cardCode() & 0x7fffffff;
        String name = c == 0 ? null : CardTips.name(c);
        if (name != null && !name.isEmpty()) {
            return name;
        }
        return o.label() == null || o.label().isEmpty() ? ("#" + c) : o.label();
    }

    private int panelCode(int mouseX, int mouseY) {
        if (submitted) {
            return subjectCode();
        }
        int hovered = hoveredCode(mouseX, mouseY);
        return hovered != 0 ? hovered : subjectCode();
    }

    /**
     * 这道询问的「主角卡」：选项表里第一张带卡号的卡。
     *
     * <p>对 {@code SELECT_EFFECTYN}（是否发动效果）这种只有一项、且项上带卡号的询问，
     * 它精确地就是被问的那张卡。对多选类它只是「第一张候选」，
     * 用来在光标不在牌桌上时给个参考——光标一动就会被真正的悬停卡覆盖。
     */
    private int subjectCode() {
        if (question == null) {
            return 0;
        }
        for (DuelQuestion.Option o : question.options()) {
            int c = o.cardCode() & 0x7fffffff;
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /** 一行文字超宽时截断加省略号——面板宽度有限，溢出会画到场地上去。 */
    private String clip(String s, int maxWidth) {
        if (font.width(s) <= maxWidth) {
            return s;
        }
        int n = s.length();
        while (n > 1 && font.width(s.substring(0, n) + "…") > maxWidth) {
            n--;
        }
        return s.substring(0, n) + "…";
    }
}
