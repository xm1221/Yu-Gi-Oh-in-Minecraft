package cn.xm1221.ygomc.common.registry;

import cn.xm1221.ygomc.common.Ygomc;
import cn.xm1221.ygomc.common.card.CardItem;
import cn.xm1221.ygomc.common.card.CardRarity;
import cn.xm1221.ygomc.common.data.CardDataDb;
import cn.xm1221.ygomc.common.data.DataPacks;
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
     * 卡库标签页的图标卡号：青眼白龙。
     *
     * <p>选它只是因为它的卡号在数据包里稳定存在、且是辨识度最高的一张。
     * 万一数据包里没有这张卡，图标会回退成卡背——不会崩，也不会是空白格。
     *
     * <p>这个常量必须声明在 {@link #CARDS} <b>之前</b>：它在标签页的图标
     * lambda 里被引用，而 lambda 体在语法上属于那个字段初始化表达式的一部分，
     * 于是落入 JLS 8.3.3 的「非法前向引用」——即使运行时根本还没执行到。
     */
    private static final int ICON_CODE = 89631139;

    /**
     * {@code ygomc:cards}：卡库，<b>一张卡一个条目</b>。
     *
     * <h2>为什么是一张卡一个条目而不是一个 {@code card} 物品</h2>
     * {@code card} 物品不带 {@code card_ref} 时是一张「没有身份」的卡。
     * 如果卡库里只放一个裸物品，创造模式就拿不到任何具体的卡——
     * 而卡号恰恰是这一整套设计里唯一的身份来源。所以每张卡都造一个
     * 带 {@code card_ref} 的 stack 放进来。
     *
     * <h2>一千五百个条目会不会太多</h2>
     * 条目数就是卡池大小（实测 15019 张）。原版创造界面的滚动列表按行渲染，
     * 只画可见的那几行，所以显示开销与条目数无关；代价只在打开标签页时
     * 构造 15019 个 {@code ItemStack}，那是毫秒级。真到了要分页/分卡包的时候
     * （数据驱动的卡包是后续里程碑），换的是这个生成器，条目语义不变。
     *
     * <h2>数据包没加载时</h2>
     * 放<b>一个裸卡</b>而不是留空。空标签页会让人以为模组坏了，
     * 而实际原因只是数据包没放；裸卡会显示卡背，反而正好说明了问题。
     */
    public static final RegistrySupplier<CreativeModeTab> CARDS =
            TABS.register("cards", () -> CreativeModeTab.builder(CreativeModeTab.Row.BOTTOM, 1)
                    .title(Component.translatable("itemGroup.ygomc.cards"))
                    .icon(() -> CardItem.stack(ICON_CODE, CardRarity.COMMON))
                    .displayItems((parameters, output) -> {
                        CardDataDb db = DataPacks.get().cardData();
                        if (db == null || db.size() == 0) {
                            output.accept(new ItemStack(YgomcItems.CARD.get()));
                            return;
                        }
                        for (int code : db.codes()) {
                            output.accept(CardItem.stack(code, CardRarity.COMMON));
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
