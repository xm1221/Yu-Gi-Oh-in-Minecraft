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
 * {@code DuelScreen} 只负责按这里的矩形去画，不再自己算坐标——坐标只有一个来源。
 *
 * <h2>左场地 + 右信息面板</h2>
 * 屏幕被分成两块：<b>左侧是场地</b>，<b>右侧是卡片信息面板</b>
 * （大卡图 + 卡名 + 数值 + 卡文）。这是用户明确要求的排布，
 * 也和 ygo 客户端一致的方向——ygopro 把悬停大卡图放在右侧
 * {@code ResizeCardHint(574,150)}（drawing.cpp:1009，基准 1024×640），
 * 只是它把卡文放在左侧标签页 {@code wInfos(1,275,301,639)}（game.cpp:375）
 * 或鼠标跟随的 tooltip 里。这里把两者合并到右侧面板，
 * 好处是「看卡」和「点卡」不会互相挡住：面板在场地之外，
 * 不再像以前的鼠标跟随提示那样盖住正在点的格子。
 *
 * <h2>按现行大师规则排布</h2>
 * 每边：怪兽区 5、魔陷区 5（灵摆已并入 szone 0/4，不画独立灵摆列）、场地区 1、
 * 墓地 1、卡组 1、额外卡组 1；额外怪兽区 2 格位于中线中间列、双方共用。
 * 行序：对手手牌在最上，往下依次是双方魔陷、怪兽，中线放额外怪兽区，我方在最下。
 *
 * <h2>手牌与场上卡【等高】</h2>
 * {@code handH = cellH}。手牌本来就是和场上一样大的卡，不是缩小版图标；
 * 代价是场上卡小一点，靠悬停放大补回来。这一条是用户连续两轮明确要求的，
 * {@code LayoutCheck} 里有对应断言钉死。
 *
 * <h2>横向要把牌桌铺开</h2>
 * 纵向空间通常先耗尽，于是卡高偏小、横向还剩一大片。这时把余量摊到<b>列间距</b>上，
 * 而不是让七列缩在屏幕中间一小块——ygo 客户端的怪兽区本来就是摊开的。
 * 间距上限是一个卡宽：再宽五格就成了一排孤零零的小卡片。
 *
 * <h2>底部不再留状态条</h2>
 * 原先底部有 {@code FOOTER_H=38} 的状态条（问题标题 + 提示 + 进度）。
 * 那些文字现在都进右侧信息面板，于是这 38 像素全部还给牌桌——
 * 纵向本来就是卡高的瓶颈，这是让场地变大最直接的一处。
 */
