package cn.xm1221.ygomc.common.sync;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 下载地址：{@code pins.json} 是默认值。{@code ygomc/urls.json} 优先于 Cloth 的
 * {@code config/ygomc-sync.json}。某一类列表非空时，{@code prepend} 为真就排在 pin 前面，
 * 否则整表替换。
 */
public final class SyncUrls {

    public enum Key {
        CARDS_CDB, STRINGS_CONF, SCRIPTS, SETS, PICS
    }

    private static final Gson GSON = new Gson();

    private SyncUrls() {
    }

    public static List<String> resolve(Path game, SyncSettings settings, Key key, List<String> pinned) {
        File file = load(game);
        boolean prepend = settings.urlPrepend;
        List<String> override = settings.list(key);
        if (file != null) {
            if (file.prepend != null) {
                prepend = file.prepend;
            }
            List<String> fromFile = file.list(key);
            if (fromFile != null) {
                override = fromFile;
            }
        }
        return merge(pinned, override, prepend);
    }

    static List<String> merge(List<String> pinned, List<String> override, boolean prepend) {
        List<String> extra = clean(override);
        List<String> base = pinned == null ? List.of() : pinned;
        if (extra.isEmpty()) {
            return List.copyOf(base);
        }
        if (!prepend) {
            return extra;
        }
        List<String> all = new ArrayList<>(extra);
        all.addAll(base);
        return all;
    }

    private static File load(Path game) {
        if (game == null) {
            return null;
        }
        Path file = CacheLayout.root(game).resolve("urls.json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return GSON.fromJson(reader, File.class);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static List<String> clean(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String url : raw) {
            if (url != null && !url.isBlank()) {
                out.add(url.trim());
            }
        }
        return out;
    }

    static final class File {
        Boolean prepend;
        @SerializedName(value = "cardsCdb", alternate = {"cards_cdb", "database"})
        List<String> cardsCdb;
        @SerializedName(value = "stringsConf", alternate = {"strings_conf"})
        List<String> stringsConf;
        List<String> scripts;
        List<String> sets;
        List<String> pics;

        List<String> list(Key key) {
            return switch (key) {
                case CARDS_CDB -> cardsCdb;
                case STRINGS_CONF -> stringsConf;
                case SCRIPTS -> scripts;
                case SETS -> sets;
                case PICS -> pics;
            };
        }
    }
}
