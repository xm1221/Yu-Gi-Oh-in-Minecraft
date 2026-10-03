package cn.xm1221.ygomc;

import net.fabricmc.api.ModInitializer;

/**
 * Fabric 入口。
 *
 * <p>只做一件事：把控制权交给公共入口 {@link Ygomc#init()}。
 * 具体的注册内容全在 common，平台类保持「零逻辑」可以避免
 * 两个平台各维护一份注册代码而慢慢分叉。
 */
public final class YgomcFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        // Fabric 的这个回调触发时，注册表已经可以安全写入了。
        Ygomc.init();
    }
}
