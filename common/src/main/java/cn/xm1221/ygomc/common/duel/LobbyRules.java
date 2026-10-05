package cn.xm1221.ygomc.common.duel;

/**
 * 双人候场的判据。**纯逻辑**：只吃布尔、返回枚举，不碰 Minecraft，所以能离线穷举。
 *
 * <p>为什么不跟 {@link DuelLobby} 放一起：那边一引用 {@code Component}（要说话），
 * 整个类的加载就离不开 Minecraft 运行时，判据也就跟着没法离线跑了。
 * 「那么做的时候加载不了」这种约束不会在编译期露头——编译期是好的，
 * 只有真去跑才报 {@code NoClassDefFoundError: net/minecraft/...}。
 * 判据与动作分开，这条约束就不存在了。
 *
 * <h2>顺序就是内容</h2>
 * 两个毛病同时存在时先说哪一个，是<b>有语义的</b>：它决定玩家下一步干什么。
 * 比如自己已经开局了、对方也没选卡组，就该先说「你已有一局在进行中」——
 * 说「对方还没选卡组」会让他跑去催对方，而该做的是先把自己那局收掉。
 * 所以这几条 if 的顺序不能随手调换，{@code LobbyCheck} 里逐条钉住了。
 */
public final class LobbyRules {

    /** 开不了局的原因。{@code null} 表示没问题。 */
    public enum Blocker {
        /** 邀请自己。 */
        SELF,
        /** 发起方已经有对局。 */
        ME_DUELING,
        /** 对方已经有对局。 */
        THEM_DUELING,
        /** 没有人邀请你。 */
        NO_INVITE,
        /** 邀请你的人不在了。 */
        INVITER_GONE,
        /** 自己还没选卡组。 */
        MY_DECK,
        /** 对方还没选卡组。 */
        THEIR_DECK
    }

    private LobbyRules() {
    }

    /**
     * 邀请能不能发出去。
     *
     * <p>先判「自己」再判「忙」，最后才轮到「对方忙」——把对方的状况排在自己前面，
     * 会让一个自己都没空的人收到「对方没空」这种答非所问的回话。
     */
    public static Blocker inviteBlocker(boolean samePlayer, boolean meDueling, boolean themDueling) {
        if (samePlayer) {
            return Blocker.SELF;
        }
        if (meDueling) {
            return Blocker.ME_DUELING;
        }
        if (themDueling) {
            return Blocker.THEM_DUELING;
        }
        return null;
    }

    /**
     * 接受邀请能不能开局。
     *
     * <p>「没有邀请」最先（这时后面几个参数本来就没意义），然后是「人不在了」，
     * 再是双方忙闲，最后才轮到卡组。
     */
    public static Blocker acceptBlocker(boolean hasInvite, boolean inviterOnline,
                                        boolean meDueling, boolean themDueling,
                                        boolean myDeck, boolean theirDeck) {
        if (!hasInvite) {
            return Blocker.NO_INVITE;
        }
        if (!inviterOnline) {
            return Blocker.INVITER_GONE;
        }
        if (meDueling) {
            return Blocker.ME_DUELING;
        }
        if (themDueling) {
            return Blocker.THEM_DUELING;
        }
        if (myDeck) {
            // 两个都缺时先说自己的：那是自己马上能解决的一件事。
            return theirDeck ? null : Blocker.THEIR_DECK;
        }
        return Blocker.MY_DECK;
    }
}
