package cn.xm1221.ygomc.fabric.mixin;

import cn.xm1221.ygomc.common.sync.SetsPackFinder;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.server.packs.repository.ServerPacksSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.Arrays;

/**
 * Fabric 1.21.1 的公开 resource loader 只能登记 jar 里的内置包。
 * 原版 {@link net.minecraft.server.packs.repository.PackRepository} 的来源集合是不可变的，
 * 没有 NeoForge 的 {@code addPackFinder}。这里在专用服和单人建仓库时，把
 * {@code ygomc/sets} 追加成最后一个来源。
 *
 * <p>世界 {@code datapacks/} 仍是这个方法里原有的 {@code FolderRepositorySource}，
 * 默认位置 TOP，后加载，盖过我们的 BOTTOM 包。
 */
@Mixin(ServerPacksSource.class)
public class ServerPacksSourceMixin {

    @ModifyArg(
            method = "createPackRepository(Ljava/nio/file/Path;Lnet/minecraft/world/level/validation/DirectoryValidator;)Lnet/minecraft/server/packs/repository/PackRepository;",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/packs/repository/PackRepository;<init>([Lnet/minecraft/server/packs/repository/RepositorySource;)V"))
    private static RepositorySource[] ygomc$addSetsPack(RepositorySource[] sources) {
        RepositorySource[] expanded = Arrays.copyOf(sources, sources.length + 1);
        expanded[sources.length] = SetsPackFinder::load;
        return expanded;
    }
}
