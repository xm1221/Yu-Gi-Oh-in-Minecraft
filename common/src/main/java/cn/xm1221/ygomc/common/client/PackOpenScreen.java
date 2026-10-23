package cn.xm1221.ygomc.common.client;

import cn.xm1221.ygomc.common.net.PackNet;
import cn.xm1221.ygomc.common.pack.model.PrintRef;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 开包展示。卡在进这个界面之前已经进了背包，本屏不负责发牌或任何动作。
 * 顶部尽量画真实包面；缺图时仍用染色占位背景。
 *
 * <p>结果按罕贵从低到高排。不是随机翻，也不是按抽出顺序一张一张翻。
 */
public final class PackOpenScreen extends Screen {

    private static final int GAP = 8;
    private static final int FLIP_INTERVAL = 4;
    private static final int FACE_H = 72;

    private final List<PrintRef> cards;
    private final boolean[] faceUp;
    private final ResourceLocation boosterId;
    private final String faceUrl;
    private Button reveal;
    private boolean revealing;
    private int tick;

    public PackOpenScreen(PackNet.OpenedPack pack) {
        super(Component.translatable("ygomc.pack.screen"));
        this.cards = new ArrayList<>(pack.cards());
        this.cards.sort(Comparator.comparingInt((PrintRef card) -> card.rarity().ordinal())
                .thenComparingInt(PrintRef::code));
        this.faceUp = new boolean[this.cards.size()];
        this.boosterId = pack.boosterId();
        this.faceUrl = pack.faceUrl();
    }

    /** @deprecated 兼容旧调用；无包面信息。 */
    @Deprecated
    public PackOpenScreen(List<PrintRef> cards) {
        this(new PackNet.OpenedPack(cards, null, ""));
    }

    @Override
    protected void init() {
        reveal = Button.builder(Component.translatable("ygomc.pack.reveal_all"), button -> {
            if (allFaceUp()) {
                onClose();
            } else {
                revealing = true;
            }
        }).bounds(width / 2 - 60, height - 28, 120, 20).build();
        addRenderableWidget(reveal);
    }

    @Override
    public void tick() {
        if (!revealing) {
            return;
        }
        tick++;
        if (tick % FLIP_INTERVAL != 0) {
            return;
        }
        for (int i = 0; i < faceUp.length; i++) {
            if (!faceUp[i]) {
                faceUp[i] = true;
                break;
            }
        }
        if (allFaceUp()) {
            revealing = false;
            reveal.setMessage(Component.translatable("ygomc.pack.close"));
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int[] layout = layout();
            int cardW = layout[0];
            int cardH = layout[1];
            int x0 = layout[2];
            int y0 = layout[3];
            int columns = layout[4];
            for (int i = 0; i < cards.size(); i++) {
                int col = i % columns;
                int row = i / columns;
                int x = x0 + col * (cardW + GAP);
                int y = y0 + row * (cardH + GAP);
                if (mouseX >= x && mouseX < x + cardW && mouseY >= y && mouseY < y + cardH) {
                    faceUp[i] = true;
                    if (allFaceUp()) {
                        revealing = false;
                        reveal.setMessage(Component.translatable("ygomc.pack.close"));
                    }
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        PackTextures.background(g, 0, 0, width, height);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawString(font, title, (width - font.width(title)) / 2, 8, 0xFFF0E2B0, true);
        drawPackFace(g);
        int[] layout = layout();
        int cardW = layout[0];
        int cardH = layout[1];
        int x0 = layout[2];
        int y0 = layout[3];
        int columns = layout[4];
        for (int i = 0; i < cards.size(); i++) {
            int col = i % columns;
            int row = i / columns;
            int x = x0 + col * (cardW + GAP);
            int y = y0 + row * (cardH + GAP);
            PackTextures.slot(g, x - 2, y - 2, cardW + 4, cardH + 4);
            PrintRef card = cards.get(i);
            if (faceUp[i]) {
                CardArt.draw(g, card.code(), x, y, cardW, cardH, RarityLooks.of(card.rarity()));
            } else {
                CardArt.drawBack(g, x, y, cardW, cardH);
            }
        }
    }

    private void drawPackFace(GuiGraphics g) {
        if (boosterId == null) {
            return;
        }
        ResourceLocation tex = PackFaceTextures.get(boosterId.toString(), faceUrl);
        if (tex == null) {
            return;
        }
        int texW = PackFaceTextures.width(boosterId.toString());
        int texH = PackFaceTextures.height(boosterId.toString());
        if (texW <= 0 || texH <= 0) {
            return;
        }
        int h = FACE_H;
        int w = Math.max(40, h * texW / texH);
        int x = (width - w) / 2;
        int y = 22;
        g.blit(tex, x, y, w, h, 0.0F, 0.0F, texW, texH, texW, texH);
    }

    @Override
    public void removed() {
        super.removed();
        if (minecraft != null) {
            minecraft.tell(PackClient::openPending);
        }
    }

    /** 卡宽、卡高、左上 x、左上 y、每行列数。 */
    private int[] layout() {
        int columns = Math.max(1, Math.min(cards.size(), 5));
        int rows = Math.max(1, (cards.size() + columns - 1) / columns);
        int top = 22 + FACE_H + 8;
        int availW = width - 24 - GAP * (columns - 1);
        int availH = height - top - 36 - GAP * (rows - 1);
        int cardW = Math.min(96, availW / columns);
        int cardH = cardW * 290 / 200;
        if (cardH * rows + GAP * (rows - 1) > height - top - 36) {
            cardH = Math.max(48, availH / rows);
            cardW = cardH * 200 / 290;
        }
        int gridW = columns * cardW + (columns - 1) * GAP;
        int gridH = rows * cardH + (rows - 1) * GAP;
        int x0 = (width - gridW) / 2;
        int y0 = top + Math.max(0, (height - top - 36 - gridH) / 2);
        return new int[]{cardW, cardH, x0, y0, columns};
    }

    private boolean allFaceUp() {
        for (boolean up : faceUp) {
            if (!up) {
                return false;
            }
        }
        return true;
    }
}
