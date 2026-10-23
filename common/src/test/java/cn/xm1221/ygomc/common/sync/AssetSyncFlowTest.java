package cn.xm1221.ygomc.common.sync;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetSyncFlowTest {

    @AfterEach
    void reset() {
        AssetSync.testingReset();
    }

    @Test
    void disabledDoesNotCreateFiles() throws Exception {
        Path game = Files.createTempDirectory("ygomc-off");
        SyncSettings settings = SyncSettings.defaults();
        settings.enabled = false;
        AssetSync.runOnce(game, settings, SyncPins.loadDefault(), false);
        assertFalse(Files.exists(CacheLayout.datapack(game)));
        assertEquals(AssetSync.Phase.IDLE, AssetSync.status().phase());
        delete(game);
    }

    @Test
    void keepsLocalBinsWhenPinIsUnchanged() throws Exception {
        Path game = Files.createTempDirectory("ygomc-keep");
        Path fixture = Path.of("src/test/resources/sync/fixture.cdb");
        Path conf = Path.of("src/test/resources/sync/strings.conf");
        CdbToBins.convert(fixture, conf, CacheLayout.datapack(game));
        byte[] before = Files.readAllBytes(CacheLayout.datapack(game).resolve("cards.bin"));
        SyncPins pins = SyncPins.loadDefault();
        SyncManifest manifest = new SyncManifest();
        manifest.databaseSha = pins.database.sha;
        manifest.bins.put("cards.bin", "not-the-real-hash");
        manifest.bins.put("texts.bin", Digests.sha256(CacheLayout.datapack(game).resolve("texts.bin")));
        manifest.bins.put("strings.bin", Digests.sha256(CacheLayout.datapack(game).resolve("strings.bin")));
        manifest.save(CacheLayout.manifest(game));
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            pins.database.files.get(0).urls = java.util.List.of(base + "/cards.cdb");
            pins.database.files.get(1).urls = java.util.List.of(base + "/strings.conf");
            SyncSettings settings = SyncSettings.defaults();
            settings.scripts = false;
            settings.sets = false;
            settings.timeoutSeconds = 2;
            AssetSync.runOnce(game, settings, pins, false);
            assertEquals(0, hits.get());
            assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(CacheLayout.datapack(game).resolve("cards.bin"))));
            assertEquals(AssetSync.Phase.READY, AssetSync.status().phase());
        } finally {
            server.stop(0);
            delete(game);
        }
    }

    @Test
    void missingDatabaseDoesNotThrowWhenOffline() throws Exception {
        Path game = Files.createTempDirectory("ygomc-miss");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "no".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            SyncPins pins = SyncPins.loadDefault();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            pins.database.files.get(0).urls = java.util.List.of(base + "/cards.cdb");
            pins.database.files.get(0).sha256 = "";
            pins.database.files.get(1).urls = java.util.List.of(base + "/strings.conf");
            pins.database.files.get(1).sha256 = "";
            SyncSettings settings = SyncSettings.defaults();
            settings.scripts = false;
            settings.sets = false;
            AssetSync.runOnce(game, settings, pins, false);
            // 脚本这一步关着，所以不算「什么都没有」的失败，只是卡库没下到。
            assertEquals(AssetSync.Phase.DEGRADED, AssetSync.status().phase());
            assertFalse(Files.exists(CacheLayout.datapack(game).resolve("cards.bin")));
        } finally {
            server.stop(0);
            delete(game);
        }
    }

    private static void delete(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
