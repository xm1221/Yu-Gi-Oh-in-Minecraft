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
    void silentSkipIsNotChatted() {
        SyncAnnounce.Gate gate = new SyncAnnounce.Gate();
        AssetSync.Status skip = status(AssetSync.Phase.READY, "ygomc.sync.msg.skip", false, false);
        SyncAnnounce.Outcome quiet = gate.tick(skip, true);
        assertFalse(quiet.log());
        assertFalse(quiet.chat());
    }

    private static AssetSync.Status status(AssetSync.Phase phase, String key, boolean announce, boolean offline) {
        return new AssetSync.Status(phase, "done", -1, -1, key, List.of("database:download"), announce, offline);
    }
}
