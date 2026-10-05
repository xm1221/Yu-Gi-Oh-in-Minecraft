package cn.xm1221.ygomc.common.client;

import java.util.List;

/**
 * 场地格子材质的清单：一个 id、一个文件、一对源尺寸、一句用处。
 *
 * <p>这是<b>纯数据</b>（不碰任何 Minecraft 类），所以能离线断言；
 * 真正的加载与绘制在 {@link FieldTextures}。
 *
 * <p>为什么要有这一层：格子的外观原本硬编码在 {@code DuelScreen} 里（一堆
 * {@code g.fill} + {@code outline}），想换一张皮就得改代码。现在改成
 * 「按 id 找贴图，找不到才退回原来画的矩形」——咩咩只要往
 * {@code assets/ygomc/textures/gui/duel/} 里放同名 PNG 就换掉了，
 * 一张不画也照样能跑。
 *
 * <p>源尺寸写在这里是为了<b>告知画的人该按什么比例画</b>：贴图会被拉伸到格子的
 * 实际像素尺寸（格子大小随窗口变），所以比例比绝对像素重要。
 */
public final class FieldTextureSpec {

    private FieldTextureSpec() {
    }

    /** 模组命名空间，和 {@code CardTextures} 一致。 */
    public static final String NAMESPACE = "ygomc";

    /** 贴图目录（相对 {@code assets/ygomc/}）。 */
    public static final String DIR = "textures/gui/duel/";

    /** 怪兽区空槽。额外怪兽区与它共用——/{@code FieldLayout} 里它们本来就是同一行。 */
    public static final String ZONE_MONSTER = "zone_monster";

    /** 魔法陷阱区空槽。 */
    public static final String ZONE_SPELL = "zone_spell";

    /** 场地区空槽（魔陷区序号 5、6）。 */
    public static final String ZONE_FIELD = "zone_field";

    /** 牌堆槽：卡组/额外/墓地/除外。 */
    public static final String ZONE_PILE = "zone_pile";

    /** 牌垫底：会被<b>平铺</b>（不是拉伸），所以画成四边能接上的无缝底纹。 */
    public static final String MAT = "mat";

    /** 全部材质。{@code w}/{@code h} 是建议的源尺寸。 */
    public static final List<Entry> ENTRIES = List.of(
            new Entry(ZONE_MONSTER, 64, 96, "怪兽区空槽（额外怪兽区共用）"),
            new Entry(ZONE_SPELL, 64, 96, "魔法陷阱区空槽"),
            new Entry(ZONE_FIELD, 64, 96, "场地区空槽"),
            new Entry(ZONE_PILE, 64, 72, "牌堆槽：卡组/额外/墓地/除外"),
            new Entry(MAT, 16, 16, "牌垫底，会平铺，四边要能接上"));

    /** 一条材质。 */
    public record Entry(String id, int w, int h, String usage) {

        /** @return 资源路径（不含命名空间） */
        public String path() {
            return DIR + id + ".png";
        }
    }

    /**
     * @param id 材质 id
     * @return 清单里的那一条；id 不在清单里返回 {@code null}（调用方应当什么也不画）
     */
    public static Entry of(String id) {
        for (Entry e : ENTRIES) {
            if (e.id().equals(id)) {
                return e;
            }
        }
        return null;
    }
}
