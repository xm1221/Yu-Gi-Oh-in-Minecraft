package cn.xm1221.ygomc.common.sync;

import cn.xm1221.ygomc.common.data.DataPacks;
import cn.xm1221.ygomc.common.ocg.OcgEngine;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.platform.Platform;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 启动之后在守护线程里把卡库、脚本和卡包补到 {@code ygomc/}。
 * 缺网或失败都不阻断模组加载。见计划第 5、6 节。
 *
 * <p>{@code picsPrefetchAll} 即使打开也不在启动时遍历 {@code cards.bin}。
 * 全库预取只由 {@code /ygomc sync prefetch} 开始。
 */
public final class AssetSync {

    private static final Logger LOGGER = LoggerFactory.getLogger("ygomc/sync");
    private static final Object LOCK = new Object();
    private static final List<String> BIN_NAMES = List.of("cards.bin", "texts.bin", "strings.bin");

    public enum Phase {
        IDLE, CHECKING, DOWNLOADING, CONVERTING, APPLYING, READY, DEGRADED, FAILED
    }

    public record Status(Phase phase, String step, long doneBytes, long totalBytes, String messageKey,
                          List<String> args, boolean announce, boolean offlineCache) {
        public Object[] argsArray() {
            return args.toArray();
        }
    }

    private static volatile Status status = new Status(Phase.IDLE, "", -1, -1,
            "ygomc.sync.msg.idle", List.of(), false, false);
    private static volatile boolean running;
    private static volatile boolean lifecycleHooked;
    private static volatile MinecraftServer server;

    private AssetSync() {
    }

    public static Status status() {
        return status;
    }

    public static Component statusComponent() {
        Status current = status;
        return Component.translatable("ygomc.sync.status",
                Component.translatable("ygomc.sync.phase." + current.phase.name().toLowerCase(java.util.Locale.ROOT)),
                current.step,
                Component.translatable(current.messageKey, current.argsArray()),
                Long.toString(current.doneBytes),
                Long.toString(current.totalBytes));
    }

    /** 模组入口在 {@code DataPacks.init} 和 {@code OcgEngine.prepare} 之后调用。立刻返回。 */
    public static void schedule() {
        hookLifecycle();
        SyncSettings settings;
        try {
            settings = SyncConfig.current();
        } catch (Throwable e) {
            LOGGER.warn("同步配置读不了，这次不下载：{}", e.toString());
            return;
        }
        if (!settings.enabled) {
            LOGGER.info("资源同步已关闭");
            set(Phase.IDLE, "", "ygomc.sync.msg.disabled", false, false);
            return;
        }
        startWorker(Platform.getGameFolder(), settings);
    }

    /** {@code /ygomc sync run}。仍然不盖玩家手改的文件，除非配置允许。 */
    public static void requestRun() {
        hookLifecycle();
        SyncSettings settings = SyncConfig.current();
        if (!settings.enabled) {
            LOGGER.info("资源同步已关闭，忽略 sync run");
            set(Phase.IDLE, "", "ygomc.sync.msg.disabled", true, false);
            return;
        }
        startWorker(Platform.getGameFolder(), settings);
    }

    /**
     * {@code /ygomc sync apply}。没有对局时把 pending 的 bin 和脚本换上。
     * 不在这里重新下载。
     */
    public static void applyPending() {
        synchronized (LOCK) {
            Path game = Platform.getGameFolder();
            SyncManifest manifest = SyncManifest.load(CacheLayout.manifest(game));
            if (!manifest.pendingReload && !OcgEngine.isApplyPending()) {
                set(Phase.READY, "apply", "ygomc.sync.msg.nothing_pending", true, false);
                return;
            }
            if (OcgEngine.duelBlocksReload()) {
                LOGGER.info("对局进行中，引擎重新装配推迟");
                set(Phase.DEGRADED, "apply", "ygomc.sync.msg.deferred", true, true);
                return;
            }
            try {
                set(Phase.APPLYING, "apply", "ygomc.sync.msg.applying", true, false);
                promotePending(game, manifest);
                DataPacks.reload();
                OcgEngine.reprepare();
                SyncListeners.fireApplied();
                manifest.pendingReload = OcgEngine.isApplyPending();
                manifest.save(CacheLayout.manifest(game));
                reloadServerPacks();
                LOGGER.info("已重新装载卡库与脚本");
                set(manifest.pendingReload ? Phase.DEGRADED : Phase.READY, "apply",
                        manifest.pendingReload ? "ygomc.sync.msg.deferred" : "ygomc.sync.msg.applied",
                        true, false);
            } catch (IOException e) {
                LOGGER.warn("换上暂存的卡库或脚本失败：{}", e.toString());
                set(Phase.FAILED, "apply", "ygomc.sync.msg.failed", true, true, e.getMessage());
            }
        }
    }

