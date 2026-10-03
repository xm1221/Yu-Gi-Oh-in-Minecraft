package cn.xm1221.ygomc.common.data;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 数据包的统一入口：把卡牌数据、卡名卡文、卡图三份文件聚合成一个门面。
 *
 * <h2>缺文件不是致命错误</h2>
 * 数据包（约 341 MB）不随模组分发，用户得自己用 {@code tools/} 下的脚本生成。
 * 所以没生成完是**正常情况**，不该让游戏起不来。{@link #open} 因此不抛异常，
 * 缺什么就记在 {@link #problems()} 里，由模组在日志/界面上提示用户去跑哪条命令。
 *
 * <p>三份文件各自独立：即使卡图缺失，卡牌本身仍然可玩（只是没图看）。
 * 所以这里分成三个独立的可空成员，而不是「缺一个就全灭」。
 *
 * <h2>显示一律用卡片自身卡号，不要碰 alias</h2>
 * 内核 {@code card_data.h:76} 有个容易误读的函数：
 * <pre>
 *   // get the printed code on card
 *   uint32_t get_original_code() const { return alias ? alias : code; }
 * </pre>
 * 注释说「卡面上印刷的卡号」，但<b>照它去查卡名会显示成另一张卡</b>。
 * 实测本数据集（15019 张）：
 * <ul>
 *   <li>{@code datas} 与 {@code texts} 的 id 集合<b>完全相同</b>，每张卡都有自己的卡文行；</li>
 *   <li>580 张 {@code alias != 0} 的卡<b>每一张都有自己的卡图</b>，没有一张需要回退；</li>
 *   <li>这 580 张里只有 18 张「自己的卡名 ≠ alias 的卡名」，例如
 *       {@code 295517 传说之都 亚特兰蒂斯} 的 alias 是 {@code 22702055 海}。</li>
 * </ul>
 * 也就是说 {@code alias} 是<b>「这张卡的卡名视为某某」的规则别名</b>，
 * 不是「异画指向原型」。这一点在内核里可以印证：{@code playerop.cpp:872} 的
 * {@code is_declarable()} 一遇到 {@code cd.alias} 就直接返回 FALSE ——
 * 你不可能去宣言「传说之都 亚特兰蒂斯」，因为它永远当作「海」。
 * 剩下 562 张同名的才是异画，而它们各自也有独立的文本行与卡图。
 *
 * <p>所以本类<b>不做任何 alias 重定向</b>：{@link #nameOf} / {@link #descOf} /
 * {@link #imageOf} 全部直接用调用方给的卡号。全库只有 2 张卡（19144623 妖精王子、
 * 77571455 不明）确实没有卡图，渲染端对它们回退到通用卡背即可。
 */
public final class DataPack implements Closeable {

    /** 数据包默认位置（相对仓库根/工作目录）。该目录已被 .gitignore 挡掉。 */
    public static final String DEFAULT_DIR = "local-data/datapack";

    private final Path dir;
    private final CardDataDb cardData;
    private final CardTextDb cardText;
    private final CardImageDb cardImages;
    private final List<String> problems;

    private DataPack(Path dir, CardDataDb cardData, CardTextDb cardText,
                     CardImageDb cardImages, List<String> problems) {
        this.dir = dir;
        this.cardData = cardData;
        this.cardText = cardText;
        this.cardImages = cardImages;
        this.problems = Collections.unmodifiableList(problems);
    }

    /**
     * 打开数据包目录。<b>不抛异常</b>——任何缺失或损坏都记进 {@link #problems()}。
     * 已经成功打开的部分仍然可用。
     */
    public static DataPack open(Path dir) {
        List<String> problems = new ArrayList<>();
        CardDataDb data = null;
        CardTextDb text = null;
        CardImageDb images = null;

        if (!Files.isDirectory(dir)) {
            problems.add("数据包目录不存在: " + dir.toAbsolutePath()
                    + "。请先运行 tools/mkdatapack.py 与 tools/mkpics.py");
        } else {
            data = tryOpen(problems, "cards.bin", "tools/mkdatapack.py",
                    () -> CardDataDb.load(dir.resolve("cards.bin")));
            text = tryOpen(problems, "texts.bin", "tools/mkdatapack.py",
                    () -> CardTextDb.load(dir.resolve("texts.bin")));
            images = tryOpen(problems, "pics.bin", "tools/mkpics.py",
                    () -> CardImageDb.open(dir.resolve("pics.bin")));
        }
        return new DataPack(dir, data, text, images, problems);
    }

    /** 打开默认位置。 */
    public static DataPack openDefault() {
        return open(Path.of(DEFAULT_DIR));
    }

    private interface Loader<T> {
        T load() throws IOException;
    }

    private static <T> T tryOpen(List<String> problems, String name, String script,
                                 Loader<T> loader) {
        try {
            return loader.load();
        } catch (IOException | RuntimeException e) {
            problems.add(name + " 加载失败（可运行 " + script + " 重新生成）: " + e.getMessage());
            return null;
        }
    }

    /** 三份文件是否都可用。 */
    public boolean isComplete() {
        return cardData != null && cardText != null && cardImages != null;
    }

    /** 人能看懂的缺失/损坏说明；完整时为空列表。 */
    public List<String> problems() {
        return problems;
    }

    public Path dir() {
        return dir;
    }

    /** @return 可能为 null（见类注释） */
    public CardDataDb cardData() {
        return cardData;
    }

    /** @return 可能为 null */
    public CardTextDb cardText() {
        return cardText;
    }

    /** @return 可能为 null */
    public CardImageDb cardImages() {
        return cardImages;
    }

    // ── 门面：界面层用这三个就够 ──────────────────────────────────────────

    /**
     * 取卡牌字段。返回的 {@code code} 就是调用方传入的那个。
     *
     * @return 数据包不可用或卡号不存在时返回 null
     */
    public CardDataDb.Stats statsOf(int code) {
        return cardData == null ? null : cardData.stats(code);
    }

    /**
     * 取卡名。
     *
     * @return 查不到时返回 null（调用方应回退到一个通用名称，例如「未知卡片」）
     */
    public String nameOf(int code) {
        return cardText == null ? null : cardText.name(code);
    }

    /** 取卡文。 */
    public String descOf(int code) {
        return cardText == null ? null : cardText.desc(code);
    }

    /**
     * 取卡图字节（JPEG）。
     *
     * @param tier {@link CardImageDb#TIER_FULL} 或 {@link CardImageDb#TIER_ICON}
     * @return 缺图时返回 null，渲染端应回退到通用卡背
     */
    public byte[] imageOf(int code, int tier) {
        return cardImages == null ? null : cardImages.image(code, tier);
    }

    /** 这张卡有没有卡图。 */
    public boolean hasImage(int code) {
        return imageOf(code, CardImageDb.TIER_ICON) != null;
    }

    @Override
    public void close() throws IOException {
        if (cardImages != null) {
            cardImages.close();
        }
    }

    @Override
    public String toString() {
        return "DataPack[" + dir + " " + (isComplete() ? "完整" : ("问题 " + problems.size() + " 项"))
                + (cardData == null ? "" : ", " + cardData.size() + " 张卡")
                + "]";
    }
}
