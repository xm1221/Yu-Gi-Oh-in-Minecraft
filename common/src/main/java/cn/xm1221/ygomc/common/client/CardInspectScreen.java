package cn.xm1221.ygomc.common.client;

import cn.xm1221.ygomc.common.card.RarityEntry;
import cn.xm1221.ygomc.common.data.DataPacks;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

/**
 * 手持卡右键：单卡大图 + 名称 / 属性行 / 卡文。无翻页。
 *
 * <p>只复用 {@link CardArt} / {@link CardTips} 的绘制与换行工具；
 * 不走卡册 {@link BinderScreen}，也不复用 {@link CardBrowserScreen} 的分页浏览 UI。
 *
 * <p>背景：覆盖 {@code renderBackground} 为空，在 {@code render} 里自填半透明底，
 * 避免 1.21 原版 blur/dim 叠成灰蒙蒙。见 {@code .agent/常见错误}。
 */
public final class CardInspectScreen extends Screen {

    private static final int PANEL_W = 380;
    private static final int PANEL_H = 220;
    private static final int ART_W = 122;
    private static final int ART_H = 177;
    private static final int TEXT_WIDTH = 210;
    /** 自控半透明底，对齐 DuelScreen，勿再调原版 renderBackground。 */
    private static final int DIM = 0xA03C5566;

    private final int code;

    public CardInspectScreen(int code) {
        super(DuelText.c(DuelText.INSPECT_TITLE));
        this.code = code;
    }

    /** 仅客户端调用。 */
    public static void open(int code) {
        net.minecraft.client.Minecraft.getInstance().setScreen(new CardInspectScreen(code));
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 故意为空：1.21 Screen 调用链已会压暗；再调原版会灰蒙蒙。
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, DIM);

        int x = (width - PANEL_W) / 2;
        int y = (height - PANEL_H) / 2;
        g.fill(x, y, x + PANEL_W, y + PANEL_H, 0xE0101014);
        g.fill(x, y, x + PANEL_W, y + 1, 0xFF6A6A78);
        g.fill(x, y + PANEL_H - 1, x + PANEL_W, y + PANEL_H, 0xFF6A6A78);

        int artX = x + 12;
        int artY = y + 12;
        boolean hasArt = CardArt.draw(g, code, artX, artY, ART_W, ART_H, (RarityEntry) null);
        if (!hasArt) {
            g.drawString(font, DuelText.s(DuelText.BROWSER_NO_ART),
                    artX + 6, artY + ART_H / 2 - 4, 0xFFAAAAAA, false);
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
            for (String line : CardTips.wrap(font, desc, TEXT_WIDTH)) {
                if (textY > y + PANEL_H - 16) {
                    g.drawString(font, DuelText.s(DuelText.BROWSER_ELLIPSIS), textX, textY, 0xFF777777, false);
                    break;
                }
                g.drawString(font, line, textX, textY, 0xFFB0B0B0, false);
                textY += 10;
            }
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}