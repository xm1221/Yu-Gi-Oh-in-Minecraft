package cn.xm1221.ygomc.common.spectacle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 客户端演出队列：入队、按墙钟推进、可跳过。不依赖 Minecraft。
 *
 * <p>关 GUI 后仍应继续 tick——世界层以后会读同一个 bus。
 * {@code scale <= 0} 时入队即丢弃（等价于关动画）。
 */
public final class SpectacleBus {

    private final ArrayDeque<SpectacleEvent> queue = new ArrayDeque<>();
    private SpectacleEvent current;
    private long currentStartedAtMs;
    private float scale = 1.0f;

    public float scale() {
        return scale;
    }

    /** @param scale 0 关动画；1 正常；允许略大于 1 拉长 */
    public void setScale(float scale) {
        this.scale = Math.max(0.0f, scale);
        if (this.scale == 0.0f) {
            clear();
        }
    }

    public void enqueue(SpectacleEvent event) {
        Objects.requireNonNull(event, "event");
        if (scale <= 0.0f) {
            return;
        }
        queue.addLast(event);
    }

    public void enqueueAll(List<SpectacleEvent> events) {
        if (events == null || events.isEmpty() || scale <= 0.0f) {
            return;
        }
        for (SpectacleEvent e : events) {
            if (e != null) {
                queue.addLast(e);
            }
        }
    }

    /** 清空队列与当前条（换局/离场时）。 */
    public void clear() {
        queue.clear();
        current = null;
        currentStartedAtMs = 0L;
    }

    /** 立刻结束当前并丢掉排队。 */
    public void skipAll() {
        clear();
    }

    public SpectacleEvent current() {
        return current;
    }

    public int queued() {
        return queue.size();
    }

    public boolean idle() {
        return current == null && queue.isEmpty();
    }

    /**
     * 用墙钟推进。{@code nowMs} 单调即可（{@code System.currentTimeMillis()}）。
     *
     * @return 这一拍结束后正在播的事件；空闲则 null
     */
    public SpectacleEvent tick(long nowMs) {
        if (scale <= 0.0f) {
            clear();
            return null;
        }
        if (current != null) {
            long dur = scaledDuration(current.durationMs());
            if (nowMs - currentStartedAtMs >= dur) {
                current = null;
            }
        }
        if (current == null && !queue.isEmpty()) {
            current = queue.pollFirst();
            currentStartedAtMs = nowMs;
        }
        return current;
    }

    /** 当前事件进度 0..1；无当前事件返回 0。 */
    public float progress(long nowMs) {
        if (current == null) {
            return 0.0f;
        }
        long dur = scaledDuration(current.durationMs());
        if (dur <= 0L) {
            return 1.0f;
        }
        float p = (float) (nowMs - currentStartedAtMs) / (float) dur;
        if (p < 0.0f) {
            return 0.0f;
        }
        return Math.min(1.0f, p);
    }

    private long scaledDuration(int baseMs) {
        return Math.max(1L, Math.round(baseMs * (double) scale));
    }

    /** 测试用：快照排队内容（不含当前）。 */
    public List<SpectacleEvent> snapshotQueue() {
        return new ArrayList<>(queue);
    }
}
