package cn.xm1221.ygomc.common.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code ygomc/cache/manifest.json}。不进 git。 */
public final class SyncManifest {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public int schema = 1;
    public String appliedAt = "";
    public int pinsSchema = 1;
    public String databaseSha = "";
    public Map<String, String> databaseSha256 = new LinkedHashMap<>();
    public String scriptsSha = "";
    public String setsSha256 = "";
    /** SetsGenerator 输出格式；升版本会强制用缓存 zip 重生成（例如补 face）。 */
    public int setsFormat = 0;
    /** 对局进行中已经转换好、还没换上的版本。 */
    public String stagedDatabaseSha = "";
    public String stagedScriptsSha = "";
    public Map<String, String> bins = new LinkedHashMap<>();
    public int cardCount;
    public int scriptFiles;
    public Sets sets = new Sets();
    public List<String> degraded = new ArrayList<>();
    /** 新文件已在 cache/pending，等没有对局时再换上。见计划第 6 节。 */
    public boolean pendingReload;

    public static final class Sets {
        public int cardSets;
        public int boosters;
        public int boxes;
    }

    public static SyncManifest load(Path file) {
        if (!Files.isRegularFile(file)) {
            return new SyncManifest();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            SyncManifest manifest = GSON.fromJson(reader, SyncManifest.class);
            if (manifest == null) {
                return new SyncManifest();
            }
            if (manifest.databaseSha256 == null) {
                manifest.databaseSha256 = new LinkedHashMap<>();
            }
            if (manifest.bins == null) {
                manifest.bins = new LinkedHashMap<>();
            }
            if (manifest.sets == null) {
                manifest.sets = new Sets();
            }
            if (manifest.degraded == null) {
                manifest.degraded = new ArrayList<>();
            }
            if (manifest.stagedDatabaseSha == null) {
                manifest.stagedDatabaseSha = "";
            }
            if (manifest.stagedScriptsSha == null) {
                manifest.stagedScriptsSha = "";
            }
            return manifest;
        } catch (IOException | RuntimeException e) {
            SyncManifest broken = new SyncManifest();
            broken.degraded.add("manifest 读不了: " + e.getMessage());
            return broken;
        }
    }

    public void save(Path file) throws IOException {
        appliedAt = Instant.now().toString();
        Files.createDirectories(file.getParent());
        Path partial = file.resolveSibling(file.getFileName() + ".partial");
        try (Writer writer = Files.newBufferedWriter(partial, StandardCharsets.UTF_8)) {
            GSON.toJson(this, writer);
            writer.write('\n');
        }
        try {
            Files.move(partial, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
