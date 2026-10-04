package cn.xm1221.ygomc.common.ocg;

import cn.xm1221.ygomc.common.ocg.msg.Msg;
import cn.xm1221.ygomc.common.ocg.msg.MsgCodec;
import cn.xm1221.ygomc.common.ocg.msg.MsgCodecException;
import cn.xm1221.ygomc.common.ocg.msg.MsgType;

import java.util.ArrayList;
import java.util.Arrays;
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
     * <b>同一条询问</b>（原始字节逐字相同）被原样重问到这个次数就判定失败。
     *
     * <h2>为什么不能只看「连续 RETRY」</h2>
     * 内核拒绝一条应答时写 {@code MSG_RETRY} 并返回 {@code FALSE}，下一次 {@code process()}
     * 会<b>把被拒的那条询问原样再写一遍</b>，然后是下一条 {@code MSG_RETRY}……也就是说
     * 真实的重试风暴是 {@code 询问, RETRY, 询问, RETRY, …} 交替，而不是一长串 RETRY。
     * 只看「连续 RETRY 条数」的阈值在这个交替下<b>永远不会触发</b>：每条询问都把计数清零了。
     * 症状因此不是「报错」，而是「安静地跑满步数上限」——正是最难查的那种。
     *
     * <p>所以判据换成「同一条询问被重问了几次」：只有应答被拒时内核才会原样重问，
     * 所以它既精确（不会把「正常地又问了一次同类型问题」算进来）又必然触发。
     */
    private static final int SAME_QUERY_REASK_LIMIT = 8;

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

    /** 被拒应答的总次数。 */
    private int retries;
    /** <b>被拒 N 次、按类型</b>：询问类型 → 被拒次数。类型是该应答所属询问的类型。 */
    private final Map<Integer, Integer> retriesByType = new LinkedHashMap<>();
    /** 每种询问<b>第一次</b>被拒时的「请求字节 / 应答字节」。定位非法字段靠它。 */
    private final Map<Integer, String> firstRejectionByType = new LinkedHashMap<>();
    /** 上一次被应答的询问的原始字节（内核是照着它重问的）。 */
    private byte[] lastQueryBytes;
    /** 上一条消息是 RETRY，正在等同一条询问被重问。 */
    private boolean retryPending;
    /** 同一条询问（原始字节逐字相同）被重问了几次。 */
    private int sameQueryReasks;

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
     * 给某一方灌主卡组。必须在 {@link #start()} 之前。
     *
     * <p>卡号必须已经在 {@link OcgEngine} 里灌过；未知卡号不会崩，但那张卡在
     * 对局里没有效果也没有数值（内核会按全零处理）。
     */
    public OcgDuel addDeck(int player, int[] codes) {
        requireNotStarted("灌卡组");
        for (int code : shuffled(codes)) {
            Ocg.newCard(handle, code, player, player, Ocg.LOCATION_DECK, 0, Ocg.POS_FACEDOWN_DEFENSE);
        }
        return this;
    }

    /**
     * 洗牌——**这一步不能省**。
     *
     * <p>内核只在效果要求时才洗牌（{@code field::shuffle}），开局时它<b>照单全收宿主给的顺序</b>。
     * 也就是说「卡组顺序」这件事完全是宿主的责任：ygopro 客户端就是在把卡组交给内核
     * <b>之前</b>自己先洗一遍（{@code single_duel.cpp:429-431}、{@code tag_duel.cpp:399-403}
     * 里的 {@code rnd.shuffle_vector(pdeck[i].main)}）。
     *
     * <p>我们以前是照着 {@code .ydk} 的文件顺序逐张灌进去的，等于<b>永远不洗牌</b>：
     * 每局的开局手牌、每次抽牌的顺序都一模一样。对玩家来说是「测不出东西」，
     * 对测试来说更糟——它会让「换一副卡组重跑」这种对照失去意义。
     *
     * <p>用 Fisher-Yates，并且<b>不改动传进来的数组</b>（公开出来是为了能在离线自检里直接断言）：调用方常常直接传自己的常量表。
     */
    public static int[] shuffled(int[] codes) {
        int[] out = codes.clone();
        for (int i = out.length - 1; i > 0; i--) {
            int j = SHUFFLE_RNG.nextInt(i + 1);
            int t = out[i];
            out[i] = out[j];
            out[j] = t;
        }
        return out;
    }

    /**
     * 洗牌用的随机源。
     *
     * <p>默认每次对局都不一样（玩家要的就是这个）。但要能<b>钉死</b>：
     * 回归测试需要可复现的开局，否则「上一次跑出来的 837 步」这种对照就没法比。
     */
    private static final java.util.Random SHUFFLE_RNG = new java.util.Random();

    /** 用固定种子钉住洗牌，供离线回归测试复现同一局。 */
    public static void seedShuffle(long seed) {
        SHUFFLE_RNG.setSeed(seed);
    }

    /**
     * 给某一方灌额外卡组。必须在 {@link #start()} 之前。
     *
     * <p>额外卡组必须用 {@link Ocg#LOCATION_EXTRA} 单独灌，不能混进主卡组——
     * 融合/同调/超量/连接怪兽只有在额外卡组里才能被特殊召唤，
     * 混进主卡组的话它们会变成「抽得到、永远出不来」的死牌。
     */
    public OcgDuel addExtraDeck(int player, int[] codes) {
        requireNotStarted("灌额外卡组");
        for (int code : codes) {
            Ocg.newCard(handle, code, player, player, Ocg.LOCATION_EXTRA, 0, Ocg.POS_FACEDOWN_DEFENSE);
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

    /**
     * 取内核的整场快照（一个完整的 {@code MSG_RELOAD_FIELD}）。
     *
     * <p><b>只能在对局线程上调用。</b>内核除 {@code create} / {@code end_duel} 之外不加锁，
     * 从别的线程碰它就是在和数据竞争。界面需要在「引擎刚问完、应答还没交回去」
     * 那个精确时刻取快照，所以调用点是 {@link Observer#onMessage}，
     * <b>不是</b>客户端的渲染线程。
     *
     * <p>拿到的是内核自己的状态，而不是「我对消息流的复述」——这正是它比逐条重建
     * MOVE / DRAW / POS_CHANGE 可靠的地方：漏解一条消息、算错一个坐标，
     * 重建出来的牌桌会安静地偏掉，而且往往看起来还挺合理；快照不会。
     *
     * <p>注意快照里只有<b>区域占用与位置</b>，没有卡号（卡号要按可见性另查
     * {@code Ocg.queryFieldCard}）——这是内核刻意为之，否则就等于把对手的盖牌
     * 直接告诉客户端了。
     *
     * @return 快照；内核未给出内容时返回 null
     */
    public Msg.ReloadField snapshot() {
        byte[] out = new byte[Ocg.SIZE_QUERY_BUFFER];
        int n = Ocg.queryFieldInfo(handle, out);
        if (n <= 0) {
            return null;
        }
        Msg m = MsgCodec.decode(out, 0);
        if (!(m instanceof Msg.ReloadField field)) {
            throw new IllegalStateException("queryFieldInfo 返回的不是 MSG_RELOAD_FIELD，而是 "
                    + MsgType.name(m.type()));
        }
        return field;
    }

    /**
     * {@code queryFieldCard} 的原始字节，长度已裁到实际写出量。
     *
     * <p>调用方必须<b>在持有本句柄的线程上</b>调用（内核除 {@code create}/{@code end_duel}
     * 外都不是线程安全的）。解析见 {@code FieldCodes.parse}。
     *
     * @param seat     座位 0/1
     * @param location {@code common.h:55-64} 的 {@code LOCATION_*}
     * @param flag     {@code common.h:230-252} 的 {@code QUERY_*} 位组合
     */
    public byte[] fieldCardBytes(int seat, int location, int flag) {
        byte[] out = new byte[Ocg.SIZE_QUERY_BUFFER];
        int n = Ocg.queryFieldCard(handle, seat, location, flag, false, out);
        if (n < 0 || n > out.length) {
            throw new IllegalStateException("queryFieldCard 返回了非法长度 " + n
                    + "（座位 " + seat + "，区域 " + location + "）");
        }
        return n == out.length ? out : java.util.Arrays.copyOf(out, n);
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
    /**
     * @param retries              被拒应答的总次数（{@code MSG_RETRY} 的条数）
     * @param retriesByType        <b>被拒 N 次、按询问类型</b>；键是应答被拒的那条询问的类型
     * @param firstRejectionByType 每种询问第一次被拒时的「消息 + 请求字节 + 应答字节」
     */
    public record Outcome(int winner, int reason, int steps, int queries,
                          Map<Integer, Integer> messageCounts, String error,
                          int retries, Map<Integer, Integer> retriesByType,
                          Map<Integer, String> firstRejectionByType) {

        /** 没有重试数据可报时用（例如还没开局就抛异常）。 */
        public Outcome(int winner, int reason, int steps, int queries,
                       Map<Integer, Integer> messageCounts, String error) {
            this(winner, reason, steps, queries, messageCounts, error, 0, Map.of(), Map.of());
        }

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

        /**
         * 「被拒 N 次、按类型降序」的表。
         *
         * <p>只看 {@code MSG_RETRY} 的总数没有诊断价值——它既不说是哪一类询问在挨拒，
         * 也不说拒了几次。这张表是「哪条应答构造错了」的第一手证据。
         */
        public String retryHistogram() {
            if (retriesByType.isEmpty()) {
                return "（没有任何应答被拒）";
            }
            List<Map.Entry<Integer, Integer>> entries = new ArrayList<>(retriesByType.entrySet());
            entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
            StringBuilder sb = new StringBuilder();
            int rank = 1;
            for (Map.Entry<Integer, Integer> e : entries) {
                sb.append(String.format("%n  %2d. %-22s 被拒 %d 次", rank++,
                        MsgType.name(e.getKey()), e.getValue()));
            }
            sb.append("\n  合计 ").append(retries).append(" 次（涉及 ")
              .append(retriesByType.size()).append(" 种询问）");
            return sb.toString();
        }
    }

    /** 默认的推进上限。实测一整局只需几千步，这个值足够宽松又能兜住死循环。 */
    public static final int DEFAULT_MAX_STEPS = 200_000;

    /**
     * 一方要用的牌。主卡组与额外卡组必须分开给——额外卡组要灌进
     * {@link Ocg#LOCATION_EXTRA}，混进主卡组会让那些卡永远无法出场。
     *
     * @param main  主卡组卡号
     * @param extra 额外卡组卡号；没有就给空数组
     */
    public record DeckLoadout(int[] main, int[] extra) {

        public DeckLoadout {
            main = main.clone();
            extra = extra.clone();
        }

        /** 只有主卡组。 */
        public static DeckLoadout of(int[] main) {
            return new DeckLoadout(main, new int[0]);
        }
    }

    /**
     * 用给定策略把一局跑到底。<b>会阻塞当前线程</b>。
     *
     * @param decks {@code decks[0]} 是先手方的牌，{@code decks[1]} 是后手方
     */
    public static Outcome playOut(int[] seeds, DeckLoadout[] decks, Responder responder, int maxSteps) {
        return playOut(seeds, decks, responder, maxSteps, null);
    }

    /**
     * 对局过程中的观察点。
     *
     * <p><b>在对局线程上同步调用</b>，所以实现里可以安全地用
     * {@link OcgDuel#snapshot()} 向内核索取状态——这是唯一能安全拿到内核状态的线程。
     *
     * <p>{@code awaitingAnswer} 为 true 的那一次调用是界面的关键点：此时引擎已经问完、
     * 应答尚未交回，所以拿到的快照是「玩家该做决定的那一刻」的牌桌，
     * 还没有被这次决定改变。
     *
     * <p>实现抛出的异常会终止本局并记为失败，不会被吞掉。
     */
    @FunctionalInterface
    public interface Observer {
        void onMessage(OcgDuel duel, Msg m, boolean awaitingAnswer);

        /**
         * 原始消息字节的旁路：一条消息一段，长度已经由解码器切好。
         *
         * <p>默认什么都不做。存在的理由是：{@link Msg} 只保留解出来的字段，
         * <b>不带原始字节</b>，而「把实时对局录下来、再离线跑同一个比对」需要原样字节。
         * 靠字段重新编码去伪造是不行的——那会引入第二个编码器，
         * 而两个编码器互相印证等于自证。
         *
         * <p>实现必须<b>立刻拷走</b>自己需要的内容：{@code buffer} 是复用的。
         */
        default void onRawMessage(byte[] buffer, int offset, int length) {
        }

        /**
         * 一个「步」的消息全部处理完之后调用一次（{@code duel.advance()} 的边界）。
         *
         * <p>存在的理由是<b>逐步同步</b>：一次 {@code advance()} 可能带回十几条消息
         * （见本文件开头那段实测），逐条发给客户端既浪费也没意义——客户端只画最后一帧。
         * 按「步」发，正好是「一步一片牌桌」。
         *
         * <p>与 {@link #onMessage} 的分工：{@code onMessage} 是「每条消息」，
         * 用来观察/计数；这里是「一步的末尾」，用来做状态同步。
         */
        default void onStepEnd(OcgDuel duel) {
        }
    }

    /**
     * 带观察点地跑完一局。
     *
     * @param observer 可为 null
     */
    public static Outcome playOut(int[] seeds, DeckLoadout[] decks, Responder responder, int maxSteps,
                                  Observer observer) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        int steps = 0;
        int queries = 0;
        try (OcgDuel duel = OcgDuel.create(seeds)) {
            duel.addDeck(0, decks[0].main());
            duel.addExtraDeck(0, decks[0].extra());
            duel.addDeck(1, decks[1].main());
            duel.addExtraDeck(1, decks[1].extra());
            duel.start();

            int winner = -1;
            int reason = -1;
            // 无进展检测用：连续同一种询问之间没有任何「非询问」消息，就是在原地打转。
            int repeatedType = -1;
            int repeatStreak = 0;

            while (steps++ < maxSteps && winner < 0) {
                // 内核自己不响应中断，所以「中止」只能落在消息边界上——
                // DuelSession.abort() 打的中断标记就是靠这一句生效的。
                if (Thread.currentThread().isInterrupted()) {
                    return new Outcome(-1, -1, steps, queries, counts, "被中止",
                            duel.retries, duel.retriesByType, duel.firstRejectionByType);
                }
                Step step = duel.advance();
                for (Msg m : step.messages()) {
                    counts.merge(m.type(), 1, Integer::sum);
                    if (observer != null) {
                        // 原始字节旁路放在所有分支【之前】：WIN 与询问那两类都会
                        // 各自 continue，晚一步调用就会把最关键的消息录丢。
                        observer.onRawMessage(duel.buffer, m.offset(), m.length());
                    }

                    if (m.type() == MsgType.WIN) {
                        Msg.Win win = (Msg.Win) m;
                        winner = win.winner();
                        reason = win.reason();
                        duel.retryPending = false;
                        if (observer != null) {
                            observer.onMessage(duel, m, false);
                        }
                        continue;
                    }
                    if (!m.isQuery()) {
                        // 任何非询问消息都说明对局状态真的变了（发牌、移动、阶段推进…），
                        // 这是「有进展」的判据。
                        repeatedType = -1;
                        repeatStreak = 0;
                        // 非询问消息插入进来，说明「上一次应答被拒后内核原样重问」这条链断了，
                        // 于是同一条询问的重问计数也该归零。
                        duel.retryPending = false;
                        duel.sameQueryReasks = 0;
                        if (observer != null) {
                            observer.onMessage(duel, m, false);
                        }
                        continue;
                    }

                    if (m.type() == repeatedType) {
                        if (++repeatStreak > NO_PROGRESS_LIMIT) {
                            return new Outcome(-1, -1, steps, queries, counts,
                                    "连续 " + repeatStreak + " 次 " + MsgType.name(m.type())
                                            + " 之间没有任何状态变化：应答策略在做「合法但无进展」的原地循环。"
                                            + "注意这类问题不会产生 RETRY（每个应答本身都合法），"
                                            + "所以只能这样探测。最后一次询问=" + m,
                                    duel.retries, duel.retriesByType, duel.firstRejectionByType);
                        }
                    } else {
                        repeatedType = m.type();
                        repeatStreak = 1;
                    }

                    if (m.type() == MsgType.RETRY) {
                        // 被拒的是【上一次应答】所对应的那条询问，所以按 lastQuery 的类型归类。
                        // 不这样做的话这里只有「总共多少条 RETRY」，看不出是哪一类询问在挨拒。
                        if (duel.lastQuery != null) {
                            duel.retries++;
                            duel.retriesByType.merge(duel.lastQuery.type(), 1, Integer::sum);
                            duel.firstRejectionByType.putIfAbsent(duel.lastQuery.type(),
                                    duel.lastQuery + "\n      请求字节 " + hex(duel.lastQueryBytes)
                                            + "\n      应答字节 " + describe(duel.lastResponse));
                        }
                        if (++duel.retryStreak > RETRY_STORM_LIMIT) {
                            return new Outcome(-1, -1, steps, queries, counts,
                                    "连续 " + duel.retryStreak + " 次 RETRY：应答策略给出的值始终非法。"
                                            + "最后一次询问是 " + duel.lastQuery,
                                    duel.retries, duel.retriesByType, duel.firstRejectionByType);
                        }
                        if (duel.lastResponse == null) {
                            return new Outcome(-1, -1, steps, queries, counts,
                                    "收到 RETRY 但没有可重发的应答",
                                    duel.retries, duel.retriesByType, duel.firstRejectionByType);
                        }
                        duel.retryPending = true;
                        duel.respond(duel.lastResponse);
                        continue;
                    }

                    duel.retryStreak = 0;

                    // ── 同一条询问被原样重问？ ──────────────────────────────────
                    // 内核拒绝应答后会【逐字重发同一条询问】，所以「原始字节相同」就是
                    // 「应答被拒」的确证，而不是「又问了一次同类型的问题」。
                    byte[] raw = Arrays.copyOfRange(duel.buffer, m.offset(), m.offset() + m.length());
                    if (duel.retryPending && Arrays.equals(raw, duel.lastQueryBytes)) {
                        duel.sameQueryReasks++;
                    } else {
                        duel.sameQueryReasks = 0;
                    }
                    duel.retryPending = false;
                    duel.lastQueryBytes = raw;
                    if (duel.sameQueryReasks > SAME_QUERY_REASK_LIMIT) {
                        return new Outcome(-1, -1, steps, queries, counts,
                                "同一条询问被原样重问 " + (duel.sameQueryReasks + 1)
                                        + " 次：应答一直被内核拒绝，而重发的是同一个值。"
                                        + "\n" + describeRejection(m, duel)
                                        + "\n（注意交替出现的询问/RETRY 会让「连续 RETRY」计数永远归零，"
                                        + "所以只看那个计数是发现不了的。）",
                                duel.retries, duel.retriesByType, duel.firstRejectionByType);
                    }
                    // 观察点：引擎已经问完、应答还没交回去。
                    // 此时取快照拿到的就是「玩家该做决定的那一刻」的牌桌。
                    if (observer != null) {
                        observer.onMessage(duel, m, true);
                    }
                    Responder.Response response = responder.answer(m);
                    if (response == null) {
                        return new Outcome(-1, -1, steps, queries, counts,
                                "应答策略对 " + MsgType.name(m.type()) + " 返回了 null",
                                duel.retries, duel.retriesByType, duel.firstRejectionByType);
                    }
                    duel.lastQuery = m;
                    duel.lastResponse = response;
                    queries++;
                    duel.respond(response);
                }
                // 一步的消息处理完了。逐步同步挂在这里：此处是 advance() 的边界，
                // 牌桌状态这时才是自洽的（不会发到「移动了一半」的中间态）。
                if (observer != null) {
                    observer.onStepEnd(duel);
                }
            }

            if (winner < 0) {
                return new Outcome(-1, -1, steps, queries, counts,
                        "推进 " + steps + " 步仍未收局（可能卡在某个询问上）",
                        duel.retries, duel.retriesByType, duel.firstRejectionByType);
            }
            duel.finished = true;
            return new Outcome(winner, reason, steps, queries, counts, null,
                    duel.retries, duel.retriesByType, duel.firstRejectionByType);

        } catch (RuntimeException e) {
            return new Outcome(-1, -1, steps, queries, counts, e.toString());
        }
    }

    // ── 报错文本里的字节 ──────────────────────────────────────────────────

    /** 一段字节的十六进制形式；{@code null} 或空数组给 {@code "(无)"}。 */
    private static String hex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "(无)";
        }
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    /** 一条应答的可读形式，和送进内核的字节一一对应。 */
    private static String describe(Responder.Response r) {
        if (r == null) {
            return "(无)";
        }
        return r.isBytes() ? "bvalue[" + hex(r.bytes()) + "]" : "ivalue=" + r.value();
    }

    /**
     * 「这条询问的请求字节 + 我回的应答字节 + 内核会怎么判它」。
     *
     * <p>失败时把两边的字节都摆出来，是为了让定位停在「哪几个字节」上，
     * 而不是停在一句「应答被拒」上。
     */
    private static String describeRejection(Msg query, OcgDuel duel) {
        return "询问 " + MsgType.name(query.type()) + " → " + query
                + "\n      请求字节 " + hex(duel.lastQueryBytes)
                + "\n      应答字节 " + describe(duel.lastResponse);
    }
}
