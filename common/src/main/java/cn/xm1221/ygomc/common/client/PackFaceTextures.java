package cn.xm1221.ygomc.common.client;

import cn.xm1221.ygomc.common.sync.PackFaceCache;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 卡包包面动态贴图。缺文件时交给 {@link PackFaceCache} 排队，本帧返回 null。
 */
public final class PackFaceTextures {

    private static final Logger LOGGER = LoggerFactory.getLogger("ygomc/pack-faces");
    private static final int MAX = 64;
    private static final Map<String, Entry> CACHE = new LinkedHashMap<>(64, 0.75f, true);

    private PackFaceTextures() {
    }

    private record Entry(ResourceLocation location, int width, int height) {
    }

    public static ResourceLocation get(String boosterId, String faceUrl) {
        if (boosterId == null || boosterId.isBlank()) {
            return null;
        }
        String key = PackFaceCache.sanitize(boosterId);
        Entry cached = CACHE.get(key);
        if (cached != null) {
            return cached.location();
        }
        Path file = PackFaceCache.peek(boosterId, faceUrl);
        if (file == null) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            NativeImage image = decode(bytes);
            if (image == null) {
                return null;
            }
            ResourceLocation location = ResourceLocation.fromNamespaceAndPath(
                    "ygomc", "dynamic/pack_face/" + key);
            DynamicTexture texture = new DynamicTexture(image);
            Minecraft.getInstance().getTextureManager().register(location, texture);
            while (CACHE.size() >= MAX) {
                Iterator<Map.Entry<String, Entry>> it = CACHE.entrySet().iterator();
                if (!it.hasNext()) {
                    break;
                }
                Map.Entry<String, Entry> eldest = it.next();
                it.remove();
                Minecraft.getInstance().getTextureManager().release(eldest.getValue().location());
            }
            CACHE.put(key, new Entry(location, image.getWidth(), image.getHeight()));
            return location;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("包面贴图加载失败 id={}：{}", key, e.toString());
            return null;
        }
    }

    public static int width(String boosterId) {
        Entry e = CACHE.get(PackFaceCache.sanitize(boosterId));
        return e == null ? 0 : e.width();
    }

    public static int height(String boosterId) {
        Entry e = CACHE.get(PackFaceCache.sanitize(boosterId));
        return e == null ? 0 : e.height();
    }

    public static void invalidate() {
        for (Entry entry : CACHE.values()) {
            Minecraft.getInstance().getTextureManager().release(entry.location());
        }
        CACHE.clear();
    }

    private static NativeImage decode(byte[] bytes) {
        try {
            BufferedImage buffered = ImageIO.read(new ByteArrayInputStream(bytes));
            if (buffered == null) {
                return null;
            }
            int w = buffered.getWidth();
            int h = buffered.getHeight();
            NativeImage out = new NativeImage(NativeImage.Format.RGBA, w, h, false);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = buffered.getRGB(x, y);
                    int a = (argb >>> 24) & 0xFF;
                    int r = (argb >>> 16) & 0xFF;
                    int g = (argb >>> 8) & 0xFF;
                    int b = argb & 0xFF;
                    // NativeImage setPixelRGBA 要 ABGR
                    out.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                }
            }
            return out;
        } catch (IOException e) {
            return null;
        }
    }
}
