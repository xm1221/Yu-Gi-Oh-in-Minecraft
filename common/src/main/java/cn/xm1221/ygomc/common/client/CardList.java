package cn.xm1221.ygomc.common.client;

/**
 * 卡列表——对应 ygo 客户端的 {@code wCardSelect} 窗口。
 *
 * <p>用途：当询问是「从墓地/卡组/额外/除外这类区域的卡里选」时，那些卡在牌桌上
 * <b>没有各自的格子</b>（整个墓地就是一堆），只能靠列表列出卡名来选。
 * ygo 的做法见 {@code ClientField::ShowSelectCard()}（{@code client_field.cpp:431-527}）：
 * 弹出窗口 + 卡名列表 + 滚动条。
 *
 * <p>这里只放<b>纯几何与滚动</b>，不碰任何 Minecraft 类型：面板有多大、
 * 第几项画在哪一行、点到了第几项、滚动该停在哪。抽出来的理由和前面几次一样——
 * 这类纯逻辑写在界面里就只能靠肉眼保证，而它恰恰是「点错一张卡」这种
 * 不报错的错误的来源。
 */
public final class CardList {

    /** 一行的行高。字号是原版的 9，留 11 是为了行间有缝、一眼能分清行。 */
    public static final int ROW_H = 11;

    /** 表头（标题那条）的高度。 */
    public static final int TITLE_H = 14;

    /** 面板内四周的留白。 */
    public static final int PAD = 4;

    private final FieldLayout.Rect panel;
    private final int count;
    private int scroll;

    public CardList(FieldLayout.Rect panel, int count) {
        this.panel = panel;
        this.count = Math.max(0, count);
    }

    public FieldLayout.Rect panel() {
        return panel;
    }

    public int count() {
        return count;
    }

    /** 表头下面真正放行的区域。 */
    public FieldLayout.Rect body() {
        int y = panel.y() + TITLE_H;
        return new FieldLayout.Rect(panel.x() + PAD, y,
                Math.max(1, panel.w() - 2 * PAD),
                Math.max(1, panel.bottom() - PAD - y));
    }

    /** 一屏能放几行。至少给 1 行，否则面板再小也不该「一行都画不出来」。 */
    public int visibleRows() {
        return Math.max(1, body().h() / ROW_H);
    }

    public int maxScroll() {
        return Math.max(0, count - visibleRows());
    }

    public int scroll() {
        return scroll;
    }

    /** 滚动；自动夹在合法范围内（滚过头会让列表整片空白，看起来像「卡坏了」）。 */
    public void scrollBy(int delta) {
        scroll = clamp(scroll + delta, 0, maxScroll());
    }

    /** 滚动到刚好能看见第 {@code index} 项。 */
    public void scrollTo(int index) {
        if (index < 0 || index >= count) {
            return;
        }
        if (index < scroll) {
            scroll = index;
        } else if (index >= scroll + visibleRows()) {
            scroll = index - visibleRows() + 1;
        }
        scroll = clamp(scroll, 0, maxScroll());
    }

    public static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    /** 第 {@code index} 项的行矩形；不在可见范围内返回 {@code null}。 */
    public FieldLayout.Rect row(int index) {
        if (index < scroll || index >= scroll + visibleRows() || index >= count) {
            return null;
        }
        FieldLayout.Rect b = body();
        int y = b.y() + (index - scroll) * ROW_H;
        return new FieldLayout.Rect(b.x(), y, b.w(), Math.max(1, Math.min(ROW_H, b.bottom() - y)));
    }

    /** 命中了第几项；没命中返回 -1。行高固定，所以不需要逐行遍历。 */
    public int indexAt(double mx, double my) {
        if (!body().contains(mx, my)) {
            return -1;
        }
        int i = scroll + (int) ((my - body().y()) / ROW_H);
        return i >= 0 && i < count ? i : -1;
    }

    /** 是否需要画滚动条。 */
    public boolean scrollable() {
        return count > visibleRows();
    }
}
