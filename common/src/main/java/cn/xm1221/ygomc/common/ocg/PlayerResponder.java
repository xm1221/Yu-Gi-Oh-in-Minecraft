package cn.xm1221.ygomc.common.ocg;

import cn.xm1221.ygomc.common.duel.DuelQuestion;
import cn.xm1221.ygomc.common.ocg.msg.Msg;
import java.util.List;

/**
 * 把「引擎问一句」变成「挂起这局，等玩家点一下，再答回去」。
 *
 * <h2>为什么不需要改引擎</h2>
 * {@code OcgDuel.playOut} 是在<b>对局线程上同步调用</b> {@link Responder#answer} 的。
 * 所以「等玩家」就只是「在这个方法里阻塞」——对局线程被挂住，内核自然停在那一步，
 * 一行引擎代码都不用动。反过来也意味着：{@link Responder#answer} 要么返回，
 * 要么必须能被别的线程唤醒；睡着不醒就是死局。
 *
 * <h2>同时只有一个待答问题</h2>
 * 因为对局线程阻塞着，不可能产生第二个问题，所以这一层不需要队列：
 * 一个「当前问题 + 已作答标志」就够，也就没有乱序作答的可能。
 *
 * <h2>不能建模的询问要显式回退，不能静默当作已处理</h2>
 * 本项目还有几种询问没实现（见 {@link DuelQuestion.Mode#UNSUPPORTED}）。
 * 遇到时交给 {@code fallback} 并计数——静默处理会让「玩家明明没点过这一步」
 * 变成事后无法解释的现象。
 *
 * @see cn.xm1221.ygomc.common.duel.AutoPlayer
 */
public final class PlayerResponder implements Responder {

    private final Object lock = new Object();
    private final Responder fallback;

    /**
     * 「新问题诞生」的通知口，由对局房间装入，用来把问题推给客户端。
     *
     * <p>有了它，真实客户端就不必像 {@code AutoPlayer} 那样轮询 {@code pending()}——
     * 轮询是给进程内替身用的临时手段，延迟与空转都不该带进正式链路。
     */
    private volatile java.util.function.Consumer<DuelQuestion> listener;

    /**
     * 「要对玩家说一句话」的通知口，由对局房间装入（发聊天栏）。
     *
     * <p>超时兜底必须<b>说出来</b>：玩家走开一趟回来发现对局已经自己走了一步，
     * 如果界面上没有任何痕迹，那就和「界面点不动/答错卡」完全分不开。
     */
    private volatile java.util.function.Consumer<String> notice;

    // 以下全部只在持有 lock 时读写。
    private DuelQuestion pending;
    private Response answer;
    private boolean answered;
    private boolean cancelled;
    private long asked;
    private long autoAnswered;
    /**
     * 超时替真人作答的次数。
     *
     * <p>与 {@link #autoAnswered} <b>分开计数</b>：那个数的是「本来就不该问真人」的询问
     * （对手席位、界面未实现），这个数的是「问了真人但没等到」。
     * 混在一起时，一局里出现大量自动应答根本分不清是哪种原因——
     * 前者正常，后者说明玩家在走开或者界面卡住了。
     */
    private long timeoutAnswers;

    /**
     * 超时之后该做什么，由外面定：房间接的是「判负」。
     *
     * <p>没人接就一直等（见 {@link #fireTimeout}）——无论哪种，都<b>不</b>替他作答。
     */
    private volatile Runnable onTimeout;
    private long rejectedSubmits;

