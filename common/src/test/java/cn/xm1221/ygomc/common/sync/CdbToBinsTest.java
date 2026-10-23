package cn.xm1221.ygomc.common.sync;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CdbToBinsTest {

    @Test
    void matchesPythonOnFixture() throws Exception {
        Path fixture = Path.of("src/test/resources/sync/fixture.cdb");
        Path conf = Path.of("src/test/resources/sync/strings.conf");
        assertTrue(Files.isRegularFile(fixture), fixture.toString());
        Path javaOut = Files.createTempDirectory("ygomc-java-bins");
        Path pyOut = Files.createTempDirectory("ygomc-py-bins");
        try {
            CdbToBins.Result result = CdbToBins.convert(fixture, conf, javaOut);
            assertTrue(result.cards() == 3);
            Process process = new ProcessBuilder("python3", repoRoot().resolve("tools/mkdatapack.py").toString(),
                    fixture.toAbsolutePath().toString(), pyOut.toAbsolutePath().toString(),
                    conf.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();
            String log = new String(process.getInputStream().readAllBytes());
            int code = process.waitFor();
            assertTrue(code == 0, log);
            for (String name : new String[]{"cards.bin", "texts.bin", "strings.bin"}) {
                assertArrayEquals(Files.readAllBytes(pyOut.resolve(name)), Files.readAllBytes(javaOut.resolve(name)), name);
            }
        } finally {
            delete(javaOut);
            delete(pyOut);
        }
    }

    @Test
    void refusesMismatchedIdsAndKeepsOldBin() throws Exception {
        Path dir = Files.createTempDirectory("ygomc-bad-cdb");
        Path cdb = dir.resolve("bad.cdb");
        Path conf = Path.of("src/test/resources/sync/strings.conf");
        Path out = dir.resolve("out");
        Files.createDirectories(out);
        Files.writeString(out.resolve("strings.bin"), "keep");
        Class.forName("org.sqlite.JDBC");
        try (var con = java.sql.DriverManager.getConnection("jdbc:sqlite:" + cdb.toAbsolutePath())) {
            try (var st = con.createStatement()) {
                st.execute("create table datas (id integer, ot integer, alias integer, setcode integer, type integer, atk integer, def integer, level integer, race integer, attribute integer, category integer)");
                st.execute("create table texts (id integer, name text, desc text, str1 text, str2 text, str3 text, str4 text, str5 text, str6 text, str7 text, str8 text, str9 text, str10 text, str11 text, str12 text, str13 text, str14 text, str15 text, str16 text)");
                st.execute("insert into datas values (1,0,0,0,1,0,0,1,1,1,0)");
                st.execute("insert into texts values (2,'x','y','','','','','','','','','','','','','','','','')");
            }
        }
        assertThrows(java.io.IOException.class, () -> CdbToBins.convert(cdb, conf, out));
        assertArrayEquals("keep".getBytes(), Files.readAllBytes(out.resolve("strings.bin")));
        delete(dir);
    }

    @Test
    void stringsConfKeepsFirstSystemAndCutsSetnameTab() throws Exception {
        StringsConf parsed = StringsConf.parse(Path.of("src/test/resources/sync/strings.conf"));
        assertTrue(parsed.system.get(30L).equals("重复前"));
        assertTrue(parsed.counter.get(1L).equals("后者覆盖"));
        assertTrue(parsed.setname.get(0x10L).equals("虚构系列"));
        assertTrue(parsed.ignored.containsKey("!unknown"));
    }

    private static Path repoRoot() {
        Path here = Path.of("").toAbsolutePath();
        if (Files.isRegularFile(here.resolve("tools/mkdatapack.py"))) {
            return here;
        }
        return here.getParent();
    }

    private static void delete(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
