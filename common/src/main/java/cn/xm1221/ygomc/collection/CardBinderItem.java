package cn.xm1221.ygomc.collection;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 卡册：收藏与检索全部持有的卡。
 *
 * <h2>为什么卡册不自己存一张「我有哪些卡」的清单</h2>
 * 收藏的权威来源是<b>物品本身</b>——卡在谁背包里、在哪个箱子里，就是谁拥有它。
 * 如果卡册再存一份清单，一旦玩家把卡私下交易出去，两份数据就会打架，
 * 而且没法判断哪份是对的。所以卡册只是一个<b>视图</b>：
 * 打开时扫描玩家（以及可配置范围内的容器）里的 {@code card} 物品，
 * 按 {@code CardRef} 聚合去重，渲染成册页。
 *
 * <p>扫描背包是 O(背包格数)，对单人背包完全够快；服务端将来维护的
 * per-player 索引（在背包变化时增量更新）只是为了在卡很多时加速搜索，
 * 属于纯优化，删掉它功能依然正确。
 */
public class CardBinderItem extends Item {

    public CardBinderItem(Properties properties) {
        super(properties);
    }

    /**
     * 右键：打开卡册界面。
     *
     * <p>返回类型是 {@link InteractionResultHolder}，见 {@code Item#use} 在 1.21 的签名。
     */
    // TODO(M3): 服务端扫描玩家背包/末影箱 -> 按 CardRef 聚合 -> 下发分页列表 ->
    //           客户端 CardBinderScreen 渲染（卡图按需从 pics.bin 解码）。
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        return super.use(level, player, hand);
    }

    /**
     * 卡册里装的卡是不是也该显示在悬停提示里？
     * 现在不做——卡册不持有内容（见类注释），没有可显示的东西。
     */
    // 刻意不覆写 appendHoverText。
}