    /**
     * 跑一轮。{@code pins} 为空时读 jar 里的 pin。{@code applyEngine} 为假时不碰
     * {@code DataPacks} / {@code OcgEngine}，给单测用。
     */
    public static void runOnce(Path gameDir, SyncSettings settings, SyncPins pins, boolean applyEngine) {
        synchronized (LOCK) {
            if (!settings.enabled) {
                set(Phase.IDLE, "", "ygomc.sync.msg.disabled", false, false);
                return;
            }
            try {
                if (pins == null) {
                    pins = SyncPins.loadDefault();
                }
                runLocked(gameDir, settings, pins, applyEngine);
            } catch (Exception e) {
                LOGGER.warn("资源同步失败，继续使用已有文件：{}", e.toString());
                set(Phase.FAILED, "sync", "ygomc.sync.msg.failed", true, true, String.valueOf(e.getMessage()));
            }
        }
    }

    private static void startWorker(Path gameDir, SyncSettings settings) {
        synchronized (LOCK) {
            if (running) {
                LOGGER.info("资源同步已经在跑");
                return;
            }
            running = true;
        }
        Thread thread = new Thread(() -> {
            try {
                runOnce(gameDir, settings, null, true);
            } finally {
                running = false;
            }
        }, "ygomc-asset-sync");
        thread.setDaemon(true);
        thread.start();
    }

    private static void hookLifecycle() {
        if (lifecycleHooked) {
            return;
        }
        lifecycleHooked = true;
        LifecycleEvent.SERVER_STARTED.register(current -> server = current);
        LifecycleEvent.SERVER_STOPPED.register(current -> {
            if (server == current) {
                server = null;
            }
        });
    }

