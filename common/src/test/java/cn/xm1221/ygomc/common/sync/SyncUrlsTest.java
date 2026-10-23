package cn.xm1221.ygomc.common.sync;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SyncUrlsTest {

    @Test
    void emptyOverrideKeepsPins() {
        assertEquals(List.of("https://pin"), SyncUrls.merge(List.of("https://pin"), List.of(), false));
        assertEquals(List.of("https://pin"), SyncUrls.merge(List.of("https://pin"), List.of("  "), true));
    }

    @Test
    void replaceAndPrepend() {
        assertEquals(List.of("https://override"),
                SyncUrls.merge(List.of("https://pin"), List.of("https://override"), false));
        assertEquals(List.of("https://extra", "https://pin"),
                SyncUrls.merge(List.of("https://pin"), List.of("https://extra"), true));
    }

    @Test
    void urlsFileBeatsClothAndHonorsAliases() throws Exception {
        Path game = Files.createTempDirectory("ygomc-urls");
        Files.createDirectories(CacheLayout.root(game));
        Files.writeString(CacheLayout.root(game).resolve("urls.json"),
                "{\"prepend\":true,\"cards_cdb\":[\"https://file\"],\"strings_conf\":[\"\"],\"pics\":[\"https://pic/{code}\"]}\n");
        SyncSettings settings = SyncSettings.defaults();
        settings.urlPrepend = false;
        settings.cardsCdbUrls = List.of("https://cloth");
        settings.stringsConfUrls = List.of("https://cloth-conf");
        assertEquals(List.of("https://file", "https://pin"),
                SyncUrls.resolve(game, settings, SyncUrls.Key.CARDS_CDB, List.of("https://pin")));
        assertEquals(List.of("https://pin-conf"),
                SyncUrls.resolve(game, settings, SyncUrls.Key.STRINGS_CONF, List.of("https://pin-conf")));
        assertEquals(List.of("https://pic/{code}", "https://pin-pic/{code}"),
                SyncUrls.resolve(game, settings, SyncUrls.Key.PICS, List.of("https://pin-pic/{code}")));
    }
}
