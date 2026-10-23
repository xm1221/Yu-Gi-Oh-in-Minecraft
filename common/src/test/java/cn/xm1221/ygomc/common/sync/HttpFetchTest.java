package cn.xm1221.ygomc.common.sync;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpFetchTest {

    @Test
    void rejectsPlainHttpOffLoopback() throws Exception {
        Path dest = Files.createTempFile("ygomc-http", ".bin");
        Files.delete(dest);
        HttpFetch.Result result = HttpFetch.get(List.of("http://example.com/cards.cdb"), dest, options());
        assertFalse(result.ok);
        assertTrue(result.error.contains("https"));
        assertFalse(Files.exists(dest));
    }

    @Test
    void notFoundThenSuccess() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/missing", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.createContext("/ok", exchange -> {
            byte[] body = "ygomc".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            Path dest = Files.createTempFile("ygomc-ok", ".bin");
            HttpFetch.Result result = HttpFetch.get(List.of(
                    "http://127.0.0.1:" + port + "/missing",
                    "http://127.0.0.1:" + port + "/ok"), dest, options());
            assertTrue(result.ok, result.error);
            assertEquals("ygomc", Files.readString(dest));
            Files.deleteIfExists(dest);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void progressReportsBytesAndLength() throws Exception {
        byte[] body = "ygomc-progress".getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/body", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Path dest = Files.createTempDirectory("ygomc-progress").resolve("file.bin");
            HttpFetch.Options options = options();
            java.util.concurrent.atomic.AtomicLong seenDone = new java.util.concurrent.atomic.AtomicLong(-1);
            java.util.concurrent.atomic.AtomicLong seenTotal = new java.util.concurrent.atomic.AtomicLong(-2);
            options.progress = (done, total) -> {
                seenDone.set(done);
                seenTotal.set(total);
            };
            HttpFetch.Result result = HttpFetch.get(List.of(url(server, "/body")), dest, options);
            assertTrue(result.ok, result.error);
            assertEquals(body.length, seenDone.get());
            assertEquals(body.length, seenTotal.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shaMismatchDeletesPartial() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bad", exchange -> {
            byte[] body = "nope".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Path dest = Files.createTempDirectory("ygomc-sha").resolve("file.bin");
            HttpFetch.Options options = options();
            options.expectedSha256 = "0".repeat(64);
            HttpFetch.Result result = HttpFetch.get(List.of(url(server, "/bad")), dest, options);
            assertFalse(result.ok);
            assertTrue(result.error.contains("sha256"));
            assertFalse(Files.exists(dest));
            assertFalse(Files.exists(Path.of(dest + ".partial")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void truncatedBodyIsRejected() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cut", exchange -> {
            exchange.sendResponseHeaders(200, 20);
            exchange.getResponseBody().write("abcd".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        try {
            Path dest = Files.createTempDirectory("ygomc-cut").resolve("file.bin");
            HttpFetch.Result result = HttpFetch.get(List.of(url(server, "/cut")), dest, options());
            assertFalse(result.ok, result.error);
            assertFalse(Files.exists(dest));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void oversizedContentLengthIsRejected() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/huge", exchange -> {
            exchange.sendResponseHeaders(200, 50_000_000);
            exchange.close();
        });
        server.start();
        try {
            Path dest = Files.createTempDirectory("ygomc-huge").resolve("file.bin");
            HttpFetch.Options options = options();
            options.maxBytes = 1000;
            HttpFetch.Result result = HttpFetch.get(List.of(url(server, "/huge")), dest, options);
            assertFalse(result.ok);
            assertTrue(result.error.contains("Content-Length"));
            assertFalse(Files.exists(dest));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void etag304DoesNotRewrite() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger bodyBytes = new AtomicInteger();
        server.createContext("/etag", exchange -> {
            hits.incrementAndGet();
            String match = exchange.getRequestHeaders().getFirst("If-None-Match");
            if ("\"v1\"".equals(match)) {
                exchange.sendResponseHeaders(304, -1);
                exchange.close();
                return;
            }
            byte[] body = "body".getBytes(StandardCharsets.UTF_8);
            bodyBytes.addAndGet(body.length);
            exchange.getResponseHeaders().set("ETag", "\"v1\"");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Path dir = Files.createTempDirectory("ygomc-etag");
            Path dest = dir.resolve("file.bin");
            HttpFetch.Options options = options();
            options.etagStore = dir.resolve("etag.json");
            HttpFetch.Result first = HttpFetch.get(List.of(url(server, "/etag")), dest, options);
            assertTrue(first.ok, first.error);
            long modified = Files.getLastModifiedTime(dest).toMillis();
            Thread.sleep(20);
            HttpFetch.Result second = HttpFetch.get(List.of(url(server, "/etag")), dest, options);
            assertTrue(second.ok, second.error);
            assertTrue(second.notModified);
            assertEquals("body", Files.readString(dest));
            assertEquals(modified, Files.getLastModifiedTime(dest).toMillis());
            assertEquals(2, hits.get());
            assertEquals(4, bodyBytes.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void primary500FallsThrough() throws Exception {
        AtomicInteger bodies = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/down", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.createContext("/up", exchange -> {
            byte[] body = "spare".getBytes(StandardCharsets.UTF_8);
            bodies.addAndGet(body.length);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Path dest = Files.createTempDirectory("ygomc-500").resolve("file.bin");
            HttpFetch.Result result = HttpFetch.get(List.of(url(server, "/down"), url(server, "/up")), dest, options());
            assertTrue(result.ok, result.error);
            assertEquals("spare", Files.readString(dest));
            assertTrue(result.url.endsWith("/up"));
            assertEquals(5, bodies.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void slowHeadersBeyondConnectBudgetStillFinish() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService pool = daemonPool();
        server.setExecutor(pool);
        server.createContext("/slow", exchange -> {
            pause(1200);
            byte[] body = "late".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Path dest = Files.createTempDirectory("ygomc-slow-hdr").resolve("file.bin");
            HttpFetch.Options options = options();
            options.connectTimeout = Duration.ofMillis(300);
            options.readTimeout = Duration.ofSeconds(4);
            HttpFetch.Result result = HttpFetch.get(List.of(url(server, "/slow")), dest, options);
            assertTrue(result.ok, result.error);
            assertEquals("late", Files.readString(dest));
        } finally {
            server.stop(0);
            pool.shutdownNow();
        }
    }

    @Test
    void slowBodyWithinReadTimeoutFinishes() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService pool = daemonPool();
        server.setExecutor(pool);
        server.createContext("/drip", exchange -> {
            byte[] body = "abcdef".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            var out = exchange.getResponseBody();
            out.write(body, 0, 2);
            out.flush();
            pause(350);
            out.write(body, 2, 2);
            out.flush();
            pause(350);
            out.write(body, 4, 2);
            out.close();
            exchange.close();
        });
        server.start();
        try {
            Path dest = Files.createTempDirectory("ygomc-drip").resolve("file.bin");
            HttpFetch.Options options = options();
            options.connectTimeout = Duration.ofMillis(200);
            options.readTimeout = Duration.ofSeconds(5);
            HttpFetch.Result result = HttpFetch.get(List.of(url(server, "/drip")), dest, options);
            assertTrue(result.ok, result.error);
            assertEquals("abcdef", Files.readString(dest));
        } finally {
            server.stop(0);
            pool.shutdownNow();
        }
    }

    @Test
    void bodyPastReadTimeoutDeletesPartial() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService pool = daemonPool();
        server.setExecutor(pool);
        server.createContext("/stall", exchange -> {
            byte[] body = "abcdefghij".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            var out = exchange.getResponseBody();
            out.write(body, 0, 2);
            out.flush();
            pause(3000);
            out.write(body, 2, body.length - 2);
            out.close();
            exchange.close();
        });
        server.start();
        try {
            Path dest = Files.createTempDirectory("ygomc-stall").resolve("file.bin");
            HttpFetch.Options options = options();
            options.connectTimeout = Duration.ofSeconds(2);
            options.readTimeout = Duration.ofMillis(600);
            HttpFetch.Result result = HttpFetch.get(List.of(url(server, "/stall")), dest, options);
            assertFalse(result.ok);
            assertTrue(result.error.contains("读取超时"), result.error);
            assertFalse(Files.exists(dest));
            assertFalse(Files.exists(Path.of(dest + ".partial")));
        } finally {
            server.stop(0);
            pool.shutdownNow();
        }
    }

    @Test
    void firstTimeoutDoesNotSkipRemainingUrls() throws Exception {
        AtomicInteger hangHits = new AtomicInteger();
        AtomicInteger okHits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService pool = daemonPool();
        server.setExecutor(pool);
        server.createContext("/hang", exchange -> {
            hangHits.incrementAndGet();
            pause(3000);
            byte[] body = "too-late".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/ok", exchange -> {
            okHits.incrementAndGet();
            byte[] body = "spare".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Path dest = Files.createTempDirectory("ygomc-failopen").resolve("file.bin");
            HttpFetch.Options options = options();
            options.connectTimeout = Duration.ofSeconds(2);
            options.readTimeout = Duration.ofMillis(700);
            HttpFetch.Result result = HttpFetch.get(List.of(url(server, "/hang"), url(server, "/ok")), dest, options);
            assertTrue(result.ok, result.error);
            assertEquals("spare", Files.readString(dest));
            assertTrue(result.url.endsWith("/ok"));
            assertEquals(1, hangHits.get());
            assertEquals(1, okHits.get());
        } finally {
            server.stop(0);
            pool.shutdownNow();
        }
    }

    @Test
    void largePinDownloadKeepsShortConnectAndLongBody() {
        SyncSettings settings = SyncSettings.defaults();
        HttpFetch.Options options = AssetSync.options(settings, 64L * 1024 * 1024, Path.of("game"));
        assertEquals(30, options.connectTimeout.toSeconds());
        assertTrue(options.readTimeout.toSeconds() >= 10 * 60, options.readTimeout.toString());
        // 约 56MB 的卡包 zip，按 15KB/s 需要一小时以上。
        long setsAtSlowProxy = (56L * 1024 * 1024) / (15 * 1024);
        assertTrue(options.readTimeout.toSeconds() >= setsAtSlowProxy, options.readTimeout.toString());
        assertTrue(options.readTimeout.compareTo(options.connectTimeout) > 0);
    }

    @Test
    void followsOneRedirectAndStopsAtThree() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/one", exchange -> redirect(exchange, url(server, "/land")));
        server.createContext("/land", exchange -> {
            byte[] body = "landed".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/hop0", exchange -> redirect(exchange, url(server, "/hop1")));
        server.createContext("/hop1", exchange -> redirect(exchange, url(server, "/hop2")));
        server.createContext("/hop2", exchange -> redirect(exchange, url(server, "/hop3")));
        server.createContext("/hop3", exchange -> {
            byte[] body = "too-far".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            Path dest = Files.createTempDirectory("ygomc-redir").resolve("file.bin");
            HttpFetch.Result ok = HttpFetch.get(List.of(url(server, "/one")), dest, options());
            assertTrue(ok.ok, ok.error);
            assertEquals("landed", Files.readString(dest));
            Path far = dest.resolveSibling("far.bin");
            HttpFetch.Result stopped = HttpFetch.get(List.of(url(server, "/hop0")), far, options());
            assertFalse(stopped.ok);
            assertTrue(stopped.error.contains("跳转"));
            assertFalse(Files.exists(far));
        } finally {
            server.stop(0);
        }
    }

    private static void redirect(com.sun.net.httpserver.HttpExchange exchange, String location) throws java.io.IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static String url(HttpServer server, String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ExecutorService daemonPool() {
        return Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "ygomc-test-http");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static HttpFetch.Options options() {
        HttpFetch.Options options = new HttpFetch.Options();
        options.connectTimeout = Duration.ofSeconds(5);
        options.readTimeout = Duration.ofSeconds(5);
        return options;
    }
}
