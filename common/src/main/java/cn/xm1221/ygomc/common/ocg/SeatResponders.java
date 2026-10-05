package cn.xm1221.ygomc.common.ocg;

import cn.xm1221.ygomc.common.duel.DuelQuestion;

import java.util.function.Consumer;

/**
 * 按席位组装应答器的两种摆法。
 *
 * <p>引擎只认一个 {@link Responder}，但它会把<b>双方</b>的询问都送进来。
 * {@link PlayerResponder} 的规矩是：设了 {@code seat} 之后只为自己那一席阻塞等待，
 * 别席的询问原样交给兜底（{@code PlayerResponder.answer} 里那条
 * {@code question.player() != seat} 的分支）。所以「谁坐哪一席」这件事
 * 完全由<b>兜底链</b>表达：
 *
 * <pre>
 *   人机：human(seat=0, fallback=贪心)            ← 只有 0 号席会等人
 *   双人：a(seat=0, fallback=b)，b(seat=1, fallback=贪心)
 *         引擎拿 a：0 号席的问题 a 自己等人；
 *                   1 号席的问题 a 转给 b，由 b 去等另一个真人
 * </pre>
 *
 * <p>抽出来是因为这条链<b>必须是对的</b>：摆错了不会报错，只会让某个真人的
 * 界面弹出对手该答的问题，或者让某一席一直没人应答直到超时兜底——
 * 看起来像「引擎卡住了」，实际是席位配错了。摆对了就能离线用真实内核验
 * （见 {@code VersusProbe}）。
 *
 * <p>注意<b>不要把「双人」摆法用在人机上</b>：那样 1 号席也会变成「等人」，
 * 而人机局里没人坐在 1 号席，每步都要等满超时才动。
 */
public final class SeatResponders {

    private SeatResponders() {
    }

    /**
     * 人机：真人坐 {@code humanSeat}，另一席全交给 {@code bot}。
     *
     * @param notice 超时提醒往哪说（可为 {@code null}）
     */
    public static PlayerResponder humanVsBot(int humanSeat, Responder bot,
                                             Consumer<DuelQuestion> toHuman,
                                             Consumer<String> notice) {
        PlayerResponder human = new PlayerResponder(bot);
        human.setSeat(humanSeat);
        human.setListener(toHuman);
        human.setNotice(notice);
        return human;
    }

    /**
     * 双人：0 号席是 {@code first}，1 号席是 {@code second}，两席都会等人。
     *
     * @param bot  万一某一席的提问落在意料之外时最后的兜底（正常不会用到）
     * @return 长度 2 的数组，{@code [0]} 给引擎，{@code [1]} 是 1 号席那一个
     */
    public static PlayerResponder[] versus(Responder bot,
                                          Consumer<DuelQuestion> toFirst,
                                          Consumer<String> noticeFirst,
                                          Consumer<DuelQuestion> toSecond,
                                          Consumer<String> noticeSecond) {
        PlayerResponder second = new PlayerResponder(bot);
        second.setSeat(1);
        second.setListener(toSecond);
        second.setNotice(noticeSecond);

        // 1 号席那一个当 0 号席的兜底：0 号席不接的询问原样落到 1 号席的等待上。
        PlayerResponder first = new PlayerResponder(second);
        first.setSeat(0);
        first.setListener(toFirst);
        first.setNotice(noticeFirst);
        return new PlayerResponder[]{first, second};
    }
}
