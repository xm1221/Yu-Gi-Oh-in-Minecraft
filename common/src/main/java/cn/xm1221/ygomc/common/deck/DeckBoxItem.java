package cn.xm1221.ygomc.common.deck;

import cn.xm1221.ygomc.common.card.CardComponents;
import cn.xm1221.ygomc.common.card.DeckData;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 卡组盒：承载一副卡组（主卡组 / 额外 / 副卡组），并把 {@link DeckData} 存在自己身上。
 *
 * <h2>为什么卡组存物品而不存服务端文件（决策：物品即权威来源）</h2>
 * 卡组盒会被玩家丢地上、塞末影箱、用别的容器模组搬运。如果卡组真身在服务端某个
 * map 里（以玩家 UUID 为键），这些操作全都会让「你手上那个盒子」和「服务器记的卡组」
 * 对不上，需要一整套同步与对账逻辑。存在物品上则天然跟随物品走：
 * 存档由组件 Codec 负责，网络由 StreamCodec 负责，服务端不需要额外记账。
 * 服务端将来会维护的 per-player 索引只是可以随时重建的<b>缓存</b>，不是第二权威源。
 *
 * <p>空卡组时读到 {@code null}，由调用方决定显示「空卡组」还是自动初始化。
 * 这里不自动补一个 {@link DeckData#EMPTY}，是因为「有没有组件」与
 * 「组件里是不是空卡组」对 UI 是两种不同状态（前者是刚合出来的新盒子）。
 */
public class DeckBoxItem extends Item {

    public DeckBoxItem(Properties properties) {
        super(properties);
    }

    /**
     * 读取卡组盒里的卡组数据。
     *
     * @return 卡组数据；该 stack 不带 {@code deck_data} 时返回 {@code null}
     */
    public static DeckData deck(ItemStack stack) {
        DataComponentType<DeckData> type = CardComponents.DECK_DATA.get();
        return stack.get(type);
    }

    /**
     * 写入卡组数据。
     *
     * <p>写入方是本模组自己（组卡 GUI / 导入导出），所以这里直接 {@code set}；
     * 将来做网络同步时要注意：组件变化不会自动广播，需要 {@code InventoryMenu}
     * 的槽位同步或显式包（M4 处理）。
     */
    public static void setDeck(ItemStack stack, DeckData data) {
        stack.set(CardComponents.DECK_DATA.get(), data);
    }

    /**
     * 右键：打开卡组编辑器。
     *
     * <p>返回类型是 {@link InteractionResultHolder}，见 {@code Item#use} 在 1.21 的签名。
     */
    // TODO(M4): 服务端校验权限后打开 DeckEditorScreen（主 60 / 额外 15 / 副 15 布局），
    //           并在服务端用 DeckValidator 校验 40–60、同名 ≤3、lflist 禁限卡表。
    //           注意：界面在客户端开，但「能不能开」的判定必须在服务端，两端职责不要混。
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        return super.use(level, player, hand);
    }
}
