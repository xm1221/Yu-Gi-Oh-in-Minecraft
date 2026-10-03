package cn.xm1221.ygomc.common.ocg;

import dev.architectury.platform.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 原生库的提取与加载。
 *
 * <h2>为什么不直接 {@code System.loadLibrary}</h2>
 * {@code System.loadLibrary} 只会去 {@code java.library.path} 里找，而模组的
 * 原生库是打包在 jar 里的资源，那个路径里根本不存在。所以必须
 * 「从 classpath 读出字节 → 落到磁盘 → {@code System.load(绝对路径)}」。
 *
 * <h2>为什么每次构建落到不同的目录</h2>
 * <b>Windows 不允许覆盖已被本进程加载的 DLL</b>（会抛
 * "另一个程序正在使用此文件"）。开发时反复重编 natives 是很常见的，
 * 如果固定写到同一个路径，第二次启动就会在覆盖那一步失败。
 * 所以目标目录名带上<b>两个库的内容指纹</b>：同一份构建重复启动会命中同一目录
 * 而完全跳过写入，换了构建则落到新目录，永远不会去碰正在被占用的那个文件。
 *
 * <h2>为什么提取失败不算致命</h2>
 * 与数据包同理：模组本身（物品、方块、界面）不依赖引擎也能加载。
 * 缺原生库只在真正要开局时才暴露，所以这里 {@link #init()} 只记日志，
 * 由 {@link #ensureLoaded()} 在开局前给出带原因的异常。
 *
 * <h2>平台范围</h2>
 * 只打包了 Windows x64 的两个库（见 {@code common/build.gradle}）。
 * 其他平台的玩家会在日志里看到明确说明，而不是一个莫名其妙的
 * {@code UnsatisfiedLinkError}。
 */
public final class Natives {

    private static final Logger LOGGER = LoggerFactory.getLogger("ygomc/natives");

    /** 资源目录。放在 mod 自己的命名空间下，避免和别的模组撞名。 */
    private static final String RESOURCE_DIR = "/ygomc/natives/win-x64/";

    private static final String CORE = "ocgcore.dll";
    private static final String BRIDGE = "ygomc_ocg.dll";

    /**
     * 显式指定 DLL 所在目录：{@code -Dygomc.natives=<目录>}。
     *
     * <p>开发时用它指向 {@code natives/build/Release} 附近的目录，
     * 可以反复重编 DLL 而<b>不必重新打包模组</b>——这是调引擎时最常用的循环。
     */
    public static final String PROPERTY = "ygomc.natives";

    private static String problem;
    private static Path directory;

    private Natives() {
    }

    /**
     * 尝试加载原生库，失败只记日志。
     *
     * <p>由 {@link cn.xm1221.ygomc.common.Ygomc#init()} 调用，目的是让状态在启动日志里可见。
     */
    public static synchronized void init() {
        try {
            ensureLoaded();
            LOGGER.info("原生库就绪: {}", directory);
        } catch (Exception | UnsatisfiedLinkError e) {
            problem = e.getMessage();
            LOGGER.warn("原生库不可用，对局功能将无法使用: {}", problem);
        }
    }

    /**
     * 保证两个原生库已按正确顺序加载，否则抛出说明原因的异常。
     *
     * <p>顺序不能反：{@code ygomc_ocg.dll} 动态依赖 {@code ocgcore.dll}，
     * 而 Windows 的依赖解析只看「这个模块名是否已经在本进程里」，
     * 不会去同目录找。所以必须先把 {@code ocgcore.dll} 顶上去。
     */
    public static synchronized void ensureLoaded() {
        if (Ocg.isLoaded()) {
            return;
        }
        if (!isWindowsX64()) {
            throw new IllegalStateException(
                    "本模组目前只提供 Windows x64 的原生库，当前系统是 "
                            + System.getProperty("os.name") + "/" + System.getProperty("os.arch"));
        }

        String override = System.getProperty(PROPERTY);
        if (override != null && !override.isBlank()) {
            // 显式指定的目录直接用，不从资源里解——这样重编 DLL 后重启即可生效。
            Path dir = Path.of(override).toAbsolutePath().normalize();
            directory = dir;
            requireBoth(dir);
            Ocg.load(dir.resolve(CORE).toString(), dir.resolve(BRIDGE).toString());
            return;
        }

        byte[] core = readResource(CORE);
        byte[] bridge = readResource(BRIDGE);
        Path dir = Platform.getGameFolder()
                .resolve("ygomc/natives")
                // 指纹用「长度 + 内容哈希」：长度单独用会漏掉「改了实现但体积没变」，
                // 哈希单独用则要完整读一遍——两者都要，反正字节已经读进来了。
                .resolve(Long.toHexString(((long) core.length << 32) | bridge.length)
                        + "-" + Integer.toHexString(fnv1a(core) ^ fnv1a(bridge)));
        directory = dir;

        try {
            Files.createDirectories(dir);
            writeIfAbsent(dir.resolve(CORE), core);
            writeIfAbsent(dir.resolve(BRIDGE), bridge);
        } catch (IOException e) {
            throw new IllegalStateException("解出原生库到 " + dir + " 失败: " + e, e);
        }
        Ocg.load(dir.resolve(CORE).toString(), dir.resolve(BRIDGE).toString());
    }

    /** 已加载成功的库所在目录；未加载时返回 null。 */
    public static Path directory() {
        return directory;
    }

    /** 上一次失败的原因；成功或尚未尝试时返回 null。 */
    public static String problem() {
        return problem;
    }

    /** 资源里有没有打包原生库。用来区分「没打包」和「打包了但提取失败」。 */
    public static boolean isBundled() {
        return Natives.class.getResource(RESOURCE_DIR + CORE) != null;
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    private static void requireBoth(Path dir) {
        for (String name : new String[]{CORE, BRIDGE}) {
            if (!Files.isRegularFile(dir.resolve(name))) {
                throw new IllegalStateException(
                        "-D" + PROPERTY + " 指向的目录里缺少 " + name + ": " + dir);
            }
        }
    }

    private static byte[] readResource(String name) {
        try (InputStream in = Natives.class.getResourceAsStream(RESOURCE_DIR + name)) {
            if (in == null) {
                throw new IllegalStateException(
                        "模组里没有打包 " + RESOURCE_DIR + name
                                + "。请先构建 natives（natives/build-jni.ps1）再重新构建模组，"
                                + "或用 -D" + PROPERTY + "=<目录> 指定 DLL 所在位置。");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("读取资源 " + name + " 失败: " + e, e);
        }
    }

    /**
     * 文件已存在且大小一致就认为可以直接用，<b>不覆写</b>。
     *
     * <p>这不只是省一次写：Windows 下已被加载的 DLL 是写不动的，
     * 无条件覆写会在「用同一份构建重启一次」这种最常见的情况下失败。
     * 而目录名已经带了内容指纹，所以「大小一致但内容不同」的概率可以忽略。
     */
    private static void writeIfAbsent(Path target, byte[] bytes) throws IOException {
        if (Files.isRegularFile(target) && Files.size(target) == bytes.length) {
            return;
        }
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static boolean isWindowsX64() {
        return System.getProperty("os.name", "").toLowerCase().contains("win")
                && System.getProperty("os.arch", "").contains("64");
    }

    /** FNV-1a 32 位。用途只是「区分不同构建」，不涉及安全，所以不需要密码学哈希。 */
    private static int fnv1a(byte[] data) {
        int hash = 0x811C9DC5;
        for (byte b : data) {
            hash ^= (b & 0xFF);
            hash *= 0x01000193;
        }
        return hash;
    }
}
