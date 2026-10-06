package cn.xm1221.ygomc.common.client;

/**
 * 对局界面「该不该推到玩家眼前」的判据。<b>纯逻辑</b>：只吃布尔、返回布尔，
 * 不碰 Minecraft，所以能离线穷举。
 *
 * <p>抽出来的理由和 {@code LobbyRules} 一样：这条判据错了<b>不会报任何错</b>，
 * 只会「什么都没发生」——界面不弹出来。2026-10-05 咩咩报的正是这个：
 * 双人局同意后没有弹出界面。原来的判据是「有询问才开」，而开局那几帧只有牌桌
 * （对手回合里每一步末尾也都只有牌桌），先被问的往往不是自己，
 * 于是另一方从开局起一帧界面都看不到，只能对着游戏画面干等。
 */
public final class DuelScreenFlow {

    private DuelScreenFlow() {
    }

    /**
     * @param everShown   当前这个界面推给玩家看过没有
     * @param hasQuestion 这一帧带没带需要玩家作答的询问
     * @param isCurrent   这个界面是不是已经是当前屏了
     */
    /**
     * 「确认（完成）」这颗键该不该出现。
     *
     * <p>ygo 全场只有一颗 {@code btnCancelOrFinish}（event_handler.cpp:2348-2367），
     * 文字在「取消」（sys 1295）/「完成」（sys 1296）之间变。规则：
     * <ul>
     *   <li>连锁的第一段（先问「XX时，是否发动效果？」）→ 要给「确认」；</li>
     *   <li>是/否类 → 要给（确认＝是）；</li>
     *   <li>选卡类 → <b>够条件才出现</b>（{@code select_ready}，duelclient.cpp:1677-1678）。
     *       这一点很要紧：ygo 是「<b>不摆</b>」，不是「摆着但是灰的」——
     *       一直摆一颗点不动的键，看起来就是咩咩说的「时灵时不灵」。</li>
     * </ul>
     */
    /**
     * 必发提示条该留还是该收——这是本条提示的<b>生命周期</b>规则。
     *
     * <p>咩咩 2026-10-05 报的「最底下那圈黄框是错误的残留」就是这里：
     * 原来的写法是 `if (notice != null) this.notice = notice;`，只有<b>来新提示</b>才覆盖，
     * 于是那条金黄边会一直挂在状态条右端，直到下一次必发为止。
     *
     * <p>规则：来了新的就覆盖旧的；没来的话，只要还停在同一问上就留着
     * （对手回合每一步末尾都会推不带提示的牌桌帧，那些帧不能把它清掉——
     * 否则玩家还没看清就没了）；一旦<b>换了询问</b>，上一条就已经过期，收掉。
     *
     * <p>另外玩家一旦作答（{@code DuelScreen.dispatch}）也立刻收掉：他动了手，
     * 就是看过了。提示不是询问，收掉它不产生任何应答。
     *
     * @param current      现在挂着的那条（可为 null）
     * @param incoming     这一帧带来的（可为 null）
     * @param sameQuestion 这一帧是不是还停在同一个询问上
     */
    public static <T> T nextNotice(T current, T incoming, boolean sameQuestion) {
        if (incoming != null) {
            return incoming;
        }
        return sameQuestion ? current : null;
    }

    public static boolean showFinish(boolean askStage, boolean yesNo, boolean ready) {
        return askStage || yesNo || ready;
    }

    /**
     * 「取消」这颗键该不该出现。
     *
     * <p>规则来自 ygo 每次点选之后的那段状态机（event_handler.cpp:1320-1326、:711-717）：
     * <b>可取消 且 一个都还没选</b> 才显示——一旦选了东西，「取消」就消失
     * （要改主意就把已选的那张再点一次）。是/否类的「取消」就是「否」，与选没选无关。
     *
     * @param cancelable   询问自己带的可取消标志（{@code DuelQuestion.cancelable()}）
     * @param nothingChosen 当前一个都没选
     */
    public static boolean showCancel(boolean askStage, boolean yesNo, boolean cancelable,
                                     boolean nothingChosen) {
        if (askStage) {
            // 连锁第一段的「取消」＝不发动（ygo 在窗里点否 → SetResponseI(-1)，:245-249）。
            return true;
        }
        if (yesNo) {
            return true;
        }
        return cancelable && nothingChosen;
    }

    public static boolean shouldShow(boolean everShown, boolean hasQuestion, boolean isCurrent) {
        if (isCurrent) {
            // 已经在他眼前了。重复 setScreen 会把界面重建一次，
            // 选卡类问题里表现为「勾好的又没了」。
            return false;
        }
        if (hasQuestion) {
            // 有询问就必须显示：玩家不答，对局就停在那儿。
            // 这也是「玩家自己关掉界面」之后唯一会把他拽回来的情形——
            // 关掉是他的自由，但不该让对局永久卡住。
            return true;
        }
        // 只有牌桌：只在从没露过面时显示。
        // 「露过面」之后就不再自动弹了，否则对手回合里每一步末尾的同步
        // 都会把刚关掉界面的玩家从别的界面里拽回来。
        return !everShown;
    }

