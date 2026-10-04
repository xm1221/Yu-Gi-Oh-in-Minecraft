package cn.xm1221.ygomc.common.client;

/**
 * 牌桌几何：由屏幕尺寸算出每一个格子的矩形。
 *
 * <h2>为什么把它单独抽出来</h2>
 * 原先所有坐标都直接写在 {@code DuelScreen.render} 里、按 {@code height} 加减常数，
 * 结果是魔陷行整行落在屏幕外、问题标题压在我方牌桌上——而这两种错误
 * <b>编译期完全看不出来</b>，只能靠人盯着游戏画面发现。
 *
 * <p>这个类<b>不依赖任何 Minecraft 类</b>（纯 JDK），所以可以在没有游戏的情况下
 * 对任意屏幕尺寸断言「没有任何矩形越界」「行与行不重叠」。
 * {@code DuelScreen} 只负责按这里的矩形去画，不再自己算坐标——
 * 坐标只有一个来源。
 *
 * <h2>按现行大师规则排布</h2>
 * 每边：怪兽区 5、魔陷区 5（灵摆已并入 0/4，不画独立灵摆列）、场地区 1、
 * 墓地 1、卡组 1、额外卡组 1；额外怪兽区 2 格位于中线中间列、双方共用。
 * 行序：怪兽行贴中线，魔陷行在外侧，手牌最外。
 * 格子数与灵摆的处理依据见 {@code DuelScreen} 类注释里引的内核行号。
 */
