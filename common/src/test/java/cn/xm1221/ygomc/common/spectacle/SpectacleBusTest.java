package cn.xm1221.ygomc.common.spectacle;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SpectacleBusTest {

    @Test
    void scaleZeroDropsEnqueue() {
        SpectacleBus bus = new SpectacleBus();
        bus.setScale(0.0f);
        bus.enqueue(SpectacleEvent.handReveal(123, 0, 1, 500));
        assertTrue(bus.idle());
    }

    @Test
    void playsThenAdvances() {
        SpectacleBus bus = new SpectacleBus();
        bus.enqueue(SpectacleEvent.handReveal(1, 0, 0, 100));
        bus.enqueue(SpectacleEvent.attackNudge(0, 0, 4, 1, 1, 4, 2, 100));
        assertNull(bus.current());
        assertEquals(SpectacleKind.HAND_REVEAL, bus.tick(1000L).kind());
        assertEquals(SpectacleKind.HAND_REVEAL, bus.tick(1050L).kind());
        assertEquals(SpectacleKind.ATTACK_NUDGE, bus.tick(1100L).kind());
        assertNull(bus.tick(1200L));
        assertTrue(bus.idle());
    }

    @Test
    void wireRoundTrip() {
        List<SpectacleEvent> events = List.of(
                SpectacleEvent.handReveal(89631139, 0, 2, 700),
                SpectacleEvent.attackNudge(0, 1, 4, 3, 0, 4, 1, 420),
                SpectacleEvent.appearOrActivate(46986414, 0, 4, 2, 550));
        byte[] raw = SpectacleWire.encode(events);
        List<SpectacleEvent> back = SpectacleWire.decode(raw);
        assertEquals(events, back);
    }
}
