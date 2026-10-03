package cn.xm1221.ygomc.common.deck;

import cn.xm1221.ygomc.common.card.DeckData;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code .ydk} 卡组文件的读写。
 *
 * <h2>.ydk 是什么</h2>
 * ygopro 客户端的卡组格式，也是最方便与既有工具互通的一种：纯文本、一行一个卡号，
 * 用三个段标记分组。实测本机的 526 副卡组全都是这个形状：
 *
 * <pre>
 * #created by ...
 * #main
 * 23064604
 * 23064604
 * ...
 * #extra
 * !side
 * 14532163
 * </pre>
 *
 * <h2>三个段标记的写法并不统一</h2>
 * 主卡组和额外卡组用 {@code #}，<b>副卡组用 {@code !}</b>（{@code !side}）。
 * 这是历史遗留，但既然是互通格式就只能照收。逐字写死这三个标记，
 * 并且<b>对未知的 {@code #}/{@code !} 行一律忽略</b>——ygopro 自己会往里写
 * {@code #created by ...} 这类注释，遇到没见过的标记就报错会让用户无法加载自己的卡组。
 *
 * <h2>段标记之前的卡号算主卡组</h2>
 * 有的工具会省略 {@code #main}。默认归到主卡组比报错更符合「能读就读」的预期，
 * 而且主卡组是三者里唯一必有内容的一项。
 *
 * <h2>本类不做任何校验</h2>
 * 张数、同名限制、卡是否存在都是 {@link DeckValidator} 的事。
 * 这里只负责「文本 ↔ {@link DeckData}」，这样解析能被单独测试，
 * 校验规则变化时也不必碰解析代码。
 */
public final class DeckIo {

    private static final String MAIN_MARK = "#main";
    private static final String EXTRA_MARK = "#extra";
    private static final String SIDE_MARK = "!side";

    private DeckIo() {
    }

    /**
     * 解析一个 {@code .ydk} 文件。
     *
     * @throws IllegalArgumentException 某行不是合法卡号时，消息里带行号
     * @throws IOException              读文件失败
     */
    public static DeckData read(Path file) throws IOException {
        return parse(Files.readAllLines(file, StandardCharsets.UTF_8));
    }

    /**
     * 解析若干行。
     *
     * @throws IllegalArgumentException 某行不是合法卡号时，消息里带行号（从 1 开始）
     */
    public static DeckData parse(List<String> lines) {
        List<Integer> main = new ArrayList<>();
        List<Integer> extra = new ArrayList<>();
        List<Integer> side = new ArrayList<>();

        // 0 = 主（也是「还没遇到段标记」时的默认归属），1 = 额外，2 = 副
        int section = 0;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#") || line.startsWith("!")) {
                if (line.equalsIgnoreCase(MAIN_MARK)) {
                    section = 0;
                } else if (line.equalsIgnoreCase(EXTRA_MARK)) {
                    section = 1;
                } else if (line.equalsIgnoreCase(SIDE_MARK)) {
                    section = 2;
                }
                // 其余 # / ! 行（注释、未知标记）忽略。
                continue;
            }

            int code;
            try {
                code = Integer.parseInt(line);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "第 " + (i + 1) + " 行不是合法卡号: \"" + line + "\"");
            }
            if (code <= 0) {
                // 卡号 0 在 cards.bin 里不存在，也不可能是一张真卡；
                // 放进去只会在对局里变成一张无名的空卡，不如当场拒绝。
                throw new IllegalArgumentException(
                        "第 " + (i + 1) + " 行的卡号不是正数: " + code);
            }
            switch (section) {
                case 1 -> extra.add(code);
                case 2 -> side.add(code);
                default -> main.add(code);
            }
        }
        return new DeckData(main, extra, side);
    }

    /**
     * 写出 {@code .ydk} 的文本行。
     *
     * <p>顺序与段标记都照 ygopro 的写法，这样导出的卡组能直接被 ygopro 读回去——
     * 互通是单向的就没有意义了。
     */
    public static List<String> write(DeckData deck) {
        List<String> out = new ArrayList<>();
        out.add("#created by ygomc");
        out.add(MAIN_MARK);
        for (int code : deck.main()) {
            out.add(Integer.toString(code));
        }
        out.add(EXTRA_MARK);
        for (int code : deck.extra()) {
            out.add(Integer.toString(code));
        }
        out.add(SIDE_MARK);
        for (int code : deck.side()) {
            out.add(Integer.toString(code));
        }
        return out;
    }

    /** {@link #write} 的落盘版本。 */
    public static void save(DeckData deck, Path file) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(file, write(deck), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("写出卡组失败: " + file, e);
        }
    }
}
