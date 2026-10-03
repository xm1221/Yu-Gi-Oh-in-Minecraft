package cn.xm1221.ygomc.fabric;

import cn.xm1221.ygomc.common.Ygomc;
import net.fabricmc.api.ModInitializer;

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
        // Fabric 的这个回调触发时，注册表已经可以安全写入了。
        Ygomc.init();
    }
}
