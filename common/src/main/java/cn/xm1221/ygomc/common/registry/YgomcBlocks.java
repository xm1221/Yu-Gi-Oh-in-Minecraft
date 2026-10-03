package cn.xm1221.ygomc.common.registry;

import cn.xm1221.ygomc.common.Ygomc;
import cn.xm1221.ygomc.common.block.DuelTableBlock;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

/**
 * 方块注册（本轮只有 {@code duel_table}）。
 *
 * <h2>为什么在 common 里注册就够</h2>
 * Architectury 的 {@link DeferredRegister} 在两个平台上各自落到原生机制
 * （Fabric 直接写注册表，NeoForge 走 {@code RegisterEvent}），
 * 所以同一份注册代码在两边都生效，不需要为每个平台复制一遍注册表。
 * 平台模块因此只负责真正无法共通的东西（原生库加载、客户端初始化等）。
 *
 * <h2>为什么方块的 {@code Properties} 要写得这么细</h2>
 * {@code BlockBehaviour.Properties} 的每一项都会实际影响行为，不能靠父类默认值蒙：
 * <ul>
 *   <li>{@code noOcclusion()}：决斗台以后要渲染成有缝隙/半透明的结构（台面 + 格子线），
 *       如果仍然遮挡相邻面，挨着它放的方块会丢面，视觉上出现黑洞。</li>
 *   <li>{@code strength(2.5F, 6.0F)}：硬度接近木头，爆炸抗性略高，
 *       让它在自家大厅里不会被随手一箭打飞。</li>
 *   <li>{@code pushReaction(PushReaction.BLOCK)}：它是「场地设施」不是可推动的装饰，
 *       被活塞推动会让正在进行的对局失去落点。</li>
 *   <li>{@code sound(SoundType.WOOD)}：木质台面，踩上去/挖掉的声音要合理。</li>
 * </ul>
 */
public final class YgomcBlocks {

    private YgomcBlocks() {
    }

    /** 方块注册器。 */
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(Ygomc.MOD_ID, Registries.BLOCK);

    /**
     * 决斗台。
     *
     * <p>对应的物品形态（{@code BlockItem}）登记在 {@link YgomcItems} 里，
     * 因为「有哪些物品」这件事应该只有一个真相源——创造模式标签页直接遍历物品注册器，
     * 不需要再去方块注册器里挑一遍，也就不会出现「加了方块忘了加物品」的漏洞。
     */
    public static final RegistrySupplier<Block> DUEL_TABLE =
            BLOCKS.register("duel_table", () -> new DuelTableBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .sound(SoundType.WOOD)
                    .strength(2.5F, 6.0F)
                    .noOcclusion()
                    .pushReaction(PushReaction.BLOCK)
                    .ignitedByLava()));

    /**
     * 注册方块。必须在 {@link YgomcItems#init()} 之前调用——
     * 决斗台的物品形态要引用这里的方块实例。
     *
     * <p>{@code register()} 必须显式调用：{@code DeferredRegister} 只做缓冲，
     * 不自动落库。原因与后果详见 {@link cn.xm1221.ygomc.common.card.CardComponents#init()}。
     */
    public static void init() {
        BLOCKS.register();
    }
}
