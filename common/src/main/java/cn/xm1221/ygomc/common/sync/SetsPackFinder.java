package cn.xm1221.ygomc.common.sync;

import dev.architectury.platform.Platform;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 把 {@code <游戏目录>/ygomc/sets} 挂成服务端数据包，包 id {@code ygomc_sets}。
 * 位置用 {@link Pack.Position#BOTTOM}：世界 {@code datapacks/} 里的包默认在 TOP，后加载，盖过这里。
 * 目录或 {@code pack.mcmeta} 还不存在时什么都不登记，下次 {@code PackRepository.reload} 再看。
 */
public final class SetsPackFinder {

    public static final String PACK_ID = "ygomc_sets";

    private static final Logger LOGGER = LoggerFactory.getLogger("ygomc/sync");

    private SetsPackFinder() {
    }

    public static void load(Consumer<Pack> consumer) {
        Path path;
        try {
            path = CacheLayout.sets(Platform.getGameFolder());
        } catch (Throwable e) {
            LOGGER.warn("游戏目录还不可用，跳过卡包查找器：{}", e.toString());
            return;
        }
        if (!Files.isRegularFile(path.resolve("pack.mcmeta"))) {
            return;
        }
        Pack pack = Pack.readMetaAndCreate(
                new PackLocationInfo(PACK_ID, Component.literal("ygomc sets"), PackSource.WORLD, Optional.empty()),
                new PathPackResources.PathResourcesSupplier(path),
                PackType.SERVER_DATA,
                new PackSelectionConfig(true, Pack.Position.BOTTOM, false));
        if (pack != null) {
            consumer.accept(pack);
        }
    }
}
