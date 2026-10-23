package cn.xm1221.ygomc.common.sync;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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

    private static HttpFetch.Options options() {
        HttpFetch.Options options = new HttpFetch.Options();
        options.connectTimeout = java.time.Duration.ofSeconds(5);
        return options;
    }
}
