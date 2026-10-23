package cn.xm1221.ygomc.common.client;

import cn.xm1221.ygomc.common.net.PackNet;
import cn.xm1221.ygomc.common.pack.model.PrintRef;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayDeque;
import java.util.List;

/**
 * 开包结果入队与展示。关当前开包屏后才开下一包。
 */
public final class PackClient {

    private static final ArrayDeque<PackNet.OpenedPack> PENDING = new ArrayDeque<>();

    private PackClient() {
    }

    public static void init() {
        PackTips.shiftDown = Screen::hasShiftDown;
        PackNet.registerClient(PackClient::accept);
    }

    private static void accept(PackNet.OpenedPack pack) {
        if (pack.cards().isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof PackOpenScreen) {
            PENDING.add(pack);
        } else {
            minecraft.setScreen(new PackOpenScreen(pack));
        }
    }

    /** 当前开包屏关掉之后再开队列里的下一包；已有别的界面时不抢。 */
    public static void openPending() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null || PENDING.isEmpty()) {
            return;
        }
        minecraft.setScreen(new PackOpenScreen(PENDING.removeFirst()));
    }
}
