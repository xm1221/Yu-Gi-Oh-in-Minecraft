package cn.xm1221.ygomc.common.spectacle;

/**
 * 一条演出事件。卡密为 0 表示未知/不可见（渲染应退回牌背或跳过亮牌）。
 *
 * @param kind               种类
 * @param code               纯卡密（已剥高位）；不可见时为 0
 * @param controller         发动/登场/攻击方控制者 0/1
 * @param location           {@code LOCATION_*}
 * @param sequence           区内序号
 * @param targetController   攻击目标控制者；无目标（直击）时为 -1
 * @param targetLocation     攻击目标区域；无目标时为 0
 * @param targetSequence     攻击目标序号
 * @param durationMs         建议时长（未乘 scale）
 */
public record SpectacleEvent(
        SpectacleKind kind,
        int code,
        int controller,
        int location,
        int sequence,
        int targetController,
        int targetLocation,
        int targetSequence,
        int durationMs) {

    public SpectacleEvent {
        if (kind == null) {
            throw new IllegalArgumentException("kind");
        }
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs=" + durationMs);
        }
    }

    public static SpectacleEvent handReveal(int code, int controller, int sequence, int durationMs) {
        return new SpectacleEvent(SpectacleKind.HAND_REVEAL, code, controller,
                0x02 /* HAND */, sequence, -1, 0, 0, durationMs);
    }

    public static SpectacleEvent attackNudge(int code, int controller, int location, int sequence,
                                            int targetController, int targetLocation, int targetSequence,
                                            int durationMs) {
        return new SpectacleEvent(SpectacleKind.ATTACK_NUDGE, code, controller, location, sequence,
                targetController, targetLocation, targetSequence, durationMs);
    }

    public static SpectacleEvent appearOrActivate(int code, int controller, int location, int sequence,
                                                  int durationMs) {
        return new SpectacleEvent(SpectacleKind.APPEAR_OR_ACTIVATE, code, controller, location, sequence,
                -1, 0, 0, durationMs);
    }

    public boolean hasTarget() {
        return targetController >= 0;
    }
}
