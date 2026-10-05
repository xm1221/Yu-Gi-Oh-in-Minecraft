package cn.xm1221.ygomc.common.client;

import cn.xm1221.ygomc.common.card.RarityEntry;
import cn.xm1221.ygomc.common.data.CardDataDb;
import cn.xm1221.ygomc.common.data.DataPack;
import cn.xm1221.ygomc.common.data.DataPacks;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 卡图浏览界面：翻看卡池里的卡，看卡图、卡名、数值与卡文。
 *
 * <h2>为什么先做这个界面</h2>
 * 卡图链路（{@code pics.bin} 解码 → 纹理 → 绘制）需要能一眼看到才谈得上验证。
 * 物品栏里的图标要靠平台专属代码才能换成卡面，而界面里的 {@link CardArt#draw}
 * 是纯 {@code GuiGraphics}，两平台共用同一份——所以这个界面是<b>最快能把
 * 整条链路看全</b>的地方，也正好是 M4 组卡界面的前身。
 *
 * <h2>翻的是整个卡池，不是「你拥有的卡」</h2>
 * 卡册的语义是「检索我持有的卡」，那需要服务端把收藏同步下来（M3/M4）。
 * 现在没有那条链路，所以这里先翻整个卡池，并在界面上如实标明。
 * 直接翻卡池对验证反而更有用：一步就能看到随机若干张卡的图有没有解对。
 *
 * <h2>为什么只按卡号顺序翻</h2>
 * 卡池有 15017 张。按卡号翻页（而不是维护一个可分页的列表）意味着这个界面
 * 不持有任何索引状态，翻到哪张就解码哪张；配合 {@link CardTextures} 的 LRU，
 * 连续翻页不会让显存无限增长。搜索框留给 M4。
 */
public final class CardBrowserScreen extends Screen {

    /** 面板尺寸。取偶数好让文字对齐不出半像素。 */
    private static final int PANEL_W = 380;
    private static final int PANEL_H = 232;

    /** 卡图显示尺寸，保持 200×290 的宽高比。 */
    private static final int ART_W = 122;
    private static final int ART_H = 177;

    /** 卡文折行的目标宽度（像素，按原版字体约 6px/字符估算后取保守值）。 */
    private static final int TEXT_WIDTH = 210;

    private final int[] codes;
    private int index;

    public CardBrowserScreen(int startCode) {
        super(DuelText.c(DuelText.BROWSER_TITLE));
        DataPack pack = DataPacks.get();
        CardDataDb db = pack.cardData();
        // 数据包缺失时给一个空表而不是崩：这是预期情况（卡图与卡文有版权，
        // 不随模组分发），界面应当照样能打开并说明原因。
        this.codes = db == null ? new int[0] : db.codes();
        this.index = indexOf(startCode);
    }

    /** 打开界面。由平台客户端入口调用（它们只在客户端加载，所以这里可以安全引用 Screen）。 */
    public static void open(int startCode) {
        net.minecraft.client.Minecraft.getInstance().setScreen(new CardBrowserScreen(startCode));
    }

    private int indexOf(int code) {
        for (int i = 0; i < codes.length; i++) {
            if (codes[i] == code) {
                return i;
            }
        }
        return 0;
    }

    @Override
    protected void init() {
        int x = (width - PANEL_W) / 2;
        int y = (height - PANEL_H) / 2;
        int by = y + PANEL_H - 26;

        addRenderableWidget(Button.builder(DuelText.c(DuelText.BROWSER_PREV), b -> step(-1))
                .bounds(x + 10, by, 78, 20).build());
        addRenderableWidget(Button.builder(DuelText.c(DuelText.BROWSER_NEXT), b -> step(1))
                .bounds(x + 92, by, 78, 20).build());
        // 一次跳 100 张：15017 张卡一张一张翻是翻不完的，而逐张翻是验证解码才对的做法，
        // 所以两个粒度都留。
        addRenderableWidget(Button.builder(DuelText.c(DuelText.BROWSER_PREV_100), b -> step(-100))
                .bounds(x + 192, by, 78, 20).build());
        addRenderableWidget(Button.builder(DuelText.c(DuelText.BROWSER_NEXT_100), b -> step(100))
                .bounds(x + 274, by, 78, 20).build());
    }

    private void step(int delta) {
        if (codes.length == 0) {
            return;
        }
        // 用取模让它首尾相接，省掉「到头了」的边界判断与相应的一堆禁用逻辑。
        index = Math.floorMod(index + delta, codes.length);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);

        int x = (width - PANEL_W) / 2;
        int y = (height - PANEL_H) / 2;
        g.fill(x, y, x + PANEL_W, y + PANEL_H, 0xE0101014);
        g.fill(x, y, x + PANEL_W, y + 1, 0xFF6A6A78);
        g.fill(x, y + PANEL_H - 1, x + PANEL_W, y + PANEL_H, 0xFF6A6A78);

        if (codes.length == 0) {
            g.drawString(font, DuelText.s(DuelText.BROWSER_NO_PACK), x + 12, y + 12, 0xFFCC5555, false);
            g.drawString(font, DuelText.s(DuelText.BROWSER_NO_PACK_DETAIL), x + 12, y + 26, 0xFFAAAAAA, false);
            super.render(g, mouseX, mouseY, partialTick);
            return;
        }

        int code = codes[index];

        // 卡图。稀有度取默认值——卡池里的卡没有「这张卡是什么稀有度」的数据，
        // 稀有度是每张实例的属性（CardRef.rarity），不是卡号本身的属性。
        int artX = x + 12;
        int artY = y + 12;
        boolean hasArt = CardArt.draw(g, code, artX, artY, ART_W, ART_H, (RarityEntry) null);
        if (!hasArt) {
            g.drawString(font, DuelText.s(DuelText.BROWSER_NO_ART), artX + 6, artY + ART_H / 2 - 4, 0xFFAAAAAA, false);
        }

        int textX = artX + ART_W + 14;
        int textY = y + 12;

        String name = DataPacks.get().nameOf(code);
        g.drawString(font, name == null ? DuelText.s(DuelText.BROWSER_NO_NAME) : name,
                textX, textY, 0xFFFFFFFF, true);
        textY += 12;

        g.drawString(font, DuelText.s(DuelText.DECLARE_CARD_CODE, code), textX, textY, 0xFF8888AA, false);
        textY += 12;

        String stats = CardTips.statsLine(code);
        if (stats != null) {
            g.drawString(font, stats, textX, textY, 0xFFCCCCCC, false);
            textY += 12;
        }
        textY += 4;

        String desc = DataPacks.get().descOf(code);
        if (desc == null || desc.isBlank()) {
            g.drawString(font, DuelText.s(DuelText.BROWSER_NO_DESC), textX, textY, 0xFF777777, false);
        } else {
            // 这里用字体真实宽度折行，而不是估值：屏幕上宽度是确定的，
            // 按像素折行才能保证不越出面板。服务端的工具提示做不到这一点，
            // 所以那边用的是「显示列数」的近似算法。
            // 折行算法走 CardTips（它又委托给纯逻辑的 TextWrap）——两处各写一份
            // 曾经导致「同一个卡号在两个界面显示不同」。
            for (String line : CardTips.wrap(font, desc, TEXT_WIDTH)) {
                if (textY > y + PANEL_H - 30) {
                    g.drawString(font, DuelText.s(DuelText.BROWSER_ELLIPSIS), textX, textY, 0xFF777777, false);
                    break;
                }
                g.drawString(font, line, textX, textY, 0xFFB0B0B0, false);
                textY += 10;
            }
        }

        g.drawString(font, DuelText.s(DuelText.BROWSER_PAGE, index + 1, codes.length)
                        + DuelText.s(DuelText.BROWSER_PAGE_NOTE),
                x + 12, y + PANEL_H - 40, 0xFF808080, false);

        g.drawString(font, CardTextures.stats(), x + 12, y + PANEL_H - 40 + 10, 0xFF606060, false);

        super.render(g, mouseX, mouseY, partialTick);
    }

    /** 数据包缺失时也允许关闭。 */
    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
