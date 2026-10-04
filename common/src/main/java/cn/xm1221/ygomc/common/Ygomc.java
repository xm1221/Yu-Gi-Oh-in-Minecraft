package cn.xm1221.ygomc.common;

import cn.xm1221.ygomc.common.card.CardComponents;
import cn.xm1221.ygomc.common.command.YgomcCommand;
import cn.xm1221.ygomc.common.duel.DuelRoom;
import cn.xm1221.ygomc.common.net.YgomcNet;
import cn.xm1221.ygomc.common.data.DataPacks;
import cn.xm1221.ygomc.common.ocg.Natives;
import cn.xm1221.ygomc.common.ocg.OcgEngine;
import cn.xm1221.ygomc.common.registry.YgomcBlocks;
import cn.xm1221.ygomc.common.registry.YgomcItems;
import cn.xm1221.ygomc.common.registry.YgomcTabs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ygomc 的公共入口（Architectury 的 common 模块）。
 *
 * <h2>为什么公共初始化要有这么薄的一层</h2>
 * 两个平台的入口类（{@code YgomcFabric} / {@code YgomcNeoForge}）除了「被谁调用」
 * 之外没有任何差别，所以真正的初始化只有这一份，平台类只负责转发。
 * 任何写进平台类的逻辑都会变成两份需要同步维护的副本——
 * 只有确实无法共通的东西（原生库加载、客户端专属初始化）才允许下沉到平台模块。
 *
 * <h2>初始化顺序是有意义的，不能随意调换</h2>
 * <ol>
 *   <li>{@link CardComponents}：物品的默认组件要引用组件类型，必须先登记；</li>
 *   <li>{@link YgomcBlocks}：{@code duel_table} 的物品形态需要方块实例，方块必须先登记；</li>
 *   <li>{@link YgomcItems}：引用上面的方块；</li>
 *   <li>{@link YgomcTabs}：标签页的图标引用物品。</li>
 * </ol>
 * 这些都是「登记」而非「实例化」——真正创建对象发生在注册事件触发时。
 * 但登记顺序仍然重要：某个工厂在注册事件里 {@code get()} 一个还没被登记的
 * {@code RegistrySupplier} 就会直接抛 NPE。
 *
 * <p><b>每个 {@code init()} 内部都必须调用自己那个
 * {@code DeferredRegister.register()}。</b> Architectury 的 {@code DeferredRegister}
 * 只做缓冲、<b>不会自动落库</b>；漏掉调用时编译、启动、日志全都正常，
 * 只有注册表是空的。完整说明见 {@link CardComponents#init()}。
 */
public final class Ygomc {

    /** 模组 id。注册表命名空间、资源路径、数据包目录都用它，必须与元数据文件一致。 */
    public static final String MOD_ID = "ygomc";

    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private Ygomc() {
    }

    /**
     * 公共初始化。由各平台入口在模组加载就绪后调用一次。
     *
     * <p>本方法只做注册表的<b>登记</b>，不碰世界、不碰客户端资源——
     * 在这个时间点它们都还没准备好。
     */
    public static void init() {
        CardComponents.init();
        YgomcBlocks.init();
        YgomcItems.init();
        YgomcTabs.init();
        LOGGER.info("ygomc：内容登记完成");

        // 命令要在注册表之后登记：它本身不动注册表，但玩家可能立刻就敲 /ygomc。
        YgomcCommand.init();

        // 网络也要早些登记：S2C 的载荷类型漏注册的症状是「客户端一连就断」，
        // 而服务端日志里没有线索，所以宁可让它出现在启动路径上。
        YgomcNet.registerCommon();
        // 收到应答后交给哪个房间。放在这里而不是 DuelRoom 的静态块里：
        // 静态块什么时候被触发不好预测，而「没接上」的症状是应答被静默丢弃。
        DuelRoom.registerAnswerSink();

        // 数据包不是加载的前提（缺文件是正常情况），这里只是让状态在日志里可见。
        DataPacks.init();

        // 原生库同理：缺了也能进游戏，只是开不了局。提前加载是为了让失败原因
        // 出现在启动日志里，而不是等玩家点了「开始决斗」才报错。
        Natives.init();

        // 引擎装配是「原生库 → 脚本根 → 卡表」三步，成功会把卡张数与脚本根写进日志，
        // 失败只记日志不抛——缺 Lua 脚本是预期情况（脚本是 GPLv2，不能随模组分发）。
        OcgEngine.prepare();
    }
}
