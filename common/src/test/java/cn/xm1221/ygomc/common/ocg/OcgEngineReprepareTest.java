package cn.xm1221.ygomc.common.ocg;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 不调用 {@code Platform.getGameFolder()}。那条路径离开 Minecraft 会直接断言失败。 */
class OcgEngineReprepareTest {

    @AfterEach
    void reset() {
        OcgEngine.testingReset();
    }

    @Test
    void failedPrepareDoesNotRetryUntilReprepareAndDuelDefersIt() {
        OcgEngine.testingMarkFailed("缺脚本");
        assertFalse(OcgEngine.prepare());
        assertEquals("缺脚本", OcgEngine.problem());

        OcgEngine.testingDuelActive = true;
        assertFalse(OcgEngine.reprepare());
        assertTrue(OcgEngine.isApplyPending());
        assertFalse(OcgEngine.prepare());
        assertEquals("缺脚本", OcgEngine.problem());
    }

    @Test
    void reprepareDuringDuelKeepsAWorkingEngine() {
        OcgEngine.testingMarkReady(Path.of("old-root"));
        OcgEngine.testingDuelActive = true;
        assertTrue(OcgEngine.reprepare());
        assertTrue(OcgEngine.isReady());
        assertNull(OcgEngine.problem());
        assertTrue(OcgEngine.isApplyPending());
        assertEquals(Path.of("old-root"), OcgEngine.scriptRoot());
    }
}
