package cn.xm1221.ygomc.common.client;

import cn.xm1221.ygomc.common.data.DataPack;
import cn.xm1221.ygomc.common.data.DataPacks;
import cn.xm1221.ygomc.common.sync.AssetSync;
import cn.xm1221.ygomc.common.sync.PicCache;
import cn.xm1221.ygomc.common.sync.PicStatus;
import cn.xm1221.ygomc.common.sync.SyncAnnounce;
import cn.xm1221.ygomc.common.sync.SyncConfig;
import cn.xm1221.ygomc.common.sync.SyncListeners;
import cn.xm1221.ygomc.common.sync.SyncSettings;
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
    private static final SyncAnnounce.PicGate PICS = new SyncAnnounce.PicGate();
    private static boolean hooked;
    private static boolean picPrefetchArmed;

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
        if (!outcome.log() && !outcome.chat() && !outcome.actionBar()) {
            announcePics(minecraft, playerPresent);
            return;
        }
        AssetSync.Status current = outcome.status();
        boolean warn = current.phase() == AssetSync.Phase.DEGRADED || current.phase() == AssetSync.Phase.FAILED;
        Component detail = Component.translatable(current.messageKey(), current.argsArray());
        Component line = Component.translatable(warn ? "ygomc.sync.chat.warn" : "ygomc.sync.chat", detail);
        if (outcome.log()) {
            LOGGER.info("资源同步：{}", line.getString());
        }
        if (minecraft.player != null) {
            if (outcome.chat()) {
                minecraft.player.displayClientMessage(line, false);
            }
            if (outcome.actionBar()) {
                minecraft.player.displayClientMessage(line, true);
            }
        }
        announcePics(minecraft, playerPresent);
    }

    /**
     * 卡图进度走同一条客户端 tick。全库开始、结束、失败进聊天；张数变化只改动作条。
     * {@code picsPrefetchAll} 在卡库就绪后自动开跑，专用服不会进到这里。
     */
    private static void announcePics(Minecraft minecraft, boolean playerPresent) {
        maybeAutoPrefetch();
        SyncAnnounce.PicCue cue;
        try {
            cue = PICS.tick(PicCache.status(), playerPresent);
        } catch (RuntimeException e) {
            LOGGER.warn("卡图进度读不了：{}", e.toString());
            return;
        }
        if (cue.deliveredTerminal()) {
            PicStatus now = PicCache.status();
            if (now.bulk() && (now.kind() == PicStatus.Kind.READY
                    || now.kind() == PicStatus.Kind.STOPPED
                    || now.kind() == PicStatus.Kind.FAILED)) {
                PicCache.acknowledgeTerminal();
            }
        }
        if (cue.messageKey() == null || (!cue.log() && !cue.chat() && !cue.actionBar())) {
            return;
        }
        Component detail = Component.translatable(cue.messageKey(), cue.argsArray());
        Component line = Component.translatable(cue.warn() ? "ygomc.sync.chat.warn" : "ygomc.sync.chat", detail);
        if (cue.log()) {
            LOGGER.info("卡图：{}", line.getString());
        }
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        if (cue.chat()) {
            minecraft.player.displayClientMessage(line, false);
        }
        if (cue.actionBar()) {
            minecraft.player.displayClientMessage(line, true);
        }
    }

    private static void maybeAutoPrefetch() {
        if (picPrefetchArmed) {
            return;
        }
        if (PicCache.prefetchRunning()) {
            picPrefetchArmed = true;
            return;
        }
        SyncSettings settings;
        try {
            settings = SyncConfig.current();
        } catch (Throwable e) {
            return;
        }
        if (!settings.enabled || !settings.picsOnDemand || !settings.picsPrefetchAll) {
            return;
        }
        DataPack pack;
        try {
            pack = DataPacks.get();
        } catch (Throwable e) {
            return;
        }
        if (pack.cardData() == null || pack.cardData().codes().length == 0) {
            return;
        }
        int started = PicCache.startPrefetch(pack.cardData().codes(), false);
        if (started == 1 || started == 2 || started == -1) {
            picPrefetchArmed = true;
        }
    }
}
