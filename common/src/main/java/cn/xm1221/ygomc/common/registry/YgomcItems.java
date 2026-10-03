package cn.xm1221.ygomc.common.registry;

import cn.xm1221.ygomc.common.Ygomc;
import cn.xm1221.ygomc.common.card.CardItem;
import cn.xm1221.ygomc.common.collection.CardBinderItem;
import cn.xm1221.ygomc.common.deck.DeckBoxItem;
import cn.xm1221.ygomc.common.duel.DuelDiskItem;
import cn.xm1221.ygomc.common.pack.CardPackItem;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

/**
 * 物品注册。全部物品都在这里，且<b>只在 common 注册</b>（两个平台自动生效）。
 *
 * <h2>为什么这里只有 5 个物品，却能表示一万两千多张卡</h2>
 * 见 {@link CardItem} 的类注释：卡的身份放在数据组件里，物品只按「东西的种类」注册。
 * 这份清单里的每一项都是「玩家能拿在手上做不同事情的一类东西」，
 * 而不是「一张具体的卡」。
 *
 * <h2>为什么决斗台的物品形态也在这里，而不是在 {@link YgomcBlocks}</h2>
 * {@code BlockItem} 是「把方块当物品拿在手里」的桥接物。如果它也注册在方块那边，
 * 就会出现两个「物品清单」——创造标签页遍历物品注册器时会漏掉它，
 * 必须记得补一句。统一放在这里之后，「所有物品」只有一个真相源，
 * 添加新方块时忘记加物品也只是少一个物品，而不是少一条注册路径。
 *
 * <h2>为什么每个属性都要显式写 {@code new Item.Properties()}</h2>
 * 1.21 的 {@code Item.Properties} 已经不再携带「属于哪个创造标签页」这类字段
 * （标签页内容改由标签页自己的生成器决定），所以这里的属性只剩
 * 「上限、稀有度、耐久…」这些真正属于物品本身的东西。
 * 本项目所有非卡牌物品都做成不可堆叠的<b>工具/容器</b>语义
 * （卡包堆叠与否等 M3 定），因此显式 {@code stacksTo(1)} 而不依赖默认值——
 * 默认值随物品类型变化（方块物品 64、普通物品 1），显式写出才不会被以后的改动误伤。
 */
public final class YgomcItems {

    private YgomcItems() {
    }

    /** 物品注册器。 */
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Ygomc.MOD_ID, Registries.ITEM);

    // ---------------------------------------------------------------- 卡牌

    /**
     * 卡牌本体。整个模组只有这一个物品承担全部卡池，见 {@link CardItem}。
     *
     * <p>{@code stacksTo(1)} 是决策 D2 的硬性要求。
     */
    public static final RegistrySupplier<CardItem> CARD =
            ITEMS.register("card", CardItem::createDefault);

    // ------------------------------------------------------------ 对局相关

    /** 便携决斗盘：副手/主手右键玩家发起挑战。 */
    public static final RegistrySupplier<DuelDiskItem> DUEL_DISK =
            ITEMS.register("duel_disk",
                    () -> new DuelDiskItem(new Item.Properties().stacksTo(1)));

    /** 固定决斗台的物品形态（拿着它右键地面放下台子）。 */
    public static final RegistrySupplier<BlockItem> DUEL_TABLE =
            ITEMS.register("duel_table",
                    () -> new BlockItem(YgomcBlocks.DUEL_TABLE.get(), new Item.Properties().stacksTo(1)));

    // ---------------------------------------------------------------- 组卡

    /** 卡组盒：承载一副卡组。 */
    public static final RegistrySupplier<DeckBoxItem> DECK_BOX =
            ITEMS.register("deck_box",
                    () -> new DeckBoxItem(new Item.Properties().stacksTo(1)));

    // ---------------------------------------------------------------- 开包

    /** 卡包：右键开封。 */
    public static final RegistrySupplier<CardPackItem> CARD_PACK =
            ITEMS.register("card_pack",
                    () -> new CardPackItem(new Item.Properties().stacksTo(1)));

    // ---------------------------------------------------------------- 收藏

    /** 卡册：浏览已持有的卡。 */
    public static final RegistrySupplier<CardBinderItem> CARD_BINDER =
            ITEMS.register("card_binder",
                    () -> new CardBinderItem(new Item.Properties().stacksTo(1)));

    /**
     * 注册全部物品。
     *
     * <p>调用顺序有要求：必须在 {@link YgomcBlocks#init()} <b>之后</b>。
     * {@link #DUEL_TABLE} 的工厂会立刻取 {@code YgomcBlocks.DUEL_TABLE.get()}，
     * 而 {@code register()} 在 Fabric 侧是<b>同步落库</b>的——方块还没注册就取，
     * 拿到的就是空条目。
     *
     * <p>{@code register()} 必须显式调用：{@code DeferredRegister} 只做缓冲，
     * 不自动落库。漏掉的后果（编译和启动都不会报错，但注册表空）详见
     * {@link cn.xm1221.ygomc.common.card.CardComponents#init()}。
     */
    public static void init() {
        ITEMS.register();
    }
}
