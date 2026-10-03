package cn.xm1221.ygomc.common.pack;

import cn.xm1221.ygomc.common.card.CardComponents;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 卡包：右键开封，按权重抽若干张卡。
 *
 * <h2>为什么卡包只存「是哪一种包」，不存里面有什么</h2>
 * 抽卡结果必须由<b>开启的那一刻</b>在服务端决定，否则客户端可以提前读到未来
 * （每个客户端都拿到了完整的卡包内容，改包就能偷看）。所以物品上只留
 * {@code pack_id}——它是「哪一版卡包」的键，真正的概率表、卡池、
 * 每种稀有度的抽中权重全部在服务端按这个键查表（PLAN §5.3 的
 * {@code Distribution}/{@code Pull}/{@code PullEntry} 模型）。
 *
 * <p>这条设计还有一个副作用是好的：调整某版卡包的概率表不需要动已发出的物品，
 * 也不需要发模组更新。
 */
public class CardPackItem extends Item {

    public CardPackItem(Properties properties) {
        super(properties);
    }

    /**
     * 读取卡包种类。
     *
     * @return 卡包种类键；该 stack 不带 {@code pack_id} 时返回 {@code null}
     */
    public static ResourceLocation packId(ItemStack stack) {
        DataComponentType<ResourceLocation> type = CardComponents.PACK_ID.get();
        return stack.get(type);
    }

    /**
     * 右键开封。
     *
     * <p>返回值类型必须是 {@link InteractionResultHolder} 而不是
     * {@link net.minecraft.world.InteractionResult}——这是 1.21 里
     * {@code Item#use} 的签名（它要把「用完之后手上这格变成什么」一并带回去）。
     * 本轮先留空实现：既不消耗卡包也不生成卡，
     * 避免半成品的开包逻辑把玩家的包吃掉。
     */
    // TODO(M3): 服务端按 pack_id 查 Distribution -> 抽样 -> 生成带 CardRef 的 CardItem ->
    //           放入玩家背包或丢出；再扣掉一个卡包。用 level 的 RandomSource 复用原版随机源。
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        return super.use(level, player, hand);
    }
}
