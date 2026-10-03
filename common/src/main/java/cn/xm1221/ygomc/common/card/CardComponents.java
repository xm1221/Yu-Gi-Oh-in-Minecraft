package cn.xm1221.ygomc.common.card;

import cn.xm1221.ygomc.common.Ygomc;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;

/**
 * 本模组的数据组件（1.21 取代旧版 NBT 的机制）注册表。
 *
 * <h2>为什么用数据组件而不是 NBT（1.21 的硬性变化）</h2>
 * 1.20.5 起，{@code ItemStack} 的自定义数据不再是可以随手读写的 {@code CompoundTag}，
 * 而是<b>强类型</b>的 {@link DataComponentType}。好处是明显的：每个字段有明确的
 * Codec（存档）与 StreamCodec（网络），写错类型在编译期就报错，
 * 而不是像以前那样在运行期从 NBT 里读出一个 {@code null} 才崩。
 * 代价是<b>每个组件都必须显式提供这两套 Codec</b>——1.21 两套都要，缺一不可：
 * 只有 Codec 会无法同步到客户端，只有 StreamCodec 会无法存进存档。
 *
 * <h2>为什么这里的组件也要走 DeferredRegister</h2>
 * 组件本身也是注册表条目（{@code Registries.DATA_COMPONENT_TYPE}）。
 * 用同一个 Architectury 注册器统一登记，两个平台都生效，
 * 不需要为 fabric / neoforge 各写一份。
 */
public final class CardComponents {

    private CardComponents() {
    }

    /** 组件注册器。三个组件都挂在 {@code ygomc} 命名空间下。 */
    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Ygomc.MOD_ID, Registries.DATA_COMPONENT_TYPE);

    /**
     * 卡片身份：{@link CardRef}。
     *
     * <p>这是整个卡牌系统最关键的组件——它把一个普通的 {@code card} 物品
     * 变成「某一张具体的卡」。因为它是<b>持久化 + 同步</b>的，
     * 一张卡放进箱子、跨维度搬运、发给别的玩家之后仍然知道自己是谁。
     */
    public static final RegistrySupplier<DataComponentType<CardRef>> CARD_REF =
            COMPONENTS.register("card_ref", () -> DataComponentType.<CardRef>builder()
                    .persistent(CardRef.CODEC)
                    .networkSynchronized(CardRef.STREAM_CODEC)
                    .build());

    /**
     * 卡包种类：{@link ResourceLocation}。
     *
     * <p>存的是「这是哪一版卡包」（例如 {@code ygomc:legend_of_blue_eyes}），
     * 而不是卡包里已抽出的内容——抽出结果由开包逻辑按权重实时决定（M3），
     * 不需要提前写进物品。因此这个组件在卡包被消耗前是<b>只读</b>的。
     */
    public static final RegistrySupplier<DataComponentType<ResourceLocation>> PACK_ID =
            COMPONENTS.register("pack_id", () -> DataComponentType.<ResourceLocation>builder()
                    .persistent(ResourceLocation.CODEC)
                    .networkSynchronized(ResourceLocation.STREAM_CODEC)
                    .build());

    /**
     * 卡组内容：{@link DeckData}。
     *
     * <p>放在物品上而不是服务端的一个 map 里，是决策「物品即权威来源」的体现：
     * 卡组盒被玩家丢来丢去、放进末影箱、被别的模组搬运时内容不会丢，
     * 也不存在「服务器数据文件和实际持有的物品不一致」这种双权威源问题。
     * 服务端另建的 per-player 索引只是加速搜索用的缓存，可以随时重建。
     */
    public static final RegistrySupplier<DataComponentType<DeckData>> DECK_DATA =
            COMPONENTS.register("deck_data", () -> DataComponentType.<DeckData>builder()
                    .persistent(DeckData.CODEC)
                    .networkSynchronized(DeckData.STREAM_CODEC)
                    .build());

    /**
     * 触发组件注册。
     *
     * <p>类初始化只在第一次被引用时发生。这里被 {@link cn.xm1221.ygomc.common.Ygomc#init()}
     * 显式调用，是为了让「组件先于物品」这个顺序在代码里看得见：
     * {@link CardItem} 的默认组件需要一个已构造好的 {@link DataComponentType}，
     * 而这个类型只有 {@link #CARD_REF} 的注册内容被执行后才存在。
     *
     * <h2>{@code register()} 这一句不能省</h2>
     * {@code DeferredRegister.register(String, Supplier)} <b>只是把条目记进内部缓冲</b>，
     * 真正落进注册表要么由 Fabric 侧的 {@code Registrar} 立刻写入、
     * 要么由 NeoForge 侧的 {@code RegisterEvent} 监听器在事件里刷出。
     * Architectury 的 {@code DeferredRegister} <b>不会自动调用 {@code register()}</b>，
     * 漏掉这一句的表现是：模组正常加载、{@code init()} 的日志照常打印、
     * {@code RegisterEvent} 也照常触发，但本命名空间在注册表里<b>一个条目都没有</b>——
     * 编译期与启动日志都不会报任何错，只有引用这些条目的数据包
     * （例如方块掉落表）才会以 {@code Unknown registry key} 的形式暴露出来。
     *
     * <p>本方法对同一个注册器<b>只能调用一次</b>，重复调用会抛
     * {@code IllegalStateException: Cannot register a deferred register twice!}。
     */
    public static void init() {
        COMPONENTS.register();
    }
}
