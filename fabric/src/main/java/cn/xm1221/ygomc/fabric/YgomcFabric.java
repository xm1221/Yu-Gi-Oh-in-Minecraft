package cn.xm1221.ygomc.fabric;

import cn.xm1221.ygomc.common.Ygomc;
import cn.xm1221.ygomc.common.pack.data.BoosterBoxDef;
import cn.xm1221.ygomc.common.pack.data.BoosterDef;
import cn.xm1221.ygomc.common.pack.data.CardSetDef;
import cn.xm1221.ygomc.common.pack.data.PackCatalog;
import cn.xm1221.ygomc.common.pack.data.PackRegistries;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.registry.DynamicRegistries;

/**
 * Fabric 入口。
 *
 * <p>只做一件事：把控制权交给公共入口 {@link Ygomc#init()}。
 * 具体的注册内容全在 common，平台类保持「零逻辑」可以避免
 * 两个平台各维护一份注册代码而慢慢分叉。
 *
 * <p>本类<b>必须</b>待在 {@code .fabric} 子包：三个模块的包名不能重叠，
 * 否则 NeoForge 会因「拆分包」崩在模块解析阶段。
 * 完整原因见 {@code YgomcNeoForge} 的类注释。
 */
public final class YgomcFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        // 动态注册表要在数据包加载前登记。网络编解码用同一份 Codec，客户端才能看到包名和概率。
        DynamicRegistries.registerSynced(PackRegistries.CARD_SET, CardSetDef.CODEC, CardSetDef.CODEC);
        DynamicRegistries.registerSynced(PackRegistries.BOOSTER, BoosterDef.DIRECT_CODEC, BoosterDef.DIRECT_CODEC);
        DynamicRegistries.registerSynced(PackRegistries.BOOSTER_BOX, BoosterBoxDef.DIRECT_CODEC, BoosterBoxDef.DIRECT_CODEC);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> PackCatalog.validate(server.registryAccess()));
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, manager, success) -> {
            if (success) {
                PackCatalog.validate(server.registryAccess());
            }
        });
        // 卡包查找器在 ServerPacksSourceMixin：公开 API 只能登记 jar 内置包。
        Ygomc.init();
    }
}
