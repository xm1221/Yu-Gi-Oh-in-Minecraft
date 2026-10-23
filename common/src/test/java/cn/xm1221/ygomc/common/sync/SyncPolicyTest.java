package cn.xm1221.ygomc.common.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SyncPolicyTest {

    @Test
    void databaseKeepsHandPlacedFilesUntilThePinChanges() {
        assertEquals(SyncPolicy.Database.FETCH, SyncPolicy.database(false, false, null, "abc", false));
        assertEquals(SyncPolicy.Database.KEEP_LOCAL, SyncPolicy.database(true, false, null, "abc", false));
        assertEquals(SyncPolicy.Database.SKIP, SyncPolicy.database(true, true, "abc", "abc", false));
        assertEquals(SyncPolicy.Database.KEEP_LOCAL, SyncPolicy.database(true, false, "abc", "abc", false));
        assertEquals(SyncPolicy.Database.FETCH, SyncPolicy.database(true, false, "abc", "abc", true));
        assertEquals(SyncPolicy.Database.FETCH, SyncPolicy.database(true, true, "old", "new", false));
    }

    @Test
    void scriptsTreatSentinelWithoutManifestAsPlayerFiles() {
        assertEquals(SyncPolicy.Scripts.SKIP_PLAYER, SyncPolicy.scripts(true, null, "abc"));
        assertEquals(SyncPolicy.Scripts.SKIP_PIN, SyncPolicy.scripts(true, "abc", "abc"));
        assertEquals(SyncPolicy.Scripts.FETCH, SyncPolicy.scripts(true, "old", "new"));
        assertEquals(SyncPolicy.Scripts.FETCH, SyncPolicy.scripts(false, "abc", "abc"));
    }
}
