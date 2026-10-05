package cn.xm1221.ygomc.common.client;

import cn.xm1221.ygomc.common.data.CardDataDb;
import cn.xm1221.ygomc.common.data.DataPack;
import cn.xm1221.ygomc.common.data.DataPacks;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 卡牌提示框：悬停时把「这是哪张卡、什么数值、什么效果」摊出来。
 *
 * <h2>为什么决斗界面必须有这个</h2>
 * 对局里能看到的只有一张缩小的卡图。牌桌<em>故意</em>不摆按钮之后，
 * 玩家判断「这张卡现在能不能发动」全靠读卡文——没有卡文，界面就只剩下一堆
 * 认不出来的小图，那比原来的按钮网格更难用。
 *
 * <h2>为什么单独一个类</h2>
 * 卡组浏览界面（{@link CardBrowserScreen}）本来自己写了一套折行与数值行。
 * 两个界面各写一份的下场是「同一个卡号在两处显示不同」，
 * 所以卡文的读法只留这一处。
 *
 * <h2>不依赖对局</h2>
 * 只吃卡号与数据包，所以卡组界面、物品提示都能用同一份。
 */
public final class CardTips {

    /** 卡图按真卡比例（200×290）缩放时的宽度。 */
    public static final int ART_W = 100;
    public static final int ART_H = ART_W * 290 / 200;

    /** 文字区宽度：够放一行完整的卡文，又不至于横跨半个屏幕。 */
    public static final int TEXT_W = 190;
    /** 最多画几行卡文，超出就截断——提示框不该盖住整张牌桌。 */
    private static final int MAX_DESC_LINES = 12;

    private static final int PANEL_BG = 0xF012161E;
    private static final int PANEL_EDGE = 0xFF8A9AC0;

    private CardTips() {
    }

    /** 卡名；数据包缺失或查不到时返回 {@code null}，由调用方决定怎么显示。 */
    @Nullable
    public static String name(int code) {
        DataPack pack = DataPacks.get();
        if (pack == null || code == 0) {
            return null;
        }
        String n = pack.nameOf(code);
        return n == null || n.isEmpty() ? null : n;
    }

    /**
     * 数值行：等级/阶级/连接 + 攻守；魔法陷阱只报种类。
     *
     * <p>连接怪兽没有守备力，写出来会是一个假的 0。
     */
    @Nullable
    public static String statsLine(int code) {
        DataPack pack = DataPacks.get();
        if (pack == null) {
            return null;
        }
        CardDataDb.Stats s = pack.statsOf(code);
        if (s == null) {
            return null;
        }
        int type = s.type();
        if ((type & CardDataDb.CardTypes.TYPE_MONSTER) == 0) {
            return (type & CardDataDb.CardTypes.TYPE_SPELL) != 0 ? "魔法卡" : "陷阱卡";
        }
        String rank = (type & CardDataDb.CardTypes.TYPE_XYZ) != 0 ? "阶级 "
                : (type & CardDataDb.CardTypes.TYPE_LINK) != 0 ? "连接 " : "等级 ";
        StringBuilder sb = new StringBuilder(rank).append(s.level());
        sb.append("    ATK ").append(s.attack());
        if ((type & CardDataDb.CardTypes.TYPE_LINK) == 0) {
            sb.append(" / DEF ").append(s.defense());
        }
        return sb.toString();
    }

    /** 卡文（效果文本）；没有则返回 {@code null}。 */
    @Nullable
    public static String desc(int code) {
        DataPack pack = DataPacks.get();
        if (pack == null) {
            return null;
        }
        String d = pack.descOf(code);
        return d == null || d.isBlank() ? null : d;
    }

    /**
     * 按字体真实宽度折行。
     *
     * <p>用像素折行而不是估算字数：屏幕上宽度是确定的，按字数折一定会在
     * 某些字符（英文卡名、全角标点）上越过面板边界。
     * {@code \r} 也要一起处理，否则它会被画成一个方块。
     */
    public static List<String> wrap(Font font, String text, int maxWidth) {
        // 算法搬到 TextWrap（纯逻辑，能离线断言）；这里只把「怎么量宽度」递进去。
        // 原来那份实现只按空格拆词，中文整段没有空格 → 整段挤成一行被裁掉，
        // 那正是「文字总显示不全」的来源。详见 TextWrap 的类注释。
        return TextWrap.wrap(text, maxWidth, font::width);
    }

