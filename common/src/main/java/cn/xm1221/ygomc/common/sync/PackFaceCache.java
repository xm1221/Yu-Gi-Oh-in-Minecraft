package cn.xm1221.ygomc.common.sync;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * 卡包包面图缓存。URL 写在 booster JSON 的 {@code display.face}（YGOJSON / Yugipedia）。
 * 只在客户端拉；开局尽量一次拉全，缺图时按 booster id 再补。
 */
public final class PackFaceCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("ygomc/pack-faces");
    private static final AtomicBoolean PREFETCH_RUNNING = new AtomicBoolean();
    private static final AtomicBoolean PREFETCH_STOP = new AtomicBoolean();
    private static final AtomicInteger DONE = new AtomicInteger();
    private static final AtomicInteger FAIL = new AtomicInteger();
    private static volatile int total;
    private static volatile boolean armed;
    private static volatile PicStatus STATUS = PicStatus.idle();

    private PackFaceCache() {
    }

    public static PicStatus status() {
        return STATUS;
    }

    public static boolean prefetchRunning() {
        return PREFETCH_RUNNING.get();
    }

    public static boolean armed() {
        return armed;
    }

    /** 本地已有包面文件时返回路径，否则 null 并排队下载。 */
    public static Path peek(String boosterId) {
        if (boosterId == null || boosterId.isBlank() || !client() || !enabled()) {
            return null;
        }
        String id = sanitize(boosterId);
        Path file = fileOf(id);
        if (Files.isRegularFile(file)) {
            return file;
        }
        // 未知包面：按需补一张（不反复全量）
        requestOne(id, null);
        return null;
    }

    public static Path peek(String boosterId, String faceUrl) {
        if (boosterId == null || boosterId.isBlank() || !client() || !enabled()) {
            return null;
        }
        String id = sanitize(boosterId);
        Path file = fileOf(id);
        if (Files.isRegularFile(file)) {
            return file;
        }
        requestOne(id, faceUrl);
        return null;
    }

    /**
     * 开局预取：扫 {@code ygomc/sets} 里所有 booster 的 face URL，缺的一次拉全。
     * @return 1 已启动；0 已在跑/无需；-1 非客户端；-2 同步关
     */
    public static int startPrefetchFromSets() {
        if (!client()) {
            return -1;
        }
        if (!enabled()) {
            return -2;
        }
        if (!PREFETCH_RUNNING.compareAndSet(false, true)) {
            return 0;
        }
        PREFETCH_STOP.set(false);
        armed = true;
        Path game = Platform.getGameFolder();
        Path sets = CacheLayout.sets(game);
        Thread thread = new Thread(() -> runPrefetch(sets), "ygomc-pack-face-prefetch");
        thread.setDaemon(true);
        thread.start();
        return 1;
    }

    public static boolean stopPrefetch() {
        if (!PREFETCH_RUNNING.get()) {
            return false;
        }
        PREFETCH_STOP.set(true);
        return true;
    }

    private static void runPrefetch(Path setsDir) {
        try {
            Map<String, String> jobs = collectFaces(setsDir);
            total = jobs.size();
            DONE.set(0);
            FAIL.set(0);
            if (jobs.isEmpty()) {
                STATUS = new PicStatus(PicStatus.Kind.READY, true, 0, 0, 0, 0, 0);
                return;
            }
            LOGGER.info("卡包包面开始：{} 张要下", jobs.size());
            STATUS = new PicStatus(PicStatus.Kind.BULK, true, 0, jobs.size(), 0, 0, 0);
            Path game = Platform.getGameFolder();
            SyncSettings settings = SyncConfig.current();
            for (Map.Entry<String, String> job : jobs.entrySet()) {
                if (PREFETCH_STOP.get()) {
                    STATUS = new PicStatus(PicStatus.Kind.STOPPED, true, DONE.get(), total, FAIL.get(), 0, 0);
                    return;
                }
                if (Files.isRegularFile(fileOf(job.getKey()))) {
                    DONE.incrementAndGet();
                    STATUS = new PicStatus(PicStatus.Kind.BULK, true, DONE.get(), total, FAIL.get(), 0, 0);
                    continue;
                }
                if (download(job.getKey(), job.getValue(), game, settings)) {
                    DONE.incrementAndGet();
                } else {
                    FAIL.incrementAndGet();
                }
                STATUS = new PicStatus(PicStatus.Kind.BULK, true, DONE.get(), total, FAIL.get(), 0, 0);
            }
            if (PREFETCH_STOP.get()) {
                STATUS = new PicStatus(PicStatus.Kind.STOPPED, true, DONE.get(), total, FAIL.get(), 0, 0);
            } else if (FAIL.get() > 0 && DONE.get() == 0) {
                STATUS = new PicStatus(PicStatus.Kind.FAILED, true, DONE.get(), total, FAIL.get(), 0, 0);
            } else {
                STATUS = new PicStatus(PicStatus.Kind.READY, true, DONE.get(), total, FAIL.get(), 0, 0);
            }
            LOGGER.info("卡包包面结束：成功 {} 失败 {} / {}", DONE.get(), FAIL.get(), total);
        } catch (Throwable e) {
            LOGGER.warn("卡包包面预取失败：{}", e.toString());
            STATUS = new PicStatus(PicStatus.Kind.FAILED, true, DONE.get(), Math.max(total, 1), FAIL.get(), 0, 0);
        } finally {
            PREFETCH_RUNNING.set(false);
        }
    }

    private static void requestOne(String id, String faceUrl) {
        if (PREFETCH_RUNNING.get()) {
            return;
        }
        Path game = Platform.getGameFolder();
        String url = faceUrl;
        if (url == null || url.isBlank()) {
            url = findFaceUrl(CacheLayout.sets(game), id);
        }
        if (url == null || url.isBlank()) {
            return;
        }
        String finalUrl = url;
        Thread thread = new Thread(() -> {
            try {
                SyncSettings settings = SyncConfig.current();
                download(id, finalUrl, game, settings);
            } catch (Throwable e) {
                LOGGER.warn("单张包面下载失败 id={}：{}", id, e.toString());
            }
        }, "ygomc-pack-face-" + id);
        thread.setDaemon(true);
        thread.start();
    }

    private static boolean download(String id, String url, Path game, SyncSettings settings) {
        if (url == null || url.isBlank()) {
            return false;
        }
        Path dest = fileOf(id);
        try {
            Files.createDirectories(dest.getParent());
            HttpFetch.Options options = new HttpFetch.Options();
            options.connectTimeout = Duration.ofSeconds(settings.timeoutSeconds());
            options.readTimeout = Duration.ofMinutes(2);
            options.maxBytes = 8L * 1024 * 1024;
            HttpFetch.Result result = HttpFetch.get(List.of(url.trim()), dest, options);
            if (!result.ok) {
                Files.deleteIfExists(dest);
                LOGGER.warn("包面下载失败 id={}：{}", id, result.error);
                return false;
            }
            return true;
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(dest);
            } catch (IOException ignored) {
            }
            LOGGER.warn("包面下载异常 id={}：{}", id, e.toString());
            return false;
        }
    }

    static Map<String, String> collectFaces(Path setsDir) {
        Map<String, String> out = new LinkedHashMap<>();
        if (setsDir == null || !Files.isDirectory(setsDir)) {
            return out;
        }
        Path boosters = setsDir.resolve("data").resolve("ygomc").resolve("ygomc").resolve("booster");
        if (!Files.isDirectory(boosters)) {
            return out;
        }
        try (Stream<Path> walk = Files.list(boosters)) {
            for (Path file : walk.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                String id = file.getFileName().toString();
                id = id.substring(0, id.length() - 5);
                String face = readFace(file);
                if (face != null && !face.isBlank()) {
                    out.put(sanitize(id), face.trim());
                }
            }
        } catch (IOException e) {
            LOGGER.warn("扫描包面清单失败：{}", e.toString());
        }
        return out;
    }

    private static String findFaceUrl(Path setsDir, String id) {
        Path file = setsDir.resolve("data").resolve("ygomc").resolve("ygomc").resolve("booster")
                .resolve(id + ".json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        return readFace(file);
    }

    private static String readFace(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonObject display = root.getAsJsonObject().getAsJsonObject("display");
            if (display == null || !display.has("face") || display.get("face").isJsonNull()) {
                return null;
            }
            return display.get("face").getAsString();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Path fileOf(String id) {
        return CacheLayout.packFaces(Platform.getGameFolder()).resolve(id + ".img");
    }

    public static String sanitize(String boosterId) {
        String id = boosterId.contains(":") ? boosterId.substring(boosterId.indexOf(':') + 1) : boosterId;
        id = id.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(id.length());
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                out.append(c);
            } else {
                out.append('_');
            }
        }
        return out.isEmpty() ? "pack" : out.toString();
    }

    private static boolean enabled() {
        try {
            SyncSettings settings = SyncConfig.current();
            return settings.enabled && settings.sets;
        } catch (Throwable e) {
            return true;
        }
    }

    private static boolean client() {
        try {
            return Platform.getEnvironment() == Env.CLIENT;
        } catch (Throwable e) {
            return false;
        }
    }
}
