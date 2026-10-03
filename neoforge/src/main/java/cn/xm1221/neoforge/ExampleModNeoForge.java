package cn.xm1221.neoforge;

import net.neoforged.fml.common.Mod;

import cn.xm1221.ExampleMod;

@Mod(ExampleMod.MOD_ID)
public final class ExampleModNeoForge {
    public ExampleModNeoForge() {
        // Run our common setup.
        ExampleMod.init();
    }
}
