package cn.xm1221.ygomc.common.spectacle;

import cn.xm1221.ygomc.common.ocg.msg.Msg;
import cn.xm1221.ygomc.common.ocg.msg.MsgType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpectacleFromMsgTest {

    private static Msg.Location loc(int controller, int location, int sequence) {
        int packed = (controller & 0xff)
                | ((location & 0xff) << 8)
                | ((sequence & 0xff) << 16);
        return Msg.Location.of(packed);
    }

    @Test
    void handChainingIsReveal() {
        Msg.Chaining c = new Msg.Chaining(MsgType.CHAINING, 0, 0, 89631139,
                loc(0, Msg.Location.HAND, 3), 0, Msg.Location.HAND, 3, 0, 1);
        SpectacleEvent e = SpectacleFromMsg.of(c);
        assertNotNull(e);
        assertEquals(SpectacleKind.HAND_REVEAL, e.kind());
        assertEquals(89631139, e.code());
        assertEquals(3, e.sequence());
    }

    @Test
    void fieldChainingIsAppearHook() {
        Msg.Chaining c = new Msg.Chaining(MsgType.CHAINING, 0, 0, 46986414,
                loc(0, Msg.Location.SZONE, 2), 0, Msg.Location.SZONE, 2, 0, 1);
        SpectacleEvent e = SpectacleFromMsg.of(c);
        assertEquals(SpectacleKind.APPEAR_OR_ACTIVATE, e.kind());
    }

    @Test
    void attackIsNudge() {
        Msg.Attack a = new Msg.Attack(MsgType.ATTACK, 0, 0,
                loc(0, Msg.Location.MZONE, 1), loc(1, Msg.Location.MZONE, 2));
        SpectacleEvent e = SpectacleFromMsg.of(a);
        assertEquals(SpectacleKind.ATTACK_NUDGE, e.kind());
        assertTrue(e.hasTarget());
        assertEquals(1, e.targetController());
        assertEquals(2, e.targetSequence());
    }

    @Test
    void directAttackHasNoTarget() {
        Msg.Attack a = new Msg.Attack(MsgType.ATTACK, 0, 0,
                loc(0, Msg.Location.MZONE, 0), Msg.Location.NONE);
        SpectacleEvent e = SpectacleFromMsg.of(a);
        assertFalse(e.hasTarget());
    }
}
