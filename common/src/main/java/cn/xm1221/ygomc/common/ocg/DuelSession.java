package cn.xm1221.ygomc.common.ocg;

import java.util.function.Consumer;

/**
 * 一个在<b>自己的线程上</b>跑的对局会话。
 *
 * <h2>为什么不能直接在调用方的线程上跑</h2>
 * 一局要推进几百到几千步。实测空载下一局约 700 ms，但真实服务器上会有卡组更复杂的
 * 对局、会有多个玩家同时开局，赌「它很快」就是拿整个服务器的卡顿做赌注。
 * 而内核本来就期望「每局一个专用线程」（它除了 {@code create}/{@code end_duel}
 * 之外不加锁），所以另开线程既安全又是它推荐的用法。
 *
 * <h2>结果的交付</h2>
 * 结果要发回玩家时必须绕回服务端主线程——{@code CommandSourceStack}、
 * 实体、世界都不是线程安全的。所以这里只负责把结果放进字段并回调
 * {@code onDone}，由调用方在回调里自己做 {code server.execute(...)}。
 * 本类<b>不</b>替调用方决定线程切换。
 *
 * <h2>取消</h2>
 * {@link #abort()} 只是打一个中断标记；真正的退出发生在 {@link OcgDuel#playOut}
 * 的循环里检查中断的那一刻（内核自己不响应中断，所以只能在消息边界上停）。
 * 退出路径仍然是 {@code try-with-resources}，{@code Ocg.destroy} 一定会执行，
 * 不会泄漏原生侧的对局对象。
 */
public final class DuelSession {

    public enum State {
        /** 正在推进。 */
        RUNNING,
        /** 正常收局（含平局）。 */
        FINISHED,
        /** 没跑完：中断、超步数、或应答策略出错。 */
        FAILED
    }

    private final String label;
    private final Thread thread;
    private final long startedAtNanos = System.nanoTime();
    private volatile State state = State.RUNNING;
    private volatile OcgDuel.Outcome outcome;
    private volatile String failure;
    private volatile long millis;

    private DuelSession(String label, Thread thread) {
        this.label = label;
        this.thread = thread;
    }

    /**
     * 开一局。
     *
     * @param label  用于日志和线程名，例如 {@code "selftest"} 或玩家名
     * @param decks  {@code decks[0]} 先手方的牌，{@code decks[1]} 后手方
     * @param onDone  结束时回调，<b>在自动对局线程上</b>执行；允许为 null
     */
    public static DuelSession start(String label, OcgDuel.DeckLoadout[] decks, Responder responder,
                                    Consumer<DuelSession> onDone) {
        DuelSession[] holder = new DuelSession[1];
        Thread thread = new Thread(() -> {
            // 线程里要能拿到 session 自己，所以用一个单元素数组把引用传进去
            // （线程体在构造完成前就开始执行了，不能用普通字段）。
            DuelSession self = holder[0];
            try {
                OcgDuel.Outcome result = OcgDuel.playOut(
                        new int[]{1, 2, 3, 4, 5, 6, 7, 8}, decks, responder,
                        OcgDuel.DEFAULT_MAX_STEPS);
                self.outcome = result;
                self.failure = result.error();
                self.state = result.won() ? State.FINISHED : State.FAILED;
            } catch (Throwable t) {
                // 这里必须兜住 Throwable 而不只是 Exception：线程体一旦抛出，
                // 状态会永远停在 RUNNING，调用方就会一直以为对局还在跑。
                self.failure = t.toString();
                self.state = State.FAILED;
            } finally {
                // 耗时在这里记，而不是让调用方自己掐表：调用方拿到回调时对局已经结束了，
                // 它掐出来的只会是「回调处理用了多久」。
                self.millis = (System.nanoTime() - self.startedAtNanos) / 1_000_000L;
                if (onDone != null) {
                    try {
                        onDone.accept(self);
                    } catch (RuntimeException e) {
                        self.state = State.FAILED;
                    }
                }
            }
        }, "ygomc-duel-" + label);
        thread.setDaemon(true);

        DuelSession session = new DuelSession(label, thread);
        holder[0] = session;
        thread.start();
        return session;
    }

    public String label() {
        return label;
    }

    public State state() {
        return state;
    }

    public boolean isRunning() {
        return state == State.RUNNING;
    }

    /** 收局结果；还在跑、或没跑完时为 null。 */
    public OcgDuel.Outcome outcome() {
        return outcome;
    }

    /** 没跑完的原因；正常收局或还在跑时为 null。 */
    public String failure() {
        return failure;
    }

    /** 从开局到结束的毫秒数；还在跑时为 0。 */
    public long millis() {
        return millis;
    }

    /**
     * 请求中止。立即返回，不等待线程结束。
     *
     * @return 是否确实发出了中断（false 表示这局已经结束了）
     */
    public boolean abort() {
        if (!isRunning()) {
            return false;
        }
        thread.interrupt();
        return true;
    }

    /** 一行状态，给 {@code /ygomc status} 和日志用。 */
    public String describe() {
        StringBuilder sb = new StringBuilder(label).append(": ");
        switch (state) {
            case RUNNING -> sb.append("进行中");
            case FINISHED -> sb.append("玩家 ").append(outcome.winner())
                    .append(" 获胜（胜因 ").append(outcome.reason()).append("），")
                    .append(outcome.steps()).append(" 步");
            case FAILED -> sb.append("未完成：").append(failure);
        }
        return sb.toString();
    }
}
