package cn.xm1221.ygomc.common.client;

import cn.xm1221.ygomc.common.data.CardImageDb;
import cn.xm1221.ygomc.common.data.DataPack;
import cn.xm1221.ygomc.common.data.DataPacks;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * 卡图贴图的按需解码与缓存：{@code pics.bin} 里的 JPEG → GPU 纹理。
 *
 * <h2>为什么不用资源包注入</h2>
 * 参考实现（YgoDuelingMod）走的是「注入一个自定义资源包，把卡图伪装成普通贴图」，
 * 好处是缓存与显存回收全交给 Minecraft 的 {@code TextureManager}。这里没有照做，
 * 原因很实际：原版加载贴图走的是 {@link NativeImage#read}，<b>它只认 PNG</b>；
 * 而卡图是 JPEG（同尺寸 PNG 要大 5 倍，15017 张会从 297 MB 涨到 1.5 GB）。
 * 要伪装就得在资源包里把每张 JPEG 现场转码成 PNG，而那个转换发生在渲染线程的
 * 贴图加载路径上，一次几十毫秒的卡顿会直接表现为掉帧。
 * 所以这里直接解码成 {@link DynamicTexture}：省掉转码，代价是要自己管一个上限。
 *
 * <h2>为什么必须有上限</h2>
 * 一张 FULL 档卡图是 200×290×4 ≈ 232 KB 显存。要是「看过就留着」，
 * 翻一遍收藏册就能吃掉几个 GB。所以按 LRU 保留有限张数，
 * 超出时释放最久未用的——{@link DynamicTexture#close()} 会把显存还回去。
 *
 * <h2>只能在渲染线程调用</h2>
 * 贴图上传是 GL 操作。本类不做线程检查（检查本身也要访问 GL 上下文），
 * 而是靠调用约定：所有调用点都在 GUI/物品渲染里，天然在渲染线程上。
 * <b>不要在异步解码里调它</b>——那样得到的是随机的纹理错乱，不是崩溃，很难查。
 */
public final class CardTextures {

    private static final Logger LOGGER = LoggerFactory.getLogger("ygomc/card-textures");

    /** 同时保留多少张卡图纹理。192 张 FULL ≈ 44 MB 显存。 */
    private static final int MAX_TEXTURES = 192;

    /** 纹理路径的命名空间与前缀。 */
    private static final String PATH_PREFIX = "dynamic/card/";

    private record Key(int code, int tier) {
    }

    /**
     * 一条已缓存的纹理。
     *
     * <p><b>不要把它改名成 {@code Entry}。</b>下面那个匿名 {@code LinkedHashMap} 子类
     * 从 {@code Map} 继承了成员类型 {@code Map.Entry}，而类体作用域比外层类更内层，
     * 于是签名写成 {@code Map.Entry<Key, Entry>} 时，第二个 {@code Entry} 会解析成
     * {@code Map.Entry}——参数类型与父类的 {@code removeEldestEntry} 不一致，
     * 但擦除后都是 {@code Map.Entry}，javac 报「名称冲突……具有相同疑符，
     * 但两者均不覆盖对方」。这个名字本身就是那个坑的说明。
     */
    private record Cached(ResourceLocation location, int width, int height) {
    }

    /** key → 纹理。{@link java.util.LinkedHashMap} 按访问顺序淘汰比手写 LRU 队列更不容易写错。 */
    private static final Map<Key, Cached> CACHE = new java.util.LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Cached> eldest) {
            if (size() > MAX_TEXTURES) {
                // 这里只从表里移除；真正的显存释放在 release 里做，
                // 因为 removeEldestEntry 里调 TextureManager 会与外层加锁顺序互锁。
                PENDING_RELEASE.add(eldest.getKey());
                return true;
            }
            return false;
        }
    };

    /** 待释放的键。用双端队列是为了能按插入顺序成对处理。 */
    private static final Deque<Key> PENDING_RELEASE = new ArrayDeque<>();

    /** 缓存命中/未命中的计数，用于诊断「翻卡组时到底解码了多少张」。 */
    private static long hits;
    private static long misses;

    /**
     * 卡背的固定资源名。
     *
     * <p>它不属于任何卡号，所以走独立槽位而不是 {@link Key}——用哨兵卡号混进
     * LRU 的话，淘汰逻辑就得为「这张不能被淘汰」开特例，那比单独放一个字段更难维护。
     */
    private static final ResourceLocation BACK_LOCATION =
            ResourceLocation.fromNamespaceAndPath("ygomc", PATH_PREFIX + "back");

    private static ResourceLocation backLocation;
    private static int backWidth;
    private static int backHeight;
    private static boolean backTried;

    private CardTextures() {
    }

    /**
     * 取一张卡图的纹理位置。没图时返回 {@code null}（调用方据此回落到卡背）。
     *
     * @param tier {@link CardImageDb#TIER_FULL} 或 {@link CardImageDb#TIER_ICON}
     */
    public static ResourceLocation get(int code, int tier) {
        Key key = new Key(code, tier);
        Cached cached = CACHE.get(key);
        if (cached != null) {
            hits++;
            return cached.location();
        }
        misses++;

        // 先把上一轮淘汰出来的释放掉，再做本次加载——
        // 顺序反过来会让峰值显存多出一张图，长期跑下来没必要。
        drainPendingReleases();

        DataPack pack = DataPacks.get();
        byte[] jpeg = pack.imageOf(code, tier);
        if (jpeg == null || jpeg.length == 0) {
            return null;
        }
        NativeImage image = decode(jpeg, code, tier);
        if (image == null) {
            return null;
        }
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(
                "ygomc", PATH_PREFIX + tier + "/" + code);
        // DynamicTexture 的构造器会立刻上传，所以必须在渲染线程。
        DynamicTexture texture = new DynamicTexture(image);
        Minecraft.getInstance().getTextureManager().register(location, texture);

        Cached entry = new Cached(location, image.getWidth(), image.getHeight());
        CACHE.put(key, entry);
        // 插入可能触发淘汰，淘汰会往 PENDING_RELEASE 里放东西，下一轮再清。
        return location;
    }

    /** 纹理的像素宽度；纹理不在缓存里时返回 0。 */
    public static int width(int code, int tier) {
        Cached e = CACHE.get(new Key(code, tier));
        return e == null ? 0 : e.width();
    }

    /** 纹理的像素高度；纹理不在缓存里时返回 0。 */
    public static int height(int code, int tier) {
        Cached e = CACHE.get(new Key(code, tier));
        return e == null ? 0 : e.height();
    }

    /**
     * 卡背纹理位置。
     *
     * <p>它<b>不</b>进 LRU：全局只有这一张，淘汰它没有任何收益，
     * 而重新加载要多走一次文件 I/O 加解码。所以只在第一次调用时尝试加载，
     * 读不到就永远返回 {@code null}，不会每次调用都去碰磁盘。
     *
     * <p>没有数据组件、卡号查不到、或者这张卡在数据包里没图的，都回退到这里。
     */
    public static ResourceLocation back() {
        if (backTried) {
            return backLocation;
        }
        backTried = true;
        byte[] jpeg = DataPacks.get().cardBack();
        if (jpeg == null || jpeg.length == 0) {
            // 这不是错误：卡背是 KONAMI 的美术，不随模组分发。
            LOGGER.info("数据包里没有卡背（{} 缺失），缺图与无组件的卡会显示占位框",
                    DataPack.BACK_FILE);
            return null;
        }
        NativeImage image = decode(jpeg, 0, -1);
        if (image == null) {
            return null;
        }
        DynamicTexture texture = new DynamicTexture(image);
        Minecraft.getInstance().getTextureManager().register(BACK_LOCATION, texture);
        backLocation = BACK_LOCATION;
        backWidth = image.getWidth();
        backHeight = image.getHeight();
        return backLocation;
    }

    /** 卡背的像素宽度；没加载到卡背时返回 0。 */
    public static int backWidth() {
        return backWidth;
    }

    /** 卡背的像素高度；没加载到卡背时返回 0。 */
    public static int backHeight() {
        return backHeight;
    }

    /** 一次诊断用的统计行。 */
    public static String stats() {
        return "卡图纹理缓存 " + CACHE.size() + "/" + MAX_TEXTURES
                + "，命中 " + hits + "，解码 " + misses
                + "，卡背 " + (backLocation != null ? "有" : "无");
    }

    /** 全部释放。切换世界/重载资源时调用。 */
    public static void clear() {
        for (Cached e : CACHE.values()) {
            Minecraft.getInstance().getTextureManager().release(e.location());
        }
        CACHE.clear();
        PENDING_RELEASE.clear();
        // 卡背也要重置，否则重载后 backTried 会让我们一直用着已释放的纹理。
        if (backLocation != null) {
            Minecraft.getInstance().getTextureManager().release(backLocation);
            backLocation = null;
        }
        backTried = false;
        backWidth = 0;
        backHeight = 0;
    }

    private static void drainPendingReleases() {
        while (!PENDING_RELEASE.isEmpty()) {
            Key key = PENDING_RELEASE.poll();
            Cached entry = CACHE.get(key);
            // 淘汰之后又被重新加载过的键，这里不能再释放——那会把正在用的纹理删掉。
            if (entry != null) {
                continue;
            }
            Minecraft.getInstance().getTextureManager()
                    .release(ResourceLocation.fromNamespaceAndPath(
                            "ygomc", PATH_PREFIX + key.tier() + "/" + key.code()));
        }
    }

    /**
     * JPEG → {@link NativeImage}。
     *
     * <p>用 {@code ImageIO} 而不是 {@link NativeImage#read}：后者按 PNG 解析，
     * 喂 JPEG 会直接抛异常。这是本类存在的根本原因。
     *
     * <p>像素通道顺序要换一次：{@code BufferedImage.getRGB} 返回 ARGB，
     * 而 {@code NativeImage} 的 {@code setPixelRGBA} 按 ABGR 存。不换的话
     * 红蓝会对调——卡图整体偏蓝，是个一眼能看出但很容易想错方向的症状。
     */
    private static NativeImage decode(byte[] jpeg, int code, int tier) {
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(jpeg));
            if (src == null) {
                LOGGER.warn("卡图解码失败（不是可识别的图片格式）: code={} tier={}", code, tier);
                return null;
            }
            int w = src.getWidth();
            int h = src.getHeight();
            // 第三参数 false = 不申请显存之外的额外内存，交给 setPixelRGBA 填。
            NativeImage out = new NativeImage(NativeImage.Format.RGBA, w, h, false);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = src.getRGB(x, y);
                    int a = (argb >>> 24) & 0xFF;
                    int r = (argb >>> 16) & 0xFF;
                    int g = (argb >>> 8) & 0xFF;
                    int b = argb & 0xFF;
                    out.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                }
            }
            return out;
        } catch (IOException | RuntimeException e) {
            // 坏图不应该让整个界面崩掉：这张卡回落成卡背，其余照常显示。
            LOGGER.warn("卡图解码异常: code={} tier={} 字节={} : {}",
                    code, tier, jpeg.length, e.toString());
            return null;
        }
    }
}
