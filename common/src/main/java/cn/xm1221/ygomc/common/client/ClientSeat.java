package cn.xm1221.ygomc.common.client;

/**
 * 这一帧该用哪个座位当「我方」。
 *
 * <h2>为什么不能只看询问</h2>
 * 服务器推的帧有两种：带询问的（轮到我了）和只有牌桌的（对手回合里每一步末尾都会推）。
 * 以前 {@code mySeat} 是从询问里取的（{@code question.player()}），于是第二种帧
 * 推不出座位，只能保持 0 号席——后手玩家看到的整张牌桌都是对手的视角，
 * 而且 {@code me()} 指向对手那份数据，<b>自己的手牌一张都看不见</b>
 * （对手手牌在服务端就没有卡号）。咩咩 2026-10-05 报的就是这个。
 *
 * <p>所以座位改成<b>跟着每一帧走</b>（{@code YgomcNet.sendBoard} 的第 4 个参数，
 * 就是 {@code FieldCodes.attach} 用的那个视角座位）。询问里的座位只作为
 * 旧帧（没有座位字段）的退回判据。
 */
public final class ClientSeat {

    /** 询问座位无效时传这个（没有询问，或字段缺失）。 */
    public static final int NONE = -1;

    private ClientSeat() {
    }

    /**
     * @param frameSeat    帧里带的视角座位；没有就是 {@link #NONE}
     * @param questionSeat 询问所属座位；没有询问就是 {@link #NONE}
     * @return 0 或 1
     */
    public static int of(int frameSeat, int questionSeat) {
        if (frameSeat == 0 || frameSeat == 1) {
            // 帧里的座位是按【收件人】发的，它永远比询问可靠，冲突时以它为准。
            return frameSeat;
        }
        if (questionSeat == 0 || questionSeat == 1) {
            return questionSeat;
        }
        return 0;
    }
}
