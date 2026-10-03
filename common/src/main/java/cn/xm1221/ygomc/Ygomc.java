package cn.xm1221.ygomc;

import cn.xm1221.ygomc.card.CardComponents;
import cn.xm1221.ygomc.registry.YgomcBlocks;
import cn.xm1221.ygomc.registry.YgomcItems;
import cn.xm1221.ygomc.registry.YgomcTabs;

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
 */
public final class Ygomc {

    /** 模组 id。注册表命名空间、资源路径、数据包目录都用它，必须与元数据文件一致。 */
    public static final String MOD_ID = "ygomc";

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
    }
}
