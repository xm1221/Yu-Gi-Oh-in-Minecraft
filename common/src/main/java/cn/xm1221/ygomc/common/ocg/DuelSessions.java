package cn.xm1221.ygomc.common.ocg;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 正在跑和刚跑完的对局会话的登记处。
 *
 * <h2>为什么要限制并发局数</h2>
 * 内核允许每局一个线程、线程之间互不干扰，所以「能跑几局」的瓶颈不在引擎而在机器：
 * 每局都要读 Lua 脚本、占一个线程、推进几千步。不设上限的话，
 * 一个循环命令就能把服务器拖垮。上限取 4 是保守值——真实对局是人对人，
 * 同一台服务器同时开 4 桌已经不少了。
 *
 * <h2>为什么已完成的对局也留着</h2>
 * 玩家问「刚才那局谁赢了」时，答案在会话对象里，而那时线程早就结束了。
 * 所以完成后不立刻丢掉，而是按「最近完成」排序保留一小段历史，
 * 由 {@code /ygomc status} 展示。超出历史的按最旧的先丢，但<b>正在跑的永不丢</b>——
 * 丢掉一个还在跑的会话就等于失去了中止它的唯一手柄。
 */
public final class DuelSessions {

    /** 同时进行的对局上限。 */
    public static final int MAX_CONCURRENT = 4;

    /** 保留多少条会话记录（含已完成的）。 */
    private static final int HISTORY = 16;

    /** 会话 → 次序号。用 Map 而不是 List，是为了让「完成时刷新次序」不会插入重复项。 */
    private static final Map<DuelSession, Long> SESSIONS = new ConcurrentHashMap<>();
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private DuelSessions() {
    }

    /**
     * 开一局并登记。
     *
     * @throws IllegalStateException 同时进行的对局已达 {@link #MAX_CONCURRENT}
     */
    public static synchronized DuelSession start(String label, OcgDuel.DeckLoadout[] decks,
                                                Responder responder,
                                                Consumer<DuelSession> onDone) {
        return start(label, decks, responder, null, onDone);
    }

    /**
     * 带观察点地开一局并登记。
     *
     * @param observer 在对局线程上逐条消息回调；可为 null
     */
    public static synchronized DuelSession start(String label, OcgDuel.DeckLoadout[] decks,
                                                Responder responder, OcgDuel.Observer observer,
                                                Consumer<DuelSession> onDone) {
        long running = SESSIONS.keySet().stream().filter(DuelSession::isRunning).count();
        if (running >= MAX_CONCURRENT) {
            throw new IllegalStateException(
                    "同时进行的对局已达上限 " + MAX_CONCURRENT + "，请等一局结束");
        }
        DuelSession session = DuelSession.start(label, decks, responder, observer, self -> {
            // 完成时刷新次序，让它排到「最近」那一端，这样刚打完的局不会因为
            // 启动得早而被历史裁剪掉。
            SESSIONS.put(self, SEQUENCE.incrementAndGet());
            if (onDone != null) {
                onDone.accept(self);
            }
        });
        SESSIONS.put(session, SEQUENCE.incrementAndGet());
        trim();
        return session;
    }

    /** 正在跑的会话，按开始时间从早到晚。 */
    public static List<DuelSession> active() {
        return SESSIONS.keySet().stream()
                .filter(DuelSession::isRunning)
                .sorted(Comparator.comparingLong(SESSIONS::get))
                .toList();
    }

    /** 最近的会话（含已完成），最新的在前。 */
    public static List<DuelSession> recent(int limit) {
        return SESSIONS.entrySet().stream()
                .sorted(Map.Entry.<DuelSession, Long>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * 中止所有正在跑的对局。
     *
     * <p>服务器停机时调用，免得守护线程被硬切断在半途。注意内核不响应中断，
     * 真正停下要等到下一个消息边界。
     */
    public static void abortAll() {
        for (DuelSession session : active()) {
            session.abort();
        }
    }

    private static void trim() {
        if (SESSIONS.size() <= HISTORY) {
            return;
        }
        List<DuelSession> finishedOldestFirst = SESSIONS.entrySet().stream()
                .filter(e -> !e.getKey().isRunning())
                .sorted(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .toList();
        for (DuelSession session : finishedOldestFirst) {
            if (SESSIONS.size() <= HISTORY) {
                break;
            }
            SESSIONS.remove(session);
        }
    }
}