public record FieldLayout(int width, int height,
                          int cellW, int cellH, int handH,
                          int gapX, int gapY, int x0,
                          Rect oppHand, Rect oppSpellRow, Rect oppMonsterRow,
                          Rect extraMonsterRow,
                          Rect myMonsterRow, Rect mySpellRow, Rect myHand,
                          Rect panel) {

    /** 一行的列数：两端各一个侧格 + 中间 5 个区域格。 */
    public static final int ROW_COLS = 7;
    /** 主怪兽区 / 主魔陷区的格数（现行规则）。 */
    public static final int MAIN_ZONES = 5;
    /** 额外怪兽区格数，位于中线中间列，双方共用。 */
    public static final int EXTRA_MONSTER_ZONES = 2;

    public record Rect(int x, int y, int w, int h) {
        public int right() {
            return x + w;
        }

        public int bottom() {
            return y + h;
        }

        public boolean inside(int sw, int sh) {
            return x >= 0 && y >= 0 && right() <= sw && bottom() <= sh;
        }

        /** 是否与另一矩形有重叠面积。 */
        public boolean overlaps(Rect o) {
            return x < o.right() && o.x < right() && y < o.bottom() && o.y < bottom();
        }
    }

    /**
     * 由屏幕逻辑尺寸算出整张牌桌的矩形。
     *
     * <p>纵向要放下：对手手牌 + 对手魔陷行 + 对手怪兽行 + 额外怪兽区行
     * + 我方怪兽行 + 我方魔陷行 + 我方手牌，再加底部的提问面板。
     * 以「卡高」为单位就是 4 个卡行 + 0.8 个额外怪兽行 + 2×0.45 个手牌行 = 5.7，
     * 所以 {@code cellH = (可用高度 - 面板高 - 6 个行间距) / 5.7}。
     *
     * <p><b>那 6 个行间距必须显式减掉</b>：7 行之间有 6 个间隙，
     * 漏掉它们的表现就是牌桌整体往下溢出十来像素、压到提问面板上——
     * 这个错在 12 个屏幕尺寸里有 10 个会犯，靠眼睛看很难判断是「压住了」还是「贴得紧」。
     * {@code .agent/m3/…/LayoutCheck} 会断言这一条。
     *
     * <p>横向按 7 列算同样的卡高，取横竖两者中<b>更小</b>的那个——
     * 窄屏和矮屏因此都不会把格子挤出屏幕。
     */
    public static FieldLayout compute(int width, int height) {
        int margin = Math.max(2, Math.min(6, width / 60));
        int gapY = Math.max(1, Math.min(2, width / 240));
        // 底部只留一条细状态条。原先给了 height/5（最少 40px）——那是为了摆
        // 二十来个行动按钮的网格，等于把场地挤掉五分之一高。行动改成点卡之后
        // 这块地方不需要了，省下来的高度全部还给牌桌。
        int panelH = Math.max(22, Math.min(34, height / 14));
        // 7 行之间有 6 个间距，必须是 6：写成 5 的话会在 960x540 这类尺寸上
        // 差 1 像素压到面板上（floor 之后正好溢出）。
        int usableH = height - 2 * margin - panelH - 6 * gapY;
        // 单位是「一张卡的高度」。四行卡 + 中线额外怪兽区 + 两行手牌，
        // 手牌与场上卡【等高】——ygopro 的手牌本来就是和场上一样大的卡，
        // 不是缩小版图标。代价是场上卡小一点，靠悬停放大补回来。
        int byHeight = (int) (usableH / 6.8f);
        // 卡高先由高度定，宽度不参与——参与的话矮屏会把卡压扁。
        int cellH = Math.max(12, byHeight);
        int cellW = Math.max(8, (int) (cellH * (59f / 86f)));
        int handH = cellH;

        // 纵向定完再看横向：7 列铺满屏幕还剩很多宽度，就把它摊到列间距上，
        // 而不是让牌桌缩在中间一小块。ygo 客户端的怪兽区本来就是摊开的。
        // 上限一个卡宽，免得五格被拉成一排孤零零的小卡片。
        int want = width - 2 * margin;
        int gapX = gapY;
        if (ROW_COLS > 1) {
            int spare = want - ROW_COLS * cellW;
            gapX = Math.max(gapY, Math.min(cellW, spare / (ROW_COLS - 1)));
        }
        int rowW = ROW_COLS * cellW + (ROW_COLS - 1) * gapX;
        int x0 = Math.max(margin, (width - rowW) / 2);
        int panelTop = height - margin - panelH;

        int y = margin;
        Rect oppHand = new Rect(x0, y, rowW, handH);
        y += handH + gapY;
        Rect oppSpell = new Rect(x0, y, rowW, cellH);
        y += cellH + gapY;
        Rect oppMonster = new Rect(x0, y, rowW, cellH);
        y += cellH + gapY;
        int extraH = Math.max(8, (int) (cellH * 0.8f));
        Rect extra = new Rect(x0, y, rowW, extraH);
        y += extraH + gapY;
        Rect myMonster = new Rect(x0, y, rowW, cellH);
        y += cellH + gapY;
        Rect mySpell = new Rect(x0, y, rowW, cellH);
        y += cellH + gapY;
        Rect myHand = new Rect(x0, y, rowW, handH);
        Rect panel = new Rect(0, panelTop, width, height - panelTop);

        return new FieldLayout(width, height, cellW, cellH, handH, gapX, gapY, x0,
                oppHand, oppSpell, oppMonster, extra, myMonster, mySpell, myHand, panel);
    }

    /** 某一行里第 {@code col} 列的格子（0 = 左侧格，1..5 = 区域格，6 = 右侧格）。 */
    public Rect col(Rect band, int col) {
        return new Rect(x0 + col * (cellW + gapX), band.y(), cellW, band.h());
    }

    /**
     * 额外怪兽区第 {@code i} 格（0 = 左，1 = 右）。
     *
     * <p>位置在中线的中间列左右各偏半格——不归属任一方，
     * 因为它本来就由双方共用。
     */
    public Rect extraMonster(int i) {
        int cx = x0 + (ROW_COLS * (cellW + gapX)) / 2 - (cellW + gapX) / 2;
        int x = cx + (i == 0 ? -(cellW + gapX) / 2 : (cellW + gapX) / 2);
        return new Rect(x, extraMonsterRow.y(), cellW, extraMonsterRow.h());
    }

    /** 所有行矩形，按纵向顺序。 */
    public Rect[] bands() {
        return new Rect[]{oppHand, oppSpellRow, oppMonsterRow, extraMonsterRow,
                myMonsterRow, mySpellRow, myHand};
    }
}
