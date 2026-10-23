package cn.xm1221.ygomc.common.sync;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * jar 里的 {@code assets/ygomc/sync/pins.json}。只有 URL、commit 和哈希，没有卡文。
 */
public final class SyncPins {

    public static final String RESOURCE = "assets/ygomc/sync/pins.json";

    private static final Gson GSON = new Gson();

    public int schema;
    public Database database;
    public Scripts scripts;
    public Sets sets;
    public Pics pics;

    public static final class Database {
        public String sha;
        public List<FilePin> files;
    }

    public static final class FilePin {
        public String id;
        public List<String> urls;
        public String sha256;
    }

    public static final class Scripts {
        public String sha;
        public List<String> urls;
    }

    public static final class Sets {
        public List<String> urls;
        public String sha256;
    }

    public static final class Pics {
        public List<String> urls;
    }

    public static SyncPins loadDefault() throws IOException {
        ClassLoader loader = SyncPins.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("找不到 " + RESOURCE);
            }
            return load(in);
        }
    }

    public static SyncPins load(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(reader);
        }
    }

    public static SyncPins load(InputStream in) throws IOException {
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return parse(reader);
        }
    }

    private static SyncPins parse(Reader reader) throws IOException {
        SyncPins pins = GSON.fromJson(reader, SyncPins.class);
        if (pins == null) {
            throw new IOException("pins.json 是空的");
        }
        pins.validate();
        return pins;
    }

    public void validate() throws IOException {
        if (schema != 1) {
            throw new IOException("pins schema 必须是 1，实际 " + schema);
        }
        if (database == null || !isSha(database.sha)) {
            throw new IOException("database.sha 必须是 40 位十六进制");
        }
        requireFile("cards.cdb");
        requireFile("strings.conf");
        if (scripts == null || !isSha(scripts.sha) || scripts.urls == null || scripts.urls.isEmpty()) {
            throw new IOException("scripts pin 不完整");
        }
        if (sets == null || sets.urls == null || sets.urls.isEmpty() || !isSha256(sets.sha256)) {
            throw new IOException("sets.sha256 必须是 64 位十六进制（v1 标签会动）");
        }
        if (pics == null || pics.urls == null || pics.urls.isEmpty()) {
            throw new IOException("pics.urls 为空");
        }
    }

    public FilePin requireFile(String id) throws IOException {
        if (database.files == null) {
            throw new IOException("database.files 为空");
        }
        for (FilePin file : database.files) {
            if (file != null && id.equals(file.id)) {
                if (file.urls == null || file.urls.isEmpty()) {
                    throw new IOException(id + " 没有 URL");
                }
                if (file.sha256 != null && !file.sha256.isBlank() && !isSha256(file.sha256)) {
                    throw new IOException(id + " 的 sha256 不是 64 位十六进制");
                }
                return file;
            }
        }
        throw new IOException("pins 里没有 " + id);
    }

    static boolean isSha(String value) {
        return value != null && value.matches("[0-9a-fA-F]{40}");
    }

    static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }
}
