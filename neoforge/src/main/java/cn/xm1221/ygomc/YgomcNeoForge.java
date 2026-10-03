package cn.xm1221.ygomc;

import net.neoforged.fml.common.Mod;

/**
 * NeoForge 入口。
 *
 * <p>{@code @Mod} 的值取自公共常量 {@link Ygomc#MOD_ID}，
 * 而不是再写一遍字符串字面量：模组 id 同时出现在元数据
 * （{@code META-INF/neoforge.mods.toml}）、注册表命名空间和资源路径里，
 * 多处硬编码迟早会有一处漏改。
 *
 * <p>构造函数体只做一件事：把控制权交给公共入口 {@link Ygomc#init()}。
 */
@Mod(Ygomc.MOD_ID)
public final class YgomcNeoForge {

    public YgomcNeoForge() {
        // NeoForge 在 mod 构造阶段就已经允许通过 DeferredRegister 登记内容
        // （真正的实例化发生在 RegisterEvent 中）。
        Ygomc.init();
    }
}