    /**
     * 因为「问了也白问」而被静默放过的连锁询问次数。
     *
     * <p>在 {@code autoAnswered} 之外单独再记一笔，是为了让「玩家被反复询问」
     * 这件事在自检报告里<em>看得见</em>：只看总数分不清那些应答是真人点的
     * 还是被策略挡掉的。策略见 {@link DuelOptions}。
     */
    private long chainSkipped;
    /** 待答期间的定时任务，答完/取消后必须撤掉，否则会拿旧题去答新题。 */
    private java.util.concurrent.ScheduledFuture<?> timeoutTask;
    // timeoutTask 随代答一起删掉了：现在只剩提醒这一个定时任务。
    /** 超时后才允许替玩家作答，用来把「提醒」与「代答」分成两段（D22）。 */
    private java.util.concurrent.atomic.AtomicLong timeoutSeq =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * 共享的守护调度器。
     *
     * <p>一局一个线程已经够重了，再给每局配一个 Timer 是浪费；超时任务只做
     * 「唤醒对局线程」这一件小事，所以全局一个线程足够。
     * 线程设为 daemon，免得服务器退出时被一个睡着的超时任务拖住。
     */
    private static final java.util.concurrent.ScheduledExecutorService TIMERS =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ygomc-duel-timeout");
                t.setDaemon(true);
                return t;
            });

    /** 等玩家作答的秒数（D22）。 */
    private volatile long timeoutSeconds = 300;
    /** 超时提醒之后再宽限多久才代答（D22）。 */
    private volatile long graceSeconds = 60;

    /**
     * @param fallback 无法建模的询问交给谁；null 表示遇到就抛异常（让对局明确失败）
     */
    public PlayerResponder(Responder fallback) {
        this.fallback = fallback;
    }

    /** 装入「问题诞生」的通知口（由对局房间调用）。 */
    public void setListener(java.util.function.Consumer<DuelQuestion> listener) {
        this.listener = listener;
    }

    /** 真人席位。{@code -1} 表示不区分席位（M1 自检那样两边都答）。 */
    private volatile int seat = -1;

    /**
     * 指定真人坐哪一席。
     *
     * <p>不设的话，本类会为<b>双方</b>的询问阻塞等待——真人被问到对手该答的问题，
     * 界面上还会把对手的选项摆给他。M1 自检没暴露这一点，因为那时两种应答都是自动的。
     */
    public void setSeat(int seat) {
        this.seat = seat;
    }

    /**
     * 第一个必发连锁项的下标；一个都没有时退到 0。
     *
     * <p>调用方只在 {@code hasForced()} 为真时才走这里，所以「一个都没有」是
     * 内核字段自相矛盾的异常情况——那时回 0 仍然会被内核校验，比抛异常
     * 把整局打崩要好：这一手最多是选错一项，而抛异常会让对局直接结束。
     */
    private static int firstForcedIndex(Msg.SelectChain m) {
        List<Msg.SelectChain.ChainEntry> entries = m.entryList();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).isForced()) {
                return i;
            }
        }
        return 0;
    }

    @Override
    public Response answer(Msg msg) {
        DuelQuestion question = DuelQuestion.of(msg);
        if (question.mode() == DuelQuestion.Mode.UNSUPPORTED) {
            if (msg instanceof Msg.SelectSum s) {
                // 走到这里说明是 SELECT_SUM 的【无上限分支】（flag != 0）：
                // 有上限的那一支已经由 DuelQuestion 建成 SUM 类询问、交给玩家点了。
                // 这一支的校验规则还没逐行核实，只能明确失败——宁可报错，
                // 也不要发一个必然被 MSG_RETRY 打回的应答，那会变成「卡住」。
                synchronized (lock) {
                    autoAnswered++;
                }
                return SumSelect.solve(s);
            }
            if (fallback == null) {
                throw new IllegalStateException(
                        "这条询问还没有实现界面：" + question.describe()
                                + "；要么给它写应答策略，要么构造 PlayerResponder 时给一个 fallback");
            }
            synchronized (lock) {
                autoAnswered++;
            }
            return fallback.answer(msg);
        }

        // 对手那半场的询问要立刻交出去，不能阻塞等真人。
        if (seat >= 0 && question.player() != seat) {
            synchronized (lock) {
                autoAnswered++;
            }
            if (fallback == null) {
                throw new IllegalStateException(
                        "对手席位的询问没有 fallback：" + question.describe());
            }
            return fallback.answer(msg);
        }

        // 「明明没有可以发动的效果也要问一遍」——官方客户端在这种时点默认是【静默回 -1】的
        // （duelclient.cpp:1836：没有候选项且没开「显示时点」时直接 SendResponse，一个像素
        // 都不画）。我们【默认反过来】：唯一合法答案也摆给玩家自己按（咩咩 2026-10-05）。
        // 想省事就在配置里把「唯一合法答案自动应答」打开，那时才是官方那一套。
        if (msg instanceof Msg.SelectChain sc) {
            DuelOptions.ChainAction action =
                    DuelOptions.chainAction(sc.hasForced(), sc.entryList().size());
            if (action != DuelOptions.ChainAction.ASK) {
                synchronized (lock) {
                    autoAnswered++;
                    chainSkipped++;
                }
                return Responder.Response.of(action == DuelOptions.ChainAction.PICK_FIRST
                        ? firstForcedIndex(sc) : -1);
            }
        }

        synchronized (lock) {
            pending = question;
            answer = null;
            answered = false;
            cancelled = false;
            asked++;
            // 超时判负必须按【这一道题】挂号：拿一个自增序号把它绑到当前问题，
            // 否则玩家答完 A 题、引擎又问 B 题时，A 的定时器醒来会把 B 判负。
            long seq = timeoutSeq.incrementAndGet();
            cancelTimers();
            if (timeoutSeconds > 0) {
                timeoutTask = TIMERS.schedule(() -> fireTimeout(seq, question),
                        timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
            }
            lock.notifyAll();
        }
        // Publish after installing pending. An immediate submit is retained by answered,
        // even when it arrives before this thread starts waiting.
        try {
            var l = listener;
            if (l != null) l.accept(question);
        } catch (RuntimeException e) {
            synchronized (lock) { pending = null; }
            throw e;
        }
        synchronized (lock) {
            while (!answered) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    // 对局被中止。必须抛出去让对局线程解开，不能吞掉当作已作答——
                    // 吞掉的话内核会拿着上一次的应答继续跑，局面就串了。
                    pending = null;
                    cancelTimers();
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "对局被中止，问题未作答：" + question.describe(), e);
                }
            }
            cancelTimers();
            if (cancelled) {
                pending = null;
                throw new IllegalStateException("问题已被取消：" + question.describe());
            }
            Response r = answer;
            pending = null;
            answer = null;
            return r;
        }
    }

    /**
     * 超时了：判负（咩咩定：超时判负，默认 100 秒，服务端可配）。
     *
     * <p>注意它<b>不回答</b>这一题。不许替玩家做决定这条规矩没变，
     * 变的只是「他不动」之后怎么收场：原来是提醒完一直等，现在是判他负。
     * 具体怎么判由 {@code onTimeout} 决定——广播收局、通知两边、让对局线程
     * 解开，这些只有房间知道（照搬 ygopro {@code SingleDuel::Surrender}：
     * 它根本不碰内核，是服务端自己造收局消息）。
     *
     * <p>没人接这个回调时（例如纯逻辑自检）退回老行为：只提醒、一直等。
     * 那样至少不会静默挂住，也绝不会替他作答。
     *
     * <p>回调必须按题目序号挂号：玩家答完 A 题、引擎又问 B 题时，
     * A 的定时器醒来绝不能把 B 判负——只有「这一道题还没被答掉」才算数。
     */
    private void fireTimeout(long seq, DuelQuestion question) {
        synchronized (lock) {
            if (timeoutSeq.get() != seq || answered || pending != question) {
                return;
            }
        }
        Runnable action = onTimeout;
        if (action == null) {
            say("已经等了 " + timeoutSeconds + " 秒还没有收到你的操作；会一直等你。"
                    + "要中止这一局用 /ygomc duel abort。");
            return;
        }
        say("已经等了 " + timeoutSeconds + " 秒还没有收到你的操作，本局判负。");
        action.run();
    }

    /**
     * 挂上「超时判负」的执行者。房间在开局时装好（它才知道席位与对手）；
     * 不装就是「只提醒、一直等」。
     */
    public void setOnTimeout(Runnable action) {
        this.onTimeout = action;
    }

    // 代答（onTimeoutAnswer）已经删掉：它违背「不许替玩家做决定」。
    // answerWithDefault() 仍然留着——「托管」（AutoPlayer）用它是有意的，
    // 那是玩家明确要求替自己打；超时路径不再碰它。

    /** 撤掉挂着的定时任务。必须在持有 {@code lock} 时调用。 */
    private void cancelTimers() {
        if (timeoutTask != null) {
            timeoutTask.cancel(false);
            timeoutTask = null;
        }
        // 提醒任务上面已经撤掉了；代答任务不存在了。
    }

    private void say(String message) {
        var n = notice;
        if (n != null) {
            try {
                n.accept(message);
            } catch (RuntimeException ignored) {
                // 通知失败不能反过来影响对局：它只是聊天栏里的一行字。
            }
        }
    }

    /**
     * 玩家侧：当前待答的问题；没有则 null。
     *
     * <p><b>「待答」的定义是「还没被答」，不只是「还没被清空」。</b>
     * 对局线程从被唤醒到把 {@code pending} 置空之间有一个窗口，此时应答已经记录、
     * 但问题还在。如果这里直接把字段返回出去，客户端就会看到一个已经答过的旧问题，
     * 玩家点它必然被 {@link #submit} 拒绝——实测 248 次提问产生了 247 次这种
     * 无用提交。功能上被挡住了，但那是「靠下层的守卫兜住上层的错」，
     * 正确的做法是这一层就不要把它报成待答。
     */
    public DuelQuestion pending() {
        synchronized (lock) {
            return answered ? null : pending;
        }
    }

    /**
     * 玩家侧：用 {@link DuelQuestion#options()} 里的下标作答。
     *
     * @return 是否被接受。<b>false 不等于成功</b>：它表示这题已经答过、
     *         或者选项组合不合法。重复点击必须被挡住，否则两次点击会拼成两个应答，
     *         而引擎只收得下第一个。
     */
    public boolean submit(int... chosen) {
        DuelQuestion q;
        synchronized (lock) {
            q = pending;
        }
        if (q == null) {
            return false;
        }
        Response r;
        try {
            r = q.response(chosen);
        } catch (RuntimeException e) {
            // 选项组合不合法：让问题保持待答，玩家重选，而不是把非法应答发出去。
            synchronized (lock) {
                rejectedSubmits++;
            }
            return false;
        }
        return submit(r);
    }

    /**
     * 玩家侧：直接交一个应答。
     *
     * <p>引擎只收得下第一个，所以这里用「已作答」标志挡住后来的提交——
     * 这是<b>唯一</b>的防重复点机制。
     */
    public boolean submit(Response r) {
        if (r == null) {
            return false;
        }
        synchronized (lock) {
            if (pending == null || answered) {
                rejectedSubmits++;
                return false;
            }
            answer = r;
            answered = true;
            cancelTimers();
            lock.notifyAll();
            return true;
        }
    }

    /** 中止：让阻塞中的对局线程带着异常解开（对局记为失败，而不是悄悄继续）。 */
    public void cancel() {
        synchronized (lock) {
            if (pending == null) {
                return;
            }
            cancelled = true;
            answered = true;
            cancelTimers();
            lock.notifyAll();
        }
    }

    /** 装入「要对玩家说一句话」的通知口（由对局房间调用）。 */
    public void setNotice(java.util.function.Consumer<String> notice) {
        this.notice = notice;
    }

    /**
     * 设置等待玩家的时限（秒），以及超时提醒之后的宽限时间。
     *
     * <p>0 或负数表示不限时。默认 300 + 60（D22）。
     * 测试要能在几秒内跑完，所以不能把时限写死。
     */
    /**
     * 设超时提醒的时限。
     *
     * <p>{@code graceSeconds} 已经<b>不再起作用</b>（宽限期是给代答用的，而代答取消了）。
     * 留着这个参数只是不想惊动已有调用方；下次动这个 API 时把它删掉。
     */
    public void setTimeouts(long timeoutSeconds, long graceSeconds) {
        this.timeoutSeconds = timeoutSeconds;
        this.graceSeconds = graceSeconds;
    }

    /**
     * 超时兜底：把当前问题按默认取向答掉。
     *
     * <p>这不是「玩家没点就替他点」的常态逻辑，而是防止玩家走开时对局线程永远挂着——
     * 挂着的局会一直占着对局名额，服务端的名额是有限的。
     */
    public boolean answerWithDefault() {
        DuelQuestion q = pending();
        if (q == null || q.mode() == DuelQuestion.Mode.UNSUPPORTED) {
            return false;
        }
        return submit(q.response(q.defaultChoice()));
    }

    public long asked() {
        synchronized (lock) {
            return asked;
        }
    }

    public long autoAnswered() {
        synchronized (lock) {
            return autoAnswered;
        }
    }

    public long rejectedSubmits() {
        synchronized (lock) {
            return rejectedSubmits;
        }
    }

    /** 超时替真人作答的次数。 */
    public long timeoutAnswers() {
        synchronized (lock) {
            return timeoutAnswers;
        }
    }

    /**
     * 按询问策略静默放过的连锁次数（见 {@link DuelOptions}）。
     *
     * <p>单独暴露出来是为了在自检本里能断言「策略确实生效了」：
     * 只有它非零，才说明那些空询问真的没有摆到玩家面前。
     */
    public long chainSkipped() {
        synchronized (lock) {
            return chainSkipped;
        }
    }

    public String report() {
        DuelQuestion q;
        synchronized (lock) {
            q = pending;
        }
        return "玩家应答器：提问 " + asked() + " 次，非真人兜底 " + autoAnswered()
                + " 次，超时代答 " + timeoutAnswers() + " 次，被挡下的提交 " + rejectedSubmits() + " 次" + "，按询问策略静默放过 " + chainSkipped() + " 次"
                + "（等 " + timeoutSeconds + "s" + (graceSeconds > 0 ? "+" + graceSeconds + "s" : "")
                + "）"
                + (q != null ? "（末次提问后仍在待答）" : "");
    }
}
