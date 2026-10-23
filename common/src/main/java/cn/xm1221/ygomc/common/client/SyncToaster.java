package cn.xm1221.ygomc.common.client;

import cn.xm1221.ygomc.common.sync.AssetSync;
import cn.xm1221.ygomc.common.sync.SyncListeners;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 不挡标题画面。只有同步真的下了东西、或失败了，才在聊天里说一句。
 * 清单一致时 {@link AssetSync.Status#announce()} 是假的，这里不弹。
 */
public final class SyncToaster {

    private static final Logger LOGGER = LoggerFactory.getLogger("ygomc/sync");
    private static AssetSync.Phase shown = AssetSync.Phase.IDLE;
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
        AssetSync.Status current = AssetSync.status();
        if (current.phase() == shown || !current.announce()) {
            return;
        }
        shown = current.phase();
        Component line = Component.translatable("ygomc.sync.chat",
                Component.translatable(current.messageKey(), current.argsArray()));
        LOGGER.info("资源同步：{}", line.getString());
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(line, false);
        }
    }
}
