package cn.xm1221.ygomc.common.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SyncConfigMigrateTest {

    @Test
    void oldSaveWithoutVersionForcesPrefetchAll() {
        SyncConfig cfg = new SyncConfig();
        cfg.picsPrefetchAll = false;
        cfg.syncPolicyVersion = 0;
        assertTrue(cfg.migratePolicyIfNeeded());
        assertTrue(cfg.picsPrefetchAll);
        assertEquals(SyncConfig.SYNC_POLICY_VERSION, cfg.syncPolicyVersion);
        assertFalse(cfg.migratePolicyIfNeeded());
    }

    @Test
    void stampedVersionKeepsUserChoice() {
        SyncConfig cfg = new SyncConfig();
        cfg.picsPrefetchAll = false;
        cfg.syncPolicyVersion = SyncConfig.SYNC_POLICY_VERSION;
        assertFalse(cfg.migratePolicyIfNeeded());
        assertFalse(cfg.picsPrefetchAll);
    }
}