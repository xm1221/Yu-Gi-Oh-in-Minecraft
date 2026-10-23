package cn.xm1221.ygomc.common.spectacle;

/**
 * 默认时长（毫秒）。{@code scale == 0} 时总线直接跳过，不走进这些数字。
 */
public final class SpectacleTimings {

    private SpectacleTimings() {
    }

    public static final int HAND_REVEAL_MS = 700;
    public static final int ATTACK_NUDGE_MS = 420;
    /** 默认大图一闪。 */
    public static final int APPEAR_OR_ACTIVATE_MS = 550;
}
