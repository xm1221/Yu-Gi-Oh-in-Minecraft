package cn.xm1221.ygomc.common.duel;

import cn.xm1221.ygomc.common.ocg.PlayerResponder;

import java.util.concurrent.locks.LockSupport;

/**
 * 临时自动玩家：看到待答问题就按默认取向点一下。
 *
 * <h2>它是什么、不是什么</h2>
 * 它<b>不是</b> AI 对手，而是「坐在客户端那边的玩家」的占位实现——
 * 用来在没有界面之前把「引擎 ↔ 玩家」这条链路跑通并验证。
 * 真正的实现是客户端的 {@code DuelScreen}：服务器把问题推过去，玩家点完推回来。
 *
 * <h2>为什么这里可以轮询，而真正的实现不行</h2>
 * 这里轮询是因为「玩家」和「引擎」在同一个进程里，代价只是几次内存读。
 * 换成网络之后，服务器不能轮询等待——那样会把一个游戏线程的时间全花在空转上；
 * 必须改成「问题到达时推送、应答到达时唤醒对局线程」，也就是
 * {@link PlayerResponder#submit} 由收包线程调用，而不是这样主动去找问题。
 * 所以这个类在设计上就是一次性的：它只服务于「没有界面也要能验证」这个阶段。
 */
public final class AutoPlayer implements AutoCloseable {

    /** 没待答问题时的轮询间隔。比引擎推进一步的耗时小得多，所以不会拖慢对局。 */
    private static final long IDLE_PARK_NANOS = 200_000L;

    private final PlayerResponder responder;
    private final Thread thread;
    private volatile boolean running = true;
    private volatile long answered;
    private volatile long failed;

    public AutoPlayer(PlayerResponder responder, String label) {
        this.responder = responder;
        this.thread = new Thread(this::loop, "ygomc-autoplayer-" + label);
        this.thread.setDaemon(true);
    }

    public void start() {
        thread.start();
    }

    private void loop() {
        while (running) {
            DuelQuestion q = responder.pending();
            if (q == null) {
                LockSupport.parkNanos(IDLE_PARK_NANOS);
                continue;
            }
            try {
                if (responder.submit(q.response(q.defaultChoice()))) {
                    answered++;
                } else {
                    // 没被接受：要么已经被别人答了，要么选项组合不合法。
                    // 计入失败而不是重试——重试会在「组合不合法」时变成死循环。
                    failed++;
                    LockSupport.parkNanos(IDLE_PARK_NANOS);
                }
            } catch (RuntimeException e) {
                failed++;
                LockSupport.parkNanos(IDLE_PARK_NANOS);
            }
        }
    }

    public long answered() {
        return answered;
    }

    public long failed() {
        return failed;
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
    }
}
