package cn.xm1221.ygomc.fabric.client;

import net.fabricmc.api.ClientModInitializer;

/**
 * Fabric 客户端入口。
 *
 * <h2>为什么客户端要单独一个入口，而不是在公共入口里判 {@code Env}</h2>
 * 模组的公共代码会同时被<b>专用服务器</b>加载。任何直接引用渲染、
 * 界面、按键这类客户端专属类的代码，只要出现在公共入口的调用链上，
 * 专用服务器就会在类加载阶段抛 {@code NoClassDefFoundError} 而崩服。
 * 把客户端专属初始化放进独立的入口类，服务端根本不会加载到它，问题从结构上消失。
 *
 * <p>本轮还没有客户端专属内容：卡图的解码与叠层渲染（M3）会挂在这里。
 */
public final class YgomcFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // TODO(M3): 注册卡牌的自定义物品渲染（从数据包 pics.bin 按需解码卡图、
        //           再按 RarityEntry 的层列表叠加闪面）。
        // TODO(M2): 注册决斗相关的客户端界面与网络接收端。
    }
}
