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
    void keepsLocalPlaintextWhenPinIsUnchanged() throws Exception {
        Path game = Files.createTempDirectory("ygomc-keep");
        copyPlain(game);
        byte[] before = Files.readAllBytes(CacheLayout.datapack(game).resolve("cards.cdb"));
        SyncPins pins = SyncPins.loadDefault();
        SyncManifest manifest = new SyncManifest();
        manifest.databaseSha = pins.database.sha;
        manifest.databaseSha256.put("cards.cdb", "not-the-real-hash");
        manifest.databaseSha256.put("strings.conf",
                Digests.sha256(CacheLayout.datapack(game).resolve("strings.conf")));
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
            assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(CacheLayout.datapack(game).resolve("cards.cdb"))));
            assertFalse(Files.exists(CacheLayout.datapack(game).resolve("cards.bin")));
            assertEquals(AssetSync.Phase.READY, AssetSync.status().phase());
        } finally {
            server.stop(0);
            delete(game);
        }
    }

    @Test
    void unchangedPinDoesNotDownload() throws Exception {
        Path game = Files.createTempDirectory("ygomc-skip");
        copyPlain(game);
        Files.createDirectories(CacheLayout.script(game));
        Files.writeString(CacheLayout.script(game).resolve("constant.lua"), "return 1\n");
        Files.createDirectories(CacheLayout.sets(game));
        Files.writeString(CacheLayout.sets(game).resolve("pack.mcmeta"),
                "{\"pack\":{\"pack_format\":48,\"description\":\"x\"}}\n");
        SyncPins pins = SyncPins.loadDefault();
        SyncManifest manifest = new SyncManifest();
        manifest.databaseSha = pins.database.sha;
        manifest.scriptsSha = pins.scripts.sha;
        manifest.setsSha256 = pins.sets.sha256;
        for (String name : java.util.List.of("cards.cdb", "strings.conf")) {
            manifest.databaseSha256.put(name, Digests.sha256(CacheLayout.datapack(game).resolve(name)));
        }
        manifest.save(CacheLayout.manifest(game));
        AtomicInteger hits = new AtomicInteger();
        AtomicInteger bodyBytes = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            byte[] body = "should-not-download".getBytes(StandardCharsets.UTF_8);
            bodyBytes.addAndGet(body.length);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            pins.database.files.get(0).urls = java.util.List.of(base + "/cards.cdb");
            pins.database.files.get(1).urls = java.util.List.of(base + "/strings.conf");
            pins.scripts.urls = java.util.List.of(base + "/scripts.zip");
            pins.sets.urls = java.util.List.of(base + "/sets.zip");
            SyncSettings settings = SyncSettings.defaults();
            settings.timeoutSeconds = 2;
            AssetSync.runOnce(game, settings, pins, false);
            assertEquals(0, hits.get());
            assertEquals(0, bodyBytes.get());
            assertEquals("ygomc.sync.msg.skip", AssetSync.status().messageKey());
        } finally {
            server.stop(0);
            delete(game);
        }
    }

    @Test
    void databaseUsesNextUrlWhenFirstMirrorFails() throws Exception {
        Path game = Files.createTempDirectory("ygomc-mirror");
        byte[] cdb = Files.readAllBytes(Path.of("src/test/resources/sync/fixture.cdb"));
        byte[] conf = Files.readAllBytes(Path.of("src/test/resources/sync/strings.conf"));
        AtomicInteger denied = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/deny", exchange -> {
            denied.incrementAndGet();
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });
        server.createContext("/cards.cdb", exchange -> {
            exchange.sendResponseHeaders(200, cdb.length);
            exchange.getResponseBody().write(cdb);
            exchange.close();
        });
        server.createContext("/strings.conf", exchange -> {
            exchange.sendResponseHeaders(200, conf.length);
            exchange.getResponseBody().write(conf);
            exchange.close();
        });
        server.start();
        try {
            SyncPins pins = SyncPins.loadDefault();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            pins.database.files.get(0).urls = java.util.List.of(base + "/deny", base + "/cards.cdb");
            pins.database.files.get(0).sha256 = Digests.sha256(Path.of("src/test/resources/sync/fixture.cdb"));
            pins.database.files.get(1).urls = java.util.List.of(base + "/deny", base + "/strings.conf");
            pins.database.files.get(1).sha256 = Digests.sha256(Path.of("src/test/resources/sync/strings.conf"));
            SyncSettings settings = SyncSettings.defaults();
            settings.scripts = false;
            settings.sets = false;
            settings.timeoutSeconds = 2;
            AssetSync.runOnce(game, settings, pins, false);
            assertTrue(denied.get() >= 2, "first mirror must be attempted for each file");
            assertTrue(java.util.Arrays.equals(cdb, Files.readAllBytes(CacheLayout.datapack(game).resolve("cards.cdb"))));
            assertTrue(java.util.Arrays.equals(conf, Files.readAllBytes(CacheLayout.datapack(game).resolve("strings.conf"))));
            assertFalse(Files.exists(CacheLayout.datapack(game).resolve("cards.bin")));
            assertEquals(AssetSync.Phase.READY, AssetSync.status().phase());
        } finally {
            server.stop(0);
            delete(game);
        }
    }

    @Test
    void failedDownloadWithLocalPlaintextStaysDegraded() throws Exception {
        Path game = Files.createTempDirectory("ygomc-local");
        copyPlain(game);
        SyncPins pins = SyncPins.loadDefault();
        SyncManifest manifest = new SyncManifest();
        manifest.databaseSha = "0123456789abcdef0123456789abcdef01234567";
        manifest.save(CacheLayout.manifest(game));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(403, -1);
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
            AssetSync.runOnce(game, settings, pins, false);
            assertEquals(AssetSync.Phase.DEGRADED, AssetSync.status().phase());
            assertEquals("ygomc.sync.msg.degraded", AssetSync.status().messageKey());
            assertTrue(AssetSync.status().announce());
            assertTrue(Files.isRegularFile(CacheLayout.datapack(game).resolve("cards.cdb")));
            assertTrue(Files.isRegularFile(CacheLayout.datapack(game).resolve("strings.conf")));
        } finally {
            server.stop(0);
            delete(game);
        }
    }

    @Test
    void urlsJsonReplacesPinnedDatabaseMirrors() throws Exception {
        Path game = Files.createTempDirectory("ygomc-urls");
        byte[] cdb = Files.readAllBytes(Path.of("src/test/resources/sync/fixture.cdb"));
        byte[] conf = Files.readAllBytes(Path.of("src/test/resources/sync/strings.conf"));
        AtomicInteger denied = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/deny", exchange -> {
            denied.incrementAndGet();
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });
        server.createContext("/cards.cdb", exchange -> {
            exchange.sendResponseHeaders(200, cdb.length);
            exchange.getResponseBody().write(cdb);
            exchange.close();
        });
        server.createContext("/strings.conf", exchange -> {
            exchange.sendResponseHeaders(200, conf.length);
            exchange.getResponseBody().write(conf);
            exchange.close();
        });
        server.start();
        try {
            SyncPins pins = SyncPins.loadDefault();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            pins.database.files.get(0).urls = java.util.List.of(base + "/deny");
            pins.database.files.get(0).sha256 = Digests.sha256(Path.of("src/test/resources/sync/fixture.cdb"));
            pins.database.files.get(1).urls = java.util.List.of(base + "/deny");
            pins.database.files.get(1).sha256 = Digests.sha256(Path.of("src/test/resources/sync/strings.conf"));
            Files.createDirectories(CacheLayout.root(game));
            Files.writeString(CacheLayout.root(game).resolve("urls.json"),
                    "{\"cardsCdb\":[\"" + base + "/cards.cdb\"],\"stringsConf\":[\"" + base + "/strings.conf\"]}\n");
            SyncSettings settings = SyncSettings.defaults();
            settings.scripts = false;
            settings.sets = false;
            settings.cardsCdbUrls = java.util.List.of(base + "/deny");
            AssetSync.runOnce(game, settings, pins, false);
            assertEquals(0, denied.get(), "urls.json replaces both pin and cloth lists");
            assertTrue(java.util.Arrays.equals(cdb, Files.readAllBytes(CacheLayout.datapack(game).resolve("cards.cdb"))));
            assertEquals(AssetSync.Phase.READY, AssetSync.status().phase());
        } finally {
            server.stop(0);
            delete(game);
        }
    }

    @Test
    void missingDatabaseAndScriptsFailsWithEmptyDatapackWarning() throws Exception {
        Path game = Files.createTempDirectory("ygomc-empty");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(404, -1);
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
            pins.scripts.urls = java.util.List.of(base + "/scripts.zip");
            SyncSettings settings = SyncSettings.defaults();
            settings.sets = false;
            AssetSync.runOnce(game, settings, pins, false);
            assertEquals(AssetSync.Phase.FAILED, AssetSync.status().phase());
            assertEquals("ygomc.sync.msg.no_datapack", AssetSync.status().messageKey());
            assertTrue(AssetSync.status().args().get(0).contains("database:download"));
            assertFalse(Files.exists(CacheLayout.datapack(game).resolve("cards.cdb")));
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
            // 脚本这一步关着，阶段仍是降级，但文案必须指出本地没有数据包。
            assertEquals(AssetSync.Phase.DEGRADED, AssetSync.status().phase());
            assertEquals("ygomc.sync.msg.no_datapack", AssetSync.status().messageKey());
            assertTrue(AssetSync.status().announce());
            assertFalse(Files.exists(CacheLayout.datapack(game).resolve("cards.cdb")));
        } finally {
            server.stop(0);
            delete(game);
        }
    }

    private static void copyPlain(Path game) throws Exception {
        Path dir = CacheLayout.datapack(game);
        Files.createDirectories(dir);
        Files.copy(Path.of("src/test/resources/sync/fixture.cdb"), dir.resolve("cards.cdb"));
        Files.copy(Path.of("src/test/resources/sync/strings.conf"), dir.resolve("strings.conf"));
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
