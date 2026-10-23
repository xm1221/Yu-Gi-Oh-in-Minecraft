package cn.xm1221.ygomc.common.sync;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncAnnounceTest {

    @Test
    void warningWaitsForPlayerThenShowsOnce() {
        SyncAnnounce.Gate gate = new SyncAnnounce.Gate();
        AssetSync.Status downloading = status(AssetSync.Phase.DOWNLOADING, "ygomc.sync.msg.downloading", true, false);
        SyncAnnounce.Outcome early = gate.tick(downloading, false);
        assertTrue(early.log());
        assertFalse(early.chat());

        SyncAnnounce.Outcome stillWaiting = gate.tick(downloading, false);
        assertFalse(stillWaiting.log());
        assertFalse(stillWaiting.chat());

        AssetSync.Status failed = status(AssetSync.Phase.FAILED, "ygomc.sync.msg.no_datapack", true, true);
        SyncAnnounce.Outcome pending = gate.tick(failed, false);
        assertTrue(pending.log());
        assertFalse(pending.chat(), "title screen must not consume the warning");

        SyncAnnounce.Outcome shown = gate.tick(failed, true);
        assertFalse(shown.log());
        assertTrue(shown.chat());
        assertTrue(shown.actionBar());
        assertEquals("ygomc.sync.msg.no_datapack", shown.status().messageKey());

        SyncAnnounce.Outcome again = gate.tick(failed, true);
        assertFalse(again.chat());
        assertFalse(again.actionBar());
    }

    @Test
    void playerAlreadyPresentGetsWarningImmediately() {
        SyncAnnounce.Gate gate = new SyncAnnounce.Gate();
        AssetSync.Status degraded = status(AssetSync.Phase.DEGRADED, "ygomc.sync.msg.degraded", true, true);
        SyncAnnounce.Outcome now = gate.tick(degraded, true);
        assertTrue(now.log());
        assertTrue(now.chat());
        assertTrue(now.actionBar());
    }

    @Test
    void progressUpdatesActionBarWithoutEatingTheFailure() {
        SyncAnnounce.Gate gate = new SyncAnnounce.Gate();
        AssetSync.Status start = bytes(0, 1000);
        SyncAnnounce.Outcome first = gate.tick(start, true);
        assertTrue(first.log());
        assertTrue(first.chat());
        assertTrue(first.actionBar());

        SyncAnnounce.Outcome mid = gate.tick(bytes(512, 1000), true);
        assertFalse(mid.log());
        assertFalse(mid.chat());
        assertTrue(mid.actionBar());
        assertEquals("512 B / 1000 B", mid.status().args().get(1));

        SyncAnnounce.Outcome same = gate.tick(bytes(512, 1000), true);
        assertFalse(same.log());
        assertFalse(same.chat());
        assertFalse(same.actionBar());

        AssetSync.Status scripts = new AssetSync.Status(AssetSync.Phase.DOWNLOADING, "scripts", 0, 80,
                "ygomc.sync.msg.progress", List.of("脚本", "0 B / 80 B"), true, false);
        SyncAnnounce.Outcome nextAsset = gate.tick(scripts, true);
        assertTrue(nextAsset.chat());
        assertTrue(nextAsset.actionBar());

        AssetSync.Status failed = status(AssetSync.Phase.FAILED, "ygomc.sync.msg.no_datapack", true, true);
        SyncAnnounce.Outcome pending = gate.tick(failed, false);
        assertFalse(pending.chat());
        SyncAnnounce.Outcome shown = gate.tick(failed, true);
        assertTrue(shown.chat());
        assertTrue(shown.actionBar());
        SyncAnnounce.Outcome again = gate.tick(failed, true);
        assertFalse(again.chat());
        assertFalse(again.actionBar());
    }

    @Test
    void bulkPicsChatOnStartAndFinishButProgressStaysOnTheActionBar() {
        SyncAnnounce.PicGate gate = new SyncAnnounce.PicGate();
        PicStatus started = new PicStatus(PicStatus.Kind.BULK, true, 10, 100, 0, 10, 0);
        SyncAnnounce.PicCue start = gate.tick(started, true);
        assertTrue(start.log());
        assertTrue(start.chat());
        assertTrue(start.actionBar());
        assertFalse(start.warn());
        assertEquals("ygomc.sync.msg.pics_progress", start.messageKey());
        assertEquals(List.of("10", "100", "0"), start.args());

        SyncAnnounce.PicCue same = gate.tick(started, true);
        assertFalse(same.chat());
        assertFalse(same.actionBar());

        PicStatus moved = new PicStatus(PicStatus.Kind.BULK, true, 11, 100, 1, 10, 0);
        SyncAnnounce.PicCue progress = gate.tick(moved, true);
        assertFalse(progress.log());
        assertFalse(progress.chat());
        assertTrue(progress.actionBar());
        assertEquals(List.of("11", "100", "1"), progress.args());

        PicStatus ready = new PicStatus(PicStatus.Kind.READY, true, 100, 100, 1, 10, 0);
        SyncAnnounce.PicCue done = gate.tick(ready, true);
        assertTrue(done.chat());
        assertTrue(done.deliveredTerminal());
        assertEquals("ygomc.sync.msg.pics_done", done.messageKey());

        SyncAnnounce.PicCue again = gate.tick(ready, true);
        assertFalse(again.chat());
        assertFalse(again.actionBar());
    }

    @Test
    void bulkPicSummaryWaitsForThePlayer() {
        SyncAnnounce.PicGate gate = new SyncAnnounce.PicGate();
        PicStatus started = new PicStatus(PicStatus.Kind.BULK, true, 0, 4, 0, 0, 0);
        SyncAnnounce.PicCue early = gate.tick(started, false);
        assertTrue(early.log());
        assertFalse(early.chat());
        assertFalse(early.deliveredTerminal());

        SyncAnnounce.PicCue shown = gate.tick(started, true);
        assertFalse(shown.log());
        assertTrue(shown.chat());
        assertTrue(shown.actionBar());
    }

    @Test
    void onDemandPicsUseTheActionBarUntilAFailureSummary() {
        SyncAnnounce.PicGate gate = new SyncAnnounce.PicGate();
        PicStatus queued = new PicStatus(PicStatus.Kind.DEMAND, false, 2, 5, 0, 0, 3);
        SyncAnnounce.PicCue bar = gate.tick(queued, true);
        assertFalse(bar.chat());
        assertTrue(bar.actionBar());
        assertEquals("ygomc.sync.msg.pics_demand", bar.messageKey());
        assertEquals(List.of("3", "2"), bar.args());

        PicStatus still = new PicStatus(PicStatus.Kind.DEMAND, false, 3, 4, 0, 0, 1);
        SyncAnnounce.PicCue next = gate.tick(still, true);
        assertFalse(next.chat());
        assertTrue(next.actionBar());

        PicStatus failed = new PicStatus(PicStatus.Kind.FAILED, false, 3, 3, 1, 0, 0);
        SyncAnnounce.PicCue warn = gate.tick(failed, true);
        assertTrue(warn.chat());
        assertTrue(warn.warn());
        assertEquals("ygomc.sync.msg.pics_demand_fail", warn.messageKey());
        assertEquals(List.of("1"), warn.args());

        SyncAnnounce.PicCue quiet = gate.tick(PicStatus.idle(), true);
        assertFalse(quiet.chat());
        assertFalse(quiet.actionBar());
    }

    @Test
    void silentSkipIsNotChatted() {
        SyncAnnounce.Gate gate = new SyncAnnounce.Gate();
        AssetSync.Status skip = status(AssetSync.Phase.READY, "ygomc.sync.msg.skip", false, false);
        SyncAnnounce.Outcome quiet = gate.tick(skip, true);
        assertFalse(quiet.log());
        assertFalse(quiet.chat());
    }

    private static AssetSync.Status bytes(long done, long total) {
        return new AssetSync.Status(AssetSync.Phase.DOWNLOADING, "cards.cdb", done, total,
                "ygomc.sync.msg.progress", List.of("卡库", done + " B / " + total + " B"), true, false);
    }

    private static AssetSync.Status status(AssetSync.Phase phase, String key, boolean announce, boolean offline) {
        return new AssetSync.Status(phase, "done", -1, -1, key, List.of("database:download"), announce, offline);
    }
}
