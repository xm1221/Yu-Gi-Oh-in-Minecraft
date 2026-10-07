package cn.xm1221.ygomc.common.client;

import java.util.List;

/**
 * 一块卡牌的<b>纯几何</b>：顶点、UV、法线、朝向，以及哪张面带哪张贴图。
 *
 * <h2>为什么单独一个类，而且不碰 Minecraft</h2>
 * 这个类只依赖 JDK。故意的：几何是「改一边忘一边」最容易出错的地方，把它抽成
 * 不依赖 MC 的纯函数后，可以用 {@code javac} + {@code java} 在离线断言里直接测
 * <b>真正被画的那一份</b>顶点（见 {@code .agent/m5/java/ygomc/m5/CardPlateCheck.java}）。
 * 如果几何留在 {@link CardItemRender} 里，断言就只能另抄一份顶点出来测——
 * 测的是副本，不是产品，那正是这个项目反复吃过的那种坑。
 *
 * <h2>形状</h2>
 * 一块薄板（长方体），六个面都画：正面、背面、四个侧面。原先只画一个 z=0.5 的四边形，
 * 从侧面看是纸片。坐标沿用物品模型空间（0..1 的立方体，中心在 0.5,0.5,0.5）。
 *
 * <h2>顶点顺序与 UV 走向——为什么「正面正着、背面也正着」</h2>
 * 约定：每个面的四个角按<b>从该面外侧看是逆时针</b>（CCW）排列，UV 左上角对应
 * {@code (0,0)}、右下角 {@code (1,1)}。绕序对外侧观察者一律 CCW，所以侧面用
 * 背面剔除的 {@code entitySolid} 也不会被剔掉。
 *
 * <p>正面（法线 +Z，z 取 {@code 0.5 + t/2}）从 +Z 方向看，观察者的「右」是 +X、
 * 「上」是 +Y，于是 UV 跟着坐标走：
 * <pre>
 *   (x0, y1) → uv(0,0)    (x1, y1) → uv(1,0)      ← 上边：u 沿 +X 增
 *   (x0, y0) → uv(0,1)    (x1, y0) → uv(1,1)      ← 下边：v 沿 -Y 增
 * </pre>
 *
 * <p>背面（法线 -Z，z 取 {@code 0.5 - t/2}）从 -Z 方向看，观察者的「右」变成 <b>-X</b>、
 * 「上」仍是 +Y（这是绕 Y 轴 180° 的后果，不是猜的）。跟着这个视角铺 UV：
 * <pre>
 *   (x1, y1) → uv(0,0)    (x0, y1) → uv(1,0)      ← 上边：u 沿 -X 增
 *   (x1, y0) → uv(0,1)    (x0, y0) → uv(1,1)      ← 下边：v 沿 -Y 增
 * </pre>
 * 也就是说：<b>同一个世界坐标 x 上，正面给的 u 与背面给的 u 相差 {@code 1 - u}——
 * 水平镜像</b>。空间上镜像两次（视角镜像一次、UV 镜像一次）互相抵消，所以从背后看
 * 牌背图案是正的；不镜像的话图案会左右反。这一条被离线断言钉死
 * （{@code 正反同名角 UV 之和 = 1}），改坏了会直接报失败。
 *
 * <p>四个侧面用<b>不透明纯色</b>铺满整张 UV，贴在原版 {@code minecraft:textures/misc/white.png}
 * 上（白图 + {@code setColor} 调色），所以不需要为它新增任何贴图资源。
 */
