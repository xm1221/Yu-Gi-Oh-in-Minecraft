package cn.xm1221.ygomc.common.ocg;

import cn.xm1221.ygomc.common.ocg.msg.Msg;
import cn.xm1221.ygomc.common.ocg.msg.MsgCodec;
import cn.xm1221.ygomc.common.ocg.msg.MsgCodecException;
import cn.xm1221.ygomc.common.ocg.msg.MsgType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一局对局的驱动。
 *
 * <h2>它做什么、不做什么</h2>
 * <b>做</b>：把内核的一局包成对象——灌卡组、开局、推进、把输出缓冲解成一串
 * {@link Msg}、把 {@link Responder} 给的应答回填给引擎。
 * <b>不做</b>：任何规则判断。规则完全在 ocgcore 里，这里只是搬运。
 *
 * <h2>推进的粒度</h2>
 * {@link #advance()} 调一次 {@code process()}，把这次拿到的<b>整个缓冲</b>解成消息列表。
 * 一个缓冲里通常有多条消息（实测平均 1.07 条，最多十几条），所以不要假设
 * 「一次 advance 一条消息」。
 *
 * <h2>线程模型</h2>
 * 内核允许每局一个专用线程，但<b>同一局不能并发调用</b>。本类不加锁，
 * 由调用方保证串行——{@link #playOut} 就是在当前线程一口气跑完的。
 * 需要不阻塞主线程时，自己把它丢到别的线程上（M1 的 {@code /ygomc selftest} 就是这么做的）。
 *
 * <h2>RETRY 的处理</h2>
 * 应答被引擎拒绝时它会发 {@code MSG_RETRY}，宿主必须<b>重发上一次应答</b>。
 * 本类记住了上一次的应答并自动重发。但重发同一个值如果一直被拒，就会变成
 * 无进展的死循环，所以连续 RETRY 超过阈值就直接判定失败并报出最后一条询问消息——
 * 否则症状是「卡住」，没有任何线索。
 */
public final class OcgDuel implements AutoCloseable {

    /** 连续 RETRY 到这个次数就认为应答策略有问题，放弃。 */
    private static final int RETRY_STORM_LIMIT = 8;

    /**
     * 连续同一种询问而无任何状态变化的上限。
     *
     * <p>用来抓「每个应答都合法、但对局原地打转」这类问题——它<b>不会</b>产生 RETRY，
     * 所以只看 RETRY 是发现不了的，最后只会表现为「跑满步数没收局」。
     */
    private static final int NO_PROGRESS_LIMIT = 500;

    private final long handle;
    private final byte[] buffer = new byte[Ocg.SIZE_MESSAGE_BUFFER];

    /** 上一次的应答，RETRY 时重发。 */
    private Responder.Response lastResponse;
    /** 触发上一次应答的那条询问消息，只用于报错。 */
    private Msg lastQuery;
    private int retryStreak;

    private boolean started;
    private boolean finished;

    private OcgDuel(long handle) {
        this.handle = handle;
    }

    /**
     * 建一局。
     *
     * @param seeds 必须是 8 个；同一副种子必然产生同一局（内核用它做确定性洗牌）
     */
    public static OcgDuel create(int[] seeds) {
        if (!OcgEngine.isReady()) {
            throw new IllegalStateException("引擎尚未就绪：" + OcgEngine.problem());
        }
        long h = Ocg.create(seeds);
        if (h == 0) {
            throw new IllegalStateException("Ocg.create 返回 0");
        }
        OcgDuel duel = new OcgDuel(h);
        Ocg.setPlayerInfo(h, 0, 8000, 5, 1);
        Ocg.setPlayerInfo(h, 1, 8000, 5, 1);
        return duel;
    }

    /**
     * 给某一方灌卡组。必须在 {@link #start()} 之前。
     *
     * <p>卡号必须已经在 {@link OcgEngine} 里灌过；未知卡号不会崩，但那张卡在
     * 对局里没有效果也没有数值（内核会按全零处理）。
     */
    public OcgDuel addDeck(int player, int[] codes) {
        requireNotStarted("灌卡组");
        for (int code : codes) {
            Ocg.newCard(handle, code, player, player, Ocg.LOCATION_DECK, 0, Ocg.POS_FACEDOWN_DEFENSE);
        }
        return this;
    }

    /** 开局。此后引擎开始吐消息。 */
    public OcgDuel start() {
        requireNotStarted("开局");
        Ocg.startDuel(handle, Ocg.CURRENT_RULE << 16);
        started = true;
        return this;
    }

    /**
     * 推进一步。
     *
     * @return 本次拿到的一批消息（可能为空）
     */
    public Step advance() {
        if (!started) {
            throw new IllegalStateException("还没开局");
        }
        int status = Ocg.process(handle);
        int available = status & Ocg.PROCESSOR_BUFFER_LEN;
        if (available == 0) {
            return new Step(List.of(), (status & Ocg.PROCESSOR_WAITING) != 0, finished);
        }

        int written = Ocg.getMessage(handle, buffer);
        List<Msg> messages = new ArrayList<>();
        for (int off = 0; off < written; ) {
            Msg m;
            try {
                m = MsgCodec.decode(buffer, off);
            } catch (MsgCodecException e) {
                // 解码错位是致命的：从这里往后整条流都是垃圾。装作没事继续跑
                // 只会得到一堆莫名其妙的消息，所以直接抛出去。
                throw new IllegalStateException(
                        "消息解码失败（偏移 " + off + "/" + written + "）：" + e.getMessage(), e);
            }
            messages.add(m);
            off += m.length();
        }
        return new Step(messages, (status & Ocg.PROCESSOR_WAITING) != 0, finished);
    }

    /** 把应答回填给引擎。 */
    public void respond(Responder.Response response) {
        if (response.isBytes()) {
            Ocg.setResponseB(handle, response.bytes());
        } else {
            Ocg.setResponseI(handle, response.value());
        }
    }

    public boolean isFinished() {
        return finished;
    }

    @Override
    public void close() {
        Ocg.destroy(handle);
    }

    private void requireNotStarted(String what) {
        if (started) {
            throw new IllegalStateException("已经开局，不能再" + what);
        }
    }

    // ── 一次推进的结果 ────────────────────────────────────────────────────

    /**
     * @param messages 本次解码出的消息
     * @param waiting  引擎是否处于「等应答」状态
     * @param finished 对局是否已结束（收到 {@code MSG_WIN}）
     */
    public record Step(List<Msg> messages, boolean waiting, boolean finished) {
    }

    // ── 直接跑完一局 ──────────────────────────────────────────────────────

    /**
     * @param winner       胜者 0/1；{-1} 表示没分出胜负，{@link Ocg#PLAYER_NONE}（5）表示平局
     * @param reason       胜因，取值见下
     * @param steps        推进次数
     * @param queries      应答过的询问数
     * @param messageCounts 各消息类型出现次数（按类型号）
     * @param error        失败原因；成功时为 null
     *
     * <p>胜因只有两个取值，来自内核 {@code processor.cpp:4836-4862} 的
     * {@code field::adjust_step}：
     * <ul>
     *   <li>{@code 1} = 生命值归零（{@code rea = 1}）；</li>
     *   <li>{@code 2} = <b>卡组抽爆</b>（{@code rea = 2}）。注意<b>不是</b>「双方同时抽爆」——
     *       双方同时抽爆时胜者会是 {@code PLAYER_NONE} 而胜因仍是 2。
     *       所以「胜因」必须和「胜者」一起看，只看胜因会误判。</li>
     * </ul>
     */
    public record Outcome(int winner, int reason, int steps, int queries,
                          Map<Integer, Integer> messageCounts, String error) {

        public boolean won() {
            return winner >= 0;
        }

        /** 人类可读的消息类型直方图，按出现次数降序。 */
        public String histogram() {
            List<Map.Entry<Integer, Integer>> entries = new ArrayList<>(messageCounts.entrySet());
            entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<Integer, Integer> e : entries) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(MsgType.name(e.getKey())).append('=').append(e.getValue());
            }
            return sb.toString();
        }
    }

    /** 默认的推进上限。实测一整局只需几千步，这个值足够宽松又能兜住死循环。 */
    public static final int DEFAULT_MAX_STEPS = 200_000;

    /**
     * 用给定策略把一局跑到底。<b>会阻塞当前线程</b>。
     *
     * @param decks {@code decks[0]} 是先手方的卡组，{@code decks[1]} 是后手方
     */
    public static Outcome playOut(int[] seeds, int[][] decks, Responder responder, int maxSteps) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        int steps = 0;
        int queries = 0;
        try (OcgDuel duel = OcgDuel.create(seeds)) {
            duel.addDeck(0, decks[0]);
            duel.addDeck(1, decks[1]);
            duel.start();

            int winner = -1;
            int reason = -1;
            // 无进展检测用：连续同一种询问之间没有任何「非询问」消息，就是在原地打转。
            int repeatedType = -1;
            int repeatStreak = 0;

            while (steps++ < maxSteps && winner < 0) {
                Step step = duel.advance();
                for (Msg m : step.messages()) {
                    counts.merge(m.type(), 1, Integer::sum);

                    if (m.type() == MsgType.WIN) {
                        Msg.Win win = (Msg.Win) m;
                        winner = win.winner();
                        reason = win.reason();
                        continue;
                    }
                    if (!m.isQuery()) {
                        // 任何非询问消息都说明对局状态真的变了（发牌、移动、阶段推进…），
                        // 这是「有进展」的判据。
                        repeatedType = -1;
                        repeatStreak = 0;
                        continue;
                    }

                    if (m.type() == repeatedType) {
                        if (++repeatStreak > NO_PROGRESS_LIMIT) {
                            return new Outcome(-1, -1, steps, queries, counts,
                                    "连续 " + repeatStreak + " 次 " + MsgType.name(m.type())
                                            + " 之间没有任何状态变化：应答策略在做「合法但无进展」的原地循环。"
                                            + "注意这类问题不会产生 RETRY（每个应答本身都合法），"
                                            + "所以只能这样探测。最后一次询问=" + m);
                        }
                    } else {
                        repeatedType = m.type();
                        repeatStreak = 1;
                    }

                    if (m.type() == MsgType.RETRY) {
                        if (++duel.retryStreak > RETRY_STORM_LIMIT) {
                            return new Outcome(-1, -1, steps, queries, counts,
                                    "连续 " + duel.retryStreak + " 次 RETRY：应答策略给出的值始终非法。"
                                            + "最后一次询问是 " + duel.lastQuery);
                        }
                        if (duel.lastResponse == null) {
                            return new Outcome(-1, -1, steps, queries, counts,
                                    "收到 RETRY 但没有可重发的应答");
                        }
                        duel.respond(duel.lastResponse);
                        continue;
                    }

                    duel.retryStreak = 0;
                    Responder.Response response = responder.answer(m);
                    if (response == null) {
                        return new Outcome(-1, -1, steps, queries, counts,
                                "应答策略对 " + MsgType.name(m.type()) + " 返回了 null");
                    }
                    duel.lastQuery = m;
                    duel.lastResponse = response;
                    queries++;
                    duel.respond(response);
                }
            }

            if (winner < 0) {
                return new Outcome(-1, -1, steps, queries, counts,
                        "推进 " + steps + " 步仍未收局（可能卡在某个询问上）");
            }
            duel.finished = true;
            return new Outcome(winner, reason, steps, queries, counts, null);

        } catch (RuntimeException e) {
            return new Outcome(-1, -1, steps, queries, counts, e.toString());
        }
    }
}
