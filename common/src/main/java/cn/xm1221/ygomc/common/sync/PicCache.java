package cn.xm1221.ygomc.common.sync;

import cn.xm1221.ygomc.common.data.CardImageDb;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 按卡密补一张图。不在启动时遍历全库。主源 YGOPRODeck，失败再试 233。
 * 见计划第 3.4 节。缩放在工作线程上做，不在渲染线程。
 *
 * <p>尺寸对齐 {@link CardImageDb}：FULL {@code 200×290} q0.85，ICON {@code 64×93} q0.78。
 * JDK 没有 Lanczos，用双三次。不生成 HOVER。
 * 按需仍然同时最多 2 个连接，全局每秒最多开始 1 次请求，界面未命中最多排队 32 张。
 *
 * <p>全库没有可直接下的压缩包（233 和 YGOPRODeck 的 {@code pics.zip} 都是 404，
 * Project Ignis 的整包是不完整的 Mega 链接）。{@code /ygomc sync prefetch}
 * 以及 {@code picsPrefetchAll} 改为并行拉 {@code pins.pics.bulkUrls}：
 * 大陆镜像在前，大约 8 路同时下，进度按张数计。{@code prefetch stop} 可以中止。
 */
public final class PicCache {

    /** 与 {@link CardImageDb#TIER_FULL} 对应的本地 JPEG。 */
    static final int FULL_WIDTH = 200;
    static final int FULL_HEIGHT = 290;
    /** 与 {@link CardImageDb#TIER_ICON} 对应的本地 JPEG。 */
    static final int ICON_WIDTH = 64;
    static final int ICON_HEIGHT = 93;

