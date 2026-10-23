package cn.xm1221.ygomc.common.client;

import cn.xm1221.ygomc.common.duel.DuelBoard;
import cn.xm1221.ygomc.common.spectacle.SpectacleEvent;
import cn.xm1221.ygomc.common.spectacle.SpectacleKind;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 把当前演出画在对局 GUI 上。世界渲染另写，不要从这里长出去。
 */
public final class GuiSpectaclePainter {

    private GuiSpectaclePainter() {
    }

    public static void paint(GuiGraphics g, FieldLayout L, DuelBoard board, int mySeat,
                             SpectacleEvent event, float progress) {
        if (event == null || L == null) {
            return;
        }
        int[] handCounts = handCounts(board);
        switch (event.kind()) {
            case HAND_REVEAL -> paintHandReveal(g, L, mySeat, event, progress, handCounts);
            case ATTACK_NUDGE -> paintAttackNudge(g, L, mySeat, event, progress, handCounts);
            case APPEAR_OR_ACTIVATE -> paintBigCard(g, L, event, progress);
        }
    }

    private static void paintHandReveal(GuiGraphics g, FieldLayout L, int mySeat,
                                        SpectacleEvent event, float p, int[] handCounts) {
        FieldLayout.Rect from = DuelTargets.rectOf(L, event.controller(), event.location(),
                event.sequence(), mySeat, handCounts);
        if (from == null) {
            return;
        }
        // 中场略放大：从手牌位插值到场地中心。
        int midX = L.x0() + L.fieldW() / 2 - L.cellW();
        int midY = L.height() / 2 - L.cellH();
        int midW = L.cellW() * 2;
        int midH = L.cellH() * 2;
        float t = p < 0.5f ? (p * 2.0f) : ((1.0f - p) * 2.0f);
        t = smooth(t);
        int x = lerp(from.x(), midX, t);
        int y = lerp(from.y(), midY, t);
        int w = lerp(from.w(), midW, t);
        int h = lerp(from.h(), midH, t);
        g.flush();
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, 1800.0F);
        if (event.code() > 0) {
            CardArt.draw(g, event.code(), x, y, w, h, null);
        } else {
            CardArt.drawBack(g, x, y, w, h);
        }
        outline(g, x, y, w, h, 0xFFFFE08A);
        g.pose().popPose();
        g.flush();
    }

    private static void paintAttackNudge(GuiGraphics g, FieldLayout L, int mySeat,
                                         SpectacleEvent event, float p, int[] handCounts) {
        FieldLayout.Rect from = DuelTargets.rectOf(L, event.controller(), event.location(),
                event.sequence(), mySeat, handCounts);
        if (from == null) {
            return;
        }
        FieldLayout.Rect to = event.hasTarget()
                ? DuelTargets.rectOf(L, event.targetController(), event.targetLocation(),
                event.targetSequence(), mySeat, handCounts)
                : null;
        int destX = from.x();
        int destY = from.y();
        if (to != null) {
            destX = to.x() + to.w() / 2 - from.w() / 2;
            destY = to.y() + to.h() / 2 - from.h() / 2;
        } else {
            boolean mine = event.controller() == mySeat;
            destY = mine ? from.y() - from.h() : from.y() + from.h();
        }
        float t = p < 0.5f ? (p * 2.0f) : ((1.0f - p) * 2.0f);
        t = smooth(t) * 0.35f;
        int x = lerp(from.x(), destX, t);
        int y = lerp(from.y(), destY, t);
        int x0 = from.x() + from.w() / 2;
        int y0 = from.y() + from.h() / 2;
        int x1 = to != null ? to.x() + to.w() / 2 : x0;
        int y1 = to != null ? to.y() + to.h() / 2
                : (event.controller() == mySeat ? y0 - 40 : y0 + 40);
        g.flush();
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, 1700.0F);
        drawLine(g, x0, y0, x1, y1, 0x88FFD070);
        g.fill(x, y, x + from.w(), y + from.h(), 0x66FFE0A0);
        outline(g, x, y, from.w(), from.h(), 0xFFFFC050);
        if (to != null) {
            int shake = (int) (Math.sin(p * Math.PI * 6.0) * 2.0);
            outline(g, to.x() + shake, to.y(), to.w(), to.h(), 0xAAFF6060);
        }
        g.pose().popPose();
        g.flush();
    }

    private static void paintBigCard(GuiGraphics g, FieldLayout L, SpectacleEvent event, float p) {
        float t = p < 0.5f ? (p * 2.0f) : ((1.0f - p) * 2.0f);
        t = smooth(t);
        FieldLayout.Rect art = L.panelArt();
        int w = Math.max(art.w(), L.cellW() * 2);
        int h = Math.max(art.h(), L.cellH() * 2);
        int x = L.x0() + (L.fieldW() - w) / 2;
        int y = (L.height() - h) / 2;
        // 略放大
        float s = 0.85f + 0.15f * t;
        int dw = Math.round(w * s);
        int dh = Math.round(h * s);
        int dx = x - (dw - w) / 2;
        int dy = y - (dh - h) / 2;
        int alpha = Math.round(40 + 140 * t);
        g.flush();
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, 1900.0F);
        g.fill(0, 0, L.width(), L.height(), (alpha << 24) | 0x000000);
        if (event.code() > 0) {
            CardArt.draw(g, event.code(), dx, dy, dw, dh, null);
        } else {
            CardArt.drawBack(g, dx, dy, dw, dh);
        }
        outline(g, dx, dy, dw, dh, 0xFFFFFFFF);
        g.pose().popPose();
        g.flush();
    }

    private static int[] handCounts(DuelBoard board) {
        int[] counts = new int[]{0, 0};
        if (board == null) {
            return counts;
        }
        if (board.player0() != null) {
            counts[0] = board.player0().handCount();
        }
        if (board.player1() != null) {
            counts[1] = board.player1().handCount();
        }
        return counts;
    }

    private static float smooth(float t) {
        return t * t * (3.0f - 2.0f * t);
    }

    private static int lerp(int a, int b, float t) {
        return Math.round(a + (b - a) * t);
    }

    private static void outline(GuiGraphics g, int x, int y, int w, int h, int argb) {
        g.fill(x, y, x + w, y + 1, argb);
        g.fill(x, y + h - 1, x + w, y + h, argb);
        g.fill(x, y, x + 1, y + h, argb);
        g.fill(x + w - 1, y, x + w, y + h, argb);
    }

    private static void drawLine(GuiGraphics g, int x0, int y0, int x1, int y1, int argb) {
        int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        steps = Math.max(1, Math.min(steps, 128));
        for (int i = 0; i <= steps; i++) {
            float t = i / (float) steps;
            int x = Math.round(x0 + (x1 - x0) * t);
            int y = Math.round(y0 + (y1 - y0) * t);
            g.fill(x, y, x + 2, y + 2, argb);
        }
    }
}
