package cn.xm1221.ygomc.common.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 按 URL 顺序下载。连接超时和正文超时分开、只走 https（环回 http 只给测试）、
 * 最多两次跳转、原子改名、失败删临时文件、ETag。一条失败就试下一条。
 * 见计划第 4.2 节与第 12 节。
 */
public final class HttpFetch {

    public static final String USER_AGENT = "ygomc";

    /**
     * 卡库、脚本、卡包的正文上限。JDK 的 request timeout 在响应头一到就取消，
     * 盖不住慢速正文，所以这里另算。约 56MB 的卡包 zip 在 15KB/s 的代理上要一小时出头。
     */
    public static final Duration LARGE_BODY_TIMEOUT = Duration.ofMinutes(90);

    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "ygomc-http-watch");
        thread.setDaemon(true);
        return thread;
    });

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final TypeToken<LinkedHashMap<String, String>> ETAG_TYPE = new TypeToken<>() {
    };

    private HttpFetch() {
    }

    public static final class Options {
        /** TCP/TLS。连不上就换下一条，不要拿它当整文件的预算。 */
        public Duration connectTimeout = Duration.ofSeconds(30);
        /**
         * 响应头和正文各自允许的时间。默认 {@link HttpFetch#LARGE_BODY_TIMEOUT}，
         * 慢代理上的数 MB 到数十 MB 文件不用先改配置。
         */
        public Duration readTimeout = LARGE_BODY_TIMEOUT;
        public long maxBytes = 64L * 1024 * 1024;
        /** 空则只要求 HTTP 成功且正文写完。 */
        public String expectedSha256;
        public Path etagStore;
        public int maxRedirects = 2;
    }

    public static final class Result {
        public final boolean ok;
        public final boolean notModified;
        public final int status;
        public final String url;
        public final String sha256;
        public final String error;

        private Result(boolean ok, boolean notModified, int status, String url, String sha256, String error) {
            this.ok = ok;
            this.notModified = notModified;
            this.status = status;
            this.url = url;
            this.sha256 = sha256;
            this.error = error;
        }

        static Result success(int status, String url, String sha256, boolean notModified) {
            return new Result(true, notModified, status, url, sha256, null);
        }

        static Result failure(int status, String url, String error) {
            return new Result(false, false, status, url, null, error);
        }
    }

    public static Result get(List<String> urls, Path destination, Options options) {
        if (urls == null || urls.isEmpty()) {
            return Result.failure(0, "", "没有 URL");
        }
        List<String> errors = new ArrayList<>();
        for (String raw : urls) {
            Result one;
            try {
                one = getOne(raw, destination, options);
            } catch (RuntimeException e) {
                errors.add(raw + " → " + (e.getMessage() == null ? e.toString() : e.getMessage()));
                continue;
            }
            if (one.ok) {
                return one;
            }
            errors.add((one.url == null || one.url.isBlank() ? raw : one.url) + " → " + one.error);
        }
        return Result.failure(0, urls.get(0), String.join("；", errors));
    }

    private static Result getOne(String raw, Path destination, Options options) {
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException e) {
            return Result.failure(0, raw, "URL 不合法");
        }
        Duration connectTimeout = positive(options.connectTimeout, Duration.ofSeconds(30));
        Duration readTimeout = positive(options.readTimeout, LARGE_BODY_TIMEOUT);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        Map<String, String> etags = loadEtags(options.etagStore);
        int redirects = 0;
        boolean ignoreEtag = false;
        URI current = uri;
        while (true) {
            if (!allowed(current)) {
                return Result.failure(0, current.toString(), "只允许 https");
            }
            String etag = ignoreEtag ? null : etags.get(uri.toString());
            HttpRequest.Builder builder = HttpRequest.newBuilder(current)
                    .timeout(readTimeout)
                    .header("User-Agent", USER_AGENT)
                    .GET();
            if (etag != null && !etag.isBlank()) {
                builder.header("If-None-Match", etag);
            }
            HttpResponse<InputStream> response;
            try {
                response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return Result.failure(0, current.toString(), e.getMessage() == null ? e.toString() : e.getMessage());
            }
            int status = response.statusCode();
            try {
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = response.headers().firstValue("Location").orElse(null);
                    closeQuiet(response.body());
                    if (location == null || location.isBlank()) {
                        return Result.failure(status, current.toString(), "跳转没有 Location");
                    }
                    redirects++;
                    if (redirects > options.maxRedirects) {
                        return Result.failure(status, current.toString(), "跳转超过 " + options.maxRedirects + " 次");
                    }
                    current = current.resolve(location);
                    continue;
                }
                if (status == 304) {
                    closeQuiet(response.body());
                    if (!Files.isRegularFile(destination)) {
                        return Result.failure(304, current.toString(), "服务器说没变，但本地没有文件");
                    }
                    try {
                        String hash = Digests.sha256(destination);
                        if (options.expectedSha256 != null && !options.expectedSha256.isBlank()
                                && !Digests.hexEquals(options.expectedSha256, hash)) {
                            if (!ignoreEtag) {
                                etags.remove(uri.toString());
                                if (options.etagStore != null) {
                                    saveEtags(options.etagStore, etags);
                                }
                                ignoreEtag = true;
                                continue;
                            }
                            return Result.failure(304, current.toString(), "sha256 不符");
                        }
                        return Result.success(304, uri.toString(), hash, true);
                    } catch (IOException e) {
                        return Result.failure(304, current.toString(), e.getMessage());
                    }
                }
                if (status != 200) {
                    closeQuiet(response.body());
                    return Result.failure(status, current.toString(), "HTTP " + status);
                }
                long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                if (declared > options.maxBytes) {
                    closeQuiet(response.body());
                    return Result.failure(status, current.toString(), "Content-Length " + declared + " 超过上限");
                }
                Path partial = destination.resolveSibling(destination.getFileName() + ".partial");
                try {
                    Files.createDirectories(destination.getParent());
                    String hash = writeBody(response.body(), partial, declared, options.maxBytes, readTimeout);
                    if (options.expectedSha256 != null && !options.expectedSha256.isBlank()
                            && !Digests.hexEquals(options.expectedSha256, hash)) {
                        Files.deleteIfExists(partial);
                        return Result.failure(status, current.toString(), "sha256 不符");
                    }
                    move(partial, destination);
                    String newEtag = response.headers().firstValue("ETag").orElse(null);
                    if (newEtag != null && options.etagStore != null) {
                        etags.put(uri.toString(), newEtag);
                        saveEtags(options.etagStore, etags);
                    }
                    return Result.success(200, uri.toString(), hash, false);
                } catch (IOException e) {
                    try {
                        Files.deleteIfExists(partial);
                    } catch (IOException ignored) {
                        // 临时文件留着也只占一次，下次启动会清 staging。
                    }
                    return Result.failure(status, current.toString(), e.getMessage());
                }
            } finally {
                closeQuiet(response.body());
            }
        }
    }

    static boolean allowed(URI uri) {
        if (uri == null || uri.getScheme() == null) {
            return false;
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return true;
        }
        if (!"http".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        String host = uri.getHost();
        return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host);
    }

    private static String writeBody(InputStream in, Path partial, long declared, long maxBytes,
                                    Duration readTimeout) throws IOException {
        MessageDigest digest = Digests.newDigest();
        long total = 0;
        long deadline = System.nanoTime() + readTimeout.toNanos();
        AtomicBoolean timedOut = new AtomicBoolean();
        // request timeout 在响应头到达时就被 JDK 取消，慢速正文要自己掐。
        ScheduledFuture<?> killer = WATCHDOG.schedule(() -> {
            timedOut.set(true);
            closeQuiet(in);
        }, Math.max(readTimeout.toMillis(), 1), TimeUnit.MILLISECONDS);
        try {
            try (InputStream body = in; var out = Files.newOutputStream(partial)) {
                byte[] buf = new byte[8192];
                while (true) {
                    int n;
                    try {
                        n = body.read(buf);
                    } catch (IOException e) {
                        if (timedOut.get() || System.nanoTime() >= deadline) {
                            throw new IOException("读取超时");
                        }
                        throw e;
                    }
                    if (n < 0) {
                        break;
                    }
                    if (timedOut.get() || System.nanoTime() >= deadline) {
                        throw new IOException("读取超时");
                    }
                    total += n;
                    if (total > maxBytes) {
                        throw new IOException("正文超过 " + maxBytes + " 字节");
                    }
                    digest.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
            }
            if (declared >= 0 && total != declared) {
                throw new IOException("正文被截断：声明 " + declared + " 字节，实际 " + total);
            }
            return Digests.hex(digest.digest());
        } finally {
            killer.cancel(false);
        }
    }

    private static Duration positive(Duration value, Duration fallback) {
        if (value == null || value.isZero() || value.isNegative()) {
            return fallback;
        }
        return value;
    }

    private static void move(Path partial, Path destination) throws IOException {
        try {
            Files.move(partial, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void closeQuiet(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (IOException ignored) {
            // 连接已经没用了。
        }
    }

    private static Map<String, String> loadEtags(Path file) {
        Map<String, String> map = new LinkedHashMap<>();
        if (file == null || !Files.isRegularFile(file)) {
            return map;
        }
        try (Reader reader = Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8)) {
            Map<String, String> parsed = GSON.fromJson(reader, ETAG_TYPE);
            if (parsed != null) {
                map.putAll(parsed);
            }
        } catch (IOException | RuntimeException ignored) {
            // 坏掉的 etag 缓存只是少一次 304，不当成下载失败。
        }
        return map;
    }

    private static void saveEtags(Path file, Map<String, String> etags) {
        try {
            Files.createDirectories(file.getParent());
            Path partial = file.resolveSibling(file.getFileName() + ".partial");
            Files.writeString(partial, GSON.toJson(etags), java.nio.charset.StandardCharsets.UTF_8);
            move(partial, file);
        } catch (IOException ignored) {
            // ETag 写失败不影响这次已经落地的文件。
        }
    }
}
