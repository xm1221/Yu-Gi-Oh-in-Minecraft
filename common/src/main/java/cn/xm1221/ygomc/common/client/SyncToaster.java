package cn.xm1221.ygomc.common.client;

import cn.xm1221.ygomc.common.sync.AssetSync;
import cn.xm1221.ygomc.common.sync.SyncAnnounce;
import cn.xm1221.ygomc.common.sync.SyncListeners;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 不挡标题画面。只有同步真的下了东西、或失败了，才在聊天里说一句。
 * 清单一致时 {@link AssetSync.Status#announce()} 是假的，这里不弹。
 * 标题画面上还没有玩家时先记着，进世界后再说，避免失败被静默吞掉。
 */
public final class SyncToaster {

    private static final Logger LOGGER = LoggerFactory.getLogger("ygomc/sync");
    private static final SyncAnnounce.Gate GATE = new SyncAnnounce.Gate();
    private static boolean hooked;

    private SyncToaster() {
    }

    public static void init() {
        if (hooked) {
            return;
        }
        hooked = true;
        SyncListeners.onApplied(() -> Minecraft.getInstance().execute(CardTextures::invalidate));
        SyncListeners.onPicReady(code -> Minecraft.getInstance().execute(() -> CardTextures.invalidate(code)));
    }

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        boolean playerPresent = minecraft != null && minecraft.player != null;
        SyncAnnounce.Outcome outcome = GATE.tick(AssetSync.status(), playerPresent);
        if (!outcome.log() && !outcome.chat()) {
            return;
        }
        AssetSync.Status current = outcome.status();
        Component detail = Component.translatable(current.messageKey(), current.argsArray());
        Component line = Component.translatable(outcome.actionBar() ? "ygomc.sync.chat.warn" : "ygomc.sync.chat", detail);
        if (outcome.log()) {
            LOGGER.info("资源同步：{}", line.getString());
        }
        if (!outcome.chat() || minecraft.player == null) {
            return;
        }
        minecraft.player.displayClientMessage(line, false);
        if (outcome.actionBar()) {
            minecraft.player.displayClientMessage(line, true);
        }
    }
}
