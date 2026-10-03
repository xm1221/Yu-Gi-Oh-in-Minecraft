package cn.xm1221.ygomc.common.deck;

import cn.xm1221.ygomc.common.card.DeckData;
import dev.architectury.platform.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 卡组文件的查找与加载。
 *
 * <p>与数据包、脚本根同构的定位规则：显式属性 → 游戏目录下的 {@code ygomc/decks}
 * → 从游戏目录逐级向上找 {@code local-data/decks}（开发用）。
 *
 * <h2>为什么支持「按名字加载」而不只是「给绝对路径」</h2>
 * 命令的参数是玩家敲进来的，让人去敲一长串绝对路径既不现实也很容易出错。
 * 按名字在固定目录里找，正是 ygopro 客户端自己的做法（{@code deck/} 目录）。
 * 代价是必须防目录穿越——见 {@link #load}。
 */
public final class DeckLibrary {

    /** 显式指定卡组目录：{@code -Dygomc.decks=<目录>}。 */
    public static final String PROPERTY = "ygomc.decks";

    /** 扩展名。ygopro 自己写出来的就是它。 */
    public static final String EXTENSION = ".ydk";

    private DeckLibrary() {
    }

    /** 与数据包/脚本根同一套候选规则，见类注释。不碰文件系统。 */
    public static List<Path> candidates(Path gameDir) {
        String override = System.getProperty(PROPERTY);
        if (override != null && !override.isBlank()) {
            return List.of(Path.of(override).toAbsolutePath().normalize());
        }
        List<Path> out = new ArrayList<>();
        Path dir = gameDir.toAbsolutePath().normalize();
        out.add(dir.resolve("ygomc/decks"));
        for (int up = 0; up <= 3 && dir != null; up++) {
            out.add(dir.resolve("local-data/decks"));
            dir = dir.getParent();
        }
        return out;
    }

    /** 第一个真实存在的卡组目录；都不存在时返回 null。 */
    public static Path directory() {
        for (Path c : candidates(Platform.getGameFolder())) {
            if (Files.isDirectory(c)) {
                return c;
            }
        }
        return null;
    }

    /**
     * 可用卡组名（不含扩展名），按名字排序。子目录里的卡组以 {@code 分类/名字} 形式给出。
     *
     * <p>递归两层是有意的：ygopro 的用户习惯按主题建子目录（本机的 {@code deck/} 下就有一个
     * {@code 机壳}）。只扫顶层会让这些卡组「存在但列不出来」。
     * 深度封在 2 是有意的：再深就不像分类，更像误把整个硬盘当卡组库。
     */
    public static List<String> list() {
        Path dir = directory();
        if (dir == null) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(dir, 2)) {
            return files
                    .filter(Files::isRegularFile)
                    .map(p -> dir.relativize(p).toString().replace('\\', '/'))
                    .filter(n -> n.toLowerCase().endsWith(EXTENSION))
                    .map(n -> n.substring(0, n.length() - EXTENSION.length()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * 按名字加载一副卡组。名字是<b>卡组目录下的相对路径</b>，可以带 {@code .ydk}，
     * 也可以带一层子目录（{@code 机壳/机壳}）。
     *
     * <p>命令参数是玩家可控输入，所以设了两道防线，任意一道单独失效都还拦得住：
     * <ol>
     *   <li>含 {@code ..} 或绝对路径形式的一律直接拒绝——本功能没有任何理由需要它们；</li>
     *   <li>拼好之后仍然归一化一次并确认结果落在卡组目录内，挡住别名、符号链接
     *       以及「不含 {@code ..} 但依然能跳出目录」的写法。</li>
     * </ol>
     * 只做第 2 步是常见的错法：Windows 的短名（{@code PROGRA~1}）与 UNC 路径会让
     * 纯字符串判断出错，而多一道「直接拒绝」几乎不花代价。
     *
     * @throws IOException              文件不存在或读失败
     * @throws IllegalArgumentException 名字非法，或内容不是合法 .ydk
     */
    public static DeckData load(String name) throws IOException {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("卡组名不能为空");
        }
        String normalized = name.replace('\\', '/').trim();
        if (normalized.contains("..")) {
            throw new IllegalArgumentException("卡组名不能包含 .. : " + name);
        }
        Path dir = directory();
        if (dir == null) {
            throw new IOException("找不到卡组目录，已依次查找: " + candidates(Platform.getGameFolder()));
        }
        String file = normalized.toLowerCase().endsWith(EXTENSION) ? normalized : normalized + EXTENSION;
        Path base = dir.toAbsolutePath().normalize();
        Path target = base.resolve(file).normalize();
        if (!target.startsWith(base)) {
            throw new IllegalArgumentException("卡组名指向卡组目录之外: " + name);
        }
        if (!Files.isRegularFile(target)) {
            throw new IOException("卡组不存在: " + target);
        }
        return DeckIo.read(target);
    }
}
