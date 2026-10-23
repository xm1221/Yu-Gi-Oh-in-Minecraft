package cn.xm1221.ygomc.common.sync;

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

/**
 * 按卡密补一张图。不在启动时遍历全库。主源 YGOPRODeck，失败再试 233。
 * 见计划第 3.4 节。缩放在这根线程上做，不在渲染线程。
 *
 * <p>TODO 计划第 9 节 P3：负缓存之外的界面抛光、全库预取命令。这里先做「缺一张拉一张」。
 */
public final class PicCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("ygomc/pics");
    private static final long MISS_TTL_MS = 24L * 60 * 60 * 1000;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "ygomc-pic");
        thread.setDaemon(true);
        return thread;
    });

    private static final Map<Integer, Long> MISSES = new ConcurrentHashMap<>();
    private static final Map<Integer, Boolean> QUEUED = new ConcurrentHashMap<>();
    private static volatile boolean missesLoaded;

    private PicCache() {
    }

    public static byte[] peek(int code, int tier) {
        if (code <= 0 || !enabled()) {
            return null;
        }
        try {
            Path file = file(code, tier);
            if (Files.isRegularFile(file)) {
                return Files.readAllBytes(file);
            }
            enqueue(code);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("读卡图缓存失败 code={}：{}", code, e.toString());
        }
        return null;
    }

    private static boolean enabled() {
        try {
            if (Platform.getEnvironment() != Env.CLIENT) {
                return false;
            }
        } catch (Throwable e) {
            return false;
        }
        try {
            SyncSettings settings = SyncConfig.current();
            return settings.enabled && settings.picsOnDemand;
        } catch (Throwable e) {
            return true;
        }
    }

    private static void enqueue(int code) {
        long missedAt = misses().getOrDefault(code, 0L);
        if (missedAt != 0 && System.currentTimeMillis() - missedAt < MISS_TTL_MS) {
            return;
        }
        if (QUEUED.putIfAbsent(code, Boolean.TRUE) != null) {
            return;
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
        // mkpics.py 用 LANCZOS。JDK 没有 Lanczos，这里用双三次，档位尺寸与质量对齐。
        writeJpeg(scale(image, 200, 290), dir.resolve(code + ".full.jpg"), 0.85f);
        writeJpeg(scale(image, 64, 93), dir.resolve(code + ".icon.jpg"), 0.78f);
        LOGGER.info("卡图已缓存 {}", code);
        SyncListeners.firePicReady(code);
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Path file(int code, int tier) {
        String suffix = tier == 1 ? ".icon.jpg" : ".full.jpg";
        return picsDir().resolve(code + suffix);
    }

    private static Path picsDir() {
        return CacheLayout.pics(Platform.getGameFolder());
    }

    private static BufferedImage scale(BufferedImage src, int width, int height) {
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
