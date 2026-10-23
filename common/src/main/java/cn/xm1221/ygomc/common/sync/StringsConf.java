package cn.xm1221.ygomc.common.sync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code strings.conf} 的解析。规则与 {@code tools/mkdatapack.py} 的 {@code parse_strings_conf} 相同。
 */
public final class StringsConf {

    public final Map<Long, String> system = new TreeMap<>();
    public final Map<Long, String> counter = new TreeMap<>();
    public final Map<Long, String> victory = new TreeMap<>();
    public final Map<Long, String> setname = new TreeMap<>();
    public final Map<String, Integer> ignored = new LinkedHashMap<>();

    private StringsConf() {
    }

    public static StringsConf parse(Path file) throws IOException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    public static StringsConf parse(String text) throws IOException {
        StringsConf out = new StringsConf();
        for (String raw : text.split("\n", -1)) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (!line.startsWith("!")) {
                continue;
            }
            String[] parts = line.substring(1).split("\\s+", 3);
            if (parts.length < 3) {
                out.note(line.substring(0, Math.min(40, line.length())));
                continue;
            }
            String name = parts[0];
            String tok = parts[1];
            String body = parts[2];
            Map<Long, String> section = switch (name) {
                case "system" -> out.system;
                case "counter" -> out.counter;
                case "victory" -> out.victory;
                case "setname" -> out.setname;
                default -> null;
            };
            if (section == null) {
                out.note("!" + name);
                continue;
            }
            long key;
            try {
                key = name.equals("system") ? Long.parseLong(tok, 10) : parseHex(tok);
            } catch (NumberFormatException e) {
                out.note(line.substring(0, Math.min(40, line.length())));
                continue;
            }
            if (name.equals("setname")) {
                int tab = body.indexOf('\t');
                if (tab >= 0) {
                    body = body.substring(0, tab);
                }
            }
            if (name.equals("system") && section.containsKey(key)) {
                continue;
            }
            section.put(key, body);
        }
        out.requireRange("属性 1010..1016", 1010, 1016);
        out.requireRange("种族 1020..1045", 1020, 1045);
        out.requireRange("类型 1050..1075", 1050, 1075);
        return out;
    }

    private void requireRange(String label, int from, int to) throws IOException {
        StringBuilder missing = new StringBuilder();
        for (int id = from; id <= to; id++) {
            if (!system.containsKey((long) id)) {
                if (!missing.isEmpty()) {
                    missing.append(", ");
                }
                missing.append(id);
            }
        }
        if (!missing.isEmpty()) {
            throw new IOException("strings.conf 的 !system 缺 " + label + "：" + missing);
        }
    }

    private void note(String key) {
        ignored.merge(key, 1, Integer::sum);
    }

    static long parseHex(String tok) {
        String text = tok;
        if (text.startsWith("0x") || text.startsWith("0X")) {
            text = text.substring(2);
        }
        return Long.parseUnsignedLong(text, 16);
    }
}
