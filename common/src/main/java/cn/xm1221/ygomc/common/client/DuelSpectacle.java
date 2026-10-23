package cn.xm1221.ygomc.common.client;

import cn.xm1221.ygomc.common.spectacle.SpectacleBus;
import cn.xm1221.ygomc.common.spectacle.SpectacleEvent;

import java.util.List;

/**
 * 客户端演出入口：总线活在 Screen 之外，关 GUI 也不清（离世界才清）。
 *
 * <p>世界内牌桌以后从这里取同一条总线；不要把队列藏进 {@link DuelScreen}。
 */
public final class DuelSpectacle {

    private static final SpectacleBus BUS = new SpectacleBus();

    private DuelSpectacle() {
    }

    public static SpectacleBus bus() {
        return BUS;
    }

    /** 动画倍率；0 = 关。 */
    public static void setScale(float scale) {
        BUS.setScale(scale);
    }

    public static void accept(List<SpectacleEvent> events) {
        BUS.enqueueAll(events);
    }

    public static SpectacleEvent tickNow() {
        return BUS.tick(System.currentTimeMillis());
    }

    public static float progress() {
        return BUS.progress(System.currentTimeMillis());
    }

    public static void clear() {
        BUS.clear();
    }

    public static void skipAll() {
        BUS.skipAll();
    }
}
