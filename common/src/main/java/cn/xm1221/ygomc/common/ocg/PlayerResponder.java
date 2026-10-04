package cn.xm1221.ygomc.common.ocg;

import cn.xm1221.ygomc.common.duel.DuelQuestion;
import cn.xm1221.ygomc.common.duel.SumSelect;
import cn.xm1221.ygomc.common.ocg.msg.Msg;

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

    // 以下全部只在持有 lock 时读写。
    private DuelQuestion pending;
    private Response answer;
    private boolean answered;
    private boolean cancelled;
    private long asked;
    private long autoAnswered;
    private long rejectedSubmits;

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

    @Override
    public Response answer(Msg msg) {
        DuelQuestion question = DuelQuestion.of(msg);
        if (question.mode() == DuelQuestion.Mode.UNSUPPORTED) {
            if (msg instanceof Msg.SelectSum s) {
                // SELECT_SUM 的应答是「一组下标」而不是「一个数值」，塞不进当前的问题模型
                // （选项表 + 一个取值），所以先在这里直接求解。
                //
                // 待清理：这形成了 ocg → duel 的包间环（DuelQuestion 本来就在 duel → ocg）。
                // 正确的归宿是把 SumSelect 挪进 ocg（它只依赖 Responder/Msg），
                // 再让 DuelQuestion 反向调用它来给界面摆选项。现在先保证功能可用。
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

        synchronized (lock) {
            pending = question;
            answer = null;
            answered = false;
            cancelled = false;
            asked++;
            // 通知放在【持锁区内】是有意的：此时 pending 已经就位，玩家就算立刻
            // 应答，也会在 submit 里等这把锁，直到下面进入 wait() 才继续，
            // 于是不可能出现「应答比等待先到」而被丢掉的真空窗口。
            // 放到锁外就正好有这么一个窗口，而且它只在玩家手速快时出现。
            java.util.function.Consumer<DuelQuestion> l = listener;
            if (l != null) {
                l.accept(question);
            }
            lock.notifyAll();
            while (!answered) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    // 对局被中止。必须抛出去让对局线程解开，不能吞掉当作已作答——
                    // 吞掉的话内核会拿着上一次的应答继续跑，局面就串了。
                    pending = null;
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "对局被中止，问题未作答：" + question.describe(), e);
                }
            }
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
            lock.notifyAll();
        }
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

    public String report() {
        DuelQuestion q;
        synchronized (lock) {
            q = pending;
        }
        return "玩家应答器：提问 " + asked() + " 次，自动兜底 " + autoAnswered()
                + " 次，被挡下的提交 " + rejectedSubmits() + " 次"
                + (q != null ? "（末次提问后仍在待答）" : "");
    }
}
