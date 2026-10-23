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
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 按卡密补一张图。不在启动时遍历全库。主源 YGOPRODeck，失败再试 233。
 * 见计划第 3.4 节。缩放在工作线程上做，不在渲染线程。
 *
 * <p>尺寸对齐 {@link CardImageDb}：FULL {@code 200×290} q0.85，ICON {@code 64×93} q0.78。
 * JDK 没有 Lanczos，用双三次。不生成 HOVER。
 * 同时最多 2 个连接，全局每秒最多开始 1 次请求。界面未命中最多排队 32 张，下一帧再试。
 *
 * <p>{@code /ygomc sync pic} 只排一张。{@code /ygomc sync prefetch} 按 cards.bin 慢速预取，
 * 默认不在启动时跑，{@code prefetch stop} 可以中止。
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
    private static volatile boolean missesLoaded;

    private PicCache() {
    }

    /**
     * 排队一张。专用服（物理端不是客户端）直接拒绝，避免连到图床。
     *
     * @return 1 已排队或正在下；0 负缓存里，24 小时内不再请求；-1 不是客户端或卡号非法
     */
    public static int request(int code) {
        if (code <= 0 || !client()) {
            return -1;
        }
        return enqueue(code, true) ? 1 : 0;
    }

    /**
     * 按卡密慢速预取。不在启动时调用。专用服返回 -1。已经在跑返回 0。开始返回 1。
     * 已有 FULL 和 ICON 的跳过。每秒最多开始一次请求，和按需下载共用限速。
     */
    public static int startPrefetch(int[] codes) {
        if (!client()) {
            return -1;
        }
        if (codes == null || codes.length == 0) {
            return 0;
        }
        if (!PREFETCH_RUNNING.compareAndSet(false, true)) {
            return 0;
        }
        PREFETCH_STOP.set(false);
        int[] copy = codes.clone();
        Thread thread = new Thread(() -> {
            int touched = 0;
            try {
                LOGGER.info("全库预取开始，{} 张，每秒最多 1 张。/ygomc sync prefetch stop 可中止", copy.length);
                for (int code : copy) {
                    if (PREFETCH_STOP.get()) {
                        LOGGER.info("全库预取已中止，已处理 {} 张", touched);
                        return;
                    }
                    if (code <= 0 || hasScaled(code)) {
                        continue;
                    }
                    try {
                        download(code);
                        touched++;
                    } catch (Exception e) {
                        LOGGER.warn("预取卡图 {} 失败：{}", code, e.toString());
                        noteMiss(code, e.getMessage());
                        touched++;
                    }
                }
                LOGGER.info("全库预取结束，处理 {} 张", touched);
            } finally {
                PREFETCH_RUNNING.set(false);
            }
        }, "ygomc-pic-prefetch");
        thread.setDaemon(true);
        thread.start();
        return 1;
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
            return true;
        }
        EXECUTOR.execute(() -> {
            try {
                download(code);
            } catch (Exception e) {
                LOGGER.warn("卡图 {} 下载失败：{}", code, e.toString());
                noteMiss(code, e.getMessage());
            } finally {
                QUEUED.remove(code);
            }
        });
        return true;
    }

    private static void download(int code) throws IOException {
        SyncPins pins = SyncPins.loadDefault();
        SyncSettings settings = safeSettings();
        Path dir = picsDir();
        Files.createDirectories(dir);
        Path raw = dir.resolve(code + ".jpg");
        HttpFetch.Options options = new HttpFetch.Options();
        options.connectTimeout = java.time.Duration.ofSeconds(settings.timeoutSeconds());
        options.maxBytes = settings.maxPicBytes();
        options.etagStore = CacheLayout.etag(Platform.getGameFolder());
        java.util.List<String> urls = new java.util.ArrayList<>();
        for (String pattern : pins.pics.urls) {
            urls.add(pattern.replace("{code}", Integer.toUnsignedString(code)));
        }
        pace();
        HttpFetch.Result result = HttpFetch.get(urls, raw, options);
        if (!result.ok) {
            noteMiss(code, result.error);
            return;
        }
        byte[] bytes = Files.readAllBytes(raw);
        if (bytes.length < 3 || (bytes[0] & 0xFF) != 0xFF || (bytes[1] & 0xFF) != 0xD8) {
            Files.deleteIfExists(raw);
            noteMiss(code, "不是 JPEG");
            return;
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) {
            Files.deleteIfExists(raw);
            noteMiss(code, "解码失败");
            return;
        }
        writeJpeg(scale(image, FULL_WIDTH, FULL_HEIGHT), dir.resolve(code + suffix(CardImageDb.TIER_FULL)), 0.85f);
        writeJpeg(scale(image, ICON_WIDTH, ICON_HEIGHT), dir.resolve(code + suffix(CardImageDb.TIER_ICON)), 0.78f);
        LOGGER.info("卡图已缓存 {}", code);
        SyncListeners.firePicReady(code);
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
