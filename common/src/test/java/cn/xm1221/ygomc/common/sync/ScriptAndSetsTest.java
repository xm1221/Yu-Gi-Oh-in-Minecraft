package cn.xm1221.ygomc.common.sync;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptAndSetsTest {

    @Test
    void scriptZipStripsTopDirectory() throws Exception {
        Path dir = Files.createTempDirectory("ygomc-script-zip");
        Path zip = dir.resolve("scripts.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            put(out, "ygopro-scripts-abc/constant.lua", "CONSTANT = 1\n");
            put(out, "ygopro-scripts-abc/c1001.lua", "return 1\n");
            put(out, "ygopro-scripts-abc/nested/note.lua", "return 2\n");
        }
        Path script = dir.resolve("script");
        int files = ScriptInstaller.install(zip, script);
        assertEquals(3, files);
        assertTrue(Files.isRegularFile(script.resolve("constant.lua")));
        assertTrue(Files.isRegularFile(script.resolve("c1001.lua")));
        assertFalse(Files.exists(script.resolve("ygopro-scripts-abc")));
        delete(dir);
    }

    @Test
    void setsMatchPythonAndNeverWriteDemo() throws Exception {
        Path dir = Files.createTempDirectory("ygomc-sets");
        Path zip = dir.resolve("individual.zip");
        writeFixtureZip(zip);
        Path javaOut = dir.resolve("java");
        Path pyOut = dir.resolve("py");
        SetsGenerator.Result result = SetsGenerator.generate(zip, javaOut);
        assertTrue(result.cardSets() >= 3, result.toString());
        assertFalse(Files.exists(javaOut.resolve("data/ygomc/ygomc/card_set/demo.json")));
        JsonObject plain = readJson(javaOut.resolve("data/ygomc/ygomc/booster/pln_jp.json"));
        JsonObject tcg = readJson(javaOut.resolve("data/ygomc/ygomc/booster/tcg_jp.json"));
        JsonObject pre = readJson(javaOut.resolve("data/ygomc/ygomc/booster/pre_jp.json"));
        JsonObject box = readJson(javaOut.resolve("data/ygomc/ygomc/booster_box/pln_jp.json"));
        assertEquals("print_weight", plain.get("odds_origin").getAsString());
        assertEquals("sheet_adapted", tcg.get("odds_origin").getAsString());
        assertEquals("preconstructed", pre.get("odds_origin").getAsString());
        assertEquals(PackColors.of("PLN-JP"), plain.getAsJsonObject("display").get("color").getAsString());
        assertEquals(PackColors.of("TCG-JP"), tcg.getAsJsonObject("display").get("color").getAsString());
        assertEquals(plain.getAsJsonObject("display").get("color").getAsString(),
                box.getAsJsonObject("display").get("color").getAsString());
        assertFalse(plain.getAsJsonObject("display").get("color").getAsString().equalsIgnoreCase("#FFFFFF"));
        assertEquals(PackColors.of("PLN-JP"), PackColors.of("PLN-JP"));
        Process process = new ProcessBuilder("python3", repoRoot().resolve("tools/mksets.py").toString(),
                "--from", "ygojson", "--zip", zip.toString(), "--out", pyOut.toString())
                .redirectErrorStream(true)
                .start();
        String log = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), log);
        // Java 禁止 id demo，离线脚本没有这条。对拍时把两边的 demo 文件都挪走。
        Files.deleteIfExists(pyOut.resolve("data/ygomc/ygomc/card_set/demo.json"));
        Files.deleteIfExists(pyOut.resolve("data/ygomc/ygomc/booster/demo.json"));
        Files.deleteIfExists(javaOut.resolve("data/ygomc/ygomc/card_set/demo_2.json"));
        Files.deleteIfExists(javaOut.resolve("data/ygomc/ygomc/booster/demo_2.json"));
        try (var walk = Files.walk(pyOut)) {
            for (Path file : walk.filter(path -> path.toString().endsWith(".json") || path.endsWith("pack.mcmeta")).toList()) {
                Path rel = pyOut.relativize(file);
                Path other = javaOut.resolve(rel);
                assertTrue(Files.isRegularFile(other), rel.toString());
                JsonElement left = JsonParser.parseString(Files.readString(file));
                JsonElement right = JsonParser.parseString(Files.readString(other));
                assertEquals(left, right, rel.toString());
            }
        }
        delete(dir);
    }

    @Test
    void pinsInJarAreComplete() throws Exception {
        SyncPins pins = SyncPins.loadDefault();
        assertEquals(1, pins.schema);
        assertEquals(40, pins.database.sha.length());
        assertEquals(40, pins.scripts.sha.length());
        assertEquals(64, pins.sets.sha256.length());
        assertTrue(pins.pics.urls.get(0).contains("{code}"));
    }

    private static void writeFixtureZip(Path zip) throws Exception {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            put(out, "cards.json", "[\"c1\",\"c2\",\"c3\",\"c4\",\"c5\",\"c6\",\"ghost\",\"missing\"]");
            for (int i = 1; i <= 6; i++) {
                put(out, "cards/c" + i + ".json", "{\"passwords\":[\"" + (1000 + i) + "\"]}");
            }
            put(out, "cards/ghost.json", "{\"passwords\":[]}");
            put(out, "cards/missing.json", "{}");
            put(out, "sets.json", "[\"plain\",\"tcg\",\"pre\",\"empty\",\"demo\"]");
            put(out, "sets/plain.json", """
                    {"name":{"en":"Plain Pack","ja":"虚构补充包"},"locales":{"jp":{"prefix":"PLN-JP","date":"2020-01-01"}},
                     "contents":[{"locales":["jp"],"cards":[
                       {"card":"c1","rarity":"common","qty":1},
                       {"card":"c2","rarity":"rare","qty":2},
                       {"card":"c3","rarity":"super"},
                       {"card":"c4","rarity":"ultra"},
                       {"card":"c5","rarity":"secret"},
                       {"card":"c6","rarity":"common"},
                       {"card":"missing","rarity":"rare"}
                     ]}]}
                    """);
            put(out, "sets/tcg.json", """
                    {"name":{"ja":"虚构九张"},"locales":{"jp":{"prefix":"TCG-JP"}},
                     "contents":[{"locales":["jp"],"distrobution":"dist-tcg","cards":[
                       {"card":"c1","rarity":"common"}]}]}
                    """);
            put(out, "sets/pre.json", """
                    {"name":{"ja":"虚构预组"},"locales":{"jp":{"prefix":"PRE-JP"}},
                     "contents":[{"locales":["jp"],"distrobution":"preconstructed","cards":[
                       {"card":"c1","rarity":"ultra"},{"card":"c2","rarity":"common"}]}]}
                    """);
            put(out, "sets/empty.json", """
                    {"name":{"ja":"没有卡密"},"locales":{"jp":{"prefix":"NOP-JP"}},
                     "contents":[{"locales":["jp"],"cards":[{"card":"ghost","rarity":"common"}]}]}
                    """);
            put(out, "sets/demo.json", """
                    {"name":{"ja":"演示撞名"},"locales":{"jp":{"prefix":"demo"}},
                     "contents":[{"locales":["jp"],"cards":[{"card":"c1","rarity":"common"}]}]}
                    """);
            put(out, "distributions.json", "[\"dist-tcg\"]");
            StringBuilder slots = new StringBuilder("[");
            slots.append("{\"type\":\"pool\",\"qty\":5,\"rarity\":[{\"rarities\":[\"common\"],\"chance\":1}]}");
            for (int i = 0; i < 4; i++) {
                slots.append(",{\"type\":\"pool\",\"qty\":1,\"rarity\":[{\"rarities\":[\"rare\"],\"chance\":1}]}");
            }
            slots.append(']');
            put(out, "distributions/dist-tcg.json",
                    "{\"name\":\"TCG Sample\",\"slots\":" + slots + "}");
            put(out, "sealedProducts.json", "[\"box-two\",\"box-one\"]");
            put(out, "sealedProducts/box-two.json", """
                    {"name":{"ja":"两种包"},"locales":{"jp":{}},
                     "contents":[{"locales":["jp"],"packs":[{"set":"plain","qty":2},{"set":"pre","qty":1}]}]}
                    """);
            put(out, "sealedProducts/box-one.json", """
                    {"name":{"ja":"一种包"},"locales":{"jp":{}},
                     "contents":[{"locales":["jp"],"packs":[{"set":"plain","qty":15}]}]}
                    """);
        }
    }

    private static JsonObject readJson(Path file) throws Exception {
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }

    private static void put(ZipOutputStream out, String name, String body) throws Exception {
        out.putNextEntry(new ZipEntry(name));
        out.write(body.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    private static Path repoRoot() {
        Path here = Path.of("").toAbsolutePath();
        if (Files.isRegularFile(here.resolve("tools/mksets.py"))) {
            return here;
        }
        return here.getParent();
    }

    private static void delete(Path root) throws Exception {
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
