package cn.xm1221.ygomc.common.sync;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 三个 bin 必须一起换上。源不齐时正式目录保持原样，不能留下混装。
 */
class PublishBinsTest {

    @Test
    void replacesAllThreeAndDropsBackups() throws Exception {
        Path root = Files.createTempDirectory("ygomc-bins");
        Path from = root.resolve("from");
        Path to = root.resolve("to");
        write(from, "new");
        write(to, "old");
        AssetSync.publishBins(from, to);
        assertArrayEquals("new-cards.bin".getBytes(), Files.readAllBytes(to.resolve("cards.bin")));
        assertArrayEquals("new-texts.bin".getBytes(), Files.readAllBytes(to.resolve("texts.bin")));
        assertArrayEquals("new-strings.bin".getBytes(), Files.readAllBytes(to.resolve("strings.bin")));
        assertFalse(Files.exists(to.resolve("cards.bin.bak")));
        assertFalse(Files.exists(to.resolve("texts.bin.bak")));
        assertFalse(Files.exists(to.resolve("strings.bin.bak")));
    }

    @Test
    void incompleteSourceLeavesTheLiveBinsAlone() throws Exception {
        Path root = Files.createTempDirectory("ygomc-bins-keep");
        Path from = root.resolve("from");
        Path to = root.resolve("to");
        write(from, "new");
        Files.delete(from.resolve("texts.bin"));
        write(to, "old");
        assertThrows(IOException.class, () -> AssetSync.publishBins(from, to));
        assertArrayEquals("old-cards.bin".getBytes(), Files.readAllBytes(to.resolve("cards.bin")));
        assertArrayEquals("old-texts.bin".getBytes(), Files.readAllBytes(to.resolve("texts.bin")));
        assertArrayEquals("old-strings.bin".getBytes(), Files.readAllBytes(to.resolve("strings.bin")));
        assertFalse(Files.exists(to.resolve("cards.bin.bak")));
    }

    private static void write(Path dir, String mark) throws IOException {
        Files.createDirectories(dir);
        for (String name : new String[]{"cards.bin", "texts.bin", "strings.bin"}) {
            Files.writeString(dir.resolve(name), mark + "-" + name);
        }
    }
}
