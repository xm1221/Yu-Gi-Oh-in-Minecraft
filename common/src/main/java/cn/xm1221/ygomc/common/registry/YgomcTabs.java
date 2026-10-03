package cn.xm1221.ygomc.common.registry;

import cn.xm1221.ygomc.common.Ygomc;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 创造模式标签页 {@code ygomc:main}。
 *
 * <h2>为什么用「生成器」而不是把物品一个个塞进去</h2>
 * 1.20.5 起 {@link CreativeModeTab} 的内容由
 * {@link CreativeModeTab.DisplayItemsGenerator} 在<b>打开标签页时</b>现算，
 * 而不是在注册时写死一份清单。这样做的直接好处是：只要物品注册器变了
 * （哪怕是被别的模组/数据包加进来的），标签页自动跟着变；
 * 我们这里干脆遍历整张 {@link YgomcItems#ITEMS} 注册器，
 * 于是「新增物品」和「出现在标签页里」变成同一件事，不会再漏加。
 *
 * <h2>为什么图标不用 {@code CARD}</h2>
 * {@code card} 物品本身不携带 {@code card_ref}（卡号要等 M3 从数据包解析），
 * 现在把它当图标会是一张没有卡图的空白卡。对应的物品形态同样如此。
 * 等 M3 能造出真正的 {@code ItemStack} 之后再换成某个代表性卡面。
 */
public final class YgomcTabs {

    private YgomcTabs() {
    }

    /** 创造标签页注册器。 */
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Ygomc.MOD_ID, Registries.CREATIVE_MODE_TAB);

    /**
     * {@code ygomc:main}：本模组的全部内容。
     *
     * <p>位置用 {@link CreativeModeTab.Row#BOTTOM} 第 0 列（不额外指定左右对齐），
     * 让它落在创造模式界面底部那一排的起始位置——这个位置在两个平台上语义一致
     * （NeoForge 的补丁只是增加了更多可选的位置参数，没有改这两个的含义）。
     */
    public static final RegistrySupplier<CreativeModeTab> MAIN =
            TABS.register("main", () -> CreativeModeTab.builder(CreativeModeTab.Row.BOTTOM, 0)
                    .title(Component.translatable("itemGroup.ygomc.main"))
                    .icon(() -> new ItemStack(YgomcItems.CARD_BINDER.get()))
                    .displayItems((parameters, output) -> {
                        // 直接遍历物品注册器：见类注释里对「新增即出现」的说明。
                        //
                        // 用 RegistrySupplier::get 而不是提前取实例，是因为本生成器
                        // 在标签页构建时才被调用，那时注册已经完成；
                        // 提前求值会在注册事件里引发顺序问题。
                        for (RegistrySupplier<Item> item : YgomcItems.ITEMS) {
                            output.accept(item.get());
                        }
                    })
                    .build());

    /**
     * 注册创造标签页。必须在 {@link YgomcItems#init()} 之后调用——
     * 标签页的生成器虽然是延迟执行的，但图标那一步已经引用了物品注册器。
     *
     * <p>{@code register()} 必须显式调用：{@code DeferredRegister} 只做缓冲，
     * 不自动落库。原因与后果详见 {@link cn.xm1221.ygomc.common.card.CardComponents#init()}。
     */
    public static void init() {
        TABS.register();
    }
}