    /**
     * 画一个卡牌提示框，自动躲开屏幕边缘。
     *
     * <p><b>现在已经没有调用方了</b>（第四轮起，卡图与卡文改在右侧信息面板里画，
     * 见 {@code DuelScreen.drawInfoPanel}）。留着它是因为「跟着光标走的小窗」
     * 在窄屏上仍有价值，删掉容易、想再捡回来就得重写一遍躲边逻辑。
     * 但要注意：<b>不要因为看到这个方法就以为悬停提示还在用它</b>——
     * 那时改了这里不会有任何可见效果。
     *
     * @param preferLeft true 时优先画在光标<b>左侧</b>（牌桌上手牌在底部，
     *                   提示框往上或往旁边都要让开可点的卡）
     */
    public static void draw(GuiGraphics g, Font font, int code, int mouseX, int mouseY,
                            int screenW, int screenH, boolean preferLeft) {
        List<String> text = new ArrayList<>();
        String nm = name(code);
        text.add(nm != null ? nm : ("#" + code));
        String stats = statsLine(code);
        int statsAt = -1;
        if (stats != null) {
            statsAt = text.size();
            text.add(stats);
        }
        String d = desc(code);
        int descAt = -1;
        if (d != null) {
            descAt = text.size();
            List<String> wrapped = wrap(font, d, TEXT_W);
            if (wrapped.size() > MAX_DESC_LINES) {
                wrapped = new ArrayList<>(wrapped.subList(0, MAX_DESC_LINES));
                wrapped.add("…");
            }
            text.addAll(wrapped);
        }

        int lineH = 10;
        int textBlockH = text.size() * lineH;
        int panelH = Math.max(ART_H, textBlockH) + 12;
        int panelW = ART_W + TEXT_W + 18;
        // 屏幕装不下就整体缩到可用宽度，而不是画出去
        if (panelW > screenW - 8) {
            panelW = Math.max(60, screenW - 8);
        }
        if (panelH > screenH - 8) {
            panelH = Math.max(40, screenH - 8);
        }

        int x = preferLeft ? mouseX - panelW - 10 : mouseX + 12;
        if (x + panelW > screenW - 4) {
            x = screenW - panelW - 4;
        }
        if (x < 4) {
            x = 4;
        }
        int y = mouseY - panelH / 2;
        if (y + panelH > screenH - 4) {
            y = screenH - panelH - 4;
        }
        if (y < 4) {
            y = 4;
        }

        g.fill(x, y, x + panelW, y + panelH, PANEL_BG);
        outline(g, x, y, panelW, panelH);

        int artW = Math.min(ART_W, panelW - 8);
        int artH = artW * 290 / 200;
        if (artH > panelH - 8) {
            artH = panelH - 8;
            artW = artH * 200 / 290;
        }
        CardArt.draw(g, code, x + 4, y + 4, artW, artH, null);

        int tx = x + 4 + artW + 6;
        int ty = y + 6;
        int limit = y + panelH - 4;
        for (int i = 0; i < text.size(); i++) {
            if (ty + lineH > limit) {
                break;
            }
            int color = i == 0 ? 0xFFFFE080
                    : i == statsAt ? 0xFFCCCCCC
                    : i == descAt ? 0xFFB0B0B0 : 0xFF9A9AA8;
            String line = text.get(i);
            // 卡组/额外卡组的卡序与里侧卡不会走到这里（调用方先判可见性），
            // 但文字仍可能超宽（长卡名的英文），截断而不是溢出面板。
            int room = x + panelW - 4 - tx;
            g.drawString(font, font.plainSubstrByWidth(line, room), tx, ty, color, false);
            ty += lineH;
        }
    }

    private static void outline(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + 1, PANEL_EDGE);
        g.fill(x, y + h - 1, x + w, y + h, PANEL_EDGE);
        g.fill(x, y, x + 1, y + h, PANEL_EDGE);
        g.fill(x + w - 1, y, x + w, y + h, PANEL_EDGE);
    }
}
