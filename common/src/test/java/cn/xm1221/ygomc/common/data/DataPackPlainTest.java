package cn.xm1221.ygomc.common.data;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataPackPlainTest {

    @Test
    void openReadsCardsCdbWithoutPublishedBins() throws Exception {
        Path dir = Files.createTempDirectory("ygomc-plain");
        Files.copy(Path.of("src/test/resources/sync/fixture.cdb"), dir.resolve("cards.cdb"));
        Files.copy(Path.of("src/test/resources/sync/strings.conf"), dir.resolve("strings.conf"));
        DataPack pack = DataPack.open(dir);
        assertNotNull(pack.cardData());
        assertTrue(pack.cardData().codes().length > 0);
        assertNotNull(pack.cardText());
        assertNotNull(pack.strings());
        assertTrue(pack.problems().stream().noneMatch(line -> line.startsWith("cards.cdb")));
        pack.close();
    }
}
