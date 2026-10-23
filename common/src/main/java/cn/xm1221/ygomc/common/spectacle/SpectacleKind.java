package cn.xm1221.ygomc.common.spectacle;

/**
 * 对局演出种类。只描述「发生了什么」，不绑定 GUI 或世界渲染。
 *
 * <p>以后世界内牌桌与 GUI 共用同一套枚举；新增种类时两端一起加，
 * 老客户端遇到未知字节应跳过整条事件而不是猜。
 */
public enum SpectacleKind {
    /** 手牌发动：把那张手牌亮给双方看一眼。 */
    HAND_REVEAL,
    /** 攻击宣言：攻方卡朝目标轻晃一下。 */
    ATTACK_NUDGE,
    /**
     * 发动效果或登场时的挂钩。默认实现是短暂大图；以后可换成自定义演出。
     * 手牌发动优先走 {@link #HAND_REVEAL}，不重复叠这条。
     */
    APPEAR_OR_ACTIVATE;

    /** 线格式用的稳定编号；不要重排枚举顺序，只在末尾追加。 */
    public int wireId() {
        return ordinal() + 1;
    }

    public static SpectacleKind fromWireId(int id) {
        return switch (id) {
            case 1 -> HAND_REVEAL;
            case 2 -> ATTACK_NUDGE;
            case 3 -> APPEAR_OR_ACTIVATE;
            default -> null;
        };
    }
}
