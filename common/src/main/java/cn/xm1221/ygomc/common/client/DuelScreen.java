package cn.xm1221.ygomc.common.client;

import cn.xm1221.ygomc.common.duel.DuelBoard;
import cn.xm1221.ygomc.common.duel.DuelQuestion;
import cn.xm1221.ygomc.common.duel.DuelWire;
import cn.xm1221.ygomc.common.net.YgomcNet;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 对局界面：牌桌 + 当前询问。
 *
 * <h2>交互是「点牌桌」，不是「点按钮」</h2>
 * 选卡、选格子一律<b>直接点那张卡、那个格子</b>：左键选择/取消选择，
 * 右键确认（多选）或取消。这与 ygo 客户端和 YDM 一致——YDM 的对局界面
 * 除了聊天框几乎没有按钮，交互全在 {@code ZoneWidget} 上，
 * 点「动作源」再点「动作目标」，第二次点击本身就是确认。
 *
 * <p>只有在询问<b>没有空间落点</b>时才退化成列表：是/否、发动哪个效果、
 * 攻击还是守备、宣言种族属性。这些本来就没有卡可以点，官方客户端也是弹一个小窗。
 * 把它们做成按钮不是「多此一举」，而是它们确实没有别的地方可放。
 *
 * <h2>这个类不做决策</h2>
 * 它只把已经解码好的 {@link DuelQuestion} 摊成可点目标，把点击翻译成
 * {@link DuelQuestion#response(int...)}，再交给网络层发出去。
 * <b>所有规则判断都在 {@code response} 里</b>——它和内核的编码约定一起
 * 被 7855 条真实询问验过，界面这边再写一遍判断就等于绕开那套验证。
 *
 * <h2>坐标只有一个来源</h2>
 * 所有矩形来自 {@link FieldLayout}，落点映射来自 {@link DuelTargets}，
 * 这两个类都不依赖 Minecraft，因此可以脱离游戏断言（996 + 894 条）。
 * 坐标原先按 {@code height} 加减常数写死在 render 里，后果是魔陷行整行
 * 落到屏幕外、问题标题压在我方牌桌上——编译期看不出来，只能靠盯画面发现。
 *
 * <h2>本地玩家不一定在下面</h2>
 * 界面底部恒为「我」、顶部恒为「对手」，但座位号不由位置决定：
 * 我可能是 P0 也可能是 P1。原先无条件把 {@code player0} 画在下面，
 * 一旦本地玩家是 P1，自己的卡就会出现在对手那半边。这里按
 * {@link DuelQuestion#player()} 定座位，再取对应的一方来画。
 *
 * <h2>格子数按【现行大师规则】</h2>
 * 怪兽区 5、魔陷区 5（<b>灵摆区已并入 szone 0/4</b>，见
 * {@code field.cpp:499-512} 的 {@code get_pzone_sequence}）、场地区 1
 * （{@code szone 5}，{@code field.cpp:564}）、额外怪兽区 2（{@code mzone 5,6}，
 * 中线中间列、<b>双方共用</b>）。内核魔陷槽数组长 8（{@code ocgapi.cpp:91-92}），
 * 但 szone 6/7 是旧规则灵摆余位，<b>不画</b>。
 * 只有行序与左右相对位置参考 YDM（GPLv3，{@code Copyright (C) CAS_ual_TY}）
 * 的 {@code duel/playfield/PlayFieldTypes.java}；它的独立灵摆列不符合现行规则，未照抄。
 */
public class DuelScreen extends net.minecraft.client.gui.screens.Screen {

    private DuelBoard board;
    private DuelQuestion question;
    /** 多选/选址类里已勾选的选项下标。 */
    private final Set<Integer> chosen = new LinkedHashSet<>();
    private Button confirm;
    /** 已提交、在等下个询问。这期间界面留着但按钮全灭。 */
    private boolean submitted;
    /** 本地玩家座位。询问到达时更新；没询问时沿用上一次。 */
    private int mySeat;

    /**
     * 光标处的行动菜单：里面是选项下标。
     *
     * <p>点一张卡而这张卡当前有<b>多个</b>可做的行动时才弹（例如手牌既能通常召唤
     * 又能盖放）。这就是 ygo 的做法——行动挂在卡上，玩家点卡，由界面列出这张卡
     * 现在能做什么；而不是把整个回合的所有行动倒成一片按钮。
     */
    private final List<Integer> menu = new ArrayList<>();
    private int menuX;
    private int menuY;

    public DuelScreen(DuelBoard board, DuelQuestion question) {
        super(Component.literal("决斗"));
        this.board = board;
        this.question = question;
        if (question != null) {
            this.mySeat = question.player();
        }
    }

    /** 服务器推来新状态时调用。 */
    public void update(DuelBoard board, DuelQuestion question) {
        boolean different = this.question != question;
        if (question != null) {
            this.mySeat = question.player();
        }
        this.board = board;
        this.question = question;
        if (different) {
            chosen.clear();
            menu.clear();
            submitted = false;
            rebuild();
        }
    }

    private FieldLayout field() {
        return FieldLayout.compute(width, height);
    }

    private DuelBoard.PlayerBoard me() {
        return board == null ? null : board.playerAt(mySeat);
    }

    private DuelBoard.PlayerBoard opponent() {
        return board == null ? null : board.playerAt(1 - mySeat);
    }

    /** 当前询问在牌桌上的落点。 */
    private List<DuelTargets.Target> targets() {
        return DuelTargets.of(question, field(), mySeat);
    }

    /** 只有没有落点的询问才退化成列表。 */
    private boolean spatial() {
        return !targets().isEmpty();
    }

    @Override
    protected void init() {
        rebuild();
    }

    /**
     * 只给「没有落点」的询问生成按钮，而且是居中的<b>小窗</b>，不是铺满底部的网格。
     *
     * <p>行动类询问（这一回合能做什么、这只怪能做什么）现在全部挂到卡上，
     * 所以它们一条按钮都不会生成——那片二十来项的网格因此彻底消失。
     * 这里剩下的只有本来就无处可挂的：是/否、发动哪个效果、攻击还是守备、宣言种族属性。
     * 这些连官方客户端也是弹小窗，因为确实没有卡可以点。
     */
    private void rebuild() {
        clearWidgets();
        confirm = null;
        if (question == null || submitted || spatial()) {
            return;
        }
        List<DuelQuestion.Option> options = question.options();
        if (options.isEmpty()) {
            return;
        }
        FieldLayout L = field();
        int gap = 2;
        int bh = 16;
        int bw = 60;
        for (DuelQuestion.Option o : options) {
            bw = Math.max(bw, font.width(shortLabel(o)) + 18);
        }
        bw = Math.min(bw, Math.max(60, width / 2));
        int n = options.size();
        // 项数多（宣言种族/属性）就折成两列，别顶到屏幕上边
        int cols = n * (bh + gap) > height - 70 ? 2 : 1;
        int rows = (n + cols - 1) / cols;
        int totalH = rows * (bh + gap);
        int x0 = (width - (cols * bw + (cols - 1) * gap)) / 2;
        int y0 = Math.max(4, L.panel().y() - totalH - 8);

        for (int i = 0; i < n; i++) {
            final int index = i;
            int x = x0 + (i % cols) * (bw + gap);
            int y = y0 + (i / cols) * (bh + gap);
            addRenderableWidget(Button.builder(Component.literal(shortLabel(options.get(i))),
                    b -> onOption(index)).bounds(x, y, bw, bh).build());
        }
    }

    /** 小窗里的按钮文字：有多余位置就把卡名带上，「发动」单看不知道发动哪张。 */
    private String shortLabel(DuelQuestion.Option o) {
        String s = o.label();
        if ((o.cardCode() & 0x7fffffff) != 0) {
            s = s + " " + optionName(o);
        }
        return s.length() > 28 ? s.substring(0, 27) + "…" : s;
    }

    private boolean needsConfirm() {
        if (question == null) {
            return false;
        }
        DuelQuestion.Mode m = question.mode();
        return m == DuelQuestion.Mode.MULTI || m == DuelQuestion.Mode.PLACES
                || m == DuelQuestion.Mode.COUNTERS || m == DuelQuestion.Mode.SORT;
    }

    /**
     * 鼠标点击。
     *
     * <p>左键：点到的卡/格上若只有一个行动就立刻做；有多个就在光标处弹菜单让玩家挑。
     * 落在空白处且已经选够数则确认——「点外面的空地」是最自然的确认手势。
     * <p>右键：确认（够数时），否则取消。
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (question == null || submitted || board == null) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        // 菜单开着的时候，点击只作用于菜单
        if (!menu.isEmpty()) {
            int picked = menuHit(mouseX, mouseY);
            menu.clear();
            if (picked >= 0) {
                onOption(picked);
            }
            return true;
        }
        List<DuelTargets.Target> targets = targets();
        List<Integer> hits = DuelTargets.optionIndicesAt(targets, mouseX, mouseY);
        if (!hits.isEmpty()) {
            if (hits.size() == 1) {
                onOption(hits.get(0));
            } else {
                // 同一张卡有多个可做的行动 → 光标处弹菜单，这是 ygo 的做法
                menu.clear();
                menu.addAll(hits);
                menuX = (int) mouseX;
                menuY = (int) mouseY;
            }
            return true;
        }
        if (!spatial()) {
            // 无落点的询问（是/否、发动哪个效果、宣言种族属性）交给小窗按钮处理
            return super.mouseClicked(mouseX, mouseY, button);
        }
        if (button == 0) {
            if (needsConfirm() && countsOk()) {
                submit();
            }
            return true;
        }
        if (button == 1) {
            if (needsConfirm() && countsOk() && !chosen.isEmpty()) {
                submit();
            } else {
                cancel();
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 菜单每行的宽度（由最长的行动名决定）。 */
    private int menuWidth() {
        int w = 60;
        for (int idx : menu) {
            if (question != null && idx < question.options().size()) {
                w = Math.max(w, font.width(shortLabel(question.options().get(idx))) + 14);
            }
        }
        return Math.min(w, Math.max(60, width - 8));
    }

    /** 命中菜单第几行；没命中返回 -1。几何与 {@link #drawMenu} 用的是同一份。 */
    private int menuHit(double mx, double my) {
        int w = menuWidth();
        int x = Math.min(Math.max(2, menuX), Math.max(2, width - w - 2));
        int y = Math.min(Math.max(2, menuY), Math.max(2, height - menu.size()
                * (DuelTargets.MENU_ROW_H + DuelTargets.MENU_ROW_GAP) - 2));
        for (int i = 0; i < menu.size(); i++) {
            FieldLayout.Rect r = DuelTargets.menuRow(x, y, w, i);
            if (mx >= r.x() && mx < r.right() && my >= r.y() && my < r.bottom()) {
                return menu.get(i);
            }
        }
        return -1;
    }

    private boolean countsOk() {
        if (question == null) {
            return false;
        }
        int n = chosen.size();
        return n >= question.min() && (question.max() <= 0 || n <= question.max());
    }

    private void onOption(int index) {
        DuelQuestion q = question;
        if (q == null || submitted) {
            return;
        }
        if (!needsConfirm()) {
            send(q, new int[]{index});
            return;
        }
        if (!chosen.remove(index)) {
            chosen.add(index);
        }
        if (confirm != null) {
            confirm.active = countsOk();
        }
    }

    private void submit() {
        DuelQuestion q = question;
        if (q == null || submitted) {
            return;
        }
        List<Integer> list = new ArrayList<>(chosen);
        int[] picked = new int[list.size()];
        for (int i = 0; i < picked.length; i++) {
            picked[i] = list.get(i);
        }
        send(q, picked);
    }

    /**
     * 「取消」对应的<b>选项下标</b>，没有则 -1。
     *
     * <p>原先直接把字面量 {@code -1} 当选项下标传下去。取消在编码上不是
     * 「下标 -1」，而是「某个取值为 -1 的选项的下标」——传错的下场实测是
     * 「这个操作发不出去：构造应答失败：要选 0 个格子，实得 1」。
     */
    private int cancelIndex() {
        if (question == null) {
            return -1;
        }
        List<DuelQuestion.Option> options = question.options();
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).isCancel()) {
                return i;
            }
        }
        return -1;
    }

    private void cancel() {
        int ci = cancelIndex();
        if (ci >= 0) {
            send(question, new int[]{ci});
            return;
        }
        // 不能取消：右键就没有出口，什么都没发生。这里刻意不静默发一个
        // 必然被打回的应答——那会触发内核重发，最后以 RETRY_STORM_LIMIT 收场。
        chosen.clear();
    }

    /**
     * 唯一的出口：问 {@code response} 要字节，再交给网络层。
     *
     * <p>刻意<b>不</b>自己拼字节——每种询问的编码约定都不一样
     * （有下标、有取值、有 3 字节坐标、有计数器数组），
     * 在这里重写一遍就是给自己造第二个编码器。
     */
    private void send(DuelQuestion q, int... picked) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        cn.xm1221.ygomc.common.ocg.Responder.Response r;
        try {
            r = q.response(picked);
        } catch (RuntimeException e) {
            // 构造失败要说出来。静默什么都不发，症状就是「点了没反应」，
            // 和网络不通长得一模一样。
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal("这个操作发不出去：" + e.getMessage()), false);
            }
            return;
        }
        YgomcNet.sendAnswer(
                DuelWire.encodeAnswer(r.isBytes()
                        ? DuelWire.Responder2.of(r.bytes())
                        : DuelWire.Responder2.of(r.value())),
                mc.level == null ? null : mc.level.registryAccess());
        submitted = true;
        rebuild();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * 铺一层半透明底，<b>不调 super</b>。
     *
     * <p>1.20.5 起 {@code Screen.renderBackground} 会给整个屏幕加一层模糊后处理。
     * 牌桌要看的是卡面，模糊在这里是纯反效果，还会把格子边缘糊掉，
     * 看起来像「渲染坏了」。
     */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 不再压成近乎全黑：牌桌本身有牌垫，底色只需要把世界背景压下去一点。
        g.fill(0, 0, width, height, 0xC4182028);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        FieldLayout L = field();
        if (board == null) {
            g.drawString(font, "还没有牌桌数据", L.x0(), L.panel().y() - 14, 0xFFFFFF);
        } else {
            drawField(g, L);
        }
        drawOverlay(g, L, mouseX, mouseY);
        super.render(g, mouseX, mouseY, partialTick);
    }

    /**
     * 画整张场地。纵向从对手到我是：
     * <pre>
     *   对手手牌（卡背）
     *   对手魔陷行   [额外卡组] [S/T×5] [卡组]
     *   对手怪兽行   [场地区]   [M×5]   [墓地]
     *   额外怪兽区（中线，双方共用）＋ 双方除外区
     *   我方怪兽行   [场地区]   [M×5]   [墓地]
     *   我方魔陷行   [额外卡组] [S/T×5] [卡组]
     *   我方手牌
     * </pre>
     */
    private void drawField(GuiGraphics g, FieldLayout L) {
        // 牌垫：把整张场地垫在一层布面上，而不是让格子直接浮在暗背景上。
        // 格子本身是半透明的，没有垫子就会和世界背景糊在一起，边界看不出来。
        FieldLayout.Rect[] bands = L.bands();
        int left = L.x0() - 4;
        int right = L.x0() + bands[0].w() + 4;
        int top = bands[0].y() - 4;
        int bottom = bands[bands.length - 1].bottom() + 4;
        g.fill(left, top, right, bottom, 0xFF1B3A2C);
        outline(g, new FieldLayout.Rect(left, top, right - left, bottom - top), 0xFF35735A);
        // 中线：分隔双方场地，也让额外怪兽区看起来是「两边共用」
        int mid = L.extraMonsterRow().y() + L.extraMonsterRow().h() / 2;
        g.fill(left + 2, mid, right - 2, mid + 1, 0x60357A5A);

        graphicHand(g, L, opponent(), L.oppHand(), true);
        spellRow(g, L, opponent(), L.oppSpellRow());
        monsterRow(g, L, opponent(), L.oppMonsterRow());
        extraMonsterZones(g, L, opponent());
        removedPiles(g, L);
        monsterRow(g, L, me(), L.myMonsterRow());
        spellRow(g, L, me(), L.mySpellRow());
        graphicHand(g, L, me(), L.myHand(), false);
    }

    private void monsterRow(GuiGraphics g, FieldLayout L, DuelBoard.PlayerBoard p,
                            FieldLayout.Rect band) {
        if (p == null) {
            return;
        }
        List<DuelBoard.Zone> zones = p.monsterZones();
        pile(g, L, "场地", L.col(band, 0), 0xFF24485C);
        for (int i = 0; i < FieldLayout.MAIN_ZONES; i++) {
            zone(g, L, at(zones, i), L.col(band, 1 + i), 0x33FFFFFF, 0x66FFFFFF, true);
        }
        pile(g, L, "墓地 " + p.graveCount(), L.col(band, 6), 0xFF24485C);
    }

    private void spellRow(GuiGraphics g, FieldLayout L, DuelBoard.PlayerBoard p,
                          FieldLayout.Rect band) {
        if (p == null) {
            return;
        }
        List<DuelBoard.Zone> zones = p.spellZones();
        pile(g, L, "额外 " + p.extraCount(), L.col(band, 0), 0xFF24485C);
        for (int i = 0; i < FieldLayout.MAIN_ZONES; i++) {
            zone(g, L, at(zones, i), L.col(band, 1 + i), 0x33DFFFD8, 0x66DFFFD8, false);
        }
        pile(g, L, "卡组 " + p.deckCount(), L.col(band, 6), 0xFF24485C);
    }

    /**
     * 额外怪兽区：中线中间列 2 格。
     *
     * <p>双方共用，所以不按归属方取——哪一方占了就画谁的。
     */
    private void extraMonsterZones(GuiGraphics g, FieldLayout L, DuelBoard.PlayerBoard first) {
        for (int i = 0; i < FieldLayout.EXTRA_MONSTER_ZONES; i++) {
            FieldLayout.Rect r = L.extraMonster(i);
            // 额外怪兽区是【双方共用】的，哪一方占了就画谁的：只读一方的
            // mzone 5/6 会漏掉对手摆在那里的怪，看起来就像卡图没画出来。
            DuelBoard.Zone z = null;
            for (DuelBoard.PlayerBoard pb : new DuelBoard.PlayerBoard[]{first, me(), opponent()}) {
                if (pb == null) {
                    continue;
                }
                DuelBoard.Zone c = at(pb.monsterZones(), FieldLayout.MAIN_ZONES + i);
                if (c != null && c.occupied()) {
                    z = c;
                    break;
                }
            }
            if (z != null) {
                cardFace(g, L, z, r, true);
            } else {
                g.fill(r.x(), r.y(), r.right(), r.bottom(), 0x33C8A0E8);
                outline(g, r, 0x80C8A0E8);
                g.drawString(font, "EX", r.x() + 2, r.y() + 1, 0xB0E0C8FF);
            }
        }
    }

    /** 双方除外区：摆在中线行的两端（中线行只有中间两格有内容，两端是空的）。 */
    private void removedPiles(GuiGraphics g, FieldLayout L) {
        FieldLayout.Rect band = L.extraMonsterRow();
        DuelBoard.PlayerBoard me = me();
        DuelBoard.PlayerBoard op = opponent();
        if (op != null) {
            pile(g, L, "除外 " + op.removedCount(), L.col(band, 0), 0xFF3A4256);
        }
        if (me != null) {
            pile(g, L, "除外 " + me.removedCount(), L.col(band, 6), 0xFF3A4256);
        }
    }

    private void zone(GuiGraphics g, FieldLayout L, DuelBoard.Zone z,
                      FieldLayout.Rect r, int emptyFill, int emptyBorder, boolean monster) {
        if (z == null || !z.occupied()) {
            // 空格子画成半透明的「槽」而不是实心暗块：实心块在牌垫上看着像
            // 已经有卡了，会让人以为格子被占着。
            g.fill(r.x(), r.y(), r.right(), r.bottom(), emptyFill);
            outline(g, r, emptyBorder);
            return;
        }
        cardFace(g, L, z, r, monster);
    }

    /**
     * 画一张场上的卡。
     *
     * <p>卡号来自 {@code DuelBoard.Zone.code()}（由 {@code Ocg.queryFieldCard}
     * 按可见性过滤后填好）。卡号为 0 有两种情形，都不该画卡面：
     * 里侧盖牌，以及对手的隐藏卡——后者连卡号都没有，所以不可能泄出去。
     *
     * <p>守备表示<b>横放</b>（旋转 90°），与 ygo 客户端一致：
     * 光在卡面上压一条横杠看不出是「守备」还是「这张卡长这样」。
     */
    private void cardFace(GuiGraphics g, FieldLayout L, DuelBoard.Zone z, FieldLayout.Rect r,
                          boolean monster) {
        int code = z.code() & 0x7fffffff;
        if (monster && !z.attack()) {
            rotated(g, code, r);
        } else if (z.faceUp() && code != 0) {
            CardArt.draw(g, code, r.x(), r.y(), r.w(), r.h(), null);
            outline(g, r, 0xFFFFFFFF);
        } else if (z.faceUp()) {
            // 表侧但卡号未知（旧线格式的帧）：色块 + 边框，至少能看出表示形式
            g.fill(r.x(), r.y(), r.right(), r.bottom(), 0xFF3A70C0);
            outline(g, r, 0xFFFFFFFF);
        } else {
            CardArt.drawBack(g, r.x(), r.y(), r.w(), r.h());
        }
        if (z.overlayCount() > 0) {
            g.drawString(font, "◆" + z.overlayCount(), r.x() + 1, r.bottom() - 10, 0xFFD060);
        }
    }

    /** 横放（守备表示）：绕格子中心转 90°，卡按「宽=格高、高=格宽」画。 */
    private void rotated(GuiGraphics g, int code, FieldLayout.Rect r) {
        int cw = r.h();
        int ch = r.w();
        g.pose().pushPose();
        g.pose().translate(r.x() + r.w() / 2f, r.y() + r.h() / 2f, 0f);
        g.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(90f));
        if (code != 0) {
            CardArt.draw(g, code, -cw / 2, -ch / 2, cw, ch, null);
        } else {
            CardArt.drawBack(g, -cw / 2, -ch / 2, cw, ch);
        }
        g.pose().popPose();
        outline(g, r, 0xFFFFFFFF);
    }

    /**
     * 手牌行。
     *
     * <p>我方手牌是<b>明牌</b>、对手手牌是卡背。卡号来自
     * {@code PlayerBoard.hand()}：本地座位每张都是真卡号，对手的每张都是 0
     * ——对手手牌卡号在内核里<b>根本没被查出来</b>（那一整块用
     * {@code FLAG_HIDDEN} 只问表示形式），所以这里即使写错也泄不出去。
     *
     * <p>旧线格式（v1）的帧里这个列表是空的，此时退回按 {@code handCount()}
     * 画卡背，而不是当作「没有手牌」——那会让人以为手牌丢了。
     */
    private void graphicHand(GuiGraphics g, FieldLayout L, DuelBoard.PlayerBoard p,
                             FieldLayout.Rect band, boolean opponent) {
        if (p == null) {
            return;
        }
        int ty = band.y() + Math.max(0, (band.h() - 8) / 2);
        g.drawString(font, (opponent ? "对手" : "我方") + " 手牌 " + p.handCount(),
                band.x(), ty, opponent ? 0xFFB0B0B0 : 0xFFFFFFFF);
        int w = Math.max(6, (int) (band.h() / (86f / 59f)));
        int room = Math.max(1, (band.w() - 78) / (w + 1));
        List<DuelBoard.Zone> hand = p.hand();
        int n = hand.isEmpty() ? p.handCount() : hand.size();
        for (int i = 0; i < n && i < room; i++) {
            FieldLayout.Rect r = DuelTargets.handCard(L, band, i);
            // 对手手牌恒为卡背：连卡号都不取
            int code = opponent || i >= hand.size() ? 0 : hand.get(i).code() & 0x7fffffff;
            if (code != 0) {
                CardArt.draw(g, code, r.x(), r.y(), r.w(), r.h(), null);
            } else {
                CardArt.drawBack(g, r.x(), r.y(), r.w(), r.h());
            }
        }
        if (n > room) {
            g.drawString(font, "+" + (n - room), band.x() + 78 + room * (w + 1) + 2, ty, 0xFFD0D0D0);
        }
    }

    /** 侧格（卡组/额外/墓地/场地/除外）：一个框 + 一行字。 */
    private void pile(GuiGraphics g, FieldLayout L, String label, FieldLayout.Rect r, int fill) {
        g.fill(r.x(), r.y(), r.right(), r.bottom(), fill);
        outline(g, r, 0x70FFFFFF);
        if (r.w() >= 34) {
            g.drawString(font, label, r.x() + 2, r.y() + 2, 0xE0FFFFFF);
        }
    }

    private void outline(GuiGraphics g, FieldLayout.Rect r, int color) {
        g.fill(r.x(), r.y(), r.right(), r.y() + 1, color);
        g.fill(r.x(), r.bottom() - 1, r.right(), r.bottom(), color);
        g.fill(r.x(), r.y(), r.x() + 1, r.bottom(), color);
        g.fill(r.right() - 1, r.y(), r.right(), r.bottom(), color);
    }

    private static DuelBoard.Zone at(List<DuelBoard.Zone> zones, int i) {
        return i >= 0 && i < zones.size() ? zones.get(i) : null;
    }

    /**
     * 高亮、悬停提示与状态行。
     *
     * <p>可点目标要<b>看得出来可以点</b>：不然玩家不知道该点哪张卡，
     * 只会去屏幕上找按钮——而按钮已经没有了。
     */
    private void drawOverlay(GuiGraphics g, FieldLayout L, int mouseX, int mouseY) {
        if (question != null && !submitted && board != null) {
            List<DuelTargets.Target> targets = targets();
            for (DuelTargets.Target t : targets) {
                boolean picked = chosen.contains(t.optionIndex());
                FieldLayout.Rect r = t.rect();
                if (picked) {
                    // 已选：亮黄框 + 压暗一层
                    g.fill(r.x(), r.y(), r.right(), r.bottom(), 0x60FFE060);
                    outline(g, r, 0xFFFFE060);
                } else {
                    outline(g, r, 0x70A0FFA0);
                }
            }
            int hov = DuelTargets.hit(targets, mouseX, mouseY);
            if (hov >= 0) {
                FieldLayout.Rect r = targets.get(hov).rect();
                outline(g, r, 0xFFFFFFFF);
                String name = optionName(targets.get(hov).option());
                g.drawString(font, name, Math.min(mouseX + 8, width - font.width(name) - 4),
                        Math.max(2, mouseY - 10), 0xFFFFFF);
            }
        }
        drawMenu(g);
        drawPanel(g, L);
    }

    /**
     * 行动菜单：在光标处列出这张卡现在能做的事。
     *
     * <p>几何与 {@link #menuHit} 共用 {@code DuelTargets.menuRow}——
     * 渲染和命中各写一套坐标，症状就是「看得见但点不中」，而那和
     * 「这个操作不合法」在界面上长得一模一样。
     */
    private void drawMenu(GuiGraphics g) {
        if (menu.isEmpty() || question == null) {
            return;
        }
        int w = menuWidth();
        int x = Math.min(Math.max(2, menuX), Math.max(2, width - w - 2));
        int rowH = DuelTargets.MENU_ROW_H + DuelTargets.MENU_ROW_GAP;
        int y = Math.min(Math.max(2, menuY), Math.max(2, height - menu.size() * rowH - 2));
        int totalH = menu.size() * rowH - DuelTargets.MENU_ROW_GAP;
        g.fill(x - 2, y - 2, x + w + 2, y + totalH + 2, 0xF0141A22);
        outline(g, new FieldLayout.Rect(x - 2, y - 2, w + 4, totalH + 4), 0xFF8A9AC0);
        List<DuelQuestion.Option> options = question.options();
        for (int i = 0; i < menu.size(); i++) {
            FieldLayout.Rect r = DuelTargets.menuRow(x, y, w, i);
            int idx = menu.get(i);
            String label = idx < options.size() ? shortLabel(options.get(idx)) : "?";
            g.drawString(font, label, r.x() + 4, r.y() + 3, 0xFFFFFFFF);
        }
    }

    /** 卡名：选项的 label 对卡牌项只是占位，卡名要用卡号去查。 */
    private String optionName(DuelQuestion.Option o) {
        int code = o.cardCode() & 0x7fffffff;
        if (code != 0) {
            var pack = cn.xm1221.ygomc.common.data.DataPacks.get();
            String name = pack == null ? null : pack.nameOf(code);
            if (name != null && !name.isEmpty()) {
                return name;
            }
            return "#" + code;
        }
        return o.label();
    }

    private void drawPanel(GuiGraphics g, FieldLayout L) {
        FieldLayout.Rect p = L.panel();
        g.fill(p.x(), p.y(), p.right(), p.bottom(), 0xE0101018);
        g.fill(p.x(), p.y(), p.right(), p.y() + 1, 0xFF505060);

        String title = question == null ? "等待服务器…"
                : (submitted ? "已提交，等待对手…" : question.title());
        g.drawString(font, title, p.x() + 4, p.y() + 4, 0xFFFF80);
        if (question == null || submitted || board == null) {
            return;
        }

        String hint;
        if (spatial()) {
            hint = needsConfirm()
                    ? "左键选卡/选格　右键确认" + (cancelIndex() >= 0 ? "　右键空地取消" : "")
                    : "点一下即可";
        } else {
            hint = "选择一项";
        }
        String lps = "我方 LP " + me().lp() + "　对手 LP " + opponent().lp();
        String counts = needsConfirm()
                ? "已选 " + chosen.size() + "/" + question.min()
                        + (question.max() > 0 ? "~" + question.max() : "+")
                : "";
        g.drawString(font, hint, p.x() + 4, p.bottom() - 26, 0xA0E0A0);
        g.drawString(font, (counts.isEmpty() ? "" : counts + "　") + lps,
                p.x() + 4, p.bottom() - 14, 0xB0B0B0);
    }
}
