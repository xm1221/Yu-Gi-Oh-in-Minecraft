package cn.xm1221.ygomc.common.client;

import cn.xm1221.ygomc.common.duel.DuelBoard;
import cn.xm1221.ygomc.common.duel.DuelQuestion;
import cn.xm1221.ygomc.common.net.YgomcNet;
import cn.xm1221.ygomc.common.duel.DuelWire;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 对局界面：牌桌 + 当前问题。
 *
 * <h2>这个类不做决策</h2>
 * 它只把已经解码好的 {@link DuelQuestion} 摊成按钮，把点击翻译成
 * {@link DuelQuestion#response(int...)}，再交给网络层发出去。
 * <b>所有规则判断都在 {@code response} 里</b>——它和内核的编码约定一起
 * 被 7855 条真实询问验过，界面这边再写一遍判断就等于绕开那套验证。
 *
 * <h2>为什么放 common</h2>
 * 与 {@link CardBrowserScreen} 同理：它继承 {@code Screen}，是客户端专属类，
 * 所以<b>只能</b>被平台客户端入口引用。专用服务器不会加载到它，
 * 也就不会在类加载阶段抛 {@code NoClassDefFoundError}。
 * 关键是从公共代码到它的引用<b>不能进入服务端调用链</b>，
 * 这一点由两个平台入口各自把握。
 */
public class DuelScreen extends net.minecraft.client.gui.screens.Screen {

    private DuelBoard board;
    private DuelQuestion question;
    /** 多选类问题里已勾选的选项；单选类问题不用它。 */
    private final Set<Integer> chosen = new LinkedHashSet<>();
    private Button confirm;

    public DuelScreen(DuelBoard board, DuelQuestion question) {
        super(Component.literal("决斗"));
        this.board = board;
        this.question = question;
    }

    /**
     * 服务器推来新状态时调用。
     *
     * <p>问题换了就必须清空勾选：新问题的选项下标是另一套含义，
     * 留着旧的会直接答错卡。屏不复用同一个问题对象，所以按对象身份判断即可。
     */
    public void update(DuelBoard board, DuelQuestion question) {
        boolean different = this.question != question;
        this.board = board;
        this.question = question;
        if (different) {
            chosen.clear();
            rebuild();
        }
    }

    @Override
    protected void init() {
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        confirm = null;
        if (question == null) {
            return;
        }
        List<DuelQuestion.Option> options = question.options();
        // 按钮排成网格：卡牌类问题一次能给二十多个选项，单行摆不下。
        int cols = 4;
        int bw = Math.min(150, (width - 40) / cols - 4);
        int bh = 18;
        int rows = (options.size() + cols - 1) / cols;
        int gridH = rows * (bh + 3);
        int top = Math.max(30, height - 30 - gridH - (needsConfirm() ? 24 : 0));

        for (int i = 0; i < options.size(); i++) {
            DuelQuestion.Option o = options.get(i);
            int col = i % cols;
            int row = i / cols;
            int x = width / 2 - (cols * (bw + 4)) / 2 + col * (bw + 4);
            int y = top + row * (bh + 3);
            final int index = i;
            String label = o.label();
            if (label.length() > 22) {
                label = label.substring(0, 21) + "…";
            }
            addRenderableWidget(Button.builder(Component.literal(label), b -> onOption(index))
                    .bounds(x, y, bw, bh).build());
        }

        if (needsConfirm()) {
            confirm = Button.builder(Component.literal("确定"), b -> submit())
                    .bounds(width / 2 + 2, height - 26, 100, 20).build();
            addRenderableWidget(confirm);
            addRenderableWidget(Button.builder(Component.literal("取消"), b -> cancel())
                    .bounds(width / 2 - 102, height - 26, 100, 20).build());
        }
    }

    /** 单选类问题点一下就算答完；多选类要点「确定」。 */
    private boolean needsConfirm() {
        if (question == null) {
            return false;
        }
        DuelQuestion.Mode m = question.mode();
        return m == DuelQuestion.Mode.MULTI || m == DuelQuestion.Mode.PLACES
                || m == DuelQuestion.Mode.COUNTERS || m == DuelQuestion.Mode.SORT;
    }

    private void onOption(int index) {
        DuelQuestion q = question;
        if (q == null) {
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
            // 只在数量落在 [min, max] 内时才让点「确定」——放行一个注定被引擎
            // 打回的应答，表现是「卡住」，比按钮不可点更难查。
            int n = chosen.size();
            boolean enough = n >= q.min() && (q.max() <= 0 || n <= q.max());
            confirm.active = enough;
        }
    }

    private void cancel() {
        DuelQuestion q = question;
        if (q != null) {
            send(q, new int[]{-1});
        }
    }

    private void submit() {
        DuelQuestion q = question;
        if (q == null) {
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
     * 唯一的出口：问 {@code response} 要字节，再交给网络层。
     *
     * <p>刻意<b>不</b>自己拼字节——每一种询问的编码约定都不一样
     * （有下标、有取值、有 3 字节坐标、有计数器数组），
     * 在这里重写一遍就是给自己造第二个编码器。
     */
    private void send(DuelQuestion q, int... picked) {
        cn.xm1221.ygomc.common.ocg.Responder.Response r;
        try {
            r = q.response(picked);
        } catch (RuntimeException e) {
            // 构造失败要说出来。静默什么都不发，症状就是「点了没反应」，
            // 和网络不通长得一模一样。
            if (net.minecraft.client.Minecraft.getInstance().player != null) {
                net.minecraft.client.Minecraft.getInstance().player.displayClientMessage(
                        Component.literal("这个操作发不出去：" + e.getMessage()), false);
            }
            return;
        }
        YgomcNet.sendAnswer(DuelWire.encodeAnswer(
                r.isBytes() ? DuelWire.Responder2.of(r.bytes()) : DuelWire.Responder2.of(r.value())),
                net.minecraft.client.Minecraft.getInstance().level == null ? null
                        : net.minecraft.client.Minecraft.getInstance().level.registryAccess());
        // 答完就关掉界面：接下来的问题会由服务器推来。留着旧按钮只会让玩家
        // 重复提交同一个应答——那会被网络层记成「没有对局在等」。
        onClose();
    }

    @Override
    public boolean isPauseScreen() {
        // 对局中不该暂停：单人测试时暂停会让对局线程与界面互相等待。
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        drawBoard(g);
        drawQuestion(g);
        super.render(g, mouseX, mouseY, partialTick);
    }

    /** 牌桌：双方血量、手牌/卡组/墓地数、场上区域。 */
    private void drawBoard(GuiGraphics g) {
        if (board == null) {
            g.drawString(font, "还没有牌桌数据", 8, 8, 0xFFFFFF);
            return;
        }
        g.drawString(font, "回合 " + board.chainCount() + " 连锁   规则 " + board.duelRule(),
                8, 8, 0xFFE080);
        drawSide(g, board.player1(), 8, 22, "对手");
        drawSide(g, board.player0(), 8, height - 74, "我方");
    }

    private void drawSide(GuiGraphics g, DuelBoard.PlayerBoard p, int x, int y, String who) {
        g.drawString(font, who + "  LP " + p.lp()
                + "   手牌 " + p.handCount() + "  卡组 " + p.deckCount()
                + "  墓地 " + p.graveCount() + "  除外 " + p.removedCount()
                + "  额外 " + p.extraCount(), x, y, 0xFFFFFF);
        // 区域用色块表示占用，不画卡图：快照里【没有卡号】，
        // 要画具体卡得另查 queryFieldCard，那是下一步的事。
        int zx = x;
        int zy = y + 11;
        for (DuelBoard.Zone z : p.monsterZones()) {
            g.fill(zx, zy, zx + 22, zy + 30, z.occupied() ? 0xFF4060C0 : 0x40FFFFFF);
            if (z.overlayCount() > 0) {
                g.drawString(font, String.valueOf(z.overlayCount()), zx + 2, zy + 22, 0xFFD060);
            }
            zx += 24;
        }
        zx = x;
        zy += 34;
        for (DuelBoard.Zone z : p.spellZones()) {
            g.fill(zx, zy, zx + 22, zy + 30, z.occupied() ? 0xFF40A060 : 0x40FFFFFF);
            zx += 24;
        }
    }

    private void drawQuestion(GuiGraphics g) {
        String title = question == null ? "等待服务器…" : question.title();
        g.drawString(font, title, 8, height - 92, 0xFFFF80);
        if (question != null && needsConfirm()) {
            g.drawString(font, "已选 " + chosen.size() + "（需要 " + question.min()
                            + (question.max() > 0 ? "~" + question.max() : " 以上") + "）",
                    8, height - 80, 0xA0A0A0);
        }
    }
}
