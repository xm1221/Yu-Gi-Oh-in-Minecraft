package cn.xm1221.ygomc.common.sync;

import java.nio.file.Path;

/**
 * {@code <游戏目录>/ygomc/} 的路径。只做拼接，不调用 {@code Platform}，单测可以传入假目录。
 *
 * <p>布局见 {@code docs/ASSET_SYNC_PLAN.md} 第 4.2 节。
 */
public final class CacheLayout {

    private CacheLayout() {
    }

    public static Path root(Path gameDir) {
        return gameDir.toAbsolutePath().normalize().resolve("ygomc");
    }

    public static Path datapack(Path gameDir) {
        return root(gameDir).resolve("datapack");
    }

    public static Path script(Path gameDir) {
        return root(gameDir).resolve("script");
    }

    public static Path sets(Path gameDir) {
        return root(gameDir).resolve("sets");
    }

    public static Path cache(Path gameDir) {
        return root(gameDir).resolve("cache");
    }

    public static Path manifest(Path gameDir) {
        return cache(gameDir).resolve("manifest.json");
    }

    public static Path etag(Path gameDir) {
        return cache(gameDir).resolve("etag.json");
    }

    public static Path database(Path gameDir, String sha) {
        return cache(gameDir).resolve("database").resolve(sha);
    }

    public static Path scriptsZip(Path gameDir, String sha) {
        return cache(gameDir).resolve("scripts").resolve(sha + ".zip");
    }

    public static Path setsZip(Path gameDir, String sha256) {
        String prefix = sha256.length() >= 16 ? sha256.substring(0, 16) : sha256;
        return cache(gameDir).resolve("sets").resolve(prefix + ".zip");
    }

    public static Path pics(Path gameDir) {
        return cache(gameDir).resolve("pics");
    }

    public static Path staging(Path gameDir) {
        return cache(gameDir).resolve("staging");
    }

    public static Path locks(Path gameDir) {
        return cache(gameDir).resolve("locks");
    }

    /** 对局进行中先写到这里，{@code /ygomc sync apply} 再换上。 */
    public static Path pendingDatapack(Path gameDir) {
        return cache(gameDir).resolve("pending").resolve("datapack");
    }

    public static Path pendingScript(Path gameDir) {
        return cache(gameDir).resolve("pending").resolve("script");
    }
}
