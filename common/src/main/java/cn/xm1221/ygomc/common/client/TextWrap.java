package cn.xm1221.ygomc.common.client;

import java.util.ArrayList;
import java.util.List;

/**
 * 按像素折行。纯逻辑（不碰 Minecraft 类），所以能离线断言。
 *
 * <p>为什么要有这一层：原来的折行只按空格拆词。中文效果文本整段<b>没有空格</b>，
 * 于是整段被当成「一个词」，放不下时那条换行分支（要求行里已经有东西）进不去，
 * 整段就挤成一行、超出面板被裁掉——看起来就是「文字总显示不全」。
 * 中文必须能按<b>字符</b>断。卡表里的英文卡名也有连绵几十个字母的长词，同样要断。
 *
 * <p>策略，两步都保留：
 * <ol>
 *   <li>先按空格打包——英文卡名、效果文本里的「 / 」这类断点要留住，
 *       不然每个字母都换行更难看；</li>
 *   <li>某个词自己就超宽时，才按字符硬断（这正是原版 {@code Font.split} 的做法）。</li>
 * </ol>
 */
public final class TextWrap {

    private TextWrap() {
    }

    /** 怎么量一段文字的宽度。界面传 {@code font::width}，自检传一个假的等宽函数。 */
    public interface Widths {
        int width(String s);
    }

    /**
     * 折行。
     *
     * @param text     原文，{@code \r\n}/{@code \r}/{@code \n} 都会断行，空行保留
     * @param maxWidth 行宽上限（像素）。小于等于 0 时按 1 处理，不至于死循环
     * @return 折好的行；不会返回 {@code null}，也不会丢字符
     */
    public static List<String> wrap(String text, int maxWidth, Widths widths) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            out.add("");
            return out;
        }
        int limit = Math.max(1, maxWidth);
        for (String paragraph : text.split("\\r\\n|\\r|\\n", -1)) {
            wrapParagraph(paragraph, limit, widths, out);
        }
        return out;
    }

    private static void wrapParagraph(String paragraph, int maxWidth, Widths widths, List<String> out) {
        StringBuilder line = new StringBuilder();
        for (String word : paragraph.split(" ")) {
            if (word.isEmpty()) {
                // 连续空格不单独成词，否则会折出一堆空行
                continue;
            }
            if (widths.width(word) > maxWidth) {
                // 手上这半行先冲掉，超宽词自己独占接下来几行
                if (line.length() > 0) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                line = charBreak(word, maxWidth, widths, out);
                continue;
            }
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (widths.width(candidate) > maxWidth) {
                out.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        out.add(line.toString());
    }

    /**
     * 把放不下的一个词按字符断开。
     *
     * <p>逐字累加、放不下就换行。单字本身就超宽时也要把它单独放出去——
     * 不然会一直往同一行里塞，最后画出来还是被裁。
     */
    private static StringBuilder charBreak(String word, int maxWidth, Widths widths, List<String> out) {
        StringBuilder chunk = new StringBuilder();
        for (int i = 0; i < word.length(); i++) {
            String next = chunk + String.valueOf(word.charAt(i));
            if (chunk.length() > 0 && widths.width(next) > maxWidth) {
                out.add(chunk.toString());
                chunk.setLength(0);
            }
            chunk.append(word.charAt(i));
        }
        return chunk;
    }
}
