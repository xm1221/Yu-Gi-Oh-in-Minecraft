package cn.xm1221.ygomc.common.spectacle;

import cn.xm1221.ygomc.common.ocg.msg.Msg;

/**
 * 从内核消息抽出演出事件。纯函数，可离线测。
 *
 * <p>手牌连锁 → {@link SpectacleKind#HAND_REVEAL}；场上连锁 / 召唤类 →
 * {@link SpectacleKind#APPEAR_OR_ACTIVATE}（默认大图）；攻击宣言 →
 * {@link SpectacleKind#ATTACK_NUDGE}。其它消息返回 null。
 */
public final class SpectacleFromMsg {

    private static final int LOC_HAND = 0x02;

    private SpectacleFromMsg() {
    }

    public static SpectacleEvent of(Msg m) {
        if (m instanceof Msg.Chaining c) {
            int loc = c.location().location() & 0x7f;
            int code = c.pureCode();
            int controller = c.location().controller();
            int sequence = c.location().sequence();
            if (loc == LOC_HAND) {
                return SpectacleEvent.handReveal(code, controller, sequence,
                        SpectacleTimings.HAND_REVEAL_MS);
            }
            return SpectacleEvent.appearOrActivate(code, controller, loc, sequence,
                    SpectacleTimings.APPEAR_OR_ACTIVATE_MS);
        }
        if (m instanceof Msg.Summoning s) {
            return appear(s.code() & 0x7fffffff, s.location());
        }
        if (m instanceof Msg.SpSummoning s) {
            return appear(s.code() & 0x7fffffff, s.location());
        }
        if (m instanceof Msg.FlipSummoning s) {
            return appear(s.code() & 0x7fffffff, s.location());
        }
        if (m instanceof Msg.Attack a) {
            Msg.Location atk = a.attacker();
            Msg.Location tgt = a.target();
            int tController = a.isDirect() ? -1 : tgt.controller();
            int tLoc = a.isDirect() ? 0 : (tgt.location() & 0x7f);
            int tSeq = a.isDirect() ? 0 : tgt.sequence();
            return SpectacleEvent.attackNudge(0, atk.controller(), atk.location() & 0x7f, atk.sequence(),
                    tController, tLoc, tSeq, SpectacleTimings.ATTACK_NUDGE_MS);
        }
        return null;
    }

    private static SpectacleEvent appear(int code, Msg.Location loc) {
        return SpectacleEvent.appearOrActivate(code, loc.controller(), loc.location() & 0x7f,
                loc.sequence(), SpectacleTimings.APPEAR_OR_ACTIVATE_MS);
    }
}