public record FieldLayout(int width, int height,
                          int cellW, int cellH, int handH,
                          int gapX, int gapY, int x0,
                          int fieldW, int panelW,
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

    /** 牌桌顶部给阶段条留的高度。 */
    private static final int HEADER_H = 24;
    /** 右侧信息面板占屏幕宽度的百分比。 */
    private static final int PANEL_PCT = 28;
    /** 面板宽度下限/上限：太窄放不下卡图，太宽会把场地挤瘦。 */
    private static final int PANEL_MIN = 128;
    private static final int PANEL_MAX = 360;
    /** 面板内大卡图最多占屏幕高度的多少。 */
    private static final int PANEL_ART_MAX_H_PCT = 45;

    /**
     * 场地底部状态条的高度：询问标题 + 操作提示两行。
     *
     * <p>这两样以前挤在右侧信息面板底部，占掉 58 像素——卡文本来就长，
     * 被顶掉之后只剩「显示不全」。搬到状态条之后右面板就只剩卡片信息。
     */
    private static final int STATUS_H = 24;

    public record Rect(int x, int y, int w, int h) {
        public int right() {
            return x + w;
        }

        public int bottom() {
            return y + h;
        }

        /** 点是否落在矩形里。命中测试与绘制共用，避免两边各写一套边界。 */
        public boolean contains(double mx, double my) {
            return mx >= x && mx < right() && my >= y && my < bottom();
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
     * + 我方怪兽行 + 我方魔陷行 + 我方手牌，共 <b>7 行</b>，再加顶部阶段条。
     * 7 行之间有 <b>6</b> 个行间距——漏掉它们的表现就是牌桌整体往下溢出十几像素，
     * 而 {@code floor} 之后往往只差 1 像素，靠眼睛看不出来。
     *
     * <p>横向按同样的列数算，取横竖两者中<b>更小</b>的那个卡高，
     * 窄屏和矮屏因此都不会把格子挤出屏幕。横向只在<b>场地那半边</b>里算，
     * 右侧面板是场地之外的地方——它不参与卡宽，也就不会把卡压小。
     */
    public static FieldLayout compute(int width, int height) {
        int panelW = Math.max(PANEL_MIN, Math.min(PANEL_MAX, width * PANEL_PCT / 100));
        // 面板不能把场地挤到没有：留出至少 7 张卡宽的下限。
        panelW = Math.min(panelW, Math.max(1, width - 120));
        int fieldW = Math.max(1, width - panelW);

        int margin = Math.max(2, Math.min(6, fieldW / 60));
        int gapY = Math.max(1, Math.min(2, fieldW / 240));

        // 7 行 + 6 个间距 + 顶部阶段条 + 上下留白，全部显式减掉。底部不再留状态条。
        int usableH = height - HEADER_H - STATUS_H - 2 * margin - 6 * gapY;
        // 横向约束：7 列 + 6 个最小间距也必须放得下，否则 gapX 被压到下限时就会溢出。
        int usableW = fieldW - 2 * margin - 6 * gapY;

        // 卡高先由高度定，宽度不参与——参与的话矮屏会把卡压扁。
        int cellH = Math.max(6, Math.min(usableH / 7, usableW / 7));
        // 卡面比例贴着真卡（200:290），否则卡图会被拉扁。
        int cellW = Math.max(4, cellH * 200 / 290);

        // 纵向定完再看横向：把余量摊到列间距上，上限一个卡宽。
        int want = fieldW - 2 * margin;
        int gapX = gapY;
        if (ROW_COLS > 1) {
            int spare = want - ROW_COLS * cellW;
            gapX = Math.max(gapY, Math.min(cellW, spare / (ROW_COLS - 1)));
        }
        int rowW = ROW_COLS * cellW + (ROW_COLS - 1) * gapX;
        int x0 = Math.max(margin, (fieldW - rowW) / 2);

        int y = HEADER_H + margin;
        Rect oppHand = new Rect(x0, y, rowW, cellH);
        y += cellH + gapY;
        Rect oppSpell = new Rect(x0, y, rowW, cellH);
        y += cellH + gapY;
        Rect oppMonster = new Rect(x0, y, rowW, cellH);
        y += cellH + gapY;
        // 中线行与牌行【等高】：额外怪兽区是同一张卡，矮一截会看起来像别的种类。
        Rect extra = new Rect(x0, y, rowW, cellH);
        y += cellH + gapY;
        Rect myMonster = new Rect(x0, y, rowW, cellH);
        y += cellH + gapY;
        Rect mySpell = new Rect(x0, y, rowW, cellH);
        y += cellH + gapY;
        Rect myHand = new Rect(x0, y, rowW, cellH);

        // 信息面板在场地右侧，占满整个高度。
        Rect panel = new Rect(fieldW, 0, panelW, height);

        return new FieldLayout(width, height, cellW, cellH, cellH, gapX, gapY, x0,
                fieldW, panelW,
                oppHand, oppSpell, oppMonster, extra, myMonster, mySpell, myHand, panel);
    }

    /**
     * 场地底部的状态条（整条场地的宽度，不含右侧面板）。
     *
     * <p>由 {@link #compute} 从可用高度里减掉，不覆盖任何一行卡——
     * 覆盖的话提示会压在手上那张牌上面。
     */
    public Rect status() {
        return new Rect(0, height() - STATUS_H, fieldW(), STATUS_H);
    }

    /**
     * 必发效果提示条：「内核自己发动的必发效果」那条一次性告知（见 {@code ChainNotice}）。
     *
     * <p>为什么不另开一行：牌桌纵向已经被 7 行加状态条占满（{@link #compute} 里
     * 显式把这些减掉了），再挤一行就要把卡压小——而这条提示是<b>一次性的</b>，
     * 玩家按一下「确认」就没了，不值得为它长期缩掉牌桌。所以它压在状态条上，
     * 取状态条的右端：左端永远是我方 LP 徽章，右端只有在窄屏上才让给对手 LP
     * （见 {@code DuelScreen.oppLpInStatusWidth}），那几秒的重叠由这里认下。
     *
     * <p>压在状态条上也就<b>不压任何一行卡</b>：手牌那一行的操作提示
     * （「召唤」「发动」）画在卡的下缘，被盖住就没法点着操作了。
     */
    public Rect notice() {
        int w = Math.max(40, Math.min(300, fieldW() - 8));
        int h = Math.max(12, STATUS_H - 6);
        return new Rect(Math.max(0, fieldW() - w - 4), height() - STATUS_H + 3, w, h);
    }

    /** 某一行里第 {@code col} 列的格子（0 = 左侧格，1..5 = 区域格，6 = 右侧格）。 */
    public Rect col(Rect band, int col) {
        return new Rect(x0 + col * (cellW + gapX), band.y(), cellW, band.h());
    }

    /**
     * 额外怪兽区第 {@code i} 格（0 = 左，1 = 右）。
     *
     * <p>位置在中线的中间列左右各偏半格——不归属任一方，因为它本来就由双方共用。
     * 依据 ygopro {@code materials.cpp:45-47,72-74}：我方 {@code mzone 5} 落在第 2 列，
     * 对方 {@code mzone 6} 与它同一格，所以双方看到的是同两个位置。
     */
    public Rect extraMonster(int i) {
        return col(extraMonsterRow, i == 0 ? 2 : 4);
    }

    /**
     * 顶部阶段条第 {@code i} 格的矩形（共 6 格：抽卡／准备／主要1／战斗／主要2／结束）。
     *
     * <p>阶段条是<b>常驻</b>的：它一直在那里，能点的就亮、不能点的就灰着，
     * 不弹窗、不催玩家。所以它的几何必须和牌桌一样只有这一个来源。
     *
     * <p>它横跨<b>场地那半边</b>居中，不跨到信息面板上——
     * 面板要显示正在被问的卡，被阶段条压住一块会很难看。
     */
    public Rect phase(int i) {
        // 左手要先给「时点略过」那颗键让位：窄窗下阶段条会被挤到最左缘，
        // 和那颗键叠在同一块地方。宽窗里阶段条仍在剩下的空间居中，看不出差别。
        int room = Math.max(1, fieldW - SKIP_BTN_W - 8);
        int w = Math.max(1, Math.min(64, room / 6));
        int total = 6 * w;
        int left = Math.max(SKIP_BTN_W + 4, (fieldW - total) / 2);
        return new Rect(left + i * w, 3, w - 2, HEADER_H - 8);
    }

    /** 左上角「时点略过」那颗键占的总宽度（含它与阶段条之间的间隔）。 */
    public static final int SKIP_BTN_W = 52;

    /**
     * 左上角「时点略过」键的矩形：和阶段条同一行，宽度固定
     * （三种模式的字都是四个汉字，宽一样，点一下换模式时键不会跳）。
     */
    public Rect skipButton() {
        return new Rect(4, 3, SKIP_BTN_W - 8, HEADER_H - 8);
    }

    /**
     * 信息面板里那张<b>大卡图</b>的矩形。
     *
     * <p>宽度先按面板内边距铺满，再受屏幕高度的比例上限约束——
     * 矮屏上如果按宽度铺满，卡图会高到把卡文挤出屏幕，
     * 于是「有卡图」把「有卡文」挤掉了，而那两样都是玩家要看的。
     */
    public Rect panelArt() {
        int maxW = Math.max(16, panelW - 20);
        int maxH = Math.max(20, height * PANEL_ART_MAX_H_PCT / 100);
        int w = maxW;
        int h = w * 290 / 200;
        if (h > maxH) {
            h = maxH;
            w = Math.max(16, h * 200 / 290);
        }
        return new Rect(panel.x() + (panelW - w) / 2, 6, w, h);
    }

    /** 面板里卡名/数值/卡文那个文字区的矩形。 */
    public Rect panelText() {
        Rect art = panelArt();
        int top = art.bottom() + 6;
        int pad = 6;
        return new Rect(panel.x() + pad, top, Math.max(8, panelW - 2 * pad),
                Math.max(8, height - top - pad));
    }

    /** 所有行矩形，按纵向顺序。 */
    public Rect[] bands() {
        return new Rect[]{oppHand, oppSpellRow, oppMonsterRow, extraMonsterRow,
                myMonsterRow, mySpellRow, myHand};
    }
}
