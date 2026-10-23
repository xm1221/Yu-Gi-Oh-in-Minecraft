package cn.xm1221.ygomc.common.duel;

/**
 * 决斗台右键该做什么。**纯逻辑**：只吃布尔、返回枚举，不碰 Minecraft，所以能离线穷举。
 *
 * <p>和 {@link LobbyRules} 分开是因为台子多了「这一格上有没有人在等、有没有一局正在打」。
 * 判据若写进 {@code DuelTables}，那个类一引用 {@code ServerPlayer} 就没法离线测。
 *
 * <h2>顺序就是内容</h2>
 * 两个毛病同时存在时先说哪一个，决定玩家下一步干什么。台上已经有一局、而点的人
 * 自己也在别处开着局，就该先说「你已有一局在进行中」——说「这台正在对局」会让他
 * 站在别人的台子前发呆，该做的是先把自己那局收掉。台上的参与者例外：他点的就是
 * 自己那一局，先把界面拉回来。
 */
public final class TableRules {

    /** 这一格现在是空的、有人候场，还是已经开局。 */
    public enum Phase {
        EMPTY,
        WAITING,
        LIVE
    }

    /** 这一下右键要做的事。{@code null} 不会出现。 */
    public enum Action {
        /** 空台：坐下等对手。 */
        SIT,
        /** 候场的人再点一次：离开。 */
        LEAVE,
        /** 潜行：用已选卡组对人机开局。 */
        START_SOLO,
        /** 第二个玩家：和候场的人开双人局。 */
        JOIN,
        /** 这一局的参与者：把界面拉回来。 */
        REOPEN,
        /** 旁观：只告诉他谁在打。 */
        SPECTATE,
        /** 点的人自己已经有一局。 */
        BUSY,
        /** 候场的人已经不在线。 */
        HOST_OFFLINE,
        /** 候场的人已经用别的入口开了局。这一席该腾出来，不能再加入。 */
        HOST_STARTED,
        /** 点的人还没选卡组。 */
        NEED_MY_DECK,
        /** 候场的人还没选卡组。 */
        NEED_THEIR_DECK
    }

    /**
     * @param phase                这一格的状态
     * @param clickerIsHost        点的人是不是正在这里候场的那个
     * @param clickerIsParticipant 点的人是不是这一格正在打的那一局的人
     * @param clickerDueling       点的人是不是已经有一局（不论在哪）
     * @param hostOnline           候场的人还在不在线；空台时无意义
     * @param myDeck               点的人已经选了卡组
     * @param hostDeck             候场的人已经选了卡组；空台时无意义
     * @param sneak                这一下是不是潜行
     * @param hostDueling          候场的人自己已经有一局（例如用指令或决斗盘开的）
     */
    public record View(Phase phase, boolean clickerIsHost, boolean clickerIsParticipant,
                       boolean clickerDueling, boolean hostOnline, boolean myDeck,
                       boolean hostDeck, boolean sneak, boolean hostDueling) {
    }

    private TableRules() {
    }

    public static Action decide(View v) {
        if (v.phase == Phase.LIVE) {
            if (v.clickerIsParticipant) {
                return Action.REOPEN;
            }
            if (v.clickerDueling) {
                return Action.BUSY;
            }
            return Action.SPECTATE;
        }
        if (v.clickerDueling) {
            return Action.BUSY;
        }
        if (v.phase == Phase.WAITING) {
            if (!v.clickerIsHost && !v.hostOnline) {
                return Action.HOST_OFFLINE;
            }
            if (!v.clickerIsHost && v.hostDueling) {
                return Action.HOST_STARTED;
            }
            if (v.clickerIsHost) {
                if (!v.sneak) {
                    return Action.LEAVE;
                }
                return v.myDeck ? Action.START_SOLO : Action.NEED_MY_DECK;
            }
            if (!v.myDeck) {
                return Action.NEED_MY_DECK;
            }
            if (!v.hostDeck) {
                return Action.NEED_THEIR_DECK;
            }
            return Action.JOIN;
        }
        if (v.sneak) {
            return v.myDeck ? Action.START_SOLO : Action.NEED_MY_DECK;
        }
        return Action.SIT;
    }
}