    private static final Logger LOGGER = LoggerFactory.getLogger("ygomc/pics");
    private static final long MISS_TTL_MS = 24L * 60 * 60 * 1000;
    private static final int MAX_QUEUED = 32;
    /** 全库并行度。按需下载仍走每秒 1 张，避免看卡时把图床打满。 */
    static final int BULK_CONCURRENCY = 8;
    private static final long MIN_INTERVAL_MS = 1000;
    private static final Object PACE = new Object();
    private static long nextStartAt;
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "ygomc-pic");
        thread.setDaemon(true);
        return thread;
    });

    private static final Map<Integer, Long> MISSES = new ConcurrentHashMap<>();
    private static final Map<Integer, Boolean> QUEUED = new ConcurrentHashMap<>();
    private static final AtomicBoolean PREFETCH_RUNNING = new AtomicBoolean();
    private static final AtomicBoolean PREFETCH_STOP = new AtomicBoolean();
    private static final Object STATUS_LOCK = new Object();
    private static final AtomicInteger BULK_DONE = new AtomicInteger();
    private static final AtomicInteger BULK_FAIL = new AtomicInteger();
    private static final AtomicInteger DEMAND_DONE = new AtomicInteger();
    private static final AtomicInteger DEMAND_FAIL = new AtomicInteger();
    private static volatile int bulkTotal;
    private static volatile int bulkSkipped;
    /** 清点结束并公布全库进度之后才允许按需回调改写这条状态。 */
    private static volatile boolean bulkListed;
    private static volatile int demandFailPublished;
    private static volatile boolean terminalHeld;
    private static volatile PicStatus STATUS = PicStatus.idle();
    private static volatile boolean missesLoaded;

    private PicCache() {
    }

    /**
     * 排队一张。专用服（物理端不是客户端）直接拒绝，避免连到图床。
     *
     * @return 1 已排队或正在下；0 负缓存里，24 小时内不再请求；-1 不是客户端或卡号非法；-2 同步或按需卡图已关闭
     */
    public static int request(int code) {
        if (code <= 0 || !client()) {
            return -1;
        }
        if (!enabled()) {
            return -2;
        }
        return enqueue(code, true) ? 1 : 0;
    }

    /** 给界面读。工作线程不直接碰 Minecraft。 */
    public static PicStatus status() {
        return STATUS;
    }

    public static boolean prefetchRunning() {
        return PREFETCH_RUNNING.get();
    }

    /**
     * 全库结束的那一条聊天已经显示过了，按需进度可以接着占动作条。
     * 只由客户端线程在玩家看见提示之后调用。
     */
    public static void acknowledgeTerminal() {
        synchronized (STATUS_LOCK) {
            if (!terminalHeld) {
                return;
            }
            terminalHeld = false;
        }
        publishDemand();
    }

    /**
     * 并行下载整份卡图。专用服返回 -1。同步或 {@code picsOnDemand} 关闭返回 -2。
     * 已经在跑、或没有卡号，返回 0。开始返回 1。清点在后台做，避免卡住客户端。
     * 已有 FULL 和 ICON、或还在负缓存里的跳过，进度分母仍是整份列表。
     *
     * @param announceIfEmpty 一张都不用下时，显式命令要在聊天里说完成；自动启动则安静返回
     */
    public static int startPrefetch(int[] codes) {
        return startPrefetch(codes, true);
    }

    public static int startPrefetch(int[] codes, boolean announceIfEmpty) {
        if (!client()) {
            return -1;
        }
        if (!enabled()) {
            return -2;
        }
        if (codes == null || codes.length == 0) {
            return 0;
        }
        if (!PREFETCH_RUNNING.compareAndSet(false, true)) {
            return 0;
        }
        PREFETCH_STOP.set(false);
        bulkListed = false;
        int[] copy = codes.clone();
        Thread thread = new Thread(() -> runPrefetch(copy, announceIfEmpty), "ygomc-pic-prefetch");
        thread.setDaemon(true);
        thread.start();
        return 1;
    }

    private static void runPrefetch(int[] copy, boolean announceIfEmpty) {
        List<Integer> jobs = new ArrayList<>();
        int total = 0;
        int skipped = 0;
        try {
            for (int code : copy) {
                if (PREFETCH_STOP.get()) {
                    break;
                }
                if (code <= 0) {
                    continue;
                }
                total++;
                if (hasScaled(code) || recentMiss(code)) {
                    skipped++;
                } else {
                    jobs.add(code);
                }
            }
        } catch (RuntimeException e) {
            PREFETCH_RUNNING.set(false);
            LOGGER.warn("全库卡图无法开始：{}", e.toString());
            return;
        }
        bulkTotal = total;
        bulkSkipped = skipped;
        BULK_DONE.set(skipped);
        BULK_FAIL.set(0);
        if (jobs.isEmpty() || PREFETCH_STOP.get()) {
            PicStatus.Kind kind = PREFETCH_STOP.get() ? PicStatus.Kind.STOPPED : PicStatus.Kind.READY;
            boolean tell = PREFETCH_STOP.get() || announceIfEmpty;
            synchronized (STATUS_LOCK) {
                if (tell) {
                    STATUS = new PicStatus(kind, true, skipped, total, 0, skipped, QUEUED.size());
                    terminalHeld = true;
                }
                PREFETCH_RUNNING.set(false);
            }
            LOGGER.info("全库卡图没有要下的：{}/{}，状态 {}", skipped, total, kind);
            return;
        }
        synchronized (STATUS_LOCK) {
            terminalHeld = false;
            bulkListed = true;
            STATUS = bulkSnapshot();
        }
        LOGGER.info("全库卡图开始，{} 张里有 {} 张要下，{} 路并行。/ygomc sync prefetch stop 可中止",
                total, jobs.size(), BULK_CONCURRENCY);
        ExecutorService pool = null;
        try {
            pool = new ThreadPoolExecutor(BULK_CONCURRENCY, BULK_CONCURRENCY, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(), task -> {
                Thread worker = new Thread(task, "ygomc-pic-bulk");
                worker.setDaemon(true);
                return worker;
            });
            for (int code : jobs) {
                if (PREFETCH_STOP.get()) {
                    break;
                }
                pool.execute(() -> downloadOneBulk(code));
            }
            pool.shutdown();
            while (!pool.awaitTermination(250, TimeUnit.MILLISECONDS)) {
                if (PREFETCH_STOP.get()) {
                    pool.shutdownNow();
                }
            }
        } catch (InterruptedException e) {
            PREFETCH_STOP.set(true);
            if (pool != null) {
                pool.shutdownNow();
            }
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            LOGGER.warn("全库卡图中断：{}", e.toString());
            PREFETCH_STOP.set(true);
            if (pool != null) {
                pool.shutdownNow();
            }
        } finally {
            PicStatus.Kind kind;
            int attempted = bulkTotal - bulkSkipped;
            if (PREFETCH_STOP.get()) {
                kind = PicStatus.Kind.STOPPED;
            } else if (attempted > 0 && BULK_FAIL.get() >= attempted) {
                kind = PicStatus.Kind.FAILED;
            } else {
                kind = PicStatus.Kind.READY;
            }
            synchronized (STATUS_LOCK) {
                STATUS = new PicStatus(kind, true, BULK_DONE.get(), bulkTotal, BULK_FAIL.get(), bulkSkipped,
                        QUEUED.size());
                terminalHeld = true;
                bulkListed = false;
                PREFETCH_RUNNING.set(false);
            }
            LOGGER.info("全库卡图结束：{}/{}，失败 {}，状态 {}",
                    BULK_DONE.get(), bulkTotal, BULK_FAIL.get(), kind);
        }
    }

    private static void downloadOneBulk(int code) {
        if (stopped()) {
            return;
        }
        if (QUEUED.putIfAbsent(code, Boolean.TRUE) != null) {
            BULK_DONE.incrementAndGet();
            publishBulkRunning();
            return;
        }
        try {
            if (stopped()) {
                return;
            }
            if (!download(code, false)) {
                BULK_FAIL.incrementAndGet();
            }
            BULK_DONE.incrementAndGet();
        } catch (Exception e) {
            if (stopped()) {
                return;
            }
            LOGGER.warn("预取卡图 {} 失败：{}", code, e.toString());
            noteMiss(code, e.getMessage());
            BULK_FAIL.incrementAndGet();
            BULK_DONE.incrementAndGet();
        } finally {
            QUEUED.remove(code);
            publishBulkRunning();
        }
    }

    /** @return 当时确实有预取在跑 */
    public static boolean stopPrefetch() {
        if (!PREFETCH_RUNNING.get()) {
            return false;
        }
        PREFETCH_STOP.set(true);
        return true;
    }

    private static boolean hasScaled(int code) {
        return Files.isRegularFile(file(code, CardImageDb.TIER_FULL))
                && Files.isRegularFile(file(code, CardImageDb.TIER_ICON));
    }

    public static byte[] peek(int code, int tier) {
        if (code <= 0 || suffix(tier) == null || !enabled()) {
            return null;
        }
        try {
            Path file = file(code, tier);
            if (Files.isRegularFile(file)) {
                return Files.readAllBytes(file);
            }
            enqueue(code, false);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("读卡图缓存失败 code={}：{}", code, e.toString());
        }
        return null;
    }

    private static boolean enabled() {
        if (!client()) {
            return false;
        }
        try {
            SyncSettings settings = SyncConfig.current();
            return settings.enabled && settings.picsOnDemand;
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

    private static boolean enqueue(int code, boolean forced) {
        long missedAt = misses().getOrDefault(code, 0L);
        if (missedAt != 0 && System.currentTimeMillis() - missedAt < MISS_TTL_MS) {
            return false;
        }
        if (!forced && QUEUED.size() >= MAX_QUEUED) {
            return false;
        }
        if (QUEUED.putIfAbsent(code, Boolean.TRUE) != null) {
            publishDemand();
            return true;
        }
        publishDemand();
        EXECUTOR.execute(() -> {
            boolean ok = false;
            try {
                ok = download(code, true);
                if (!ok) {
                    DEMAND_FAIL.incrementAndGet();
                } else {
                    DEMAND_DONE.incrementAndGet();
                }
            } catch (Exception e) {
                LOGGER.warn("卡图 {} 下载失败：{}", code, e.toString());
                noteMiss(code, e.getMessage());
                DEMAND_FAIL.incrementAndGet();
            } finally {
                QUEUED.remove(code);
                publishDemand();
            }
        });
        return true;
    }

    /** @return 是否写出了 FULL 和 ICON */
    private static boolean download(int code, boolean paced) throws IOException {
        SyncPins pins = SyncPins.loadDefault();
        SyncSettings settings = safeSettings();
        Path dir = picsDir();
        Files.createDirectories(dir);
        Path raw = dir.resolve(code + ".jpg");
        HttpFetch.Options options = new HttpFetch.Options();
        options.connectTimeout = java.time.Duration.ofSeconds(settings.timeoutSeconds());
        options.readTimeout = java.time.Duration.ofSeconds(Math.max(180, settings.timeoutSeconds() * 2L));
        options.maxBytes = settings.maxPicBytes();
        // 全库是 8 路并行。共用的 etag 文件会被后写的线程整份覆盖，所以只给按需下载用。
        if (paced) {
            options.etagStore = CacheLayout.etag(Platform.getGameFolder());
        }
        if (!paced) {
            options.connectTimeout = java.time.Duration.ofSeconds(Math.min(10, settings.timeoutSeconds()));
            options.readTimeout = java.time.Duration.ofSeconds(20);
        }
        List<String> urls = pictureUrls(pins, settings, code, !paced);
        if (paced) {
            pace();
        } else if (stopped()) {
            throw new java.io.InterruptedIOException("全库卡图已停止");
        }
        HttpFetch.Result result = HttpFetch.get(urls, raw, options);
        if (!result.ok) {
            if (!paced && stopped()) {
                throw new java.io.InterruptedIOException("全库卡图已停止");
            }
            noteMiss(code, result.error);
            return false;
        }
        byte[] bytes = Files.readAllBytes(raw);
        if (bytes.length < 3 || (bytes[0] & 0xFF) != 0xFF || (bytes[1] & 0xFF) != 0xD8) {
            Files.deleteIfExists(raw);
            noteMiss(code, "不是 JPEG");
            return false;
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) {
            Files.deleteIfExists(raw);
            noteMiss(code, "解码失败");
            return false;
        }
        writeJpeg(scale(image, FULL_WIDTH, FULL_HEIGHT), dir.resolve(code + suffix(CardImageDb.TIER_FULL)), 0.85f);
        writeJpeg(scale(image, ICON_WIDTH, ICON_HEIGHT), dir.resolve(code + suffix(CardImageDb.TIER_ICON)), 0.78f);
        LOGGER.info("卡图已缓存 {}", code);
        SyncListeners.firePicReady(code);
        return true;
    }

    private static List<String> pictureUrls(SyncPins pins, SyncSettings settings, int code, boolean bulk) {
        List<String> patterns = pins.pics.urls;
        if (bulk && pins.pics.bulkUrls != null && !pins.pics.bulkUrls.isEmpty()) {
            patterns = pins.pics.bulkUrls;
        }
        patterns = SyncUrls.resolve(Platform.getGameFolder(), settings, SyncUrls.Key.PICS, patterns);
        String id = Integer.toUnsignedString(code);
        List<String> urls = new ArrayList<>();
        for (String pattern : patterns) {
            if (pattern != null) {
                urls.add(pattern.replace("{code}", id));
            }
        }
        return urls;
    }

    private static boolean stopped() {
        return PREFETCH_STOP.get() || Thread.currentThread().isInterrupted();
    }

    private static boolean recentMiss(int code) {
        long missedAt = misses().getOrDefault(code, 0L);
        return missedAt != 0 && System.currentTimeMillis() - missedAt < MISS_TTL_MS;
    }

    private static PicStatus bulkSnapshot() {
        return new PicStatus(PicStatus.Kind.BULK, true, BULK_DONE.get(), bulkTotal, BULK_FAIL.get(), bulkSkipped,
                QUEUED.size());
    }

    private static PicStatus demandSnapshot() {
        int queued = QUEUED.size();
        int done = DEMAND_DONE.get();
        return new PicStatus(PicStatus.Kind.DEMAND, false, done, done + queued, DEMAND_FAIL.get(), 0, queued);
    }

    private static void publishBulkRunning() {
        synchronized (STATUS_LOCK) {
            if (!PREFETCH_RUNNING.get() || !bulkListed) {
                return;
            }
            STATUS = bulkSnapshot();
        }
    }

    private static void publishDemand() {
        synchronized (STATUS_LOCK) {
            if (PREFETCH_RUNNING.get()) {
                if (bulkListed) {
                    STATUS = bulkSnapshot();
                }
                return;
            }
            if (terminalHeld) {
                return;
            }
            int queued = QUEUED.size();
            if (queued > 0) {
                STATUS = demandSnapshot();
                return;
            }
            int fails = DEMAND_FAIL.get();
            if (fails > demandFailPublished) {
                int burst = fails - demandFailPublished;
                demandFailPublished = fails;
                STATUS = new PicStatus(PicStatus.Kind.FAILED, false, DEMAND_DONE.get(), DEMAND_DONE.get(), burst, 0, 0);
                return;
            }
            if (STATUS.kind() == PicStatus.Kind.DEMAND) {
                STATUS = PicStatus.idle();
            }
        }
    }

    private static void pace() throws IOException {
        try {
            synchronized (PACE) {
                long wait = nextStartAt - System.currentTimeMillis();
                if (wait > 0) {
                    Thread.sleep(wait);
                }
                nextStartAt = System.currentTimeMillis() + MIN_INTERVAL_MS;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("卡图限速被打断", e);
        }
    }

    static String suffix(int tier) {
        if (tier == CardImageDb.TIER_ICON) {
            return ".icon.jpg";
        }
        if (tier == CardImageDb.TIER_FULL) {
            return ".full.jpg";
        }
        return null;
    }

    private static Path file(int code, int tier) {
        String name = suffix(tier);
        if (name == null) {
            return picsDir().resolve(code + ".missing");
        }
        return picsDir().resolve(code + name);
    }

    private static Path picsDir() {
        return CacheLayout.pics(Platform.getGameFolder());
    }

    static BufferedImage scale(BufferedImage src, int width, int height) {
        BufferedImage dst = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = dst.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.drawImage(src, 0, 0, width, height, null);
        graphics.dispose();
        return dst;
    }

    private static void writeJpeg(BufferedImage image, Path dest, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("没有 JPEG 编码器");
        }
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        writer.setOutput(new MemoryCacheImageOutputStream(bytes));
        writer.write(null, new IIOImage(image, null, null), param);
        writer.dispose();
        Path partial = dest.resolveSibling(dest.getFileName() + ".partial");
        Files.write(partial, bytes.toByteArray());
        try {
            Files.move(partial, dest, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.move(partial, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static SyncSettings safeSettings() {
        try {
            return SyncConfig.current();
        } catch (Throwable e) {
            return SyncSettings.defaults();
        }
    }

    private static Map<Integer, Long> misses() {
        if (!missesLoaded) {
            loadMisses();
        }
        return MISSES;
    }

    private static synchronized void loadMisses() {
        if (missesLoaded) {
            return;
        }
        missesLoaded = true;
        try {
            Path file = picsDir().resolve("misses.txt");
            if (!Files.isRegularFile(file)) {
                return;
            }
            for (String line : Files.readAllLines(file)) {
                String[] parts = line.split("\t");
                if (parts.length >= 2) {
                    MISSES.put(Integer.parseInt(parts[0]), Long.parseLong(parts[1]));
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("负缓存读不了：{}", e.toString());
        }
    }

    private static synchronized void noteMiss(int code, String why) {
        long now = System.currentTimeMillis();
        MISSES.put(code, now);
        try {
            Path file = picsDir().resolve("misses.txt");
            Files.createDirectories(file.getParent());
            Files.writeString(file, code + "\t" + now + "\t" + (why == null ? "" : why.replace('\n', ' ')) + "\n",
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            LOGGER.info("卡图 {} 记入负缓存：{}", code, why);
        } catch (IOException e) {
            LOGGER.warn("负缓存写不了：{}", e.toString());
        }
    }
}
