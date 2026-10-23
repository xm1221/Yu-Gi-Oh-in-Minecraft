package cn.xm1221.ygomc.common.sync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 按卡号前缀选一个固定深色。同一前缀永远同一色，不使用白色。
 * 内置 {@code demo} 包不走这里，仍是 {@code #3C5AA0}。
 * 调色板与 {@code tools/mksets.py} 的 {@code PACK_COLORS} 必须逐项相同。
 */
public final class PackColors {

    static final String[] PALETTE = {
            "#1B3A4B", "#3D2B1F", "#1F3D2B", "#3B1F3D",
            "#2C3E50", "#4A2C2A", "#1E3A2F", "#2A2E4A",
            "#3E2A1E", "#243B2F", "#3A2430", "#1F2A44",
            "#3F2D1A", "#1A3330", "#33241A", "#2A1F3A"
    };

    private PackColors() {
    }

    public static String of(String prefix) {
        String key = prefix == null || prefix.isBlank() ? "ygomc" : prefix;
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        long n = ((digest[0] & 0xFFL) << 24)
                | ((digest[1] & 0xFFL) << 16)
                | ((digest[2] & 0xFFL) << 8)
                | (digest[3] & 0xFFL);
        return PALETTE[(int) (n % PALETTE.length)];
    }
}