public record CardPlate(List<Quad> quads, float width, float height, float thickness,
                        float aspectW, float aspectH) {

    /** 标准卡面长宽（200:290，与 {@code pics.bin} 的 FULL 档一致）。 */
    public static final float STANDARD_ASPECT_W = 200.0F;
    public static final float STANDARD_ASPECT_H = 290.0F;

    /**
     * 厚度的由来：真实卡牌 59×86 mm、厚约 0.3 mm（纸 + 覆膜）。模型里卡高 0.9 方块，
     * 于是厚度 ≈ 0.9 × 0.3/59 ≈ 0.0046。按「卡高 1/50」量级取 {@code 0.9/50 = 0.018}
     * ——同量级，而且厚到从侧面看得见（真卡按严格换算只有 4.6‰ 个方块，几乎是一条线，
     * 那就白做了厚度）。它远小于「卡高的 1/20 = 0.045」，不会把卡片变成砖头。
     */
    public static final float THICKNESS = 0.018F;

    /** 侧面的纸边颜色（浅灰白，略暖）。半透明会让侧面看起来像玻璃，所以 alpha 给满。 */
    public static final int EDGE_ARGB = 0xFFF2EFE8;

    /** 侧面用的白图，配 {@code RenderType.entitySolid}。 */
    public static final String EDGE_TEXTURE = "minecraft:textures/misc/white.png";

    /**
     * 一张面用哪张贴图。
     *
     * <p>{@code FRONT} / {@code BACK} 的 UV 覆盖整张图，具体是哪张资源由
     * {@link CardItemRender} 按「有没有卡号、查不查得到图」决定。
     */
    public enum Material {
        FRONT, BACK, EDGE
    }

    // 规范构造器里做校验：几何参数错（负数、NaN）时立刻炸，而不是画出一堆看不见的面。
    // 离线断言也会喂坏参数进来验这一条。
    //
    // 这里不用 record 的紧凑构造器：紧凑形式会把外面传进来的 List 原样塞进字段，
    // 想让几何真正不可变就得换成 List.copyOf，而紧凑构造器里不允许给字段赋值。
    // 写成显式赋值反而更直白：先校验，再原样落字段，quads 顺手拷成不可变副本。
    public CardPlate(List<Quad> quads, float width, float height, float thickness,
                     float aspectW, float aspectH) {
        if (quads == null || quads.isEmpty()) {
            throw new IllegalArgumentException("卡牌几何至少要有面");
        }
        if (!(width > 0.0F) || !(height > 0.0F) || !(thickness > 0.0F)) {
            throw new IllegalArgumentException("卡牌尺寸必须是正数: " + width + "x" + height
                    + " 厚 " + thickness);
        }
        if (!(aspectW > 0.0F) || !(aspectH > 0.0F)) {
            throw new IllegalArgumentException("卡图长宽比必须是正数: " + aspectW + "x" + aspectH);
        }
        this.quads = List.copyOf(quads);
        this.width = width;
        this.height = height;
        this.thickness = thickness;
        this.aspectW = aspectW;
        this.aspectH = aspectH;
    }

    /**
     * 一个角：物品模型空间里的位置、UV、法线。
     *
     * <p>{@code colorArgb} 由面携带而不是由角携带——目前同一张面四角同色，
     * 分开存只会多 4 倍字段而没有用处。
     */
    public record Corner(float x, float y, float z, float u, float v, float nx, float ny, float nz) {
    }

    /**
     * 一个四边形面。
     *
     * @param material 用哪张贴图（见 {@link Material}）
     * @param corners  从面外侧看逆时针排的四个角
     * @param colorArgb 顶点的 ARGB 颜色（正面/背面是白色＝原样显示卡图；侧面是纸边色）
     */
    public record Quad(Material material, List<Corner> corners, int colorArgb) {

        public Quad {
            if (corners == null || corners.size() != 4) {
                throw new IllegalArgumentException(material + " 面必须是 4 个角，实际 "
                        + (corners == null ? "null" : corners.size()));
            }
            corners = List.copyOf(corners);
        }
    }

    /** 按标准卡面比例造一块卡牌薄板。 */
    public static CardPlate of(float height, float thickness) {
        return of(height, thickness, STANDARD_ASPECT_W, STANDARD_ASPECT_H);
    }

    /**
     * 造一块卡牌薄板。
     *
     * @param height    卡高（方块）。宽按 {@code aspectW:aspectH} 推出来，免得拉扁。
     * @param thickness 厚度（方块），中心面仍是 z=0.5。
     * @param aspectW   卡图宽（像素）
     * @param aspectH   卡图高（像素）
     */
    public static CardPlate of(float height, float thickness, float aspectW, float aspectH) {
        float w = height * aspectW / aspectH;
        float x0 = 0.5F - w / 2.0F;
        float x1 = 0.5F + w / 2.0F;
        float y0 = 0.5F - height / 2.0F;
        float y1 = 0.5F + height / 2.0F;
        // z 以 0.5（物品模型空间里的「方块中心」那一层，与其它 BEWLR 的惯例一致）为中心，
        // 正面往外挪半个厚度，背面往里挪半个厚度。
        float zf = 0.5F + thickness / 2.0F;
        float zb = 0.5F - thickness / 2.0F;

        int white = 0xFFFFFFFF;
        // 每个面都必须【从外侧看是逆时针】。这不是审美问题：侧面走 entitySolid，
        // 它开着背面剔除——绕序反了侧面会从外面被整片剔掉，厚度就白做了；
        // 正/背面虽然走 NoCull 不会消失，但绕序与法线不一致会让光照与剔除
        // 在换渲染类型时立刻出错。
        //
        // 注意【不能六个面一起反】：±Y 那两个侧面本来就对，一起反反而会错。
        // 这条由 .agent/m5/.../CardPlateCheck.java 逐个面断言，别凭直觉改。
        //
        // UV 与位置的对应不受绕序影响：正面 u=0 仍在 x0，背面 u=0 仍在 x1
        // （背面水平镜像，从背后看牌背图案才是正的）。
        List<Quad> quads = List.of(
                // 正面：法线 +Z，UV 与「从 +Z 看」的左右一致。
                quad(Material.FRONT, white,
                        c(x0, y0, zf, 0, 1, 0, 0, 1),
                        c(x1, y0, zf, 1, 1, 0, 0, 1),
                        c(x1, y1, zf, 1, 0, 0, 0, 1),
                        c(x0, y1, zf, 0, 0, 0, 0, 1)),
                // 背面：法线 -Z。从 -Z 看时「右」是 -X，所以 u 跟着 -X 走＝水平镜像，
                // 这样从背后看牌背图案才是正的。
                quad(Material.BACK, white,
                        c(x1, y0, zb, 0, 1, 0, 0, -1),
                        c(x0, y0, zb, 1, 1, 0, 0, -1),
                        c(x0, y1, zb, 1, 0, 0, 0, -1),
                        c(x1, y1, zb, 0, 0, 0, 0, -1)),
                // 侧面：法线沿 ±X/±Y，UV 铺满整张白图，颜色是纸边浅灰白。
                quad(Material.EDGE, EDGE_ARGB,
                        c(x1, y0, zf, 0, 1, 1, 0, 0),
                        c(x1, y0, zb, 1, 1, 1, 0, 0),
                        c(x1, y1, zb, 1, 0, 1, 0, 0),
                        c(x1, y1, zf, 0, 0, 1, 0, 0)),
                quad(Material.EDGE, EDGE_ARGB,
                        c(x0, y1, zf, 0, 1, -1, 0, 0),
                        c(x0, y1, zb, 1, 1, -1, 0, 0),
                        c(x0, y0, zb, 1, 0, -1, 0, 0),
                        c(x0, y0, zf, 0, 0, -1, 0, 0)),
                quad(Material.EDGE, EDGE_ARGB,
                        c(x0, y1, zf, 0, 0, 0, 1, 0),
                        c(x1, y1, zf, 1, 0, 0, 1, 0),
                        c(x1, y1, zb, 1, 1, 0, 1, 0),
                        c(x0, y1, zb, 0, 1, 0, 1, 0)),
                quad(Material.EDGE, EDGE_ARGB,
                        c(x0, y0, zb, 0, 0, 0, -1, 0),
                        c(x1, y0, zb, 1, 0, 0, -1, 0),
                        c(x1, y0, zf, 1, 1, 0, -1, 0),
                        c(x0, y0, zf, 0, 1, 0, -1, 0)));

        return new CardPlate(quads, w, height, thickness, aspectW, aspectH);
    }

    /** 第一个指定材质的面；没有就返回 {@code null}。 */
    public Quad face(Material material) {
        for (Quad q : quads) {
            if (q.material() == material) {
                return q;
            }
        }
        return null;
    }

    /** 指定材质的面有几个。侧面应当是 4 个。 */
    public int count(Material material) {
        int n = 0;
        for (Quad q : quads) {
            if (q.material() == material) {
                n++;
            }
        }
        return n;
    }

    private static Quad quad(Material material, int colorArgb, Corner a, Corner b, Corner c, Corner d) {
        return new Quad(material, List.of(a, b, c, d), colorArgb);
    }

    private static Corner c(float x, float y, float z, float u, float v, float nx, float ny, float nz) {
        return new Corner(x, y, z, u, v, nx, ny, nz);
    }
}