    /** 左上角那颗键的三种时点模式，字面照 ygopro {@code strings.conf} 1292/1293/1294。 */
    public enum SkipMode {
        /** 1292 忽略时点：非必发一律不问。 */
        IGNORE,
        /** 1293 显示时点：每个时点都问。 */
        ALWAYS,
        /** 1294 可用时点：有牌能连锁才问（咩咩指定的默认值）。 */
        AVAIL
    }

    /**
     * 点一下换下一个模式。
     *
     * <p>顺序按 ygopro 摆那三颗键的次序（{@code game.cpp:945-947}：忽略／显示／可用），
     * 循环一圈回到原处。默认从 {@link SkipMode#AVAIL} 起。
     */
    public static SkipMode next(SkipMode m) {
        return switch (m) {
            case IGNORE -> SkipMode.ALWAYS;
            case ALWAYS -> SkipMode.AVAIL;
            case AVAIL -> SkipMode.IGNORE;
        };
    }

    /**
     * 这一问要不要<b>不问玩家、直接回「不发动」</b>（{@code -1}）。
     *
     * <p>逐字照抄 ygopro {@code duelclient.cpp:1836}。它那里是：
     * <pre>
     * if(!select_trigger &amp;&amp; !chain_forced
     *    &amp;&amp; (ignore_chain || ((count == 0 || specount == 0) &amp;&amp; !always_chain))
     *    &amp;&amp; (count == 0 || !chain_when_avail))
     * </pre>
     * 化简（{@code specount} 那一项被 {@code count == 0} 吸收掉了，所以这里不必带它进来；
     * {@code SkipModeCheck} 对着原始式子穷举验证过）就是下面这三行：
     * <ul>
     *   <li>必发连锁（{@code chain_forced}）与诱发选择（{@code select_trigger}）——三种模式都照问；</li>
     *   <li>忽略时点：非必发就不问（有没有牌能连锁都一样）；</li>
     *   <li>可用时点：一张能连锁的牌都没有才不问；</li>
     *   <li>显示时点：全都问。</li>
     * </ul>
     *
     * @param forced  必发连锁（我们这边就是 {@code !cancelable()}）
     * @param trigger 诱发效果选择阶段（{@code titleText().key()} 是 {@code chain_trigger}）
     * @param entries 这一问里有几项可以连锁（选项里除「取消」以外的个数）
     */
    public static boolean skipChain(SkipMode mode, boolean forced, boolean trigger, int entries) {
        if (forced || trigger) {
            return false;
        }
        return switch (mode) {
            case ALWAYS -> false;
            case AVAIL -> entries == 0;
            case IGNORE -> true;
        };
    }

    /** 「确认」键此刻该干什么。 */
    public enum Confirm {
        /** 连锁第一段：同意＝要发动（之后才点亮候选）。 */
        AGREE_CHAIN,
        /** 交出当前的选中——需要确认的询问都走这里。 */
        SUBMIT,
        /** 点那个「唯一合法答案」的选项。 */
        PICK_SOLE,
        /** 是/否类里的「是」。 */
        PICK_YES
    }

    /**
     * 「确认」键按下去的动作。
     *
     * <p>2026-10-06 咩咩报：「一次召唤多个怪兽时，最后一个的位置选定后点确认反而取消了选中，
     * 无法操作」。根因就在这个次序上：那只怪兽只剩一个可用格子时 {@code soleOption() >= 0}
     * （选项表恰好剩一项、且没有取消项），确认键于是去 {@code onOption(sole)}——而这只询问是
     * <b>需要确认</b>的（选址/多选/指示物/排序/合计），点一下就是 <b>toggle</b>：刚点中的格子
     * 被取消，再点又选回来，永远交不出去。
     *
     * <p>所以次序必须是：<b>需要确认的询问，确认键只做「交卷」这一件事</b>，绝不去点候选项；
     * 「唯一合法答案」那条捷径只留给不用确认的询问（必发连锁那类：选项表里只有一个必发项，
     * 按确认就是把它交出去）。
     *
     * @param chainAsk    连锁第一段（先问「是否发动效果」）
     * @param needsConfirm 这一问要不要按确认才作答（多选/选址/指示物/排序/合计）
     * @param sole        是否存在「唯一合法答案」那一项
     * @param yes         是/否类里的「是」那一项是否存在
     */
    public static Confirm confirmAction(boolean chainAsk, boolean needsConfirm, boolean sole, boolean yes) {
        if (chainAsk) {
            return Confirm.AGREE_CHAIN;
        }
        if (needsConfirm) {
            return Confirm.SUBMIT;
        }
        if (sole) {
            return Confirm.PICK_SOLE;
        }
        if (yes) {
            return Confirm.PICK_YES;
        }
        return Confirm.SUBMIT;
    }

    /**
     * 确认键现在能不能按（ygo 的「够条件才出现」，{@code duelclient.cpp:1677-1678}）。
     *
     * <p>「唯一合法答案」<b>不能</b>给需要确认的询问当免条件：那种询问要先有选中
     * （格子数/张数够）才能交卷，否则按下去只会得到一个「构造应答失败」。
     */
    public static boolean confirmReady(boolean needsConfirm, boolean countsOk, boolean sole) {
        return countsOk || (sole && !needsConfirm);
    }
}
