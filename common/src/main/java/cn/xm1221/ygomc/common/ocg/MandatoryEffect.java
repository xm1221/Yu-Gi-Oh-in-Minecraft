package cn.xm1221.ygomc.common.ocg;

import cn.xm1221.ygomc.common.ocg.msg.MsgType;

/**
 * 「内核自己发动的必发效果」该不该告诉玩家。
 *
 * <h2>为什么需要这条提示</h2>
 * 必发效果分两种走法：
 * <ol>
 *   <li>内核<b>问过</b>玩家——比如 {@code SELECT_CHAIN} 里只有一个必发项、
 *       连「不发动」都不给。这一种界面上已经摆着一颗「确认」
 *       （{@code DuelQuestion.soleOption()}），玩家按下去才知道发生了什么；</li>
 *   <li>内核<b>没问、自己发动</b>——玩家什么都没按，连锁就起来了。
 *       这一种在界面上原来<b>什么都没有</b>：牌桌刷新一下就过去了，
 *       玩家完全不知道「刚才那一下是谁干的」。</li>
 * </ol>
 * 这里判的是第 2 种。
 *
 * <h2>ygo 怎么做的</h2>
 * 官方客户端也是<b>不弹询问</b>的：{@code MSG_CHAINING}
 * （{@code duelclient.cpp:3010}）只做表现——音效、把那张卡举起来、
 * 在连锁链上记一笔，然后 {@code WaitFrameSignal(30)} 等一下。所以这条
 * 「某某的效果发动（必发）」的提示是<b>我们有意加的</b>，不是照抄；
 * 抄的是「哪些情形算玩家自己发动的」这层判断。
 *
 * <h2>判据</h2>
 * <ul>
 *   <li>连锁的发动者是<b>这一席本人</b>（对手那边起来的不提示，不是他的效果）；</li>
 *   <li>而且这条连锁<b>不是</b>他刚回答某个询问造成的（见 {@link #answerExplains}）——
 *       他自己点的「发动」他自己知道，再弹一条就是噪音；</li>
 *   <li>开关关掉就一条都不发（{@code notifyMandatoryEffects}，默认开）。</li>
 * </ul>
 */
public final class MandatoryEffect {

    private MandatoryEffect() {
    }

    /**
     * 这条连锁要不要给 {@code humanSeat} 发一条「必发」通知。
     *
     * @param triggerSeat       这条连锁的发动者（{@code Msg.Chaining} 里那张卡的 controler）
     * @param humanSeat         收件人那一席
     * @param causedByOwnAnswer 这一席本步内刚回答过一条「可能引发自己发动」的询问
     * @param enabled           配置开关（{@code DuelOptions.notifyMandatoryEffects}）
     */
    public static boolean shouldNotify(int triggerSeat, int humanSeat, boolean causedByOwnAnswer,
                                       boolean enabled) {
        if (!enabled) {
            return false;
        }
        if (triggerSeat != humanSeat) {
            return false;
        }
        return !causedByOwnAnswer;
    }

    /**
     * 这次询问的应答，有没有可能<b>就是</b>玩家自己点了「发动」。
     *
     * <p>回答了这些询问之后紧跟着起来的、属于同一席的连锁，就是他自己的选择：
     * 「要发动哪个效果」（空闲/战斗命令）、「要不要发动」（是否类）、
     * 「连锁哪一张」（{@code SELECT_CHAIN}）、以及选项类。
     *
     * <p>反过来，{@code SELECT_PLACE}（选格子）、{@code SELECT_POSITION}（选表示形式）、
     * {@code SELECT_COUNTER}（指示物）这些答完之后起来的连锁，一定是内核自己发的
     * ——它们本来就不是「发动」。
     *
     * <p>{@code SELECT_CARD} 刻意<b>不算</b>：它绝大多数时候是在选代价或选目标
     * （「解放哪只怪」「给哪张卡装装备」），把它算进去会把「选完代价之后
     * 那只怪的必发效果自己发动了」误判成玩家点的发动。少提示一条只是少一条告知，
     * 多提示一条也不算错——但这条规则宁可漏一点，也不要把「选代价」当「发动」。
     */
    public static boolean answerExplains(int questionType) {
        return questionType == MsgType.SELECT_CHAIN
                || questionType == MsgType.SELECT_EFFECTYN
                || questionType == MsgType.SELECT_IDLECMD
                || questionType == MsgType.SELECT_BATTLECMD
                || questionType == MsgType.SELECT_YESNO
                || questionType == MsgType.SELECT_OPTION;
    }
}
