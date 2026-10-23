package cn.xm1221.ygomc.common.ocg;

import cn.xm1221.ygomc.common.data.CardDataDb;
import cn.xm1221.ygomc.common.data.DataPack;
import cn.xm1221.ygomc.common.data.DataPacks;
import dev.architectury.platform.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
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

    /** 脚本目录里必须存在的框架脚本。它不存在说明这个目录根本不是脚本根。 */
    private static final String SENTINEL_SCRIPT = "constant.lua";

    private static boolean attempted;
    private static boolean ready;
    private static String problem;
    private static Path scriptRoot;
    /** 最近一次真正装配成功。重试失败时用它把标志位放回去。 */
    private static boolean committed;
    private static Path committedRoot;
    /** {@link #prepareOrThrow} 已经调用过 {@code Ocg.init}，失败时要尽量把内核拉回旧脚本根。 */
    private static boolean nativeMutated;
    /** 对局还在跑，新的卡表先不要灌进去。 */
    private static boolean pendingApply;
    /** 单测把「有没有对局」钉死，避免去开一局。{@code null} 表示看 {@link cn.xm1221.ygomc.common.ocg.DuelSessions}。 */
    static Boolean testingDuelActive;

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
            problem = null;
            committed = true;
            committedRoot = scriptRoot;
            LOGGER.info("引擎就绪：{} 张卡，脚本根 {}", Ocg.cardCount(), scriptRoot);
        } catch (Exception | UnsatisfiedLinkError e) {
            problem = e.getMessage() == null ? e.toString() : e.getMessage();
            ready = false;
            LOGGER.warn("引擎不可用，对局功能无法使用：{}", problem);
        }
        return ready;
    }

    /**
     * 卡库或脚本换过之后再装配一次。有对局就推迟。
     * 失败时保留上一份已经能打的引擎，而不是把 {@code ready} 清掉。
     * 见计划第 6 节。只给同步线程和 {@code /ygomc sync apply} 用，不是无限重试。
     */
    public static synchronized boolean reprepare() {
        if (duelBlocksReload()) {
            pendingApply = true;
            LOGGER.info("对局进行中，引擎重新装配推迟");
            return ready;
        }
        boolean had = committed && ready;
        Path oldRoot = committedRoot;
        boolean touchedBefore = nativeMutated;
        attempted = false;
        nativeMutated = false;
        String failure = null;
        boolean ok = prepare();
        if (!ok) {
            failure = problem;
        }
        if (ok) {
            pendingApply = false;
            return true;
        }
        if (!had) {
            return false;
        }
        if (nativeMutated || touchedBefore) {
            try {
                restoreCommitted(oldRoot);
                ready = true;
                problem = null;
                scriptRoot = oldRoot;
                committed = true;
                committedRoot = oldRoot;
                attempted = true;
                nativeMutated = false;
                LOGGER.warn("重新装配失败，已回到上一份脚本根 {}：{}", oldRoot, failure);
                return true;
            } catch (Exception | UnsatisfiedLinkError e) {
                ready = false;
                committed = false;
                problem = e.getMessage() == null ? e.toString() : e.getMessage();
                attempted = true;
                LOGGER.warn("重新装配失败，且无法回到上一份引擎：{}", problem);
                return false;
            }
        }
        ready = true;
        problem = null;
        scriptRoot = oldRoot;
        committed = true;
        committedRoot = oldRoot;
        attempted = true;
        LOGGER.warn("重新装配失败，保留上一份引擎：{}", failure);
        return true;
    }

    /** 有正在进行的对局，或单测强制说有。不调用 {@code Platform}。 */
    public static boolean duelBlocksReload() {
        Boolean forced = testingDuelActive;
        if (forced != null) {
            return forced;
        }
        return !DuelSessions.active().isEmpty();
    }

    public static synchronized boolean isApplyPending() {
        return pendingApply;
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
     * 脚本根只有一个地方：<b>版本文件夹下的 {@code ygomc/script}</b>（单数，与 ygopro
     * 自己的目录名一致）。
     *
     * <p>刻意不找别处（咩咩 2026-10-05 定）：
     * <ul>
     *   <li>不收复数 {@code ygomc/scripts}——两种命名都认，只会让人把脚本放错地方还以为生效了；</li>
     *   <li>不认 {@code -Dygomc.scripts} 这类显式属性——那等于给「脚本到底从哪来」留了第二个答案；</li>
     *   <li>不逐级向上找开发用的 {@code local-data/scripts}——打包版和开发版悄悄走不同目录，
     *       出问题时两边表现还不一样，正是那四轮排查的成因。</li>
     * </ul>
     *
     * <p>目录<b>存在</b>不等于它就是脚本根：还要看里面有没有 {@code constant.lua}
     * （见 {@link #firstRoot}）。只看名字挑，会选中一个空目录，然后 {@code Ocg.init} 失败。
     *
     * <p>仍然返回 {@code List}、仍然不碰文件系统，是为了让错误消息能列出「找过哪里」，
     * 也便于脱离 Minecraft 断言。
     */
    public static List<Path> scriptCandidates(Path gameDir) {
        Path dir = gameDir.toAbsolutePath().normalize();
        return List.of(dir.resolve("ygomc/script"));
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    private static void prepareOrThrow() {
        nativeMutated = false;
        Natives.ensureLoaded();

        Path root = findScriptRoot();
        // 内核按原样把它拼进脚本路径，反斜杠在 Lua 的 require 里是转义字符，
        // 所以这里统一成正斜杠，避免 "C:\...\script\c123.lua" 被解析成乱码。
        nativeMutated = true;
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
        // `Platform.getGameFolder()` 在脱离 Minecraft 时会直接抛断言，
        // 而它以前是**无条件**求值的——于是「生产路径能不能离线跑」这件事
        // 被一个本来无关的调用卡死：整条 `OcgDuel.playOut` 在测试里起不来，
        // 那些「点了没反应的静默 bug」就只能靠玩家进游戏一个个撞出来。
        List<Path> candidates = scriptCandidates(Platform.getGameFolder());
        Path found = firstRoot(candidates);
        if (found != null) {
            return found;
        }
        StringBuilder sb = new StringBuilder("找不到 Lua 脚本根目录，已依次查找:");
        for (Path c : candidates) {
            sb.append("\n  - ").append(c);
        }
        sb.append("\n脚本来自 ygopro 客户端（GPLv2，不能随模组分发），请自行准备一份，")
          .append("放到版本文件夹下的 ygomc/script，目录里应当有 ")
          .append(SENTINEL_SCRIPT).append(" 与 c*.lua。");
        throw new IllegalStateException(sb.toString());
    }

    /**
     * 从候选里挑第一个<b>真的像脚本根</b>的目录（里面有 {@code constant.lua}）；都不像返回 {@code null}。
     *
     * <p>公开是为了能脱离 Minecraft 断言这条规则本身：咩咩 2026-10-05 的四轮排查，
     * 真因就在这一步——名单里排在前面的 {@code ygomc/scripts} 是个空目录，
     * 只按名字挑就会选中它，引擎于是永远装配不起来。
     */
    private static void restoreCommitted(Path root) {
        if (root == null || !Files.isRegularFile(root.resolve(SENTINEL_SCRIPT))) {
            throw new IllegalStateException("上一份脚本根已经不在：" + root);
        }
        Natives.ensureLoaded();
        if (!Ocg.init(root.toString().replace('\\', '/'))) {
            throw new IllegalStateException("回到脚本根失败 " + root);
        }
        scriptRoot = root;
        DataPack pack = DataPacks.get();
        CardDataDb data = pack.cardData();
        if (data == null) {
            throw new IllegalStateException("卡表不可用：" + pack.problems());
        }
        int written = Ocg.putCards(data.codes(), data.blob());
        if (written != data.size()) {
            throw new IllegalStateException("卡表灌入不完整：期望 " + data.size() + " 张，实际 " + written + " 张");
        }
    }

    /** 单测清掉一次性标志。不要在游戏里调用。 */
    static synchronized void testingReset() {
        attempted = false;
        ready = false;
        problem = null;
        scriptRoot = null;
        committed = false;
        committedRoot = null;
        nativeMutated = false;
        pendingApply = false;
        testingDuelActive = null;
    }

    static synchronized void testingMarkFailed(String why) {
        attempted = true;
        ready = false;
        committed = false;
        problem = why;
        nativeMutated = false;
    }

    static synchronized void testingMarkReady(Path root) {
        attempted = true;
        ready = true;
        committed = true;
        committedRoot = root;
        scriptRoot = root;
        problem = null;
        nativeMutated = false;
        pendingApply = false;
    }

    public static Path firstRoot(List<Path> candidates) {
        for (Path c : candidates) {
            if (Files.isRegularFile(c.resolve(SENTINEL_SCRIPT))) {
                return c;
            }
        }
        return null;
    }
}
