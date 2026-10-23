package cn.xm1221.ygomc.common.block;

import cn.xm1221.ygomc.common.deck.DeckBoxItem;
import cn.xm1221.ygomc.common.duel.DuelDiskHooks;
import cn.xm1221.ygomc.common.duel.DuelTables;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 决斗台（固定在场上的决斗场地）。
 *
 * <p>台子本身不写方块实体。谁在等、谁正在打，记在 {@link DuelTables} 里，
 * 按维度和坐标分开，所以两台可以各开一局。真正的对局仍由 {@code DuelRoom} 跑。
 * 拆掉台子只丢掉这一格的候场，不中止已经开始的对局。
 *
 * <h2>右键</h2>
 * <ul>
 *   <li>空台：坐下等对手。潜行则用已选卡组对人机开局（和 {@code /ygomc duel} 同一条路）。</li>
 *   <li>已经有人候场：另一个人右键加入；候场的人再点一次离开，潜行则改成人机。</li>
 *   <li>这一格正在打，而且点的人是参与者：客户端把对局界面拉回来。</li>
 *   <li>旁观，或自己已经有别的局：只发一句说明。</li>
 * </ul>
 * 手持卡组盒点台子，先把那副卡组写成对战卡组。潜行手里拿着方块时让路，
 * 方便把方块贴着台子放下去。
 */
public class DuelTableBlock extends Block {

    public DuelTableBlock(Properties properties) {
        super(properties);
    }

    /**
     * 1.21 起方块要能提供自己的 {@link MapCodec}（数据驱动方块的基础设施）。
     *
     * <p>这里直接沿用父类实现：本方块没有自定义方块状态，也没有需要序列化的字段。
     * 一旦以后要给决斗台加状态（朝向、是否被占用），就必须改成
     * {@code simpleCodec(DuelTableBlock::new)} 这类真实编解码，
     * 否则数据包无法描述这个方块。
     */
    @Override
    protected MapCodec<? extends Block> codec() {
        return super.codec();
    }

    /**
     * 手持物品右键决斗台。
     *
     * <p>返回类型是 {@link ItemInteractionResult}（不是 {@link InteractionResult}）：
     * 它多携带一个「手上的物品是否发生变化」的信息，原版据此决定要不要给客户端
     * 回发物品更新。用错类型会导致客户端手里的物品显示与服务端不同步。
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                             Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (player.isShiftKeyDown() && stack.getItem() instanceof BlockItem) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide()) {
            // 参与者把界面拉回来。是不是这一格的人，服务端会再判；
            // 客户端没有台子占用表，只能先按「自己有没有一局」来拉。
            DuelDiskHooks.reopenScreen();
            return ItemInteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer server) {
            DuelTables.interact(server, level, pos, hand);
        }
        return ItemInteractionResult.SUCCESS;
    }

    /**
     * 空手右键，以及主手潜行拿着方块时 {@link #useItemOn} 让路之后的默认交互。
     *
     * <p>主手空着时，原版不会再拿副手去点方块：这一下已经在这里返回成功。
     * 副手的卡组盒要在这里读，否则空主手加副手卡组盒选不上卡组。
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (player.isShiftKeyDown() && player.getMainHandItem().getItem() instanceof BlockItem) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            DuelDiskHooks.reopenScreen();
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer server) {
            DuelTables.interact(server, level, pos, deckHand(player));
        }
        return InteractionResult.SUCCESS;
    }

    /** 主手是卡组盒就用主手；主手不是、副手是，才读副手。 */
    private static InteractionHand deckHand(Player player) {
        if (player.getMainHandItem().getItem() instanceof DeckBoxItem) {
            return InteractionHand.MAIN_HAND;
        }
        if (player.getOffhandItem().getItem() instanceof DeckBoxItem) {
            return InteractionHand.OFF_HAND;
        }
        return InteractionHand.MAIN_HAND;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            DuelTables.release(level, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