    private static void runLocked(Path game, SyncSettings settings, SyncPins pins, boolean applyEngine)
            throws IOException {
        set(Phase.CHECKING, "manifest", "ygomc.sync.msg.checking", false, false);
        Files.createDirectories(CacheLayout.cache(game));
        cleanStaging(game);
        cleanPartials(CacheLayout.datapack(game));
        Path lockPath = CacheLayout.locks(game).resolve("sync.lock");
        Files.createDirectories(lockPath.getParent());
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (IOException e) {
                LOGGER.info("这个游戏目录的同步锁拿不到，跳过：{}", e.toString());
                set(Phase.DEGRADED, "lock", "ygomc.sync.msg.locked", true, true);
                return;
            }
            if (lock == null) {
                LOGGER.info("另一个进程正在写这个游戏目录，跳过");
                set(Phase.DEGRADED, "lock", "ygomc.sync.msg.locked", true, true);
                return;
            }
            try {
                work(game, settings, pins, applyEngine);
            } finally {
                lock.release();
            }
        }
    }

    private static void work(Path game, SyncSettings settings, SyncPins pins, boolean applyEngine) throws IOException {
        SyncManifest manifest = SyncManifest.load(CacheLayout.manifest(game));
        manifest.degraded = new ArrayList<>();
        boolean duel = applyEngine && OcgEngine.duelBlocksReload();
        if (manifest.pendingReload && !duel) {
            set(Phase.APPLYING, "pending", "ygomc.sync.msg.applying", true, false);
            promotePending(game, manifest);
            if (applyEngine) {
                DataPacks.reload();
                OcgEngine.reprepare();
                SyncListeners.fireApplied();
                manifest.pendingReload = OcgEngine.isApplyPending();
            } else {
                manifest.pendingReload = false;
            }
        }

        boolean offline = false;
        boolean engineChanged = false;
        boolean setsChanged = false;
        if (settings.database) {
            engineChanged |= database(game, settings, pins, manifest, duel);
            offline |= manifest.degraded.stream().anyMatch(line -> line.startsWith("database"));
        }
        if (settings.scripts) {
            engineChanged |= scripts(game, settings, pins, manifest, duel);
            offline |= manifest.degraded.stream().anyMatch(line -> line.startsWith("scripts"));
        }
        if (settings.sets) {
            setsChanged = sets(game, settings, pins, manifest);
        }
        if (settings.picsPrefetchAll) {
            LOGGER.info("picsPrefetchAll 已打开，启动时仍然不预取。要下全库请用 /ygomc sync prefetch，每秒最多一张，可 stop");
        }
        manifest.pinsSchema = pins.schema;
        manifest.save(CacheLayout.manifest(game));

        if (applyEngine && engineChanged && !manifest.pendingReload) {
            set(Phase.APPLYING, "engine", "ygomc.sync.msg.applying", true, offline);
            DataPacks.reload();
            OcgEngine.reprepare();
            SyncListeners.fireApplied();
            manifest.pendingReload = OcgEngine.isApplyPending();
            manifest.save(CacheLayout.manifest(game));
        }
        if (setsChanged && !OcgEngine.duelBlocksReload()) {
            reloadServerPacks();
        }

        boolean binsOk = binsValid(CacheLayout.datapack(game));
        boolean scriptsOk = Files.isRegularFile(CacheLayout.script(game).resolve("constant.lua"))
                || !settings.scripts;
        if (manifest.pendingReload) {
            LOGGER.info("对局进行中，新的卡库或脚本留在缓存里，下一局开始前或 /ygomc sync apply 才会换上");
            set(Phase.DEGRADED, "pending", "ygomc.sync.msg.deferred", true, true);
        } else if (!manifest.degraded.isEmpty() && (binsOk || scriptsOk)) {
            LOGGER.info("部分资源没更新，继续使用已有文件：{}", manifest.degraded);
            set(Phase.DEGRADED, "done", "ygomc.sync.msg.degraded", true, true, String.join(" ", manifest.degraded));
        } else if (!manifest.degraded.isEmpty()) {
            set(Phase.FAILED, "done", "ygomc.sync.msg.failed", true, true, String.join(" ", manifest.degraded));
        } else if (engineChanged || setsChanged) {
            LOGGER.info("资源同步完成：卡 {} 张，脚本 {} 个文件，系列 {} / 包 {} / 盒 {}",
                    manifest.cardCount, manifest.scriptFiles,
                    manifest.sets.cardSets, manifest.sets.boosters, manifest.sets.boxes);
            set(Phase.READY, "done", "ygomc.sync.msg.ready", true, false,
                    Integer.toString(manifest.cardCount), Integer.toString(manifest.scriptFiles));
        } else {
            LOGGER.info("清单一致，pin 未变，本次没有下载大文件");
            set(Phase.READY, "done", "ygomc.sync.msg.skip", false, false);
        }
    }

    private static boolean database(Path game, SyncSettings settings, SyncPins pins, SyncManifest manifest,
                                    boolean duel) throws IOException {
        Path live = CacheLayout.datapack(game);
        boolean valid = binsValid(live);
        boolean hashesMatch = valid && hashesMatch(live, manifest);
        SyncPolicy.Database decision = SyncPolicy.database(valid, hashesMatch, manifest.databaseSha,
                pins.database.sha, settings.allowReplaceNewerLocal);
        Path pending = CacheLayout.pendingDatapack(game);
        if (duel && sameSha(pins.database.sha, manifest.stagedDatabaseSha) && binsValid(pending)) {
            LOGGER.info("卡库 {} 已暂存，等对局结束再换上", shortSha(pins.database.sha));
            return false;
        }
        if (decision == SyncPolicy.Database.SKIP) {
            LOGGER.info("卡库清单一致，跳过（{}，{} 张）", shortSha(pins.database.sha), manifest.cardCount);
            return false;
        }
        if (decision == SyncPolicy.Database.KEEP_LOCAL) {
            LOGGER.info("本地数据包与清单不一致，保留本地");
            set(Phase.CHECKING, "database", "ygomc.sync.msg.keep_local", false, true);
            return false;
        }
        set(Phase.DOWNLOADING, "cards.cdb", "ygomc.sync.msg.downloading", true, false, "cards.cdb");
        LOGGER.info("开始下载卡库 {}", shortSha(pins.database.sha));
        Path cache = CacheLayout.database(game, pins.database.sha);
        SyncPins.FilePin cdb = pins.requireFile("cards.cdb");
        SyncPins.FilePin conf = pins.requireFile("strings.conf");
        if (!fetchPinned(cdb, cache.resolve("cards.cdb"), settings, 64L * 1024 * 1024, game)) {
            manifest.degraded.add("database:download");
            LOGGER.warn("卡库下载失败，继续使用已有文件");
            return false;
        }
        if (!fetchPinned(conf, cache.resolve("strings.conf"), settings, 8L * 1024 * 1024, game)) {
            manifest.degraded.add("database:download");
            LOGGER.warn("strings.conf 下载失败，不替换现有的 strings.bin");
            return false;
        }
        set(Phase.CONVERTING, "bins", "ygomc.sync.msg.converting", true, false);
        Path target = duel ? pending : live;
        if (duel) {
            ScriptInstaller.deleteTree(target);
        }
        try {
            CdbToBins.Result result = CdbToBins.convert(cache.resolve("cards.cdb"), cache.resolve("strings.conf"), target);
            manifest.cardCount = result.cards();
            LOGGER.info("卡库已写入，{} 张（commit {}）", result.cards(), shortSha(pins.database.sha));
            if (duel) {
                manifest.pendingReload = true;
                manifest.stagedDatabaseSha = pins.database.sha;
                LOGGER.info("对局进行中，三个 bin 先放在缓存");
                return false;
            }
            rememberBins(live, manifest);
            manifest.databaseSha256.put("cards.cdb", Digests.sha256(cache.resolve("cards.cdb")));
            manifest.databaseSha256.put("strings.conf", Digests.sha256(cache.resolve("strings.conf")));
            manifest.databaseSha = pins.database.sha;
            manifest.stagedDatabaseSha = "";
            return true;
        } catch (IOException e) {
            LOGGER.warn("卡库转换失败，保留上一份 bin：{}", e.getMessage());
            manifest.degraded.add("database:convert");
            if (duel) {
                ScriptInstaller.deleteTree(target);
            }
            return false;
        }
    }

    private static boolean scripts(Path game, SyncSettings settings, SyncPins pins, SyncManifest manifest,
                                   boolean duel) throws IOException {
        boolean sentinel = Files.isRegularFile(CacheLayout.script(game).resolve("constant.lua"));
        SyncPolicy.Scripts decision = SyncPolicy.scripts(sentinel, manifest.scriptsSha, pins.scripts.sha);
        Path pending = CacheLayout.pendingScript(game);
        if (duel && sameSha(pins.scripts.sha, manifest.stagedScriptsSha)
                && Files.isRegularFile(pending.resolve("constant.lua"))) {
            LOGGER.info("脚本 {} 已暂存，等对局结束再换上", shortSha(pins.scripts.sha));
            return false;
        }
        if (decision == SyncPolicy.Scripts.SKIP_PIN) {
            LOGGER.info("脚本清单一致，跳过（{}，GPL-2.0，不随模组分发）", shortSha(pins.scripts.sha));
            return false;
        }
        if (decision == SyncPolicy.Scripts.SKIP_PLAYER) {
            LOGGER.info("已有玩家自备脚本（constant.lua），不下载覆盖");
            set(Phase.CHECKING, "scripts", "ygomc.sync.msg.player_scripts", false, true);
            return false;
        }
        set(Phase.DOWNLOADING, "scripts", "ygomc.sync.msg.downloading", true, false, "ygopro-scripts");
        LOGGER.info("开始下载脚本 {}（GPL-2.0，不随模组分发）", shortSha(pins.scripts.sha));
        Path zip = CacheLayout.scriptsZip(game, pins.scripts.sha);
        HttpFetch.Options options = options(settings, 80L * 1024 * 1024, game);
        if (!Files.isRegularFile(zip)) {
            HttpFetch.Result fetched = HttpFetch.get(pins.scripts.urls, zip, options);
            if (!fetched.ok) {
                LOGGER.warn("脚本下载失败，继续使用已有文件：{}", fetched.error);
                manifest.degraded.add("scripts:download");
                return false;
            }
        }
        Path target = duel ? pending : CacheLayout.script(game);
        try {
            if (duel) {
                ScriptInstaller.deleteTree(target);
            }
            int files = ScriptInstaller.install(zip, target);
            LOGGER.info("脚本已落盘，{} 个文件，commit {}，GPL-2.0，不随模组分发", files, shortSha(pins.scripts.sha));
            if (duel) {
                manifest.pendingReload = true;
                manifest.stagedScriptsSha = pins.scripts.sha;
                return false;
            }
            manifest.scriptsSha = pins.scripts.sha;
            manifest.scriptFiles = files;
            manifest.stagedScriptsSha = "";
            return true;
        } catch (IOException e) {
            LOGGER.warn("脚本解压失败，保留上一份：{}", e.getMessage());
            manifest.degraded.add("scripts:install");
            return false;
        }
    }

    private static boolean sets(Path game, SyncSettings settings, SyncPins pins, SyncManifest manifest)
            throws IOException {
        Path live = CacheLayout.sets(game);
        boolean present = Files.isRegularFile(live.resolve("pack.mcmeta"));
        if (present && pins.sets.sha256.equalsIgnoreCase(manifest.setsSha256)) {
            LOGGER.info("卡包清单一致，跳过（{}）", pins.sets.sha256.substring(0, 16));
            return false;
        }
        if (present && (manifest.setsSha256 == null || manifest.setsSha256.isBlank())
                && !settings.allowReplaceNewerLocal) {
            LOGGER.info("已有玩家自备卡包目录，不覆盖");
            return false;
        }
        set(Phase.DOWNLOADING, "sets", "ygomc.sync.msg.downloading", true, false, "YGOJSON");
        LOGGER.info("开始下载卡包 zip，期望 sha256 {}", pins.sets.sha256);
        Path zip = CacheLayout.setsZip(game, pins.sets.sha256);
        boolean cached = Files.isRegularFile(zip) && Digests.hexEquals(pins.sets.sha256, Digests.sha256(zip));
        if (!cached) {
            HttpFetch.Options options = options(settings, 200L * 1024 * 1024, game);
            options.expectedSha256 = pins.sets.sha256;
            HttpFetch.Result fetched = HttpFetch.get(pins.sets.urls, zip, options);
            if (!fetched.ok) {
                LOGGER.warn("卡包 zip 下载或校验失败，保留上一份：{}", fetched.error);
                manifest.degraded.add("sets:download");
                Files.deleteIfExists(zip);
                return false;
            }
        }
        set(Phase.CONVERTING, "sets", "ygomc.sync.msg.converting", true, false);
        Path incoming = CacheLayout.staging(game).resolve("sets-out");
        ScriptInstaller.deleteTree(incoming);
        try {
            SetsGenerator.Result result = SetsGenerator.generate(zip, incoming);
            ScriptInstaller.replaceDirectory(incoming, live);
            manifest.setsSha256 = pins.sets.sha256.toLowerCase(java.util.Locale.ROOT);
            manifest.sets.cardSets = result.cardSets();
            manifest.sets.boosters = result.boosters();
            manifest.sets.boxes = result.boxes();
            LOGGER.info("卡包已写入 ygomc/sets：系列 {} 包 {} 盒 {}，没有卡密的印刷 {}。查找器见计划第 6 节",
                    result.cardSets(), result.boosters(), result.boxes(), result.noPassword());
            return true;
        } catch (IOException e) {
            ScriptInstaller.deleteTree(incoming);
            LOGGER.warn("卡包生成失败，保留上一份：{}", e.getMessage());
            manifest.degraded.add("sets:generate");
            return false;
        }
    }

    private static void promotePending(Path game, SyncManifest manifest) throws IOException {
        Path pendingBins = CacheLayout.pendingDatapack(game);
        if (binsValid(pendingBins)) {
            Files.createDirectories(CacheLayout.datapack(game));
            for (String name : BIN_NAMES) {
                moveReplace(pendingBins.resolve(name), CacheLayout.datapack(game).resolve(name));
            }
            rememberBins(CacheLayout.datapack(game), manifest);
            if (manifest.stagedDatabaseSha != null && !manifest.stagedDatabaseSha.isBlank()) {
                manifest.databaseSha = manifest.stagedDatabaseSha;
            }
            manifest.stagedDatabaseSha = "";
            ScriptInstaller.deleteTree(pendingBins);
        }
        Path pendingScript = CacheLayout.pendingScript(game);
        if (Files.isRegularFile(pendingScript.resolve("constant.lua"))) {
            ScriptInstaller.replaceDirectory(pendingScript, CacheLayout.script(game));
            if (manifest.stagedScriptsSha != null && !manifest.stagedScriptsSha.isBlank()) {
                manifest.scriptsSha = manifest.stagedScriptsSha;
            }
            manifest.stagedScriptsSha = "";
        }
        manifest.pendingReload = false;
    }

    private static void rememberBins(Path dir, SyncManifest manifest) throws IOException {
        if (manifest.databaseSha256 == null) {
            manifest.databaseSha256 = new java.util.LinkedHashMap<>();
        }
        for (String name : BIN_NAMES) {
            manifest.bins.put(name, Digests.sha256(dir.resolve(name)));
        }
    }

    private static boolean sameSha(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static boolean fetchPinned(SyncPins.FilePin file, Path dest, SyncSettings settings, long maxBytes,
                                       Path game) throws IOException {
        if (file.sha256 != null && !file.sha256.isBlank() && Files.isRegularFile(dest)
                && Digests.hexEquals(file.sha256, Digests.sha256(dest))) {
            return true;
        }
        HttpFetch.Options options = options(settings, maxBytes, game);
        options.expectedSha256 = file.sha256;
        HttpFetch.Result result = HttpFetch.get(file.urls, dest, options);
        if (!result.ok) {
            LOGGER.warn("下载 {} 失败：{}", file.id, result.error);
            return false;
        }
        return true;
    }

    private static HttpFetch.Options options(SyncSettings settings, long maxBytes, Path game) {
        HttpFetch.Options options = new HttpFetch.Options();
        options.connectTimeout = Duration.ofSeconds(settings.timeoutSeconds());
        options.maxBytes = maxBytes;
        options.etagStore = CacheLayout.etag(game);
        return options;
    }

    private static boolean binsValid(Path dir) {
        return magic(dir.resolve("cards.bin"), "YGOMCCD1")
                && magic(dir.resolve("texts.bin"), "YGOMCTX2")
                && magic(dir.resolve("strings.bin"), "YGOMCST1");
    }

    private static boolean magic(Path file, String magic) {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        byte[] expect = magic.getBytes(StandardCharsets.US_ASCII);
        try (var in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(expect.length);
            return Arrays.equals(expect, head);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean hashesMatch(Path dir, SyncManifest manifest) throws IOException {
        if (manifest.bins == null || manifest.bins.isEmpty()) {
            return false;
        }
        for (String name : BIN_NAMES) {
            String want = manifest.bins.get(name);
            if (want == null || !Digests.hexEquals(want, Digests.sha256(dir.resolve(name)))) {
                return false;
            }
        }
        return true;
    }

    private static void cleanStaging(Path game) throws IOException {
        Path staging = CacheLayout.staging(game);
        if (!Files.isDirectory(staging)) {
            return;
        }
        long cutoff = System.currentTimeMillis() - Duration.ofDays(1).toMillis();
        try (var list = Files.list(staging)) {
            for (Path path : list.toList()) {
                if (Files.getLastModifiedTime(path).toMillis() < cutoff) {
                    ScriptInstaller.deleteTree(path);
                }
            }
        }
    }

    private static void cleanPartials(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var list = Files.list(dir)) {
            for (Path path : list.toList()) {
                if (path.getFileName().toString().endsWith(".partial")) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void moveReplace(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void reloadServerPacks() {
        MinecraftServer current = server;
        if (current == null) {
            return;
        }
        current.execute(() -> {
            try {
                var repository = current.getPackRepository();
                repository.reload();
                if (repository.getPack(SetsPackFinder.PACK_ID) != null
                        && !repository.getSelectedIds().contains(SetsPackFinder.PACK_ID)) {
                    repository.addPack(SetsPackFinder.PACK_ID);
                }
                current.reloadResources(repository.getSelectedIds());
                LOGGER.info("已请求重载数据包，让 ygomc/sets 进入当前世界");
            } catch (RuntimeException e) {
                LOGGER.warn("卡包数据包重载失败（目录仍在，下次进世界会再试）：{}", e.toString());
            }
        });
    }

    private static void set(Phase phase, String step, String key, boolean announce, boolean offline, String... args) {
        status = new Status(phase, step, -1, -1, key, List.of(args), announce, offline);
    }

    private static String shortSha(String sha) {
        if (sha == null || sha.length() < 7) {
            return String.valueOf(sha);
        }
        return sha.substring(0, 7);
    }

    static void testingReset() {
        status = new Status(Phase.IDLE, "", -1, -1, "ygomc.sync.msg.idle", List.of(), false, false);
        running = false;
    }
}
