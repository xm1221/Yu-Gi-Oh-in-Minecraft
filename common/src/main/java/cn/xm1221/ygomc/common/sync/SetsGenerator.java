package cn.xm1221.ygomc.common.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 从 YGOJSON 生成卡包数据包。语义对齐 {@code tools/mksets.py}，专用服不依赖本机 Python。
 *
 * <p>TODO 计划第 7 节（P3）：{@code odds_origin}、按卡号前缀稳定染色。这一版颜色仍是
 * {@code #3C5AA0}，和离线脚本一致，方便同一份夹具对拍。未知卡密由开包路径过滤，不写进 JSON。
 */
public final class SetsGenerator {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final List<String> FALLBACK = List.of("secret", "ultra", "super", "rare", "common");
    private static final Map<String, String> RARITY = rarityMap();

    private SetsGenerator() {
    }

    public record Result(int cardSets, int boosters, int boxes, int fromSheet, int adapted, int decks,
                          int defaulted, int noPassword, List<String> unknownRarities) {
    }

    public static Result generate(Path zip, Path outDir) throws IOException {
        List<String> unknown = new ArrayList<>();
        int setsWritten = 0;
        int boostersWritten = 0;
        int boxesWritten = 0;
        int fromSheet = 0;
        int adapted = 0;
        int decks = 0;
        int defaulted = 0;
        int noPassword = 0;
        Map<String, String> boosterIds = new LinkedHashMap<>();
        Path dataRoot = outDir.resolve("data").resolve("ygomc").resolve("ygomc");
        try (ZipFile archive = new ZipFile(zip.toFile())) {
            Map<String, Integer> codes = passwords(archive);
            String setList = find(archive, "/sets.json", "sets.json");
            String setRoot = setList.substring(0, setList.length() - "sets.json".length());
            String distList = findOptional(archive, "/distributions.json", "distributions.json");
            String distRoot = distList == null ? null
                    : distList.substring(0, distList.length() - "distributions.json".length());
            Set<String> used = new TreeSet<>();
            used.add("demo");
            for (String setId : stringList(read(archive, setList))) {
                JsonObject doc = read(archive, setRoot + "sets/" + setId + ".json").getAsJsonObject();
                JsonObject locales = object(doc, "locales");
                if (!locales.has("jp")) {
                    continue;
                }
                JsonObject locale = locales.getAsJsonObject("jp");
                String rawId = text(locale, "prefix");
                if (rawId.isBlank()) {
                    rawId = nested(doc, "name", "en");
                }
                if (rawId.isBlank()) {
                    rawId = setId;
                }
                String fileId = slug(rawId);
                String base = fileId;
                int n = 2;
                while (used.contains(fileId)) {
                    fileId = base + "_" + n;
                    n++;
                }
                used.add(fileId);
                Map<String, Map<Integer, Integer>> buckets = new LinkedHashMap<>();
                for (String rarity : FALLBACK) {
                    buckets.put(rarity, new TreeMap<>());
                }
                for (JsonElement blockEl : array(doc, "contents")) {
                    JsonObject block = blockEl.getAsJsonObject();
                    JsonArray blockLocales = array(block, "locales");
                    if (!blockLocales.isEmpty() && !contains(blockLocales, "jp")) {
                        continue;
                    }
                    for (JsonElement cardEl : array(block, "cards")) {
                        JsonObject card = cardEl.getAsJsonObject();
                        String cardId = text(card, "card");
                        Integer password = codes.get(cardId);
                        if (password == null) {
                            noPassword++;
                            continue;
                        }
                        String rarity = mapRarity(textOr(card, "rarity", "common"), unknown);
                        int qty = intOr(card, "qty", 1);
                        buckets.get(rarity).merge(password, qty, Integer::sum);
                    }
                }
                JsonObject cards = new JsonObject();
                boolean any = false;
                for (String rarity : FALLBACK) {
                    Map<Integer, Integer> pool = buckets.get(rarity);
                    if (pool.isEmpty()) {
                        continue;
                    }
                    any = true;
                    JsonArray ids = new JsonArray();
                    for (Integer password : pool.keySet()) {
                        ids.add(password);
                    }
                    cards.add(rarity, ids);
                }
                if (!any) {
                    continue;
                }
                String name = nested(doc, "name", "ja");
                if (name.isBlank()) {
                    name = nested(doc, "name", "en");
                }
                if (name.isBlank()) {
                    name = fileId;
                }
                JsonObject body = new JsonObject();
                JsonObject nameText = new JsonObject();
                nameText.addProperty("text", name);
                body.add("name", nameText);
                body.addProperty("code", text(locale, "prefix").isBlank() ? fileId.toUpperCase(Locale.ROOT) : text(locale, "prefix"));
                body.add("cards", cards);
                if (!text(locale, "date").isBlank()) {
                    body.addProperty("release", text(locale, "date"));
                }
                JsonArray uneven = new JsonArray();
                for (String rarity : FALLBACK) {
                    for (var entry : buckets.get(rarity).entrySet()) {
                        if (entry.getValue() != 1) {
                            JsonObject print = new JsonObject();
                            print.addProperty("code", entry.getKey());
                            print.addProperty("rarity", rarity);
                            print.addProperty("weight", entry.getValue());
                            uneven.add(print);
                        }
                    }
                }
                if (!uneven.isEmpty()) {
                    body.add("prints", uneven);
                }
                write(dataRoot.resolve("card_set").resolve(fileId + ".json"), body);
                setsWritten++;

                String distId = null;
                for (JsonElement blockEl : array(doc, "contents")) {
                    JsonObject block = blockEl.getAsJsonObject();
                    JsonArray blockLocales = array(block, "locales");
                    if (!blockLocales.isEmpty() && !contains(blockLocales, "jp")) {
                        continue;
                    }
                    if (block.has("distrobution") && !block.get("distrobution").isJsonNull()) {
                        distId = block.get("distrobution").getAsString();
                    }
                }
                JsonArray pulls = null;
                if ("preconstructed".equals(distId)) {
                    pulls = wholeSetPulls(cards);
                    if (pulls != null) {
                        decks++;
                    }
                } else if (distId != null && distRoot != null) {
                    String distPath = distRoot + "distributions/" + distId + ".json";
                    if (archive.getEntry(distPath) != null) {
                        JsonObject dist = read(archive, distPath).getAsJsonObject();
                        List<JsonObject> slots = new ArrayList<>();
                        for (JsonElement slotEl : array(dist, "slots")) {
                            JsonObject slot = slotEl.getAsJsonObject();
                            if (slot.has("type") && !slot.get("type").isJsonNull()
                                    && !"pool".equals(slot.get("type").getAsString())) {
                                continue;
                            }
                            slots.add(slotJson(slot, unknown));
                        }
                        int before = 0;
                        for (JsonObject slot : slots) {
                            before += slot.get("count").getAsInt();
                        }
                        slots = adaptTcg(text(dist, "name"), slots);
                        if (!slots.isEmpty()) {
                            pulls = new JsonArray();
                            JsonObject pull = new JsonObject();
                            pull.addProperty("id", "pack");
                            pull.addProperty("weight", 1);
                            JsonArray slotArray = new JsonArray();
                            int after = 0;
                            for (JsonObject slot : slots) {
                                slotArray.add(slot);
                                after += slot.get("count").getAsInt();
                            }
                            pull.add("slots", slotArray);
                            pulls.add(pull);
                            fromSheet++;
                            if (after != before) {
                                adapted++;
                            }
                        }
                    }
                }
                if (pulls == null) {
                    pulls = defaultPulls(cards);
                    if (pulls != null) {
                        defaulted++;
                    }
                }
                if (pulls == null) {
                    continue;
                }
                JsonObject booster = new JsonObject();
                booster.addProperty("set", "ygomc:" + fileId);
                JsonObject display = new JsonObject();
                display.add("name", nameText);
                display.addProperty("color", "#3C5AA0");
                display.addProperty("rarity", "common");
                booster.add("display", display);
                booster.add("pulls", pulls);
                booster.addProperty("duplicates", "avoid_in_pack");
                JsonArray fallback = new JsonArray();
                for (String rarity : FALLBACK) {
                    fallback.add(rarity);
                }
                booster.add("fallback", fallback);
                write(dataRoot.resolve("booster").resolve(fileId + ".json"), booster);
                boosterIds.put(setId, fileId);
                boostersWritten++;
            }

            String productList = findOptional(archive, "/sealedProducts.json", "sealedProducts.json");
            if (productList != null) {
                String productRoot = productList.substring(0, productList.length() - "sealedProducts.json".length());
                for (String productId : stringList(read(archive, productList))) {
                    JsonObject doc = read(archive, productRoot + "sealedProducts/" + productId + ".json").getAsJsonObject();
                    JsonObject locales = object(doc, "locales");
                    if (!locales.has("jp")) {
                        continue;
                    }
                    JsonArray packs = new JsonArray();
                    for (JsonElement blockEl : array(doc, "contents")) {
                        JsonObject block = blockEl.getAsJsonObject();
                        JsonArray blockLocales = array(block, "locales");
                        if (!blockLocales.isEmpty() && !contains(blockLocales, "jp")) {
                            continue;
                        }
                        for (JsonElement packEl : array(block, "packs")) {
                            packs.add(packEl);
                        }
                    }
                    if (packs.size() != 1) {
                        continue;
                    }
                    JsonObject pack = packs.get(0).getAsJsonObject();
                    String boosterId = boosterIds.get(text(pack, "set"));
                    if (boosterId == null) {
                        continue;
                    }
                    int qty = intOr(pack, "qty", 0);
                    if (qty <= 0) {
                        continue;
                    }
                    String name = nested(doc, "name", "ja");
                    if (name.isBlank()) {
                        name = nested(doc, "name", "en");
                    }
                    if (name.isBlank()) {
                        name = boosterId;
                    }
                    JsonObject box = new JsonObject();
                    box.addProperty("booster", "ygomc:" + boosterId);
                    box.addProperty("packs", qty);
                    box.addProperty("mode", "independent");
                    JsonObject display = new JsonObject();
                    JsonObject nameText = new JsonObject();
                    nameText.addProperty("text", name);
                    display.add("name", nameText);
                    display.addProperty("color", "#3C5AA0");
                    display.addProperty("rarity", "uncommon");
                    box.add("display", display);
                    write(dataRoot.resolve("booster_box").resolve(boosterId + ".json"), box);
                    boxesWritten++;
                }
            }
        }
        JsonObject meta = new JsonObject();
        JsonObject pack = new JsonObject();
        pack.addProperty("pack_format", 48);
        pack.addProperty("description", "ygomc sets");
        meta.add("pack", pack);
        write(outDir.resolve("pack.mcmeta"), meta);
        return new Result(setsWritten, boostersWritten, boxesWritten, fromSheet, adapted, decks, defaulted,
                noPassword, List.copyOf(new TreeSet<>(unknown)));
    }

    private static Map<String, Integer> passwords(ZipFile archive) throws IOException {
        String listing = find(archive, "/cards.json", "cards.json");
        String root = listing.substring(0, listing.length() - "cards.json".length());
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String cardId : stringList(read(archive, listing))) {
            JsonObject data = read(archive, root + "cards/" + cardId + ".json").getAsJsonObject();
            for (JsonElement password : array(data, "passwords")) {
                String text = password.getAsString();
                if (text.chars().allMatch(Character::isDigit) && !text.isEmpty()) {
                    out.put(cardId, Integer.parseInt(text));
                    break;
                }
            }
        }
        return out;
    }

    private static JsonObject slotJson(JsonObject slot, List<String> unknown) {
        int count = slot.has("qty") ? intOr(slot, "qty", 1) : intOr(slot, "count", 1);
        Map<String, Integer> table = weightsOf(array(slot, "rarity"), unknown);
        JsonObject out = new JsonObject();
        out.addProperty("count", Math.max(1, count));
        if (table.size() == 1 && table.values().iterator().next() == 1) {
            out.addProperty("rarity", table.keySet().iterator().next());
        } else {
            JsonObject rarity = new JsonObject();
            for (var entry : table.entrySet()) {
                rarity.addProperty(entry.getKey(), entry.getValue());
            }
            out.add("rarity", rarity);
        }
        return out;
    }

    private static Map<String, Integer> weightsOf(JsonArray entries, List<String> unknown) {
        List<int[]> parsed = new ArrayList<>();
        List<JsonArray> rarities = new ArrayList<>();
        if (entries.isEmpty()) {
            return Map.of("common", 1);
        }
        long scale = 1;
        for (JsonElement entryEl : entries) {
            JsonObject entry = entryEl.getAsJsonObject();
            int chance = intOr(entry, "chance", 1);
            if (chance <= 0) {
                chance = 1;
            }
            JsonArray names = array(entry, "rarities");
            if (names.isEmpty()) {
                names = new JsonArray();
                names.add("common");
            }
            parsed.add(new int[]{chance});
            rarities.add(names);
            scale = lcm(scale, chance);
        }
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (int i = 0; i < parsed.size(); i++) {
            int chance = parsed.get(i)[0];
            JsonArray names = rarities.get(i);
            int share = (int) Math.max(1, (scale / chance) / names.size());
            for (JsonElement rarity : names) {
                String key = mapRarity(rarity.getAsString(), unknown);
                totals.merge(key, share, Integer::sum);
            }
        }
        return totals;
    }

    private static List<JsonObject> adaptTcg(String name, List<JsonObject> slots) {
        if (name == null || !name.startsWith("TCG")) {
            return slots;
        }
        List<JsonObject> copy = new ArrayList<>();
        int total = 0;
        for (JsonObject slot : slots) {
            JsonObject next = slot.deepCopy();
            copy.add(next);
            total += next.get("count").getAsInt();
        }
        int extra = total - 5;
        if (extra <= 0) {
            return copy;
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < copy.size(); i++) {
            order.add(i);
        }
        order.sort((a, b) -> Integer.compare(copy.get(b).get("count").getAsInt(), copy.get(a).get("count").getAsInt()));
        for (int index : order) {
            int room = copy.get(index).get("count").getAsInt() - 1;
            if (room <= 0) {
                continue;
            }
            int take = Math.min(extra, room);
            copy.get(index).addProperty("count", copy.get(index).get("count").getAsInt() - take);
            extra -= take;
            if (extra <= 0) {
                break;
            }
        }
        copy.removeIf(slot -> slot.get("count").getAsInt() <= 0);
        return copy;
    }

    private static JsonArray wholeSetPulls(JsonObject cards) {
        JsonArray slots = new JsonArray();
        for (String rarity : FALLBACK) {
            if (!cards.has(rarity)) {
                continue;
            }
            int count = cards.getAsJsonArray(rarity).size();
            if (count > 0) {
                JsonObject slot = new JsonObject();
                slot.addProperty("count", count);
                slot.addProperty("rarity", rarity);
                slots.add(slot);
            }
        }
        if (slots.isEmpty()) {
            return null;
        }
        JsonArray pulls = new JsonArray();
        JsonObject pull = new JsonObject();
        pull.addProperty("id", "deck");
        pull.addProperty("weight", 1);
        pull.add("slots", slots);
        pulls.add(pull);
        return pulls;
    }

    private static JsonArray defaultPulls(JsonObject cards) {
        int total = 0;
        Map<String, Integer> weights = new LinkedHashMap<>();
        for (String rarity : FALLBACK) {
            int count = cards.has(rarity) ? cards.getAsJsonArray(rarity).size() : 0;
            total += count;
            if (count > 0) {
                weights.put(rarity, count);
            }
        }
        if (total <= 5) {
            return wholeSetPulls(cards);
        }
        JsonObject slot = new JsonObject();
        slot.addProperty("count", 5);
        if (weights.size() == 1) {
            slot.addProperty("rarity", weights.keySet().iterator().next());
        } else {
            JsonObject rarity = new JsonObject();
            for (var entry : weights.entrySet()) {
                rarity.addProperty(entry.getKey(), entry.getValue());
            }
            slot.add("rarity", rarity);
        }
        JsonArray slots = new JsonArray();
        slots.add(slot);
        JsonArray pulls = new JsonArray();
        JsonObject pull = new JsonObject();
        pull.addProperty("id", "pack");
        pull.addProperty("weight", 1);
        pull.add("slots", slots);
        pulls.add(pull);
        return pulls;
    }

    private static String mapRarity(String name, List<String> unknown) {
        String mapped = RARITY.get(name);
        if (mapped == null) {
            unknown.add(name);
            return "common";
        }
        return mapped;
    }

    private static String slug(String text) {
        if (text == null) {
            return "set";
        }
        String lowered = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        lowered = lowered.replaceAll("^_+|_+$", "");
        if (lowered.isBlank()) {
            return "set";
        }
        return lowered.length() > 48 ? lowered.substring(0, 48) : lowered;
    }

    private static void write(Path path, JsonElement json) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, GSON.toJson(json) + "\n", StandardCharsets.UTF_8);
    }

    private static JsonElement read(ZipFile archive, String name) throws IOException {
        ZipEntry entry = archive.getEntry(name);
        if (entry == null) {
            throw new IOException("zip 里没有 " + name);
        }
        try (Reader reader = new InputStreamReader(archive.getInputStream(entry), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader);
        }
    }

    private static String find(ZipFile archive, String... suffixes) throws IOException {
        String found = findOptional(archive, suffixes);
        if (found == null) {
            throw new IOException("zip 里没有 " + String.join(" 或 ", suffixes));
        }
        return found;
    }

    private static String findOptional(ZipFile archive, String... suffixes) {
        var entries = archive.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (name.endsWith("/")) {
                continue;
            }
            for (String suffix : suffixes) {
                if (name.endsWith(suffix)) {
                    return name;
                }
            }
        }
        return null;
    }

    private static List<String> stringList(JsonElement element) {
        List<String> out = new ArrayList<>();
        if (element != null && element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                out.add(item.getAsString());
            }
        }
        return out;
    }

    private static JsonObject object(JsonObject obj, String key) {
        if (obj.has(key) && obj.get(key).isJsonObject()) {
            return obj.getAsJsonObject(key);
        }
        return new JsonObject();
    }

    private static JsonArray array(JsonObject obj, String key) {
        if (obj.has(key) && obj.get(key).isJsonArray()) {
            return obj.getAsJsonArray(key);
        }
        return new JsonArray();
    }

    private static boolean contains(JsonArray array, String value) {
        for (JsonElement element : array) {
            if (value.equals(element.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static String text(JsonObject obj, String key) {
        if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
            return obj.get(key).getAsString();
        }
        return "";
    }

    private static String textOr(JsonObject obj, String key, String fallback) {
        String value = text(obj, key);
        return value.isBlank() ? fallback : value;
    }

    private static String nested(JsonObject obj, String objectKey, String key) {
        JsonObject child = object(obj, objectKey);
        return text(child, key);
    }

    private static int intOr(JsonObject obj, String key, int fallback) {
        if (!obj.has(key) || obj.get(key).isJsonNull() || !obj.get(key).isJsonPrimitive()) {
            return fallback;
        }
        try {
            return obj.get(key).getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static long lcm(long a, long b) {
        return a / gcd(a, b) * b;
    }

    private static long gcd(long a, long b) {
        while (b != 0) {
            long t = a % b;
            a = b;
            b = t;
        }
        return Math.abs(a);
    }

    private static Map<String, String> rarityMap() {
        Map<String, String> map = new LinkedHashMap<>();
        String[][] rows = {
                {"common", "common"}, {"shortprint", "common"}, {"kccommon", "common"},
                {"rare", "rare"}, {"rare-red", "rare"}, {"rare-copper", "rare"}, {"rare-green", "rare"},
                {"rare-wedgewood", "rare"}, {"rare-blue", "rare"}, {"rare-purple", "rare"},
                {"kcrare", "rare"}, {"parallel", "rare"}, {"commonparallel", "rare"}, {"rareparallel", "rare"},
                {"super", "super"}, {"superparallel", "super"}, {"kcsuper", "super"}, {"milleniumsuper", "super"},
                {"ultra", "ultra"}, {"ultra-green", "ultra"}, {"ultra-blue", "ultra"}, {"ultra-purple", "ultra"},
                {"ultimate", "ultra"}, {"ultraparallel", "ultra"}, {"kcultra", "ultra"}, {"milleniumultra", "ultra"},
                {"millenium", "ultra"}, {"gold", "ultra"}, {"premiumgold", "ultra"}, {"platinum", "ultra"},
                {"starfoil", "ultra"}, {"mosaic", "ultra"}, {"shatterfoil", "ultra"}, {"collectors", "ultra"},
                {"secret", "secret"}, {"secret-red", "secret"}, {"secret-blue", "secret"}, {"ultrasecret", "secret"},
                {"prismaticsecret", "secret"}, {"ghost", "secret"}, {"10000secret", "secret"}, {"20thsecret", "secret"},
                {"25thsecret", "secret"}, {"extrasecret", "secret"}, {"extrasecretparallel", "secret"},
                {"goldghost", "secret"}, {"goldsecret", "secret"}, {"ghostparallel", "secret"},
                {"platinumsecret", "secret"}, {"secretparallel", "secret"}, {"starlight", "secret"},
                {"pharaohs", "secret"}, {"kcsecret", "secret"}, {"milleniumsecret", "secret"}, {"milleniumgold", "secret"},
                {"dtpc", "common"}, {"dtpsp", "super"}, {"dtrpr", "rare"}, {"dtspr", "super"}, {"dtupr", "ultra"},
                {"dtscpr", "secret"},
        };
        for (String[] row : rows) {
            map.put(row[0], row[1]);
        }
        return Map.copyOf(map);
    }
}
