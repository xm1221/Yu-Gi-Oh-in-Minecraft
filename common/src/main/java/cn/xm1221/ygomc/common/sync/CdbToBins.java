package cn.xm1221.ygomc.common.sync;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 把 {@code cards.cdb} 收成 {@code cards.bin}、{@code texts.bin}、{@code strings.bin}。
 * 字节布局对齐 {@code tools/mkdatapack.py}，对局仍然只读 bin。
 */
public final class CdbToBins {

    public static final int TYPE_LINK = 0x4000000;
    private static final int DESC_COUNT = 16;

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("缺少 sqlite-jdbc", e);
        }
    }

    private CdbToBins() {
    }

    public record Result(int cards, int emptyDesc, int emptyStr, int[] stringCounts) {
    }

    public static Result convert(Path cdb, Path stringsConf, Path outDir) throws IOException {
        List<Integer> dataIds = new ArrayList<>();
        List<Integer> textIds = new ArrayList<>();
        Connection ids = open(cdb);
        try {
            readIds(ids, "select id from datas order by id", dataIds);
            readIds(ids, "select id from texts order by id", textIds);
        } finally {
            close(ids);
        }
        if (!dataIds.equals(textIds)) {
            throw new IOException("datas 与 texts 的 id 集合不一致：仅 datas "
                    + head(diff(dataIds, textIds)) + "，仅 texts " + head(diff(textIds, dataIds)));
        }
        if (dataIds.isEmpty()) {
            throw new IOException("cards.cdb 的 datas 表没有数据");
        }
        Files.createDirectories(outDir);
        Path cardsPartial = outDir.resolve("cards.bin.partial");
        Path textsPartial = outDir.resolve("texts.bin.partial");
        Path stringsPartial = outDir.resolve("strings.bin.partial");
        int cards;
        int emptyDesc;
        int emptyStr;
        int[] counts;
        Connection con = open(cdb);
        try {
            cards = writeCards(con, cardsPartial);
            int[] textStats = writeTexts(con, textsPartial);
            emptyDesc = textStats[0];
            emptyStr = textStats[1];
        } finally {
            close(con);
        }
        counts = writeStrings(stringsConf, stringsPartial);
        verifyMagic(cardsPartial, "YGOMCCD1");
        verifyMagic(textsPartial, "YGOMCTX2");
        verifyMagic(stringsPartial, "YGOMCST1");
        verifyAscending(cardsPartial, 20, 84);
        replace(cardsPartial, outDir.resolve("cards.bin"));
        replace(textsPartial, outDir.resolve("texts.bin"));
        replace(stringsPartial, outDir.resolve("strings.bin"));
        return new Result(cards, emptyDesc, emptyStr, counts);
    }

    private static void close(Connection con) throws IOException {
        try {
            con.close();
        } catch (SQLException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private static Connection open(Path cdb) throws IOException {
        String url = "jdbc:sqlite:" + cdb.toAbsolutePath().toUri() + "?mode=ro";
        try {
            return DriverManager.getConnection(url);
        } catch (Exception e) {
            throw new IOException("打不开 cards.cdb：" + e.getMessage(), e);
        }
    }

    private static void readIds(Connection con, String sql, List<Integer> ids) throws IOException {
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                ids.add(rs.getInt(1));
            }
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private static int writeCards(Connection con, Path out) throws IOException {
        String sql = "select id,ot,alias,setcode,type,atk,def,level,race,attribute,category "
                + "from datas order by id";
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(sql);
             var channel = Files.newOutputStream(out)) {
            List<byte[]> rows = new ArrayList<>();
            int previous = Integer.MIN_VALUE;
            boolean first = true;
            while (rs.next()) {
                int id = rs.getInt("id");
                if (!first && Integer.compareUnsigned(id, previous) <= 0) {
                    throw new IOException("datas.id 不是升序：" + previous + " → " + id);
                }
                first = false;
                previous = id;
                rows.add(cardRecord(id, rs.getInt("alias"), rs.getLong("setcode"), rs.getInt("type"),
                        rs.getInt("atk"), rs.getInt("def"), rs.getInt("level"),
                        rs.getInt("race"), rs.getInt("attribute")));
            }
            if (rows.isEmpty()) {
                throw new IOException("cards.cdb 的 datas 表没有数据");
            }
            ByteBuffer header = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
            header.put("YGOMCCD1".getBytes(StandardCharsets.US_ASCII));
            header.putInt(1);
            header.putInt(rows.size());
            header.putInt(84);
            channel.write(header.array());
            for (byte[] row : rows) {
                channel.write(row);
            }
            return rows.size();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    static byte[] cardRecord(int id, int alias, long setcode, int type, int atk, int def, int level,
                              int race, int attribute) {
        ByteBuffer buf = ByteBuffer.allocate(84).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(id);
        buf.putInt(id);
        buf.putInt(alias);
        writeSetcode(buf, setcode);
        buf.putInt(type);
        buf.putInt(level & 0xFF);
        buf.putInt(attribute);
        buf.putInt(race);
        buf.putInt(atk);
        boolean link = (type & TYPE_LINK) != 0;
        buf.putInt(link ? 0 : def);
        buf.putInt((level >>> 24) & 0xFF);
        buf.putInt((level >>> 16) & 0xFF);
        buf.putInt(link ? def : 0);
        buf.putInt(0);
        return buf.array();
    }

    static void writeSetcode(ByteBuffer buf, long setcode) {
        short[] slots = new short[16];
        int i = 0;
        long v = setcode;
        while (v != 0 && i < 16) {
            if ((v & 0xFFFFL) != 0) {
                slots[i++] = (short) (v & 0xFFFFL);
            }
            v >>>= 16;
        }
        for (short slot : slots) {
            buf.putShort(slot);
        }
    }

    private static int[] writeTexts(Connection con, Path out) throws IOException {
        StringBuilder cols = new StringBuilder("id, name, desc");
        for (int i = 1; i <= DESC_COUNT; i++) {
            cols.append(", str").append(i);
        }
        String sql = "select " + cols + " from texts order by id";
        try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            byte[] index = new byte[0];
            byte[] pool = new byte[0];
            int count = 0;
            int emptyDesc = 0;
            int emptyStr = 0;
            var indexBuf = new java.io.ByteArrayOutputStream();
            var poolBuf = new java.io.ByteArrayOutputStream();
            while (rs.next()) {
                int id = rs.getInt(1);
                String[] fields = new String[2 + DESC_COUNT];
                fields[0] = rs.getString(2) == null ? "" : rs.getString(2);
                fields[1] = rs.getString(3) == null ? "" : rs.getString(3);
                boolean anyStr = false;
                for (int i = 1; i <= DESC_COUNT; i++) {
                    String value = rs.getString(3 + i);
                    fields[1 + i] = value == null ? "" : value;
                    if (!fields[1 + i].isEmpty()) {
                        anyStr = true;
                    }
                }
                if (fields[1].trim().isEmpty()) {
                    emptyDesc++;
                }
                if (!anyStr) {
                    emptyStr++;
                }
                ByteBuffer entry = ByteBuffer.allocate(4 + (2 + DESC_COUNT) * 8).order(ByteOrder.LITTLE_ENDIAN);
                entry.putInt(id);
                for (String field : fields) {
                    byte[] bytes = field.replace("\r\n", "\n").replace("\r", "\n")
                            .getBytes(StandardCharsets.UTF_8);
                    entry.putInt(poolBuf.size());
                    entry.putInt(bytes.length);
                    poolBuf.write(bytes);
                }
                indexBuf.write(entry.array());
                count++;
            }
            if (count == 0) {
                throw new IOException("cards.cdb 的 texts 表没有数据");
            }
            index = indexBuf.toByteArray();
            pool = poolBuf.toByteArray();
            if (index.length != count * 148) {
                throw new IOException("texts 索引项不是 148 字节");
            }
            int header = 28;
            int poolOff = header + index.length;
            ByteBuffer head = ByteBuffer.allocate(header).order(ByteOrder.LITTLE_ENDIAN);
            head.put("YGOMCTX2".getBytes(StandardCharsets.US_ASCII));
            head.putInt(2);
            head.putInt(count);
            head.putInt(header);
            head.putInt(poolOff);
            head.putInt(pool.length);
            try (var channel = Files.newOutputStream(out)) {
                channel.write(head.array());
                channel.write(index);
                channel.write(pool);
            }
            return new int[]{emptyDesc, emptyStr};
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private static int[] writeStrings(Path conf, Path out) throws IOException {
        StringsConf parsed = StringsConf.parse(conf);
        String[] order = {"system", "counter", "victory", "setname"};
        Map<String, Map<Long, String>> sections = Map.of(
                "system", parsed.system,
                "counter", parsed.counter,
                "victory", parsed.victory,
                "setname", parsed.setname);
        var index = new java.io.ByteArrayOutputStream();
        var pool = new java.io.ByteArrayOutputStream();
        int[] counts = new int[4];
        for (int s = 0; s < order.length; s++) {
            Map<Long, String> section = sections.get(order[s]);
            counts[s] = section.size();
            for (var entry : section.entrySet()) {
                byte[] bytes = entry.getValue().getBytes(StandardCharsets.UTF_8);
                ByteBuffer row = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
                row.putInt(entry.getKey().intValue());
                row.putInt(pool.size());
                row.putInt(bytes.length);
                index.write(row.array());
                pool.write(bytes);
            }
        }
        byte[] indexBytes = index.toByteArray();
        byte[] poolBytes = pool.toByteArray();
        int header = 40;
        ByteBuffer head = ByteBuffer.allocate(header).order(ByteOrder.LITTLE_ENDIAN);
        head.put("YGOMCST1".getBytes(StandardCharsets.US_ASCII));
        head.putInt(1);
        for (int count : counts) {
            head.putInt(count);
        }
        head.putInt(header);
        head.putInt(header + indexBytes.length);
        head.putInt(poolBytes.length);
        try (var channel = Files.newOutputStream(out)) {
            channel.write(head.array());
            channel.write(indexBytes);
            channel.write(poolBytes);
        }
        return counts;
    }

    private static void verifyMagic(Path file, String magic) throws IOException {
        byte[] head = Files.readAllBytes(file);
        byte[] expect = magic.getBytes(StandardCharsets.US_ASCII);
        if (head.length < expect.length || !Arrays.equals(expect, 0, expect.length, head, 0, expect.length)) {
            throw new IOException(file.getFileName() + " 魔数不是 " + magic);
        }
    }

    private static void verifyAscending(Path cards, int header, int stride) throws IOException {
        byte[] data = Files.readAllBytes(cards);
        int previous = Integer.MIN_VALUE;
        boolean first = true;
        for (int off = header; off + 4 <= data.length; off += stride) {
            int id = ByteBuffer.wrap(data, off, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (!first && Integer.compareUnsigned(id, previous) <= 0) {
                throw new IOException("cards.bin 卡号不是升序");
            }
            first = false;
            previous = id;
        }
    }

    private static void replace(Path partial, Path dest) throws IOException {
        Path prev = dest.resolveSibling(dest.getFileName().toString() + ".prev");
        Files.deleteIfExists(prev);
        if (Files.exists(dest)) {
            Files.move(dest, prev, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(partial, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(prev);
        } catch (IOException e) {
            try {
                Files.move(partial, dest, StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(prev);
            } catch (IOException second) {
                if (!Files.exists(dest) && Files.exists(prev)) {
                    Files.move(prev, dest, StandardCopyOption.REPLACE_EXISTING);
                }
                throw second;
            }
        }
    }

    private static List<Integer> diff(List<Integer> left, List<Integer> right) {
        List<Integer> out = new ArrayList<>();
        for (Integer id : left) {
            if (!right.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    private static List<Integer> head(List<Integer> ids) {
        return ids.size() <= 10 ? ids : ids.subList(0, 10);
    }
}
