package cn.xm1221.ygomc.common.ocg;

import cn.xm1221.ygomc.common.data.CardDataDb;
import cn.xm1221.ygomc.common.data.DataPack;
import cn.xm1221.ygomc.common.data.DataPacks;
import dev.architectury.platform.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 引擎的一次性装配：原生库 → 脚本根目录 → 卡表灌入。
 *
 * <p>这三件事都必须在任何一局开始<b>之前</b>完成，而且都只做一次，所以集中在这里。
 * {@link OcgDuel} 只管跑局，不关心引擎是怎么准备好的。
 *
 * <h2>三件事的顺序是有约束的</h2>
 * <ol>
 *   <li>{@link Ocg#init} 必须在 {@link Ocg#putCards} 之前——脚本缓存是按脚本根建立的，
 *       换根会清空缓存；</li>
 *   <li>{@link Ocg#putCards} 必须在 {@link Ocg#create} 之前——内核是在建局时把卡表
 *       快照进 {@code duel} 的，之后再灌不会影响已经建好的局；</li>
 *   <li>原生库必须最先加载，否则上面两个 native 方法根本不存在。</li>
 * </ol>
 *
 * <h2>为什么脚本根要单独找</h2>
 * Lua 卡脚本（约 13576 个文件、32 MB）来自 ygopro 客户端，是 <b>GPLv2</b>，
 * 与本项目的 GPLv3 不兼容，所以<b>绝不随模组分发</b>，只能由用户自己准备。
 * 这与数据包（{@code cards.bin} 等）是同一类东西：模组内置的是「去哪找」的逻辑，
 * 不是内容本身。查找顺序见 {@link #scriptCandidates}。
 */
public final class OcgEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger("ygomc/engine");

    /** 显式指定脚本根目录：{@code -Dygomc.scripts=<目录>}。 */
    public static final String SCRIPTS_PROPERTY = "ygomc.scripts";

    /** 脚本目录里必须存在的框架脚本。它不存在说明这个目录根本不是脚本根。 */
    private static final String SENTINEL_SCRIPT = "constant.lua";

    private static boolean attempted;
    private static boolean ready;
    private static String problem;
    private static Path scriptRoot;

    private OcgEngine() {
    }

    /**
     * 装配引擎。<b>幂等</b>：重复调用只会返回第一次的结果。
     *
     * @return 是否可用；失败原因见 {@link #problem()}
     */
    public static synchronized boolean prepare() {
        if (attempted) {
            return ready;
        }
        attempted = true;
        try {
            prepareOrThrow();
            ready = true;
            LOGGER.info("引擎就绪：{} 张卡，脚本根 {}", Ocg.cardCount(), scriptRoot);
        } catch (Exception | UnsatisfiedLinkError e) {
            problem = e.getMessage() == null ? e.toString() : e.getMessage();
            LOGGER.warn("引擎不可用，对局功能无法使用：{}", problem);
        }
        return ready;
    }

    /** 引擎是否已装配好。不会触发装配。 */
    public static synchronized boolean isReady() {
        return ready;
    }

    /** 装配失败的原因；成功或尚未尝试时返回 null。 */
    public static synchronized String problem() {
        return problem;
    }

    /** 生效的脚本根目录；未装配时返回 null。 */
    public static synchronized Path scriptRoot() {
        return scriptRoot;
    }

    /**
     * 按顺序列出可能的脚本根目录。
     *
     * <p>与数据包同构：显式属性 → 游戏目录下的 {@code ygomc/scripts}
     * → 从游戏目录逐级向上找 {@code local-data/scripts}（开发用）。
     *
     * <p>不碰文件系统，只拼路径，便于脱离 Minecraft 测试。
     */
    public static List<Path> scriptCandidates(Path gameDir) {
        String override = System.getProperty(SCRIPTS_PROPERTY);
        if (override != null && !override.isBlank()) {
            return List.of(Path.of(override).toAbsolutePath().normalize());
        }
        List<Path> out = new ArrayList<>();
        Path dir = gameDir.toAbsolutePath().normalize();
        out.add(dir.resolve("ygomc/scripts"));
        for (int up = 0; up <= 3 && dir != null; up++) {
            out.add(dir.resolve("local-data/scripts"));
            dir = dir.getParent();
        }
        return out;
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    private static void prepareOrThrow() {
        Natives.ensureLoaded();

        Path root = findScriptRoot();
        // 内核按原样把它拼进脚本路径，反斜杠在 Lua 的 require 里是转义字符，
        // 所以这里统一成正斜杠，避免 "C:\...\script\c123.lua" 被解析成乱码。
        if (!Ocg.init(root.toString().replace('\\', '/'))) {
            throw new IllegalStateException("Ocg.init 返回 false（脚本根 " + root + "）");
        }
        scriptRoot = root;

        DataPack pack = DataPacks.get();
        CardDataDb data = pack.cardData();
        if (data == null) {
            throw new IllegalStateException("卡表不可用：" + pack.problems());
        }
        int written = Ocg.putCards(data.codes(), data.blob());
        if (written != data.size()) {
            throw new IllegalStateException(
                    "卡表灌入不完整：期望 " + data.size() + " 张，实际 " + written + " 张");
        }
    }

    private static Path findScriptRoot() {
        // 只在没有显式指定时才去问 Architectury 要游戏目录。
        // `Platform.getGameFolder()` 在脱离 Minecraft 时会直接抛断言，
        // 而它以前是**无条件**求值的——于是「生产路径能不能离线跑」这件事
        // 被一个本来无关的调用卡死：整条 `OcgDuel.playOut` 在测试里起不来，
        // 那些「点了没反应的静默 bug」就只能靠玩家进游戏一个个撞出来。
        String override = System.getProperty(SCRIPTS_PROPERTY);
        boolean explicit = override != null && !override.isBlank();
        List<Path> candidates = scriptCandidates(explicit ? null : Platform.getGameFolder());
        for (Path c : candidates) {
            if (Files.isRegularFile(c.resolve(SENTINEL_SCRIPT))) {
                return c;
            }
        }
        StringBuilder sb = new StringBuilder("找不到 Lua 脚本根目录，已依次查找:");
        for (Path c : candidates) {
            sb.append("\n  - ").append(c);
        }
        sb.append("\n脚本来自 ygopro 客户端（GPLv2，不能随模组分发），请自行准备一份，")
          .append("目录里应当有 ").append(SENTINEL_SCRIPT).append(" 与 c*.lua；")
          .append("或用 -D").append(SCRIPTS_PROPERTY).append("=<目录> 指定。");
        throw new IllegalStateException(sb.toString());
    }
}
